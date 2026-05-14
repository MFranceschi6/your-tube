package com.yourtube.core.player

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession.ConnectionResult
import androidx.media3.session.MediaSession.ControllerInfo
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.yourtube.core.common.model.PlayerState
import com.yourtube.core.common.model.Track
import com.yourtube.core.data.preferences.AutoplayPreferences
import com.yourtube.core.data.preferences.PlaybackLifecyclePreferences
import com.yourtube.core.data.repository.PlayerSnapshotRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.launch

/**
 * Real Media3 playback service backed by ExoPlayer.
 *
 * Media3 handles audio focus, becoming-noisy (headset unplug), and
 * notification + lockscreen controls automatically through [MediaSession].
 *
 * The [PlayerFactory] is injected so tests can supply a fake player.
 */
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    @Inject
    lateinit var playerFactory: PlayerFactory

    @Inject
    lateinit var playbackCommandProcessor: PlaybackCommandProcessor

    @Inject
    lateinit var playbackPlayerAdapter: PlaybackPlayerAdapter

    @Inject
    lateinit var logger: Logger

    @Inject
    lateinit var perfTracer: PlaybackPerfTracer

    /**
     * YT-0183 — `PlayerController` is the single source of truth for the in-process queue.
     * The session callback delegates skip-next taps from the lock-screen / notification to
     * [PlayerController.skipNext] so UI taps and system-control taps share one code path.
     */
    @Inject
    lateinit var playerController: PlayerController

    /**
     * YT-0241 — opt-in toggle that makes `onTaskRemoved` also stop audio + dismiss the
     * foreground notification. The flow is collected on [serviceScope] at `onCreate` time
     * by [observeStopOnTaskRemovedPreference]; the cached snapshot in
     * [stopOnTaskRemovedSnapshot] is read non-blockingly inside `onTaskRemoved`, which runs
     * on the main thread.
     */
    @Inject
    lateinit var playbackLifecyclePreferences: PlaybackLifecyclePreferences

    /**
     * YT-0291 L1 — autoplay preference consulted by [observeQueueBoundaryForCustomLayout]
     * so the lock-screen skip-next button remains visible at queue end when autoplay is ON.
     * Injected here so [buildQueueBoundary] can be a pure, testable function that receives
     * the preference value as a plain `Boolean` rather than coupling to DataStore directly.
     */
    @Inject
    lateinit var autoplayPreferences: AutoplayPreferences

    /**
     * YT-0093 — Sleep timer. Attached to [serviceScope] in [onCreate] so timers survive
     * app backgrounding; detached in [onDestroy] to release the scope and cancel any
     * active timer.
     */
    @Inject
    lateinit var sleepTimerController: SleepTimerController

    /**
     * YT-0272 — snapshot repository used at cold launch to restore the persisted queue,
     * current track, seek position, repeat mode, shuffle, and speed into [playerController]
     * paused. Injected here rather than into [playerController] so the restore is a one-shot
     * service-lifecycle concern and does not pollute the controller's ongoing state machine.
     */
    @Inject
    lateinit var playerSnapshotRepository: PlayerSnapshotRepository

    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private var perfPlayerListener: Player.Listener? = null
    private var customLayoutJob: Job? = null
    private var prewarmJob: Job? = null
    private var currentPrewarmJob: Job? = null
    private var notificationProvider: ColorizedMediaNotificationProvider? = null
    private val paletteColorCache = PaletteColorCache()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * YT-0241 — `@Volatile` snapshot of [PlaybackLifecyclePreferences.stopOnTaskRemoved]
     * so [onTaskRemoved] can read the latest persisted value without blocking the main
     * thread. Initialised from the eagerly-shared `StateFlow` populated at `onCreate`.
     * Falls back to the documented default (`false`) if `onTaskRemoved` fires before the
     * first emission lands (cold start race) — preserving YT-0076 AC#5 behaviour.
     */
    @Volatile
    private var stopOnTaskRemovedSnapshot: Boolean =
        PlaybackLifecyclePreferences.DEFAULT_STOP_ON_TASK_REMOVED

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        val exoPlayer = playerFactory.create(this)
        player = exoPlayer
        playbackPlayerAdapter.attach(exoPlayer)
        // Service-side perf listener: fires from the underlying ExoPlayer
        // BEFORE Media3's session IPC echoes the same events to the
        // MediaController. That makes the elapsed_ms values for STATE_*
        // accurate for "what the player itself observed" rather than
        // "when the controller was notified".
        val listener = object : Player.Listener {
            private var lastBufferingForVideoId: String? = null
            private var lastReadyForVideoId: String? = null

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val videoId = mediaItem?.mediaId ?: return
                perfTracer.mark("MEDIA_ITEM_TRANSITION", videoId, "reason=$reason")
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                val videoId = exoPlayer.currentMediaItem?.mediaId ?: return
                when (playbackState) {
                    Player.STATE_BUFFERING -> {
                        // Re-buffers within the same item are still useful but
                        // we only emit the first one per item to keep the
                        // first-note timeline scannable.
                        if (lastBufferingForVideoId != videoId) {
                            lastBufferingForVideoId = videoId
                            perfTracer.mark("STATE_BUFFERING", videoId)
                        }
                    }
                    Player.STATE_READY -> {
                        if (lastReadyForVideoId != videoId) {
                            lastReadyForVideoId = videoId
                            perfTracer.mark("STATE_READY", videoId)
                        }
                    }
                    Player.STATE_ENDED, Player.STATE_IDLE -> Unit
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) return
                val videoId = exoPlayer.currentMediaItem?.mediaId ?: return
                // FIRST_AUDIO is emitted on the first isPlaying=true after
                // STATE_READY for a given item. This is the closest in-process
                // proxy for "audio out the speaker" without hooking the audio
                // session.
                if (lastReadyForVideoId == videoId) {
                    perfTracer.mark("FIRST_AUDIO", videoId)
                    // Clear the ready marker so subsequent in-item pauses /
                    // resumes don't re-emit FIRST_AUDIO. The next track
                    // re-arms via STATE_READY.
                    lastReadyForVideoId = null
                }
            }
        }
        exoPlayer.addListener(listener)
        perfPlayerListener = listener
        // YT-0239 (review change-request, 2026-05-08) — hand the session a
        // [PrevCommandMaskingPlayer] so `DefaultMediaNotificationProvider` no longer renders
        // its built-in skip-prev arrow alongside our custom `SKIP_TO_PREV_QUEUE` button. The
        // masking only affects the commands surface the session exposes; the unwrapped
        // `exoPlayer` is still attached to `playbackPlayerAdapter` above so the in-process
        // audio path (timeline, listeners, perf tracer) is unchanged.
        val sessionPlayer = PrevCommandMaskingPlayer(exoPlayer)
        mediaSession = MediaSession.Builder(this, sessionPlayer)
            .setCallback(PlaybackSessionCallback())
            .setSessionActivity(nowPlayingPendingIntent())
            .build()
        // Attach the system-styled media notification (lock screen + notification shade).
        // YT-0062a Q11: Media3 1.4.1's DefaultMediaNotificationProvider.Builder does not
        // expose compact/expanded layout customization; the default layout (`skip_prev`,
        // `play_pause`, `skip_next` compact; full transport row expanded) is what ships.
        // Configuring `[shuffle, repeat]` in the expanded layout would require a custom
        // layout via `MediaSession.setCustomLayout(...)` and is tracked as a follow-up.
        // `setMediaNotificationProvider` and `DefaultMediaNotificationProvider` are still
        // marked `@UnstableApi` in Media3 1.4.1; opting in at the call site is the
        // standard pattern recommended in the Media3 samples.
        //
        // YT-0076 — wrap the default provider in [ColorizedMediaNotificationProvider] so the
        // shade / lock-screen notification picks up a palette-extracted dominant color from
        // the current track's artwork. Both `createNotification` and `handleCustomCommand` are
        // `final` on `DefaultMediaNotificationProvider` in Media3 1.4.1, so subclassing cannot
        // post-process the assembled `Notification`; the wrapper delegates to the default for
        // layout work and only mutates the resulting builder via `recoverBuilder`.
        val provider = ColorizedMediaNotificationProvider(
            context = this,
            delegate = DefaultMediaNotificationProvider.Builder(this).build(),
            cache = paletteColorCache,
            logger = logger,
        )
        notificationProvider = provider
        setMediaNotificationProvider(provider)
        observeQueueBoundaryForCustomLayout()
        observeNextTrackForArtworkPrewarm()
        observeCurrentTrackForArtworkPrewarm()
        observeStopOnTaskRemovedPreference()
        // YT-0093 — hand the sleep timer the service scope so its coroutines outlive the UI.
        sleepTimerController.attach(serviceScope)
        // YT-0272 — restore the last-known player state from the persisted snapshot so the
        // MiniPlayer is visible immediately at cold launch with the persisted metadata.
        // Runs paused; stream-URL resolution is deferred until the user taps play.
        // A null snapshot (first launch, corrupt DB) is a silent no-op per the contract.
        serviceScope.launch {
            val snapshot = playerSnapshotRepository.loadSnapshot() ?: return@launch
            playerController.restoreFromSnapshot(snapshot)
        }
    }

    /**
     * YT-0241 — eagerly collect [PlaybackLifecyclePreferences.stopOnTaskRemoved] into a
     * `@Volatile` snapshot so [onTaskRemoved] (main-thread, synchronous) can branch on the
     * latest persisted value without `runBlocking`. The collection is anchored to
     * [serviceScope] (`Dispatchers.Main.immediate`) so writes to `stopOnTaskRemovedSnapshot`
     * happen-before `onTaskRemoved` reads on the same thread; `@Volatile` is belt-and-braces
     * for the cross-thread case (DataStore IO threads emitting back to Main.immediate).
     *
     * If the user flips the toggle and then immediately swipes the app away, the snapshot
     * may still hold the previous value during the few milliseconds before DataStore emits.
     * That race window is unavoidable for any synchronous main-thread read; the documented
     * fallback is the existing YT-0076 AC#5 behaviour (notification persists).
     */
    private fun observeStopOnTaskRemovedPreference() {
        playbackLifecyclePreferences.stopOnTaskRemoved
            .onEach { value -> stopOnTaskRemovedSnapshot = value }
            .launchIn(serviceScope)
    }

    /**
     * YT-0183 / YT-0239 — toggles custom skip-prev / skip-next `CommandButton`s on the
     * lock-screen / notification card whenever the controller's queue boundary changes.
     * Buttons are only published when the relevant boundary has a neighbour, mirroring the
     * in-app NowPlaying skip-prev / skip-next visibility rules.
     *
     * Background: [PlaybackPlayerAdapter] only ever calls `Player.setMediaItem(...)`
     * (singular) per track, so ExoPlayer never has prev / next `MediaItem`s on the timeline
     * and the `COMMAND_SEEK_TO_*_MEDIA_ITEM` commands stay unavailable. Media3's
     * `DefaultMediaNotificationProvider` therefore (a) hides its built-in skip-next icon and
     * (b) collapses the prev icon into a `seekTo(0)` rewind on the current item. The custom
     * buttons bridge that gap without restructuring queue ownership: prev routes to
     * [PlayerController.skipPrevious] (which keeps the `SKIP_BACK_RESTART_THRESHOLD_MS`
     * rewind-vs-step rule), next routes to [PlayerController.skipNext].
     *
     * Implementation choice: `MediaSession.setCustomLayout(List<CommandButton>)` is the only
     * layout API on Media3 1.4.1. (The newer `setMediaButtonPreferences(...)` mentioned in
     * YT-0183's brief is a 1.7+ API and is not available here.) Both directions are emitted
     * in a single `setCustomLayout` call so the layout never flickers between partial
     * states. Default play/pause is still rendered by the provider from the player's
     * available `Player.Commands`; we publish an empty layout when the queue has neither a
     * prev nor a next entry so the custom icons disappear.
     */
    @OptIn(UnstableApi::class)
    private fun observeQueueBoundaryForCustomLayout() {
        customLayoutJob?.cancel()
        customLayoutJob = combine(
            playerController.playerState,
            autoplayPreferences.autoplayEnabled,
        ) { state, autoplayEnabled ->
            buildQueueBoundary(state, autoplayEnabled)
        }
            .distinctUntilChanged()
            .onEach { boundary ->
                val session = mediaSession ?: return@onEach
                session.setCustomLayout(buildCustomLayoutButtons(boundary))
            }
            .launchIn(serviceScope)
    }

    /**
     * YT-0076 — pre-warm the next track's artwork bitmap + palette color whenever
     * `currentQueueIndex` advances. Best-effort: failures are swallowed inside the provider's
     * `prewarm` path. Runs on the service scope (Main.immediate) but the actual bitmap load is
     * dispatched through Media3's [androidx.media3.common.util.BitmapLoader], which executes
     * on its own background executor.
     */
    private fun observeNextTrackForArtworkPrewarm() {
        prewarmJob?.cancel()
        prewarmJob = playerController.playerState
            .map { state ->
                val nextIndex = state.currentQueueIndex + 1
                state.queue.getOrNull(nextIndex)?.track
            }
            .distinctUntilChanged { old, new -> old?.videoId == new?.videoId }
            .onEach { nextTrack ->
                val track = nextTrack ?: return@onEach
                val provider = notificationProvider ?: return@onEach
                val session = mediaSession ?: return@onEach
                runCatching { provider.prewarm(track, session.bitmapLoader) }
            }
            .launchIn(serviceScope)
    }

    /**
     * YT-0076 review change-request (2026-05-08) — pre-warm the CURRENT track's artwork +
     * palette color whenever it changes. Mirrors [observeNextTrackForArtworkPrewarm] but keys
     * on `state.currentTrack?.videoId` so cold-launch / fresh `playNow` paths populate the
     * cache before Media3 issues its first `createNotification` cycle for the track.
     *
     * Without this observer, [observeNextTrackForArtworkPrewarm] only covers
     * `currentQueueIndex + 1`; on cold-launch the current track was never prewarmed and the
     * first lock-screen frame rendered with a grey thumbnail until the user interacted with
     * playback (the "first-lock cold-start grey thumbnail" gap surfaced in the manual smoke).
     *
     * Pre-warm is best-effort: failures are swallowed inside the provider's `prewarm` path.
     * Distinct-by `videoId` avoids re-warming on every position update; track changes are the
     * only meaningful trigger.
     */
    private fun observeCurrentTrackForArtworkPrewarm() {
        currentPrewarmJob?.cancel()
        currentPrewarmJob = currentTrackPrewarmFlow(playerController.playerState)
            .onEach { currentTrack ->
                val provider = notificationProvider ?: return@onEach
                val session = mediaSession ?: return@onEach
                runCatching { provider.prewarm(currentTrack, session.bitmapLoader) }
            }
            .launchIn(serviceScope)
    }

    /**
     * `PendingIntent` that opens [MainActivity] to the Now Playing route. The intent uses
     * `FLAG_ACTIVITY_SINGLE_TOP` so a re-entry from the lock-screen / notification reuses the
     * existing activity instance and routes through `onNewIntent` rather than spawning a new
     * task. Wrapped with `FLAG_IMMUTABLE` (required on API 31+) and `FLAG_UPDATE_CURRENT` so
     * subsequent rebuilds refresh the underlying intent extras.
     *
     * The activity class is resolved by full name to avoid a build-time dependency from
     * `:core:player` onto `:app`. The string class name is verified at runtime; missing the
     * class only matters if the app module is repackaged.
     */
    private fun nowPlayingPendingIntent(): PendingIntent {
        val intent = Intent().apply {
            action = ACTION_OPEN_NOW_PLAYING
            component = ComponentName(packageName, MAIN_ACTIVITY_CLASS_NAME)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    // MediaSessionService routes controller connections here.
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    /**
     * YT-0285 — cold-rebind restore guard.
     *
     * `PlaybackService.onCreate` schedules `restoreFromSnapshot` on [serviceScope] — a
     * `launch {}` that runs asynchronously. When the Activity (re)binds to the service,
     * the UI Composition starts collecting `playerController.playerState` immediately. If
     * `restoreFromSnapshot` has not yet executed, `playerState.currentTrack` is null and
     * the MiniPlayer gate (`currentTrack != null`) evaluates to false — so the MiniPlayer
     * never appears until the user interacts with the app again.
     *
     * Overriding `onBind` lets us detect the race: if the controller still has no current
     * track at bind time but a snapshot exists, we trigger an additional restore launch.
     * [shouldRestoreOnBind] encapsulates the predicate so it is unit-testable.
     *
     * `super.onBind(intent)` must be called first so Media3's `MediaSessionService` can
     * set up its own binder before we schedule work on the service scope.
     *
     * Note: `onBind` is called on the main thread; `restoreFromSnapshot` is `suspend`, so
     * it is launched on [serviceScope] (`Dispatchers.Main.immediate`) — same as the
     * `onCreate` path.
     */
    override fun onBind(intent: android.content.Intent?): android.os.IBinder? {
        val binder = super.onBind(intent)
        if (shouldRestoreOnBind(playerController.playerState.value.currentTrack)) {
            serviceScope.launch {
                val snapshot = playerSnapshotRepository.loadSnapshot() ?: return@launch
                playerController.restoreFromSnapshot(snapshot)
            }
        }
        return binder
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val session = mediaSession ?: return
        // YT-0241 — branch on the snapshot read of `stopOnTaskRemoved`. The decision is
        // extracted into [decideOnTaskRemoved] so it can be unit-tested without spinning up
        // an Android service. When `true`: stop the player AND the service unconditionally.
        // When `false`: keep the existing YT-0076 AC#5 branch (Spotify-style persistence —
        // only stopSelf when playback is idle).
        when (
            decideOnTaskRemoved(
                stopOnTaskRemoved = stopOnTaskRemovedSnapshot,
                playWhenReady = session.player.playWhenReady,
                mediaItemCount = session.player.mediaItemCount,
            )
        ) {
            OnTaskRemovedAction.StopPlayerAndService -> {
                session.player.stop()
                stopSelf()
            }
            OnTaskRemovedAction.StopServiceOnly -> stopSelf()
            OnTaskRemovedAction.None -> Unit
        }
    }

    override fun onDestroy() {
        customLayoutJob?.cancel()
        customLayoutJob = null
        prewarmJob?.cancel()
        prewarmJob = null
        currentPrewarmJob?.cancel()
        currentPrewarmJob = null
        notificationProvider = null
        // YT-0093 — detach before cancelling serviceScope so cancelInternal() can still
        // safely clear timerJob (Job.cancel() on an already-cancelled scope is a no-op).
        sleepTimerController.detach()
        serviceScope.cancel()
        playbackPlayerAdapter.detach()
        val currentListener = perfPlayerListener
        if (currentListener != null) {
            player?.removeListener(currentListener)
        }
        perfPlayerListener = null
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        player = null
        super.onDestroy()
    }

    @OptIn(UnstableApi::class)
    private inner class PlaybackSessionCallback : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: ControllerInfo,
        ): ConnectionResult = ConnectionResult.AcceptedResultBuilder(session)
            .setAvailableSessionCommands(
                ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                    .add(PlaybackSessionCommand.playTrack)
                    // YT-0183 — expose the custom skip-next action so the
                    // notification button (and any other connected controller) can
                    // dispatch it back into the session callback.
                    .add(PlaybackSessionCommand.skipToNextQueue)
                    // YT-0239 — same plumbing for the symmetric skip-prev action.
                    .add(PlaybackSessionCommand.skipToPrevQueue)
                    .build(),
            )
            .build()

        override fun onCustomCommand(
            session: MediaSession,
            controller: ControllerInfo,
            customCommand: androidx.media3.session.SessionCommand,
            args: android.os.Bundle,
        ): ListenableFuture<SessionResult> {
            return when (customCommand.customAction) {
                PlaybackSessionCommand.PLAY_TRACK_ACTION -> handlePlayTrack(args)
                PlaybackSessionCommand.SKIP_TO_NEXT_QUEUE_ACTION -> handleSkipToNextQueue()
                PlaybackSessionCommand.SKIP_TO_PREV_QUEUE_ACTION -> handleSkipToPrevQueue()
                else -> Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
            }
        }

        private fun handlePlayTrack(args: android.os.Bundle): ListenableFuture<SessionResult> {
            val request = PlaybackSessionCommand.fromBundle(args)
                ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))

            val resultFuture = SettableFuture.create<SessionResult>()
            serviceScope.launch {
                // YT-0309 — mirror the client-side withTimeoutOrNull ceiling on the service
                // side so the OkHttp call and the launch itself are bounded. Without this, the
                // service-side coroutine outlives the client timeout and eventually calls
                // playbackEngine.prepare()/play(), re-driving ExoPlayer into STATE_BUFFERING
                // after the controller already transitioned to PAUSED.
                // Recovery vector: tap-to-retry. The controller transitions to PAUSED with
                // engineLoaded=false on timeout; the user re-taps Play which re-enters
                // playQueueItem via the engineLoaded=false branch. No ConnectivityManager
                // callback is registered — the user initiates the retry.
                var failureReason: String? = null
                val resolved = kotlinx.coroutines.withTimeoutOrNull(SERVICE_RESOLVE_TIMEOUT_MS) {
                    runCatching {
                        playbackCommandProcessor.playTrack(request, playbackPlayerAdapter)
                    }.onFailure { failureReason = it.message }.isSuccess
                }
                if (resolved == true) {
                    logger.debug(TAG, "playTrack ok videoId=${request.track.videoId}")
                    resultFuture.set(SessionResult(SessionResult.RESULT_SUCCESS))
                } else {
                    val reason = if (resolved == null) "timeout" else failureReason ?: "unknown"
                    logger.error(TAG, "playTrack failed videoId=${request.track.videoId} reason=$reason")
                    resultFuture.set(SessionResult(SessionError.ERROR_UNKNOWN))
                }
            }
            return resultFuture
        }

        /**
         * YT-0183 — delegate the lock-screen / notification skip-next tap to
         * [PlayerController.skipNext]. Routing through the controller (rather than
         * `Player.seekToNextMediaItem()` directly) keeps a single source of truth: the
         * controller advances `currentQueueIndex`, kicks the perf tracer with a synthetic
         * tap, and triggers the standard stream-resolve + `playTrack` pipeline that
         * powers in-app skip-next. `skipNext()` is `suspend` and will issue a
         * `MediaController.sendCustomCommand(playTrack, ...)` that re-enters this
         * callback for the next track — that re-entry is intentional and matches
         * the existing path used by the in-app NowPlaying screen.
         */
        private fun handleSkipToNextQueue(): ListenableFuture<SessionResult> {
            val resultFuture = SettableFuture.create<SessionResult>()
            serviceScope.launch {
                runCatching {
                    playerController.skipNext()
                }.onSuccess {
                    logger.debug(TAG, "skipNext from session callback ok")
                    resultFuture.set(SessionResult(SessionResult.RESULT_SUCCESS))
                }.onFailure { error ->
                    logger.error(TAG, "skipNext from session callback failed", error)
                    resultFuture.set(SessionResult(SessionError.ERROR_UNKNOWN))
                }
            }
            return resultFuture
        }

        /**
         * YT-0239 — delegate the lock-screen / notification skip-prev tap to
         * [PlayerController.skipPrevious]. Routing through the controller (rather than
         * `Player.seekToPrevious()` directly) keeps the `SKIP_BACK_RESTART_THRESHOLD_MS`
         * rewind-vs-step policy in one place: when engineLoaded and position above the
         * threshold seeks the current item to 0; at or below the threshold advances
         * `currentQueueIndex` backward and triggers the standard stream-resolve + `playTrack`
         * pipeline used by in-app NowPlaying skip-prev. Same suspending pattern as
         * [handleSkipToNextQueue].
         */
        private fun handleSkipToPrevQueue(): ListenableFuture<SessionResult> {
            val resultFuture = SettableFuture.create<SessionResult>()
            serviceScope.launch {
                runCatching {
                    playerController.skipPrevious()
                }.onSuccess {
                    logger.debug(TAG, "skipPrevious from session callback ok")
                    resultFuture.set(SessionResult(SessionResult.RESULT_SUCCESS))
                }.onFailure { error ->
                    logger.error(TAG, "skipPrevious from session callback failed", error)
                    resultFuture.set(SessionResult(SessionError.ERROR_UNKNOWN))
                }
            }
            return resultFuture
        }
    }

    companion object {
        /**
         * Intent action used by the `setSessionActivity` PendingIntent to signal that the
         * caller (lock-screen, notification, system media controls) wants to land on the
         * Now Playing screen when [MainActivity] receives the intent. Read inside
         * `MainActivity.onNewIntent`.
         */
        const val ACTION_OPEN_NOW_PLAYING = "com.yourtube.action.OPEN_NOW_PLAYING"

        private const val TAG = "YT-PlaybackService"
        private const val MAIN_ACTIVITY_CLASS_NAME = "com.yourtube.app.MainActivity"
        // YT-0309 — matches the client-side ceiling in DefaultPlayerController.
        private const val SERVICE_RESOLVE_TIMEOUT_MS = 15_000L
    }
}

