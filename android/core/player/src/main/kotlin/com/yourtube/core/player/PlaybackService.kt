package com.yourtube.core.player

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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

    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        val exoPlayer = playerFactory.create(this)
        player = exoPlayer
        playbackPlayerAdapter.attach(exoPlayer)
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
        serviceScope.cancel()
        playbackPlayerAdapter.detach()
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
                    .build(),
            )
            .build()

        override fun onCustomCommand(
            session: MediaSession,
            controller: ControllerInfo,
            customCommand: androidx.media3.session.SessionCommand,
            args: android.os.Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction != PlaybackSessionCommand.PLAY_TRACK_ACTION) {
                return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
            }

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
