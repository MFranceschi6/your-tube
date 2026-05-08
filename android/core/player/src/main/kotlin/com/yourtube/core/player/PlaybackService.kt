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
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
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
        mediaSession = MediaSession.Builder(this, exoPlayer)
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
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build()
        )
        observeQueueBoundaryForCustomLayout()
    }

    /**
     * YT-0183 — toggles a custom skip-next `CommandButton` on the lock-screen / notification
     * card whenever the controller's queue boundary changes. The button is only published
     * when `playerState.queue.size > currentQueueIndex + 1`, mirroring the in-app NowPlaying
     * skip-next visibility rule.
     *
     * Background: [PlaybackPlayerAdapter] only ever calls `Player.setMediaItem(...)`
     * (singular) per track, so ExoPlayer never has a "next" `MediaItem` and
     * `Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM` stays unavailable. Media3's
     * `DefaultMediaNotificationProvider` therefore hides its built-in skip-next icon. The
     * custom button bridges that gap without restructuring queue ownership.
     *
     * Implementation choice: `MediaSession.setCustomLayout(List<CommandButton>)` is the only
     * layout API on Media3 1.4.1. (The newer `setMediaButtonPreferences(...)` mentioned in
     * YT-0183's brief is a 1.7+ API and is not available here.) Default skip-prev and
     * play-pause are still rendered by the provider from the player's available
     * `Player.Commands`, so the custom layout only needs to publish the missing skip-next
     * entry; we publish an empty layout when no next item exists so the icon disappears.
     */
    @OptIn(UnstableApi::class)
    private fun observeQueueBoundaryForCustomLayout() {
        customLayoutJob?.cancel()
        customLayoutJob = playerController.playerState
            .map { state -> state.queue.size > state.currentQueueIndex + 1 }
            .distinctUntilChanged()
            .onEach { hasNext ->
                val session = mediaSession ?: return@onEach
                val buttons: List<CommandButton> = if (hasNext) {
                    listOf(PlaybackSessionCommand.playbackSkipNextButton())
                } else {
                    emptyList()
                }
                session.setCustomLayout(buttons)
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
