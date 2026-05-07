package com.yourtube.core.player

import com.yourtube.core.common.model.PlaybackStatus
import com.yourtube.core.common.model.PlayerState
import com.yourtube.core.common.model.QueueItem
import com.yourtube.core.common.model.Track
import com.yourtube.core.data.preferences.AudioQualityPreferences
import com.yourtube.core.data.repository.PlaylistRepository
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Singleton
class DefaultPlayerController @Inject constructor(
    private val playbackTransport: PlaybackTransport,
    @MainDispatcher private val dispatcher: CoroutineDispatcher,
    private val audioQualityPreferences: AudioQualityPreferences,
    private val playlistRepository: PlaylistRepository,
) : PlayerController {

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutablePlayerState = MutableStateFlow(PlayerState())
    private var progressJob: Job? = null

    override val playerState: StateFlow<PlayerState> = mutablePlayerState.asStateFlow()

    override suspend fun playNow(track: Track) {
        val queueItem = track.toQueueItem()
        mutablePlayerState.value = PlayerState(
            currentTrack = track,
            queue = listOf(queueItem),
            currentQueueIndex = 0,
            playbackStatus = PlaybackStatus.LOADING,
            isPlaying = false,
            positionMs = 0L,
            durationMs = track.durationMs,
            errorMessage = null,
        )
        playQueueItem(queueItem)
    }

    override suspend fun addToQueue(track: Track) {
        mutablePlayerState.update { state ->
            state.copy(queue = state.queue + track.toQueueItem())
        }
    }

    override suspend fun playNext(track: Track) {
        mutablePlayerState.update { state ->
            val insertIndex = if (state.currentQueueIndex in state.queue.indices) {
                state.currentQueueIndex + 1
            } else {
                state.queue.size
            }
            state.copy(
                queue = state.queue.toMutableList()
                    .apply { add(insertIndex.coerceIn(0, size), track.toQueueItem()) },
            )
        }
    }

    override suspend fun skipNext() {
        val nextIndex = mutablePlayerState.value.currentQueueIndex + 1
        playQueueItemAt(nextIndex)
    }

    override suspend fun skipPrevious() {
        val state = mutablePlayerState.value
        if (state.positionMs > RESTART_THRESHOLD_MS) {
            seekTo(0L)
            return
        }
        val previousIndex = state.currentQueueIndex - 1
        playQueueItemAt(previousIndex)
    }

    override suspend fun pause() {
        if (mutablePlayerState.value.currentTrack == null) return
        progressJob?.cancel()
        playbackTransport.pause()
        mutablePlayerState.update { state ->
            state.copy(
                playbackStatus = PlaybackStatus.PAUSED,
                isPlaying = false,
            )
        }
    }

    override suspend fun resume() {
        val state = mutablePlayerState.value
        val track = state.currentTrack ?: return
        playbackTransport.resume()
        mutablePlayerState.update {
            it.copy(
                currentTrack = track,
                playbackStatus = PlaybackStatus.PLAYING,
                isPlaying = true,
                errorMessage = null,
            )
        }
        startProgressUpdates()
    }

    override suspend fun seekTo(positionMs: Long) {
        val clamped = positionMs.coerceIn(0L, mutablePlayerState.value.durationMs)
        playbackTransport.seekTo(clamped)
        mutablePlayerState.update { state ->
            state.copy(positionMs = clamped)
        }
    }

    override suspend fun removeQueueItem(queueId: String) {
        val state = mutablePlayerState.value
        val removeIndex = state.queue.indexOfFirst { it.queueId == queueId }
        if (removeIndex == -1) return

        val removingCurrentItem = removeIndex == state.currentQueueIndex
        val updatedQueue = state.queue.toMutableList().apply { removeAt(removeIndex) }
        val updatedIndex = when {
            updatedQueue.isEmpty() -> -1
            removeIndex < state.currentQueueIndex -> state.currentQueueIndex - 1
            removingCurrentItem -> state.currentQueueIndex.coerceAtMost(updatedQueue.lastIndex)
            else -> state.currentQueueIndex
        }
        val newCurrentTrack = updatedQueue.getOrNull(updatedIndex)?.track

        if (removingCurrentItem) {
            progressJob?.cancel()
            playbackTransport.pause()
        }

        mutablePlayerState.update {
            it.copy(
                queue = updatedQueue,
                currentQueueIndex = updatedIndex,
                currentTrack = newCurrentTrack,
                durationMs = newCurrentTrack?.durationMs ?: 0L,
                positionMs = 0L,
                playbackStatus = when {
                    newCurrentTrack == null -> PlaybackStatus.IDLE
                    removingCurrentItem -> PlaybackStatus.PAUSED
                    else -> it.playbackStatus
                },
                isPlaying = if (removingCurrentItem || newCurrentTrack == null) false else it.isPlaying,
            )
        }
    }

    override suspend fun setShuffleMode(enabled: Boolean) {
        // Update controller state synchronously so the UI reflects the user's intent
        // immediately, even if the transport's player isn't attached yet (the engine
        // will reconcile on its next Player.Listener callback when it does attach).
        mutablePlayerState.update { state -> state.copy(shuffleOn = enabled) }
        playbackTransport.setShuffleMode(enabled)
    }

    override suspend fun setRepeatMode(mode: Int) {
        mutablePlayerState.update { state -> state.copy(repeatMode = mode) }
        playbackTransport.setRepeatMode(mode)
    }

    override suspend fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        val state = mutablePlayerState.value
        if (fromIndex !in state.queue.indices || toIndex !in state.queue.indices || fromIndex == toIndex) return

        val updatedQueue = state.queue.toMutableList().apply {
            val moved = removeAt(fromIndex)
            add(toIndex, moved)
        }
        val currentQueueId = state.queue.getOrNull(state.currentQueueIndex)?.queueId
        val updatedIndex = updatedQueue.indexOfFirst { it.queueId == currentQueueId }

        mutablePlayerState.update {
            it.copy(
                queue = updatedQueue,
                currentQueueIndex = updatedIndex,
            )
        }
    }

    private suspend fun playQueueItemAt(index: Int) {
        val queueItem = mutablePlayerState.value.queue.getOrNull(index) ?: return
        mutablePlayerState.update {
            it.copy(
                currentTrack = queueItem.track,
                currentQueueIndex = index,
                playbackStatus = PlaybackStatus.LOADING,
                isPlaying = false,
                positionMs = 0L,
                durationMs = queueItem.track.durationMs,
                errorMessage = null,
            )
        }
        playQueueItem(queueItem)
    }

    private suspend fun playQueueItem(queueItem: QueueItem) {
        progressJob?.cancel()
        // YT-0050: stop any currently-playing audio and clear the underlying
        // player's media items BEFORE we suspend on the network for stream URL
        // resolution. Without this step the previous track keeps producing
        // samples for the several hundred milliseconds the resolve takes, and
        // the user perceives a sluggish track switch. The state-flow update in
        // `playNow` / `playQueueItemAt` already reset positionMs/durationMs
        // synchronously; this call mirrors that reset onto the engine itself
        // and refreshes MediaSession metadata.
        playbackTransport.stopAndClearCurrent()
        // Snapshot the current preference at extraction time. Reading via
        // `Flow.first()` is safe on any dispatcher and does not block — DataStore
        // delivers asynchronously through the suspending pipeline.
        val bitrate = audioQualityPreferences.bitrateKbps.first()
        when (val result = playbackTransport.playTrack(
            PlaybackRequest(track = queueItem.track, preferredMaxBitrateKbps = bitrate)
        )) {
            is PlaybackResult.Success -> {
                mutablePlayerState.update {
                    it.copy(
                        currentTrack = queueItem.track,
                        playbackStatus = PlaybackStatus.PLAYING,
                        isPlaying = true,
                        positionMs = 0L,
                        durationMs = queueItem.track.durationMs,
                        errorMessage = null,
                    )
                }
                startProgressUpdates()
                recordPlayback(queueItem.track)
            }
            is PlaybackResult.Failure -> {
                mutablePlayerState.update {
                    it.copy(
                        currentTrack = queueItem.track,
                        playbackStatus = PlaybackStatus.ERROR,
                        isPlaying = false,
                        errorMessage = result.message,
                    )
                }
            }
        }
    }

    private fun recordPlayback(track: Track) {
        scope.launch {
            runCatching { playlistRepository.recordPlayback(track) }
        }
    }

    private fun startProgressUpdates() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive) {
                delay(PROGRESS_TICK_MS)
                mutablePlayerState.update { state ->
                    if (!state.isPlaying || state.currentTrack == null) return@update state

                    val nextPosition = (state.positionMs + PROGRESS_TICK_MS).coerceAtMost(state.durationMs)
                    if (nextPosition >= state.durationMs && state.durationMs > 0L) {
                        state.copy(
                            positionMs = state.durationMs,
                            playbackStatus = PlaybackStatus.PAUSED,
                            isPlaying = false,
                        )
                    } else {
                        state.copy(positionMs = nextPosition)
                    }
                }

                if (!mutablePlayerState.value.isPlaying) break
            }
        }
    }

    private fun Track.toQueueItem(): QueueItem = QueueItem(
        track = this,
        queueId = UUID.randomUUID().toString(),
    )

    private val Track.durationMs: Long
        get() = durationSec.coerceAtLeast(0) * 1000L

    companion object {
        private const val PROGRESS_TICK_MS = 1_000L
        private const val RESTART_THRESHOLD_MS = 3_000L
    }
}
