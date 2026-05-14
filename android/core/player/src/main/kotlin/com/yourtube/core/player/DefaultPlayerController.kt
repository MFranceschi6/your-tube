package com.yourtube.core.player

import com.yourtube.core.common.model.PlaybackStatus
import com.yourtube.core.common.model.PlayerState
import com.yourtube.core.common.model.QueueItem
import com.yourtube.core.common.model.SleepTimerPreset
import com.yourtube.core.common.model.SleepTimerState
import com.yourtube.core.common.model.Track
import com.yourtube.core.data.preferences.AudioQualityPreferences
import com.yourtube.core.data.preferences.PlaybackSpeedPreferences
import com.yourtube.core.data.repository.PlayerSnapshotRepository
import com.yourtube.core.data.repository.PlaylistRepository
import com.yourtube.core.database.entity.PlayerSnapshotEntity
import com.yourtube.core.network.YoutubeService
import dagger.Lazy
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Singleton
class DefaultPlayerController @Inject constructor(
    private val playbackTransport: PlaybackTransport,
    @MainDispatcher private val dispatcher: CoroutineDispatcher,
    private val audioQualityPreferences: AudioQualityPreferences,
    private val playlistRepository: PlaylistRepository,
    private val perfTracer: PlaybackPerfTracer,
    private val playbackSpeedPreferences: PlaybackSpeedPreferences,
    private val playerSnapshotRepository: PlayerSnapshotRepository,
    private val autoplayController: AutoplayController,
    // YT-0283 — wrapped in Lazy<> to break the DI cycle:
    //   DefaultPlayerController → SleepTimerController
    //   DefaultSleepTimerController → PlayerController
    // Lazy defers resolution until the first .get() call, which happens after both
    // singletons are created (same pattern used in DefaultAutoplayController for
    // the analogous PlayerController → AutoplayController cycle).
    private val sleepTimerController: Lazy<SleepTimerController>,
    // YT-0294 — Mix queue fetch. Already a singleton in Hilt (bound via NetworkModule);
    // no Lazy wrapping needed because YoutubeService does not depend on PlayerController.
    private val youtubeService: YoutubeService,
) : PlayerController {

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutablePlayerState = MutableStateFlow(PlayerState())
    private val snapshotJson = Json { ignoreUnknownKeys = true }
    private var progressJob: Job? = null

    // YT-0244 — most recent buffering signal from the transport. Cached so
    // `handleIsPlayingChanged(false)` can suppress the spurious PAUSED clobber that
    // `Player.isPlaying = false` would otherwise trigger during an in-track seek's
    // STATE_BUFFERING window. `@Volatile` is sufficient: both the buffering callback and
    // the isPlaying callback land on the controller's main dispatcher, so the field is
    // never read off-thread.
    @Volatile
    private var isBuffering: Boolean = false

    // YT-0249 — single-flight job that owns the current `playQueueItem(...)` resolve.
    // Each new track-change cancels the in-flight resolve before launching its own; the
    // launched body gates its post-resolve `Success` write on `coroutineContext.ensureActive()`
    // so a stale Success that lands after the cancel cannot clobber the latest target's
    // PlayerState. Updated only from the controller's main dispatcher (see [scope]) so
    // a plain `var` is sufficient.
    private var currentLoadJob: Job? = null

    // YT-0272 — restored seek offset carried from [restoreFromSnapshot] into the first
    // [runPlayQueueItem] Success path. Set once at restore time; consumed (and cleared to 0)
    // on the first successful engine load so subsequent track changes start at 0 as normal.
    private var restoredPositionMs: Long = 0L

    // YT-0297 — Mix continuation pagination state.
    //
    // Holds the continuation token captured from the initial Mix load (and refreshed on
    // every subsequent continuation page) plus the in-flight prefetch job, if any. Lives
    // for the lifetime of one Mix walk:
    //  - Set in [tryLoadMixQueue] when [seedVideoId] is the current track and the queue
    //    is still just the seed (same guards YT-0294 already enforces).
    //  - Refreshed by [maybeFetchMixContinuation] on every successful continuation page;
    //    cleared when the server stops returning `nextToken` (terminal page) so the
    //    existing autoplay-related fallback can take over at the queue tail.
    //  - Cancelled and cleared on every [playNow] / [setQueueAndPlay] so a fresh tap
    //    starts a fresh Mix and the stale fetch cannot append to the new queue.
    //
    // All reads and writes happen on the controller's main dispatcher (see [scope]) so a
    // plain `var` is sufficient — no atomic or volatile needed.
    private var mixContinuationToken: String? = null
    private var mixContinuationJob: Job? = null

    // YT-0300 — single-flight guard for the eager Mix seed. Tracks the videoId for
    // which seeding was last launched; cleared by cancelMixContinuation() on playNow.
    private var mixSeedJob: Job? = null
    private var mixSeedVideoId: String? = null

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

            override fun onBufferingStateChanged(isBuffering: Boolean) {
                handleBufferingStateChanged(isBuffering)
            }

            override fun onEngineUnloaded() {
                handleEngineUnloaded()
            }
        })
    }

    private suspend fun handleTrackEnded() {
        // YT-0283 — check for an armed EndOfTrack sleep timer BEFORE doing any queue
        // advance or autoplay fetch. The previous order (advance first, timer reacts to
        // the video-ID change) allowed the next track to audibly start for ~2 seconds
        // before the EndOfTrack watcher in DefaultSleepTimerController fired. By
        // intercepting here we guarantee a clean, silent stop at the exact track boundary.
        val timerState = sleepTimerController.get().timerState.value
        if (timerState is SleepTimerState.Active &&
            timerState.preset == SleepTimerPreset.EndOfTrack
        ) {
            sleepTimerController.get().cancel()
            pause()
            return
        }

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
        // YT-0297 — before falling back to autoplay-related at the tail, try to extend
        // the Mix via continuation. Returns `true` only if the Mix actually extended and
        // a new index has been advanced into. See [tryExtendMixAtTail] for wait semantics.
        if (tryExtendMixAtTail()) return

        // No next item — attempt autoplay before settling into idle.
        val finishedVideoId = state.currentTrack?.videoId.orEmpty()
        if (tryAutoplayAdvance(finishedVideoId)) return

        // No next item and no autoplay candidate — settle into an ended/idle state.
        // We keep `currentTrack` populated so the in-app UI can keep showing what was
        // last playing (matching how Spotify et al. behave at queue end), but cancel
        // the synthetic progress tick and flip to IDLE.
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
     * YT-0291 — shared autoplay-fetch-and-advance logic used by both the natural
     * end-of-track path ([handleTrackEnded]) and the user-initiated skip-next path
     * ([skipNext]) when [skipNext] is called on the last queue item.
     *
     * Returns `true` only when a candidate was fetched, enqueued, **and** the
     * advance to it was issued via [playQueueItemAt]. Returns `false` for autoplay
     * OFF, empty/blank [finishedVideoId], fetch failure, or an empty candidate set.
     *
     * Uses `queue.lastIndex` after a successful [fetchAndEnqueue] (per YT-0288 race
     * fix) rather than a captured index so the correct tail entry is addressed
     * regardless of concurrent queue mutations.
     */
    private suspend fun tryAutoplayAdvance(finishedVideoId: String): Boolean {
        if (finishedVideoId.isBlank()) return false

        // Stop current audio immediately so the previous track does not keep playing
        // during the fetch window. State is set to LOADING so the UI shows an affordance.
        progressJob?.cancel()
        playbackTransport.stopAndClearCurrent()
        mutablePlayerState.update {
            it.copy(playbackStatus = PlaybackStatus.LOADING, isPlaying = false)
        }

        val enqueued = runCatching {
            autoplayController.fetchAndEnqueue(finishedVideoId)
        }.getOrDefault(false)

        if (!enqueued) {
            // No candidate / fetch failure / autoplay OFF — clear the loading state.
            mutablePlayerState.update {
                it.copy(playbackStatus = PlaybackStatus.IDLE, positionMs = it.durationMs)
            }
            return false
        }

        val autoplayIndex = mutablePlayerState.value.queue.lastIndex
        if (autoplayIndex < 0) {
            mutablePlayerState.update {
                it.copy(playbackStatus = PlaybackStatus.IDLE, positionMs = it.durationMs)
            }
            return false
        }

        mutablePlayerState.value.queue[autoplayIndex].track.videoId.let { videoId ->
            perfTracer.markTap(videoId, source = "autoplay")
        }
        playQueueItemAt(autoplayIndex)
        return true
    }

    /**
     * YT-0297 — Mix continuation fallback that runs at the queue tail BEFORE
     * autoplay-related. Returns `true` only when the Mix actually extended and the
     * player has been advanced to the freshly-appended next index.
     *
     * Wait semantics (kept deliberately simple per the YT-0297 brief):
     *  - If a continuation fetch is already in flight, await it with a [MIX_TAIL_WAIT_MS]
     *    timeout. The fetch was queued by an earlier near-tail prefetch; we just wait
     *    for it to land.
     *  - If no fetch is in flight but a continuation token is held, kick one off and
     *    await it with the same timeout. This handles the case where the user skipped
     *    past the prefetch trigger faster than the network responded, or the prefetch
     *    threshold was never crossed (e.g. very short initial Mix page).
     *  - If neither a token nor an in-flight fetch is held, return `false` immediately
     *    so the caller falls through to [tryAutoplayAdvance].
     *
     * The timeout exists because the contract is "feel infinite, but don't strand the
     * player at the tail forever". Two seconds is comfortably above typical InnerTube
     * `/next` round-trip times (300–800 ms observed) but short enough that the user
     * does not perceive a stall before the autoplay-related fallback fires.
     */
    private suspend fun tryExtendMixAtTail(): Boolean {
        val tailIndexBefore = mutablePlayerState.value.queue.lastIndex
        val job = mixContinuationJob ?: run {
            val token = mixContinuationToken ?: return false
            launchMixContinuation(token)
        }

        // Await the fetch with a bounded timeout. If the job is already complete this
        // returns immediately; if not, we wait up to MIX_TAIL_WAIT_MS for it.
        withTimeoutOrNull(MIX_TAIL_WAIT_MS) { job.join() }

        // Did the queue actually grow? If so, advance into the next index; otherwise the
        // continuation failed / returned no novel items, so fall through to the related
        // autoplay path. We re-read state after the await — both [currentQueueIndex] and
        // [queue] may have moved if a parallel transition raced.
        val state = mutablePlayerState.value
        val nextIndex = state.currentQueueIndex + 1
        return if (state.queue.lastIndex > tailIndexBefore && nextIndex < state.queue.size) {
            playQueueItemAt(nextIndex)
            true
        } else {
            false
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
        var indexAdvanced = false
        mutablePlayerState.update { state ->
            val matchedIndex = state.queue.indexOfFirst { it.track.videoId == mediaId }
            if (matchedIndex == -1 || matchedIndex == state.currentQueueIndex) {
                state
            } else {
                val matchedTrack = state.queue[matchedIndex].track
                indexAdvanced = matchedIndex > state.currentQueueIndex
                state.copy(
                    currentTrack = matchedTrack,
                    currentQueueIndex = matchedIndex,
                    positionMs = 0L,
                    durationMs = matchedTrack.durationSec.coerceAtLeast(0) * 1000L,
                    errorMessage = null,
                )
            }
        }
        // YT-0297 — system-shell skip-next (lock screen / notification) advances the
        // engine timeline and reaches us here, not through [playQueueItemAt]. Trigger
        // the same prefetch check so the Mix can extend when the user is driving from
        // the system shell. Only fires on forward transitions.
        if (indexAdvanced) {
            maybePrefetchMixContinuation()
            val s = mutablePlayerState.value
            maybeEagerSeedMix(s.currentQueueIndex, s.currentTrack?.videoId ?: "")
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
            // Suppress engine-side position resets during LOADING. stopAndClearCurrent()
            // issues seekTo(0) before playTrack, which would flash the scrubber to 0 while
            // the UI is showing the intended resume position. LOADING owns positionMs until
            // the Success / Failure branch resolves it.
            if (state.playbackStatus == PlaybackStatus.LOADING) return@update state
            // Also suppress an incoming 0 when a restore position is pending. ExoPlayer
            // processes seekTo(0) asynchronously — onPositionDiscontinuity(0) can arrive
            // AFTER the Failure/null branch has already written PAUSED+positionMs=startPos.
            // Letting it through would zero out the scrubber the user is watching.
            if (positionMs == 0L && restoredPositionMs > 0L) return@update state
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
        //
        // YT-0244 — additionally suppress the false→PAUSED clobber while the engine is
        // buffering. ExoPlayer drops `Player.isPlaying` to `false` during STATE_BUFFERING
        // even though the user did not pause; the BUFFERING state owns the status during
        // that window (see [handleBufferingStateChanged]). Without this guard, scrubbing
        // the slider mid-playback flips status to PAUSED until STATE_READY restores it,
        // which the user perceives as a "fake pause" flash on the play/pause button.
        mutablePlayerState.update { state ->
            when {
                state.playbackStatus == PlaybackStatus.ERROR -> state
                state.playbackStatus == PlaybackStatus.LOADING && !isPlaying -> state
                isBuffering && !isPlaying -> state
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

        // YT-0245 — re-arm the progress-tick job whenever isPlaying flips back to `true`.
        // The progress loop in [startProgressUpdates] breaks itself when `isPlaying` flips
        // to `false` (engine buffering, lock-screen pause, audio-focus loss); without this
        // restart the slider would stay frozen at the last tick after the engine resumes.
        // Idempotent: `startProgressUpdates()` cancels any prior job before launching, and
        // the active-check below skips the cancel/relaunch churn when the loop is still
        // running (true→true flips). With YT-0244 in place the BUFFERING-aware suppression
        // means the engine-side false→true round-trip is mostly absorbed inside the
        // buffering callback, so this restart fires primarily on user-driven resume /
        // focus-regain — still correct, since [resume] already starts its own job and the
        // active-check makes the duplicate path a no-op.
        if (isPlaying && progressJob?.isActive != true) {
            startProgressUpdates()
        }
    }

    /**
     * YT-0244 — propagates the engine's STATE_BUFFERING / STATE_READY transitions into
     * [PlayerState.playbackStatus] so the NowPlaying / MiniPlayer surfaces show the loading
     * spinner during in-track re-buffers. Caches the latest value into [isBuffering] so
     * [handleIsPlayingChanged] can suppress the spurious PAUSED clobber that would otherwise
     * fire from the engine-side `isPlaying = false` during a buffer.
     *
     * Transition rules (rest of the state machine left untouched):
     * - On `true`: PLAYING / PAUSED → BUFFERING. LOADING and ERROR are sticky — the
     *   controller is mid-resolve / has surfaced a transport failure and the buffering
     *   ping is engine bookkeeping that must not clobber either.
     * - On `false`: BUFFERING → PLAYING when the controller still believes audio is
     *   intended to play (`isPlaying == true`); otherwise BUFFERING → PAUSED. Idempotent
     *   for already-not-buffering: leaves PLAYING / PAUSED / IDLE / LOADING / ERROR alone.
     */
    private fun handleBufferingStateChanged(isBuffering: Boolean) {
        this.isBuffering = isBuffering
        var resumedToPlaying = false
        mutablePlayerState.update { state ->
            when {
                isBuffering -> when (state.playbackStatus) {
                    PlaybackStatus.PLAYING, PlaybackStatus.PAUSED ->
                        state.copy(playbackStatus = PlaybackStatus.BUFFERING)
                    else -> state
                }
                state.playbackStatus == PlaybackStatus.BUFFERING -> {
                    val resolved = if (state.isPlaying) {
                        PlaybackStatus.PLAYING
                    } else {
                        PlaybackStatus.PAUSED
                    }
                    if (resolved == PlaybackStatus.PLAYING) resumedToPlaying = true
                    state.copy(playbackStatus = resolved)
                }
                else -> state
            }
        }
        // YT-0245: progress loop breaks on BUFFERING (see startProgressUpdates) so the
        // BUFFERING→PLAYING resolve must restart it. startProgressUpdates is idempotent.
        if (resumedToPlaying) startProgressUpdates()
    }

    /**
     * YT-0309 round-5 — engine dropped to `Player.STATE_IDLE` after having been non-IDLE
     * (real-world buffer-drained-to-IDLE during airplane mode mid-playback). Resets
     * `engineLoaded = false` so the next `resume()` re-enters the `playQueueItem` branch
     * (which carries the 15s `withTimeoutOrNull` ceiling) instead of forwarding to
     * `playbackTransport.resume()` against an empty engine — the bug the round-5 smoke
     * surfaced.
     *
     * Also forces `isBuffering = false` and clears a stuck `BUFFERING` status to `PAUSED`
     * so the spinner does not outlive the engine. Leaves `LOADING` / `ERROR` / `IDLE`
     * alone (they own the status during their own state-machine windows).
     */
    private fun handleEngineUnloaded() {
        isBuffering = false
        progressJob?.cancel()
        // YT-0309 — save playback position before the engine resets. Media3 calls
        // onPlaybackStateChanged(STATE_IDLE) BEFORE onIsPlayingChanged(false), so positionMs
        // is still the correct drain position at this point (logcat confirmed: positionMs=22s
        // when handleEngineUnloaded fires, before any PAUSED transition). Ensures the next
        // resume() re-enters playQueueItem at the right offset (round-10 fix).
        val pos = mutablePlayerState.value.positionMs
        if (pos > 0L) {
            restoredPositionMs = pos
        }
        mutablePlayerState.update { state ->
            val clearedStatus = when (state.playbackStatus) {
                PlaybackStatus.BUFFERING, PlaybackStatus.PLAYING -> PlaybackStatus.PAUSED
                else -> state.playbackStatus
            }
            state.copy(
                engineLoaded = false,
                playbackStatus = clearedStatus,
                isPlaying = false,
            )
        }
    }

    override suspend fun setQueueAndPlay(tracks: List<Track>, startIndex: Int) {
        if (tracks.isEmpty()) return
        // YT-0289 — a manual track-switch always overrides an active sleep timer,
        // regardless of preset. The user explicitly chose to play something new,
        // so their "stop at end" (or time-based) intent is rescinded. Cancel before
        // any state mutation so no race window exists where the timer could fire
        // against the new track.
        cancelSleepTimerIfActive()
        // YT-0297 — abandon any in-flight Mix continuation prefetch and clear the held
        // token so the new queue starts a fresh Mix walk. Without this, a continuation
        // page resolving after a manual `setQueueAndPlay` could append stale Mix entries
        // to the new queue (the per-update guard would catch the seed-change case for the
        // common 1-item queue, but `setQueueAndPlay` does not guarantee `queue.size == 1`,
        // so cancellation is the only safe path).
        cancelMixContinuation()
        // YT-0290 — clear any pending restore-seek so the new track always starts at
        // position 0. restoredPositionMs is set by restoreFromSnapshot and must only
        // survive until the first runPlayQueueItem on the SAME restored track. A
        // user-initiated play replaces that intent entirely.
        restoredPositionMs = 0L
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
        // YT-0300 — seed Mix eagerly when starting track is the queue tail (covers the
        // Library-playlist → Play entry point that bypasses playQueueItemAt).
        maybeEagerSeedMix(safeIndex, starting.track.videoId)
    }

    override suspend fun playNow(track: Track) {
        // YT-0289 — a manual track-switch always overrides an active sleep timer,
        // regardless of preset. Cancel before any state mutation so no race window
        // exists where the timer could fire against the incoming track.
        // See [cancelSleepTimerIfActive] for the cancellation policy.
        cancelSleepTimerIfActive()
        // YT-0297 — fresh tap starts a fresh Mix. Cancel any in-flight continuation
        // prefetch and drop the held token so a stale page that resolves after this
        // call cannot append to the new Mix. The per-update guard in
        // [maybeFetchMixContinuation] also checks `currentTrack?.videoId == seedVideoId`
        // for the same reason, but cancelling the Job is the deterministic guarantee.
        cancelMixContinuation()
        // YT-0290 — clear any pending restore-seek so the new track always starts at
        // position 0. restoredPositionMs is set by restoreFromSnapshot and must only
        // survive until the first runPlayQueueItem on the SAME restored track. A
        // user-initiated play replaces that intent entirely.
        restoredPositionMs = 0L
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
        // YT-0294 — fire-and-forget Mix queue population. Runs after the seed track has
        // started (playQueueItem returns when the transport settle path completes or fails).
        // The coroutine is launched on the controller scope so it does not block the caller.
        scope.launch { tryLoadMixQueue(track.videoId) }
    }

    /**
     * YT-0294 / YT-0297 — fetches the initial Mix page for [seedVideoId] and appends the
     * tail (all entries after the seed itself) to the playback queue, provided the
     * controller is still on the same seed track and the queue has not been user-modified.
     *
     * YT-0297 — uses [YoutubeService.getMixQueueWithContinuation] so the first continuation
     * token is captured into [mixContinuationToken] for later lazy pagination via
     * [maybeFetchMixContinuation]. If the server does not return a token on the initial
     * page (rare), [mixContinuationToken] stays null and the existing autoplay-related
     * fallback runs at the queue tail.
     *
     * Guards:
     *  - Only extends if `state.currentTrack?.videoId == seedVideoId` (stale seed check).
     *  - Only extends if `state.queue.size == 1` (user may have added items manually).
     *  - Filters Shorts (`durationSec < MIN_MIX_DURATION_SEC`) and zero-duration entries
     *    (livestreams) per `docs/mix-queue.md §Filtering`.
     *  - Silently returns on any failure — never throws, never toasts.
     */
    private suspend fun tryLoadMixQueue(seedVideoId: String) {
        val page = runCatching {
            youtubeService.getMixQueueWithContinuation(seedVideoId)
        }.getOrNull() ?: return

        // First entry is the seed itself — skip it; it's already at index 0.
        val tail = page.items.drop(1).filter {
            it.videoId.isNotBlank() && it.durationSec >= MIN_MIX_DURATION_SEC
        }

        val items = tail.map { result ->
            Track(
                videoId = result.videoId,
                title = result.title,
                channel = result.channel,
                durationSec = result.durationSec,
                thumbnailUrl = result.thumbnailUrl,
            ).toQueueItem()
        }

        var accepted = false
        mutablePlayerState.update { state ->
            // Guard: only extend if the controller is still on the same seed track
            // and the queue still only has the seed (user may have added items manually).
            if (state.currentTrack?.videoId != seedVideoId) return@update state
            if (state.queue.size != 1) return@update state
            accepted = true
            if (items.isEmpty()) state else state.copy(queue = state.queue + items)
        }
        // Only retain the continuation token when we actually applied this Mix to the
        // queue. If a parallel `playNow` raced past us between fetch and update, the
        // queue belongs to a different seed and any token we hold would paginate against
        // that wrong Mix.
        if (accepted) {
            mixContinuationToken = page.nextToken?.takeIf { it.isNotBlank() }
        }
    }

    /**
     * YT-0300 — fires a single-flight eager Mix-seed when [index] is the queue tail
     * and the queue is not already a Mix queue. Idempotent for the same [videoId]
     * so skip-prev + skip-next does not re-fetch.
     */
    private fun maybeEagerSeedMix(index: Int, videoId: String) {
        val state = mutablePlayerState.value
        if (index < state.queue.lastIndex) return          // not at tail
        if (mixContinuationToken != null) return           // already a Mix
        if (videoId.isBlank()) return
        if (videoId == mixSeedVideoId) return              // already seeded for this track
        mixSeedVideoId = videoId
        mixSeedJob?.cancel()
        mixSeedJob = scope.launch { seedMixFromCurrentTrack(videoId) }
    }

    /**
     * YT-0300 — seeds a Mix queue from the currently-playing last track while it is
     * still playing. Appends the Mix tail (all entries after the seed itself) to the
     * queue so playback can continue seamlessly when the current track ends.
     * Never throws; all failures are silent.
     */
    private suspend fun seedMixFromCurrentTrack(seedVideoId: String) {
        if (!autoplayController.isAutoplayEnabled()) return

        val page = runCatching {
            youtubeService.getMixQueueWithContinuation(seedVideoId)
        }.getOrNull() ?: run {
            // Network / parse failure — reset guard so next visit to last position retries.
            mixSeedVideoId = null
            return
        }

        val tail = page.items.drop(1).filter {
            it.videoId.isNotBlank() && it.durationSec >= MIN_MIX_DURATION_SEC
        }
        val items = tail.map { result ->
            Track(
                videoId = result.videoId,
                title = result.title,
                channel = result.channel,
                durationSec = result.durationSec,
                thumbnailUrl = result.thumbnailUrl,
            ).toQueueItem()
        }

        var accepted = false
        mutablePlayerState.update { state ->
            if (state.currentTrack?.videoId != seedVideoId) return@update state
            if (mixContinuationToken != null) return@update state
            accepted = true
            if (items.isEmpty()) state else {
                val existing = state.queue.mapTo(HashSet(state.queue.size)) { it.track.videoId }
                val novel = items.filter { it.track.videoId !in existing }
                if (novel.isEmpty()) state else state.copy(queue = state.queue + novel)
            }
        }
        if (accepted) {
            mixContinuationToken = page.nextToken?.takeIf { it.isNotBlank() }
        } else {
            // Empty page or guard mismatch (track changed while fetching) — reset so
            // the next visit to the last position retries when conditions are right.
            mixSeedVideoId = null
        }
    }

    /**
     * YT-0297 — lazy continuation prefetch. Called from forward index transitions
     * ([playQueueItemAt], [handleMediaItemTransition]). Triggers a single continuation
     * fetch when the current index is within [PREFETCH_THRESHOLD] of the queue tail,
     * provided a continuation token is held and no fetch is already in flight.
     *
     * The launch runs on the controller's [scope] so cancellation by a subsequent
     * [playNow] / [setQueueAndPlay] (via [cancelMixContinuation]) propagates cleanly.
     * On success we append filtered + deduped items to the queue tail and refresh the
     * held token. On failure or empty result we keep the token (so a transient blip
     * does not strand the Mix) but clear the in-flight handle so the next near-tail
     * transition can retry. When the server returns `nextToken == null` the token is
     * cleared so the autoplay-related fallback can take over at the tail.
     */
    private fun maybePrefetchMixContinuation() {
        val state = mutablePlayerState.value
        val token = mixContinuationToken ?: return
        if (mixContinuationJob != null) return
        if (state.queue.isEmpty()) return
        if (state.currentQueueIndex < state.queue.size - PREFETCH_THRESHOLD) return
        mixContinuationJob = scope.launch { fetchMixContinuation(token) }
    }

    /**
     * YT-0297 — synchronous-flavour continuation fetch used by [tryAutoplayAdvance] at
     * the queue tail when a token is held but no fetch is in flight yet. Re-uses the
     * same fetch implementation as [maybePrefetchMixContinuation] so the launch / token
     * lifecycle is identical. Returns the launched job so the caller can await it.
     */
    private fun launchMixContinuation(token: String): Job {
        if (mixContinuationJob != null) return mixContinuationJob!!
        val job = scope.launch { fetchMixContinuation(token) }
        mixContinuationJob = job
        return job
    }

    private suspend fun fetchMixContinuation(token: String) {
        val page = try {
            youtubeService.getMixContinuation(token)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            // Propagate cancellation; do NOT clear the token — the controller has already
            // moved on (playNow / setQueueAndPlay) and the cancel path has cleared state.
            mixContinuationJob = null
            throw e
        } catch (_: Throwable) {
            // Transient failure: keep the token so the next near-tail transition retries.
            mixContinuationJob = null
            return
        }

        // Filter Shorts / zero-duration entries (mirror initial Mix filter rule).
        val filtered = page.items.filter {
            it.videoId.isNotBlank() && it.durationSec >= MIN_MIX_DURATION_SEC
        }

        // Dedup against entries already in the queue. `docs/mix-queue.md` notes that very
        // long Mix walks eventually start recycling earlier videoIds; the contract
        // explicitly recommends consumer-side dedup against the in-memory queue.
        mutablePlayerState.update { state ->
            val existing = state.queue.mapTo(HashSet(state.queue.size)) { it.track.videoId }
            val novel = filtered.filter { it.videoId !in existing }
            if (novel.isEmpty()) return@update state
            val appended = novel.map { result ->
                Track(
                    videoId = result.videoId,
                    title = result.title,
                    channel = result.channel,
                    durationSec = result.durationSec,
                    thumbnailUrl = result.thumbnailUrl,
                ).toQueueItem()
            }
            state.copy(queue = state.queue + appended)
        }

        // Refresh the held token. `null` (or blank) means the server ran out of pages —
        // clear so the autoplay-related fallback takes over at the queue tail.
        mixContinuationToken = page.nextToken?.takeIf { it.isNotBlank() }
        mixContinuationJob = null
    }

    /**
     * YT-0297 — cancel and clear all Mix-continuation state. Invoked by [playNow] and
     * [setQueueAndPlay] so a manual track-switch always starts a fresh Mix walk.
     */
    private fun cancelMixContinuation() {
        mixContinuationJob?.cancel()
        mixContinuationJob = null
        mixContinuationToken = null
        mixSeedJob?.cancel()      // YT-0300
        mixSeedJob = null         // YT-0300
        mixSeedVideoId = null     // YT-0300
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
        saveSnapshot()
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
        saveSnapshot()
    }

    override suspend fun skipNext() {
        val state = mutablePlayerState.value
        val nextIndex = state.currentQueueIndex + 1
        if (nextIndex < state.queue.size) {
            playQueueItemAt(nextIndex)
            return
        }
        // YT-0297 — at the queue tail, try to extend the Mix via continuation before
        // falling back to autoplay-related. If the Mix extends, [tryExtendMixAtTail]
        // advances to the freshly-appended next track and returns true.
        if (tryExtendMixAtTail()) return
        // At the end of the queue and no Mix to extend — delegate to autoplay if enabled;
        // silent no-op if off.
        tryAutoplayAdvance(state.currentTrack?.videoId.orEmpty())
    }

    override suspend fun skipPrevious() {
        // YT-0311 — industry-standard skip-back behaviour table:
        //  - engineLoaded = false (LOADING)               → walk back (restart meaningless, nothing playing)
        //  - engineLoaded = true  + position >  SKIP_BACK_RESTART_THRESHOLD_MS → seekTo(0) of current
        //  - engineLoaded = true  + position <= SKIP_BACK_RESTART_THRESHOLD_MS + idx > 0  → playQueueItemAt(idx - 1)
        //  - engineLoaded = true  + position <= SKIP_BACK_RESTART_THRESHOLD_MS + idx == 0 → seekTo(0) of current
        //    (no previous track to jump to; restart is always more useful than a silent no-op)
        // Lockscreen / notification skip-prev routes here via PlaybackService (same contract as before).
        val state = mutablePlayerState.value
        if (state.engineLoaded && state.positionMs > SKIP_BACK_RESTART_THRESHOLD_MS) {
            seekTo(0L)
            return
        }
        val previousIndex = state.currentQueueIndex - 1
        if (previousIndex < 0) {
            // First track in queue — always restart rather than no-op.
            if (state.engineLoaded) seekTo(0L)
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
        saveSnapshot()
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
                        // YT-0309 — pin positionMs to the restore target so the scrubber
                        // shows the correct position during LOADING instead of jumping to 0
                        // when stopAndClearCurrent().seekTo(0) fires inside runPlayQueueItem.
                        positionMs = if (restoredPositionMs > 0L) restoredPositionMs else it.positionMs,
                    )
                }
                // YT-0300 — seed Mix eagerly when the resumed track is the queue tail
                // (covers addToQueue → Play path that bypasses playQueueItemAt).
                maybeEagerSeedMix(state.currentQueueIndex, queueItem.track.videoId)
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
                // YT-0287 — the new `currentTrack` (the successor item) has NEVER been
                // loaded into the engine. Marking `engineLoaded = false` ensures the next
                // `resume()` routes through `playQueueItem(...)` to bootstrap the engine
                // for the new track, instead of forwarding to `playbackTransport.resume()`
                // which would run `MediaController.play()` against the stale (now-removed)
                // track's MediaItem — or an empty timeline if the removed item was the last.
                engineLoaded = if (removingCurrentItem || newCurrentTrack == null) false else it.engineLoaded,
            )
        }
        saveSnapshot()
    }

    override suspend fun setShuffleMode(enabled: Boolean) {
        // Update controller state synchronously so the UI reflects the user's intent
        // immediately, even if the transport's player isn't attached yet (the engine
        // will reconcile on its next Player.Listener callback when it does attach).
        mutablePlayerState.update { state -> state.copy(shuffleOn = enabled) }
        playbackTransport.setShuffleMode(enabled)
        saveSnapshot()
    }

    override suspend fun setRepeatMode(mode: Int) {
        mutablePlayerState.update { state -> state.copy(repeatMode = mode) }
        playbackTransport.setRepeatMode(mode)
        saveSnapshot()
    }

    override suspend fun setPlaybackSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.5f, 2.0f)
        mutablePlayerState.update { state -> state.copy(playbackSpeed = clamped) }
        playbackSpeedPreferences.setSpeed(clamped)
        playbackTransport.setPlaybackSpeed(clamped)
        saveSnapshot()
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
        saveSnapshot()
    }

    override suspend fun jumpToQueueItem(index: Int) {
        val state = mutablePlayerState.value
        if (index !in state.queue.indices) return
        if (index == state.currentQueueIndex) return  // already current — no-op
        playQueueItemAt(index)
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
        // YT-0297 — index just advanced; if the new index is within PREFETCH_THRESHOLD of
        // the queue tail and a continuation token is held, kick off a lazy Mix prefetch.
        // Safe to call here (before the suspending [playQueueItem] resolve) because the
        // prefetch runs on [scope] and does not block this caller.
        maybePrefetchMixContinuation()
        maybeEagerSeedMix(index, queueItem.track.videoId)  // YT-0300
        playQueueItem(queueItem)
    }

    /**
     * YT-0249 — single-flight launcher. Cancels any in-flight `playQueueItem(...)` resolve
     * before kicking off a new one and captures the new launch's [Job] into [currentLoadJob]
     * so the next skip can cancel us in turn. The launch runs on the controller's [scope]
     * (Main.immediate by default) so cancellation propagates through Media3's
     * `ListenableFuture.await()` extension and aborts the in-flight `MediaController.sendCustomCommand`
     * via the cancellation handler in [MediaControllerPlaybackClient].
     *
     * Rapid skip taps on a long-resolving queue used to race: tap #2 mutated state
     * synchronously (new index + LOADING) and started its own resolve while tap #1's
     * `playTrack` was still suspended; whichever resolve happened to return last clobbered
     * the most recent target's PlayerState.Success write. The launch+cancel pattern collapses
     * the burst — only the LATEST target's resolve is allowed to mutate state on Success.
     *
     * The function suspends until the launched job completes (success, failure, or cancel)
     * so existing callers — `setQueueAndPlay`, `playNow`, `playQueueItemAt`, `resume`,
     * `handleTrackEnded` — keep their pre-existing semantics: they only return after the
     * resolve has settled (or been pre-empted by a newer skip). `Job.join()` does NOT throw
     * when the JOINED job is cancelled — only when the joining coroutine itself is cancelled
     * — so a skip-cancellation from another coroutine cleanly unwinds the previous tap's
     * `playQueueItem` call without spurious exceptions.
     */
    private suspend fun playQueueItem(queueItem: QueueItem) {
        currentLoadJob?.cancel()
        val job = scope.launch { runPlayQueueItem(queueItem) }
        currentLoadJob = job
        job.join()
    }

    private suspend fun runPlayQueueItem(queueItem: QueueItem) {
        progressJob?.cancel()
        // YT-0050: stop any currently-playing audio and clear the underlying
        // player's media items BEFORE we suspend on the network for stream URL
        // resolution. Without this step the previous track keeps producing
        // samples for the several hundred milliseconds the resolve takes, and
        // the user perceives a sluggish track switch. The state-flow update in
        // `playNow` / `playQueueItemAt` already reset positionMs/durationMs
        // synchronously; this call mirrors that reset onto the engine itself
        // and refreshes MediaSession metadata.
        //
        // YT-0249 — do NOT re-introduce `clearMediaItems` here on the cancel path.
        // The YT-0238 fix removed it precisely to avoid the STATE_ENDED cascade; the
        // launch+cancel pattern in [playQueueItem] handles the abort cleanly without
        // tearing down the timeline.
        playbackTransport.stopAndClearCurrent()
        // Snapshot current preferences at extraction time. `Flow.first()` is safe on any
        // dispatcher — DataStore delivers asynchronously through the suspending pipeline.
        val bitrate = audioQualityPreferences.bitrateKbps.first()
        val speed = playbackSpeedPreferences.speed.first()
        // YT-0291 — drain restoredPositionMs BEFORE playTrack so ExoPlayer receives the
        // start position via setMediaItem(item, startPositionMs). Buffering starts at the
        // correct offset, eliminating the unreliable post-load seekTo path (YT-0272).
        // YT-0309 round-7 — read but do NOT drain restoredPositionMs here. Draining before
        // playTrack returns means a Failure or timeout discards the saved position; the next
        // resume() call would then read 0 and start from the beginning. Drain only on Success.
        val startPos = restoredPositionMs
        // YT-0309 — bounded timeout for stream-URL resolution. If the network is
        // unavailable (airplane mode, offline), OkHttp can hang for tens of seconds;
        // this cap clears LOADING and re-enables the Play button so the user can retry.
        val result = withTimeoutOrNull(STREAM_RESOLVE_TIMEOUT_MS) {
            playbackTransport.playTrack(
                PlaybackRequest(
                    track = queueItem.track,
                    preferredMaxBitrateKbps = bitrate,
                    startPositionMs = startPos,
                )
            )
        }
        // YT-0249 — gate the post-resolve state mutation on the launched coroutine still
        // being active. If a later skip cancelled this job while `playTrack` was suspended
        // on the network resolve, a stale Success returning here MUST NOT clobber the
        // freshly-written PlayerState that the next launch already advanced past. Throws
        // CancellationException so the outer launch unwinds cleanly without recording
        // history or starting the progress loop for an aborted target.
        coroutineContext.ensureActive()
        when (result) {
            is PlaybackResult.Success -> {
                // YT-0309 round-7 — drain restoredPositionMs only on Success so Failure /
                // timeout paths leave it intact for the next resume() attempt.
                restoredPositionMs = 0L
                // YT-0236 — engine timeline now holds a MediaItem for [queueItem]; flip the
                // flag so subsequent `resume()` calls forward to `playbackTransport.resume()`
                // instead of re-bootstrapping via `playQueueItem(...)`.
                mutablePlayerState.update {
                    it.copy(
                        currentTrack = queueItem.track,
                        playbackStatus = PlaybackStatus.PLAYING,
                        isPlaying = true,
                        positionMs = startPos,  // mirrors ExoPlayer's start position
                        durationMs = queueItem.track.durationMs,
                        errorMessage = null,
                        engineLoaded = true,
                        playbackSpeed = speed,
                    )
                }
                // YT-0095 — re-apply persisted speed to the engine on every new track load
                // so the rate survives track changes (global persistence mode).
                playbackTransport.setPlaybackSpeed(speed)
                // YT-0291 — post-load seekTo removed; ExoPlayer already starts at startPos
                // via setMediaItem(item, startPositionMs) in PlaybackPlayerAdapter.queue().
                startProgressUpdates()
                recordPlayback(queueItem.track)
                // YT-0089 — register the started track in the autoplay history buffer so
                // loop-avoidance can exclude it from future autoplay candidate selection.
                autoplayController.onTrackStarted(queueItem.track.videoId)
                saveSnapshot()
            }
            is PlaybackResult.Failure -> {
                perfTracer.mark(
                    "FAIL",
                    queueItem.track.videoId,
                    "stage=transport msg=\"${result.message}\"",
                )
                // YT-0236 — engine failed to load the MediaItem; keep `engineLoaded = false`
                // so any later `resume()` re-attempts the load via `playQueueItem(...)`.
                //
                // YT-0309 round-6 — route Failure to PAUSED (same as timeout `null` branch
                // below) per AC#2. Network/OkHttp callTimeout fast-fail during airplane
                // mode used to clobber LOADING to ERROR within milliseconds; the user
                // perceived this as "tap-Play did nothing" because no spinner ever showed
                // and the Play button looked unchanged. PAUSED + engineLoaded=false makes
                // the next tap-Play re-enter the same playQueueItem path. `errorMessage`
                // is still captured for logging.
                playbackTransport.stopAndClearCurrent()
                mutablePlayerState.update {
                    it.copy(
                        currentTrack = queueItem.track,
                        playbackStatus = PlaybackStatus.PAUSED,
                        isPlaying = false,
                        errorMessage = result.message,
                        engineLoaded = false,
                        // Preserve the restore position so the scrubber stays at startPos.
                        // stopAndClearCurrent() fires seekTo(0) asynchronously — it arrives
                        // after this state update and is suppressed by handlePositionChanged
                        // while restoredPositionMs > 0.
                        positionMs = if (startPos > 0L) startPos else it.positionMs,
                    )
                }
            }
            null -> {
                // YT-0309 — timeout branch: stream resolve took too long (e.g. airplane mode).
                // Explicitly stop-and-clear the engine so any lingering STATE_BUFFERING raised
                // by the service-side ghost coroutine (which may still be in-flight) is silenced
                // before the new controller state is published. Without this, the service side
                // can call prepare()/play() after our state update, pushing ExoPlayer into
                // STATE_BUFFERING again and re-latching the spinner.
                //
                // Recovery vector: tap-to-retry. engineLoaded=false ensures the next resume()
                // re-enters playQueueItem. No ConnectivityManager.NetworkCallback is registered
                // — the user initiates recovery by re-tapping Play.
                playbackTransport.stopAndClearCurrent()
                perfTracer.mark("FAIL", queueItem.track.videoId, "stage=transport msg=\"resolve timeout\"")
                mutablePlayerState.update {
                    it.copy(
                        currentTrack = queueItem.track,
                        playbackStatus = PlaybackStatus.PAUSED,
                        isPlaying = false,
                        errorMessage = null,
                        engineLoaded = false,
                        positionMs = if (startPos > 0L) startPos else it.positionMs,
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

    /**
     * Fire-and-forget snapshot persist. Wrapped in `runCatching` so a transient DB
     * failure never surfaces as an unhandled exception on the controller scope.
     * Called only from within the controller's [scope] (already main dispatcher) so
     * reading [mutablePlayerState] is race-free.
     */
    private fun saveSnapshot() {
        scope.launch {
            runCatching { playerSnapshotRepository.save(mutablePlayerState.value) }
        }
    }

    /**
     * YT-0289 — cancels the sleep timer whenever the user explicitly starts a new track
     * ([playNow] or [setQueueAndPlay]). Policy: a manual track-switch always rescinds the
     * user's "stop at end" or time-based sleep intent, because they have actively chosen to
     * play new content. Applies to ALL [SleepTimerPreset] values (EndOfTrack, Min15, Min30,
     * Min45, Min60) — no preset is exempt from the manual-override contract.
     *
     * The check + cancel is a single synchronous read of [SleepTimerState] + a [cancel] call
     * (both on the controller's Main dispatcher), so there is no race window between the read
     * and the write. A timer that is already [SleepTimerState.Inactive] is a no-op.
     */
    private fun cancelSleepTimerIfActive() {
        if (sleepTimerController.get().timerState.value is SleepTimerState.Active) {
            sleepTimerController.get().cancel()
        }
    }

    private fun startProgressUpdates() {
        progressJob?.cancel()
        progressJob = scope.launch {
            var ticksSinceSnapshot = 0
            while (isActive) {
                delay(PROGRESS_TICK_MS)
                // YT-0245: break on BUFFERING before mutating positionMs (no tick during
                // stalls); handleBufferingStateChanged restarts the loop on BUFFERING→PLAYING.
                val pre = mutablePlayerState.value
                if (!pre.isPlaying || pre.playbackStatus == PlaybackStatus.BUFFERING) break

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

                // Write the snapshot at most once every SNAPSHOT_INTERVAL_TICKS ticks so
                // we persist a fresh position during long playback without hitting the DB
                // every second.
                ticksSinceSnapshot++
                if (ticksSinceSnapshot >= SNAPSHOT_INTERVAL_TICKS) {
                    ticksSinceSnapshot = 0
                    saveSnapshot()
                }
            }
        }
    }

    /**
     * YT-0285 — Activity-side explicit restore trigger.
     *
     * Failure bucket: **controller rebind**. The process is alive and the singleton
     * controller exists, but [playerState.currentTrack] is null because no transport
     * call has been made yet (Media3 binding is lazy via
     * [MediaControllerPlaybackClient.buildController]). This method loads the persisted
     * snapshot directly from [playerSnapshotRepository] and applies it via
     * [restoreFromSnapshot], populating [playerState] before the first Compose frame
     * without waiting for any user interaction or service bind.
     *
     * Called from [MainActivity.onCreate] / [onStart] inside a `lifecycleScope.launch`.
     */
    override suspend fun ensureRestored() {
        // Already live — singleton controller was populated by a prior restore or by
        // active playback (process survived background trip). Skip the snapshot I/O.
        if (mutablePlayerState.value.currentTrack != null) return
        val snapshot = playerSnapshotRepository.loadSnapshot() ?: return
        restoreFromSnapshot(snapshot)
    }

    /**
     * YT-0272 — cold-launch state restoration.
     *
     * Deserializes the JSON queue from [snapshot.queue], validates the snapshot,
     * and populates [mutablePlayerState] with the persisted fields. The engine
     * (ExoPlayer) is NOT loaded — [PlayerState.engineLoaded] stays `false` so the
     * first [resume] call routes through `playQueueItem(...)` which triggers the
     * standard stream-URL extractor path beginning at the restored [PlayerState.positionMs].
     *
     * All recovery cases are silent no-ops (see [PlayerController.restoreFromSnapshot]).
     */
    override suspend fun restoreFromSnapshot(snapshot: PlayerSnapshotEntity) {
        val queue: List<QueueItem> = runCatching {
            snapshotJson.decodeFromString<List<SnapshotQueueItemPayload>>(snapshot.queue)
                .map { it.toQueueItem() }
        }.getOrNull() ?: return

        if (queue.isEmpty()) return

        val queueIndex = snapshot.queueIndex
        if (queueIndex !in queue.indices) return

        val currentVideoId = snapshot.currentVideoId
        if (queue[queueIndex].track.videoId != currentVideoId) return

        val currentTrack = queue[queueIndex].track
        val clampedSpeed = snapshot.playbackSpeed.coerceIn(0.5f, 2.0f)
        val coercedRepeatMode = when (snapshot.repeatMode) {
            0, 1, 2 -> snapshot.repeatMode
            else -> 0
        }

        val restoredPos = snapshot.positionMs.coerceAtLeast(0L)
        mutablePlayerState.value = PlayerState(
            currentTrack = currentTrack,
            queue = queue,
            currentQueueIndex = queueIndex,
            playbackStatus = PlaybackStatus.PAUSED,
            isPlaying = false,
            positionMs = restoredPos,
            durationMs = currentTrack.durationMs,
            errorMessage = null,
            shuffleOn = snapshot.shuffleOn,
            repeatMode = coercedRepeatMode,
            engineLoaded = false,
            // Note: playbackSpeed is also read from playbackSpeedPreferences on the first
            // runPlayQueueItem success path (YT-0246 keeps the two in sync, so the value
            // written here and the preference value always agree — no special handling needed).
            playbackSpeed = clampedSpeed,
        )

        // YT-0272 — arm the restore-seek so the first [runPlayQueueItem] Success path knows
        // where to seek the engine. Zero means "start from beginning" so we only set this when
        // the snapshot carries a real offset. Cleared on first use in [runPlayQueueItem].
        restoredPositionMs = if (restoredPos > 0L) restoredPos else 0L

        // YT-0272 — propagate repeat/shuffle to the engine immediately (best-effort: the
        // transport may not be attached yet at cold launch; runCatching absorbs any
        // IllegalStateException from an uninitialized MediaController).
        runCatching { playbackTransport.setRepeatMode(coercedRepeatMode) }
        runCatching { playbackTransport.setShuffleMode(snapshot.shuffleOn) }
    }

    /** Wire-format mirror of [com.yourtube.core.data.repository.DefaultPlayerSnapshotRepository]'s payload. */
    @Serializable
    private data class SnapshotQueueItemPayload(
        val queueId: String,
        val videoId: String,
        val title: String,
        val channel: String,
        val durationSec: Int,
        val thumbnailUrl: String,
    ) {
        fun toQueueItem(): QueueItem = QueueItem(
            track = Track(
                videoId = videoId,
                title = title,
                channel = channel,
                durationSec = durationSec,
                thumbnailUrl = thumbnailUrl,
            ),
            queueId = queueId,
        )
    }

    private fun Track.toQueueItem(): QueueItem = QueueItem(
        track = this,
        queueId = UUID.randomUUID().toString(),
    )

    private val Track.durationMs: Long
        get() = durationSec.coerceAtLeast(0) * 1000L

    companion object {
        private const val PROGRESS_TICK_MS = 1_000L
        // YT-0311 — threshold for skip-back restart vs. walk-back decision (strict greater-than).
        // Matches the industry standard used by YouTube Music, Spotify, and Apple Music (3 s).
        internal const val SKIP_BACK_RESTART_THRESHOLD_MS = 3_000L
        // YT-0294 — Shorts / livestream filter for Mix queue entries (mirrors autoplay contract).
        internal const val MIN_MIX_DURATION_SEC = 60

        // YT-0297 — lazy continuation prefetch threshold. When the current queue index
        // is within this many entries of the queue tail, the next Mix continuation page
        // is fetched in the background. N=2 means: trigger when at most one unplayed
        // entry remains AHEAD of current (so we are exactly at `size - 2` or `size - 1`).
        // Per the rate-limit guidance in `docs/mix-queue.md §Anti-Bot`, this paginates
        // lazily — one page ahead, never eagerly on `playNow`.
        internal const val PREFETCH_THRESHOLD = 2

        // YT-0297 — maximum time to wait for an in-flight continuation fetch at the
        // queue tail before falling back to autoplay-related. Bounded to keep the player
        // from stalling indefinitely if the network is slow.
        internal const val MIX_TAIL_WAIT_MS = 2_000L

        // YT-0193 — debounce window for engine-side seek dispatch. 50 ms is short enough
        // that a single tap on the scrubber feels instant (well under the ~100 ms human
        // perception threshold) but long enough to coalesce frame-rate ticks emitted by
        // the slider during a drag (16 ms at 60 Hz; 8 ms at 120 Hz).
        internal const val SEEK_DEBOUNCE_MS = 50L

        // Periodic snapshot write: once per 5 progress ticks (= once every 5 s while
        // playing). Keeps the persisted position reasonably fresh without hitting the DB
        // every second.
        internal const val SNAPSHOT_INTERVAL_TICKS = 5

        // YT-0309 — bounded timeout for stream-URL resolution. If the network is
        // unavailable (airplane mode, offline), OkHttp can hang for tens of seconds;
        // this cap clears LOADING and re-enables the Play button so the user can retry.
        internal const val STREAM_RESOLVE_TIMEOUT_MS = 15_000L
    }
}