/**
 * YT-0239 — pure value type capturing whether the current queue position has a previous
 * and/or next neighbour. Extracted so the layout-emission rule
 * (`(hasPrev, hasNext) -> List<CommandButton>`) is unit-testable without instantiating a
 * `MediaSession` — the wiring inside [PlaybackService.observeQueueBoundaryForCustomLayout]
 * delegates to [buildCustomLayoutButtons].
 */
internal data class QueueBoundary(
    val hasPrev: Boolean,
    val hasNext: Boolean,
)

/**
 * YT-0291 L1 — derives the lock-screen / notification button boundary from the
 * current [PlayerState] and the user's `autoplayEnabled` preference.
 *
 * `hasNext` is true when the queue has an explicit next item OR when autoplay is ON
 * and there is a current track (skip-next at queue end triggers the autoplay path).
 * Extracted as a pure function so the derivation rule is testable without a
 * live [MediaSession].
 */
internal fun buildQueueBoundary(state: PlayerState, autoplayEnabled: Boolean): QueueBoundary =
    QueueBoundary(
        hasPrev = state.currentTrack != null,
        hasNext = state.queue.size > state.currentQueueIndex + 1 ||
            (state.currentTrack != null && autoplayEnabled),
    )

/**
 * YT-0239 — builds the `setCustomLayout(...)` button list for a given [boundary].
 *
 * The list always orders prev before next so the lock-screen layout placement is stable
 * across emissions; when neither direction is available the list is empty and the
 * provider falls back to the default play/pause-only layout. Computing both sides in a
 * single emission avoids the double-`setCustomLayout` call that would otherwise flicker
 * the icon row when both boundaries change at once (e.g. moving from idx=0 to idx=1).
 *
 * `CommandButton.Builder` is `@UnstableApi` in Media3 1.4.x — opt-in is contained at the
 * factory call sites in [PlaybackSessionCommand].
 */
