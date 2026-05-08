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

            override fun onMediaItemTransition(mediaId: String?) {
                handleMediaItemTransition(mediaId)
            }

            override fun onPositionChanged(positionMs: Long) {
                handlePositionChanged(positionMs)
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

    /**
     * YT-0150 — reconcile [PlayerState.currentQueueIndex] with the underlying
     * player after a `Player.Listener.onMediaItemTransition`. Lock-screen and
     * notification skip-next / skip-prev taps drive `MediaController` directly
     * and bypass [skipNext] / [skipPrevious]; without this hook the in-memory
     * queue index drifts from the audio actually playing, so reopening the app
     * shows the wrong MiniPlayer entry.
     *
     * Match by `mediaId == videoId`. The controller's timeline index is NOT
     * trusted — the queue can be re-ordered (move / remove) while the
     * underlying timeline lags those writes.
     *
     * Idempotent: a transition that resolves to the same index already in state
     * is a no-op so in-app skip-next/prev (which already updated state via
     * [playQueueItemAt] and will receive a feedback echo here) does not double-
     * apply.
     */
    private fun handleMediaItemTransition(mediaId: String?) {
        if (mediaId.isNullOrEmpty()) return
        mutablePlayerState.update { state ->
            val matchedIndex = state.queue.indexOfFirst { it.track.videoId == mediaId }
            if (matchedIndex == -1 || matchedIndex == state.currentQueueIndex) {
                state
            } else {
                val matchedTrack = state.queue[matchedIndex].track
                state.copy(
                    currentTrack = matchedTrack,
                    currentQueueIndex = matchedIndex,
                    positionMs = 0L,
                    durationMs = matchedTrack.durationSec.coerceAtLeast(0) * 1000L,
                    errorMessage = null,
                )
            }
        }
    }

    /**
     * YT-0150 — reconcile [PlayerState.positionMs] with the engine after a
     * `Player.Listener.onPositionDiscontinuity` triggered by an external seek
     * (lock-screen slider, system shell, future Auto / Wear). Without this hook
     * the in-memory position drifts from the audio actually playing, so the
     * NowPlaying scrubber stays frozen at the previous position when the user
     * unlocks the device.
     *
     * The new value is clamped to `[0, durationMs]` to defend against the engine
     * reporting a position past the end of the resolved track (e.g. during the
     * reconcile window of a fresh stream URL).
     *
     * Idempotent for the current value: the in-app `seekTo(...)` path also
     * triggers an engine-side seek which echoes back through the listener, and
     * that echo must be a no-op (otherwise it would clobber the synchronously-
     * written scrubber position from a still-active drag).
     */
    private fun handlePositionChanged(positionMs: Long) {
        mutablePlayerState.update { state ->
            val clamped = positionMs.coerceIn(0L, state.durationMs.coerceAtLeast(0L))
            if (clamped == state.positionMs) {
                state
            } else {
                state.copy(positionMs = clamped)
            }
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
        // YT-0236 — `engineLoaded = false` until `playQueueItem(...)` returns Success below.
        mutablePlayerState.value = PlayerState(
            currentTrack = starting.track,
            queue = queue,
            currentQueueIndex = safeIndex,
            playbackStatus = PlaybackStatus.LOADING,
            isPlaying = false,
            positionMs = 0L,
            durationMs = starting.track.durationMs,
            errorMessage = null,
            engineLoaded = false,
        )
        playQueueItem(starting)
    }

    override suspend fun playNow(track: Track) {
        val queueItem = track.toQueueItem()
        // YT-0236 — `engineLoaded = false` until `playQueueItem(...)` returns Success below.
        mutablePlayerState.value = PlayerState(
            currentTrack = track,
            queue = listOf(queueItem),
            currentQueueIndex = 0,
            playbackStatus = PlaybackStatus.LOADING,
            isPlaying = false,
            positionMs = 0L,
            durationMs = track.durationMs,
            errorMessage = null,
            engineLoaded = false,
        )
        playQueueItem(queueItem)
    }

    override suspend fun addToQueue(track: Track) {
        // YT-0236 — when "Add to Queue" is invoked from a fully empty controller
        // (no current track AND empty queue) we surface the queued entry as the
        // current PAUSED track so the MiniPlayer (gated on currentTrack != null)
        // appears with the user's selection at queue index 0, ready for an
        // explicit play tap. We deliberately do NOT call playbackTransport.playTrack
        // / stopAndClearCurrent / startProgressUpdates: the semantic of "Add to
        // Queue" is "queue this, don't play it now."
        //
        // YT-0236 (re-fix 2026-05-08T22:35) — pin `engineLoaded = false` on the
        // empty-state branch so [resume] knows to bootstrap engine playback via
        // `playQueueItem(...)` instead of forwarding to `playbackTransport.resume()`.
        // Without this, `MediaController.play()` runs against an empty ExoPlayer
        // timeline → STATE_ENDED → `handleTrackEnded()` → IDLE with
        // `positionMs = durationMs` (audio never starts, slider jumps to end).
        mutablePlayerState.update { state ->
            val queueItem = track.toQueueItem()
            val updatedQueue = state.queue + queueItem
            if (state.currentTrack == null && state.queue.isEmpty()) {
                state.copy(
                    queue = updatedQueue,
                    currentTrack = track,
                    currentQueueIndex = 0,
                    playbackStatus = PlaybackStatus.PAUSED,
                    isPlaying = false,
                    durationMs = track.durationMs,
                    positionMs = 0L,
                    engineLoaded = false,
                )
            } else {
                state.copy(queue = updatedQueue)
            }
        }
    }

    override suspend fun playNext(track: Track) {
        // YT-0236 — same empty-controller edge case as `addToQueue`: surface the
        // inserted entry as the current PAUSED track so the MiniPlayer appears.
        // No transport play / clear / progress side-effects.
        // See `addToQueue` for the `engineLoaded = false` rationale.
        mutablePlayerState.update { state ->
            val queueItem = track.toQueueItem()
            if (state.currentTrack == null && state.queue.isEmpty()) {
                state.copy(
                    queue = listOf(queueItem),
                    currentTrack = track,
                    currentQueueIndex = 0,
                    playbackStatus = PlaybackStatus.PAUSED,
                    isPlaying = false,
                    durationMs = track.durationMs,
                    positionMs = 0L,
                    engineLoaded = false,
                )
            } else {
                val insertIndex = if (state.currentQueueIndex in state.queue.indices) {
                    state.currentQueueIndex + 1
                } else {
                    state.queue.size
                }
                state.copy(
                    queue = state.queue.toMutableList()
                        .apply { add(insertIndex.coerceIn(0, size), queueItem) },
                )
            }
        }
    }

    override suspend fun skipNext() {
        val nextIndex = mutablePlayerState.value.currentQueueIndex + 1
        playQueueItemAt(nextIndex)
    }

    override suspend fun skipPrevious() {
        // YT-0239 (round-3 spec amendment, 2026-05-08T23:30) — behaviour table:
        //  - idx > 0  + position >  RESTART_THRESHOLD_MS → seekTo(0) of current.
        //  - idx > 0  + position <= RESTART_THRESHOLD_MS → playQueueItemAt(idx - 1).
        //  - idx == 0 + position >  RESTART_THRESHOLD_MS → seekTo(0) of current.
        //  - idx == 0 + position <= RESTART_THRESHOLD_MS → seekTo(0) of current (NEW).
        // The idx=0 branch previously routed to `playQueueItemAt(-1)` which silently
        // returned (no queue item at -1), making the lock-screen prev button a no-op
        // at the head of the queue. Reviewer-led smoke surfaced that the user expects
        // to be able to rewind the first track from the lock-screen, so the idx=0
        // sub-threshold case now falls through to the same `seekTo(0L)` rewind as the
        // super-threshold case. The custom-layout `hasPrev` derivation in
        // `PlaybackService` is widened in lockstep so the button stays visible at
        // every queue position. See `PlaybackServiceLayoutTest`.
        val state = mutablePlayerState.value
        if (state.positionMs > RESTART_THRESHOLD_MS) {
            seekTo(0L)
            return
        }
        val previousIndex = state.currentQueueIndex - 1
        if (previousIndex < 0) {
            // Already at the head of the queue with position <= threshold — rewind the
            // current track instead of returning a silent no-op.
            seekTo(0L)
            return
        }
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
        // YT-0236 (re-fix 2026-05-08T22:35) — when the current track was surfaced via
        // `addToQueue` / `playNext` from an empty controller, the underlying engine has
        // NO MediaItem loaded for it. Forwarding to `playbackTransport.resume()` here
        // would run `MediaController.play()` against an empty timeline, which ExoPlayer
        // resolves as STATE_ENDED → `handleTrackEnded()` → IDLE with
        // `positionMs = durationMs` (audio never starts, slider jumps to end). Bootstrap
        // engine playback via `playQueueItem(...)` instead so the staged paused track
        // becomes a real, audible play on first tap.
        if (!state.engineLoaded) {
            val queueItem = state.queue.getOrNull(state.currentQueueIndex)
            if (queueItem != null) {
                mutablePlayerState.update {
                    it.copy(
                        playbackStatus = PlaybackStatus.LOADING,
                        isPlaying = false,
                        errorMessage = null,
                    )
                }
                playQueueItem(queueItem)
                return
            }
            // No queue item to bootstrap with — fall through to the normal resume path
            // so we at least mirror state. This is defensive; in practice `currentTrack`
            // != null implies a queue entry exists at `currentQueueIndex`.
        }
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
                // YT-0236 — engine timeline now holds a MediaItem for [queueItem]; flip the
                // flag so subsequent `resume()` calls forward to `playbackTransport.resume()`
                // instead of re-bootstrapping via `playQueueItem(...)`.
                mutablePlayerState.update {
                    it.copy(
                        currentTrack = queueItem.track,
                        playbackStatus = PlaybackStatus.PLAYING,
                        isPlaying = true,
                        positionMs = 0L,
                        durationMs = queueItem.track.durationMs,
                        errorMessage = null,
                        engineLoaded = true,
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
                // YT-0236 — engine failed to load the MediaItem; keep `engineLoaded = false`
                // so any later `resume()` re-attempts the load via `playQueueItem(...)`.
                mutablePlayerState.update {
                    it.copy(
                        currentTrack = queueItem.track,
                        playbackStatus = PlaybackStatus.ERROR,
                        isPlaying = false,
                        errorMessage = result.message,
                        engineLoaded = false,
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
