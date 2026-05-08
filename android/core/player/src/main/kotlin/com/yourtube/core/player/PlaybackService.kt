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
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
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

    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private var perfPlayerListener: Player.Listener? = null
    private var customLayoutJob: Job? = null
    private var prewarmJob: Job? = null
    private var currentPrewarmJob: Job? = null
    private var notificationProvider: ColorizedMediaNotificationProvider? = null
    private val paletteColorCache = PaletteColorCache()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

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
     * [PlayerController.skipPrevious] (which keeps the `RESTART_THRESHOLD_MS` rewind-vs-step
     * rule), next routes to [PlayerController.skipNext].
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
        customLayoutJob = playerController.playerState
            .map { state ->
                // YT-0239 (round-3 spec amendment, 2026-05-08T23:30) — `hasPrev` is now gated
                // on `currentTrack != null`, NOT on `currentQueueIndex > 0`. The button stays
                // visible whenever there is a track to operate on; at idx=0 the controller's
                // `skipPrevious()` falls through to `seekTo(0L)` so the user can rewind the
                // first track from the lock-screen / notification card. Single source of
                // truth: both surfaces (NowPlaying skip-prev, lock-screen prev) route through
                // `playerController.skipPrevious()`, so widening visibility here propagates
                // automatically.
                QueueBoundary(
                    hasPrev = state.currentTrack != null,
                    hasNext = state.queue.size > state.currentQueueIndex + 1,
                )
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

    override fun onTaskRemoved(rootIntent: Intent?) {
        val session = mediaSession ?: return
        if (!session.player.playWhenReady || session.player.mediaItemCount == 0) {
            // No active playback — stop the service so it is not kept alive unnecessarily.
            stopSelf()
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
                runCatching {
                    playbackCommandProcessor.playTrack(request, playbackPlayerAdapter)
                }.onSuccess {
                    logger.debug(TAG, "playTrack ok videoId=${request.track.videoId}")
                    resultFuture.set(SessionResult(SessionResult.RESULT_SUCCESS))
                }.onFailure { error ->
                    logger.error(TAG, "playTrack failed videoId=${request.track.videoId}", error)
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
         * `Player.seekToPrevious()` directly) keeps the `RESTART_THRESHOLD_MS` rewind-vs-step
         * policy in one place: positions above the threshold seek the current item to 0,
         * positions at or below the threshold advance `currentQueueIndex` backward and
         * trigger the standard stream-resolve + `playTrack` pipeline used by in-app
         * NowPlaying skip-prev. Same suspending pattern as [handleSkipToNextQueue].
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