@OptIn(UnstableApi::class)
internal fun buildCustomLayoutButtons(boundary: QueueBoundary): List<CommandButton> = buildList {
    if (boundary.hasPrev) add(PlaybackSessionCommand.playbackSkipPrevButton())
    if (boundary.hasNext) add(PlaybackSessionCommand.playbackSkipNextButton())
}

/**
 * YT-0241 — describes what [PlaybackService.onTaskRemoved] should do when the system
 * notifies the service that the app's task has been removed from recents.
 *
 * Extracted as a pure value type so the decision is unit-testable without instantiating a
 * `MediaSession` or `MediaSessionService`.
 */
internal enum class OnTaskRemovedAction {
    /** Stop the player AND `stopSelf()` (toggle ON; user opted into single-gesture kill). */
    StopPlayerAndService,

    /** `stopSelf()` only — preserves YT-0076 AC#5 behaviour for the toggle-OFF idle path. */
    StopServiceOnly,

    /** Leave the service running with the foreground notification (toggle-OFF, audible). */
    None,
}

/**
 * YT-0241 — pure decision function used by [PlaybackService.onTaskRemoved].
 *
 * - `stopOnTaskRemoved == true`: ALWAYS [OnTaskRemovedAction.StopPlayerAndService] regardless
 *   of `playWhenReady` or `mediaItemCount`. The user has opted in to a single-gesture kill;
 *   audible or paused playback both terminate.
 * - `stopOnTaskRemoved == false`: keep the existing YT-0076 AC#5 split — when there is no
 *   active playback (`!playWhenReady` OR empty queue) call [OnTaskRemovedAction.StopServiceOnly]
 *   so the service is not kept alive unnecessarily; otherwise [OnTaskRemovedAction.None] so
 *   the foreground notification persists Spotify-style.
 */
