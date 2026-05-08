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
    private val perfTracer: PlaybackPerfTracer,
) : PlayerController {

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutablePlayerState = MutableStateFlow(PlayerState())
    private var progressJob: Job? = null

    // YT-0193 — coalesce rapid scrubber-driven `seekTo()` calls.
    //
    // The NowPlaying slider's `onValueChange` fires every drag-tick (up to ~60 Hz on a
    // 120 Hz display). Each call previously invoked `MediaController.seekTo(...)` directly,
    // which ExoPlayer treats as a buffering reset — flooding it for the duration of a drag
    // pushes the underlying renderer in and out of `STATE_BUFFERING` and the user perceives
    // the audio dropping out (the bug surface user-reported as "moving the seek-bar slider
    // stops audio").
    //
    // Fix: hold the latest scrubber target in [pendingSeekPositionMs] and, after a short
    // debounce window, dispatch a SINGLE `playbackTransport.seekTo(...)` to the engine.
    // `playerState.positionMs` updates SYNCHRONOUSLY on every call so the UI scrubber tracks
    // the user's finger with no perceptible lag — only the (expensive) engine dispatch is
    // coalesced. There are NO `pause()` / `resume()` / `play()` calls in the seek path.
    private var seekDispatchJob: Job? = null
    @Volatile
    private var pendingSeekPositionMs: Long? = null

    override val playerState: StateFlow<PlayerState> = mutablePlayerState.asStateFlow()

    init {
        // YT-0182 / YT-0185: subscribe to player-side events so auto-advance fires
        // on STATE_ENDED and in-app state mirrors lock-screen / notification
        // pause-resume. The transport keeps a single listener slot; the listener
        // forwards onto our controller scope (already main).
        playbackTransport.setListener(object : PlaybackTransportListener {
            override fun onTrackEnded() {
                scope.launch { handleTrackEnded() }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                handleIsPlayingChanged(isPlaying)
            }
        })
    }

    private suspend fun handleTrackEnded() {
        val state = mutablePlayerState.value
        val nextIndex = state.currentQueueIndex + 1
        if (nextIndex < state.queue.size) {
            // Synthetic TAP so the auto-advance to the next track has a fresh
            // baseline; without this, EXTRACT_* / STATE_* / FIRST_AUDIO would
            // be measured against the previous user tap (already minutes old).
            state.queue[nextIndex].track.videoId.let { videoId ->
                perfTracer.markTap(videoId, source = "autoAdvance")
            }
            playQueueItemAt(nextIndex)
            return
        }
        // No next item — settle into an ended/idle state. We keep `currentTrack`
        // populated so the in-app UI can keep showing what was last playing
        // (matching how Spotify et al. behave at queue end), but cancel the
        // synthetic progress tick and flip to IDLE.
        progressJob?.cancel()
        mutablePlayerState.update {
            it.copy(
                playbackStatus = PlaybackStatus.IDLE,
                isPlaying = false,
                positionMs = it.durationMs,
            )
        }
    }

    private fun handleIsPlayingChanged(isPlaying: Boolean) {
        // Idempotent mirror — controller-driven writes already set these fields,
        // so re-applying the same values is a no-op for downstream collectors.
        // Guard against clobbering ERROR (transport already reported failure)
        // and LOADING (we are mid-resolve and will set PLAYING on success).
        mutablePlayerState.update { state ->
            when {
                state.playbackStatus == PlaybackStatus.ERROR -> state
                state.playbackStatus == PlaybackStatus.LOADING && !isPlaying -> state
                isPlaying -> state.copy(
                    isPlaying = true,
                    playbackStatus = PlaybackStatus.PLAYING,
                    errorMessage = null,
                )
                else -> {
                    val nextStatus = when (state.playbackStatus) {
                        PlaybackStatus.IDLE -> PlaybackStatus.IDLE
                        else -> PlaybackStatus.PAUSED
                    }
                    state.copy(isPlaying = false, playbackStatus = nextStatus)
                }
            }
        }
    }

    override suspend fun setQueueAndPlay(tracks: List<Track>, startIndex: Int) {
        if (tracks.isEmpty()) return
        val safeIndex = startIndex.coerceIn(0, tracks.lastIndex)
        val queue = tracks.map { it.toQueueItem() }
        val starting = queue[safeIndex]
        // Single atomic replacement so collectors never observe a half-applied queue.
        mutablePlayerState.value = PlayerState(
            currentTrack = starting.track,
            queue = queue,
            currentQueueIndex = safeIndex,
            playbackStatus = PlaybackStatus.LOADING,
            isPlaying = false,
            positionMs = 0L,
            durationMs = starting.track.durationMs,
            errorMessage = null,
        )
        playQueueItem(starting)
    }

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
        // YT-0193 — update PlayerState.positionMs synchronously so the slider thumb tracks
        // the user's finger with no perceptible lag. The engine-side dispatch is debounced
        // below to avoid flooding `MediaController.seekTo(...)` during a drag, which
        // otherwise pushes the renderer into `STATE_BUFFERING` and silences audio.
        mutablePlayerState.update { state -> state.copy(positionMs = clamped) }
        pendingSeekPositionMs = clamped
        if (seekDispatchJob?.isActive == true) {
            // A pending dispatch already owns the trailing transport call — it will pick up
            // [pendingSeekPositionMs] when its debounce window elapses. Returning here
            // collapses the burst into a single transport call.
            return
        }
        seekDispatchJob = scope.launch {
            delay(SEEK_DEBOUNCE_MS)
            // Drain the latest target. `pendingSeekPositionMs` may have advanced since the
            // delay started; use whatever the user's finger settled on.
            val target = pendingSeekPositionMs ?: clamped
            pendingSeekPositionMs = null
            playbackTransport.seekTo(target)
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
                perfTracer.mark(
                    "FAIL",
                    queueItem.track.videoId,
                    "stage=transport msg=\"${result.message}\"",
                )
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

        // YT-0193 — debounce window for engine-side seek dispatch. 50 ms is short enough
        // that a single tap on the scrubber feels instant (well under the ~100 ms human
        // perception threshold) but long enough to coalesce frame-rate ticks emitted by
        // the slider during a drag (16 ms at 60 Hz; 8 ms at 120 Hz).
        internal const val SEEK_DEBOUNCE_MS = 50L
    }
}
