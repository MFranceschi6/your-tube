package com.yourtube.core.player

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Singleton
class MediaControllerPlaybackClient @Inject constructor(
    @ApplicationContext private val context: Context,
) : PlaybackTransport {
    private val controllerMutex = Mutex()
    private var controller: MediaController? = null
    @Volatile
    private var transportListener: PlaybackTransportListener? = null
    private var playerListener: Player.Listener? = null

    override fun setListener(listener: PlaybackTransportListener?) {
        transportListener = listener
    }

    override suspend fun playTrack(request: PlaybackRequest): PlaybackResult {
        val mediaController = controllerMutex.withLock {
            controller ?: buildController().also { controller = it }
        }
        val result = mediaController.awaitCustomCommandResult(request)
        return if (result.resultCode == SessionResult.RESULT_SUCCESS) {
            PlaybackResult.Success
        } else {
            PlaybackResult.Failure("Session result code: ${result.resultCode}")
        }
    }

    override suspend fun pause() {
        val mediaController = controllerMutex.withLock {
            controller ?: buildController().also { controller = it }
        }
        withContext(Dispatchers.Main.immediate) {
            mediaController.pause()
        }
    }

    override suspend fun resume() {
        val mediaController = controllerMutex.withLock {
            controller ?: buildController().also { controller = it }
        }
        withContext(Dispatchers.Main.immediate) {
            mediaController.play()
        }
    }

    override suspend fun seekTo(positionMs: Long) {
        val mediaController = controllerMutex.withLock {
            controller ?: buildController().also { controller = it }
        }
        withContext(Dispatchers.Main.immediate) {
            mediaController.seekTo(positionMs)
        }
    }

    override suspend fun stopAndClearCurrent() {
        val mediaController = controllerMutex.withLock {
            controller ?: buildController().also { controller = it }
        }
        // Run on the main dispatcher: MediaController APIs are main-thread bound
        // and updating the active MediaSession metadata triggers a system
        // notification refresh which must happen on the main thread.
        withContext(Dispatchers.Main.immediate) {
            mediaController.pause()
            mediaController.seekTo(0L)
            mediaController.clearMediaItems()
        }
    }

    override suspend fun setShuffleMode(enabled: Boolean) {
        val mediaController = controllerMutex.withLock {
            controller ?: buildController().also { controller = it }
        }
        // MediaController writes route to the bound MediaSession's Player via Media3's
        // default plumbing; no MediaSession.Callback override is required for shuffle/repeat
        // in Media3 1.4.x (the default forwards to the player and propagates via
        // Player.Listener.onShuffleModeEnabledChanged).
        withContext(Dispatchers.Main.immediate) {
            mediaController.shuffleModeEnabled = enabled
        }
    }

    override suspend fun setRepeatMode(mode: Int) {
        val mediaController = controllerMutex.withLock {
            controller ?: buildController().also { controller = it }
        }
        withContext(Dispatchers.Main.immediate) {
            mediaController.repeatMode = mode
        }
    }

    suspend fun release() {
        controllerMutex.withLock {
            val currentController = controller
            val currentListener = playerListener
            if (currentController != null && currentListener != null) {
                withContext(Dispatchers.Main.immediate) {
                    currentController.removeListener(currentListener)
                }
            }
            currentController?.release()
            controller = null
            playerListener = null
        }
    }

    private suspend fun buildController(): MediaController = withContext(Dispatchers.Main.immediate) {
        val mediaController = MediaController.Builder(
            context,
            SessionToken(context, ComponentName(context, PlaybackService::class.java)),
        ).buildAsync().await()
        // Install a single Player.Listener per controller instance and remember it
        // so [release] can detach cleanly. The forwarding stays cheap — the
        // transport listener is a `@Volatile` reference so the controller can
        // change ownership across rebuilds without a re-registration race.
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    transportListener?.onTrackEnded()
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                transportListener?.onIsPlayingChanged(isPlaying)
            }
        }
        mediaController.addListener(listener)
        playerListener = listener
        mediaController
    }

    private suspend fun MediaController.awaitCustomCommandResult(
        request: PlaybackRequest,
    ): SessionResult = sendCustomCommand(
        PlaybackSessionCommand.playTrack,
        PlaybackSessionCommand.toBundle(request),
    ).await()
}

private suspend fun <T> ListenableFuture<T>.await(): T =
    kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
        addListener(
            {
                try {
                    continuation.resume(get())
                } catch (throwable: Throwable) {
                    continuation.resumeWithException(throwable)
                }
            },
            MoreExecutors.directExecutor(),
        )

        continuation.invokeOnCancellation { cancel(false) }
    }