internal fun decideOnTaskRemoved(
    stopOnTaskRemoved: Boolean,
    playWhenReady: Boolean,
    mediaItemCount: Int,
): OnTaskRemovedAction = when {
    stopOnTaskRemoved -> OnTaskRemovedAction.StopPlayerAndService
    !playWhenReady || mediaItemCount == 0 -> OnTaskRemovedAction.StopServiceOnly
    else -> OnTaskRemovedAction.None
}

/**
 * YT-0285 — pure predicate used by [PlaybackService.onBind] to decide whether to re-trigger
 * snapshot restore on the bind path.
 *
 * Returns `true` when [currentTrack] is null, meaning the controller has not yet been
 * populated (the async `restoreFromSnapshot` launched in `onCreate` may not have run yet).
 * The caller is responsible for checking whether a snapshot actually exists before
 * invoking `restoreFromSnapshot`; this function only decides whether a check is necessary.
 *
 * Extracting the predicate makes it unit-testable without instantiating a
 * `MediaSession` or `MediaSessionService`.
 */
internal fun shouldRestoreOnBind(currentTrack: Track?): Boolean =
    currentTrack == null

/**
 * YT-0076 review change-request (2026-05-08) — extracts the `PlayerState` → `Track` flow
 * transformation used by [PlaybackService.observeCurrentTrackForArtworkPrewarm] so the
 * "fires once per distinct videoId" rule is unit-testable without spinning up a service.
 *
 * The flow:
 *  - Maps each [PlayerState] to its `currentTrack`.
 *  - Drops null entries (no track playing — nothing to prewarm).
 *  - De-duplicates by `videoId` so position / playback-status updates do not re-trigger
 *    the prewarm side effect; only a real track change emits.
 *
 * Tested by [PlaybackServiceLayoutTest].
 */
internal fun currentTrackPrewarmFlow(source: Flow<PlayerState>): Flow<Track> =
    source
        .map { state -> state.currentTrack }
        .filterNotNull()
        .distinctUntilChanged { old, new -> old.videoId == new.videoId }
