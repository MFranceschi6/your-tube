package com.yourtube.core.player

import com.yourtube.core.common.model.SleepTimerPreset
import com.yourtube.core.common.model.SleepTimerState
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * YT-0093 — Default sleep-timer implementation.
 *
 * Coroutines are launched inside [serviceScope], which is supplied via [attach] from
 * [PlaybackService.onCreate]. This means all timer work runs in the service's lifecycle
 * and survives app backgrounding as long as the service is alive.
 *
 * Design decisions:
 * - Time-based presets use `delay` + 1-second countdown ticks.
 * - [SleepTimerPreset.EndOfTrack] observes [PlayerController.playerState] for a
 *   `currentTrack?.videoId` change (any track transition) and pauses at the first change
 *   after the timer is set.
 * - Setting a new timer while one is active cancels the previous job before launching
 *   the new one, so the two timers never race.
 */
@Singleton
class DefaultSleepTimerController @Inject constructor(
    private val playerController: PlayerController,
) : SleepTimerController {

    private val _timerState = MutableStateFlow<SleepTimerState>(SleepTimerState.Inactive)
    override val timerState: StateFlow<SleepTimerState> = _timerState.asStateFlow()

    /** Provided by [PlaybackService] via [attach]; null before the service has started. */
    private var serviceScope: CoroutineScope? = null

    /** The single active timer job. Cancelling it resets to [SleepTimerState.Inactive]. */
    private var timerJob: Job? = null

    override fun attach(scope: CoroutineScope) {
        cancelInternal()
        serviceScope = scope
    }

    override fun detach() {
        cancelInternal()
        serviceScope = null
    }

    override fun setTimer(preset: SleepTimerPreset) {
        val scope = serviceScope ?: return
        cancelInternal()
        timerJob = when (preset) {
            SleepTimerPreset.EndOfTrack -> launchEndOfTrackTimer(scope, preset)
            else -> launchCountdownTimer(scope, preset)
        }
    }

    override fun cancel() {
        cancelInternal()
    }

    // ── Internal helpers ──────────────────────────────────────────────────────────────────

    private fun cancelInternal() {
        timerJob?.cancel()
        timerJob = null
        _timerState.value = SleepTimerState.Inactive
    }

    /**
     * Launches a countdown timer for time-based presets. Emits [SleepTimerState.Active]
     * with decreasing [SleepTimerState.Active.remainingMs] every second until the preset
     * duration elapses, then calls [PlayerController.pause] and resets to
     * [SleepTimerState.Inactive].
     */
    private fun launchCountdownTimer(scope: CoroutineScope, preset: SleepTimerPreset): Job {
        _timerState.value = SleepTimerState.Active(
            remainingMs = preset.durationMs,
            preset = preset,
        )
        return scope.launch {
            var remainingMs = preset.durationMs
            while (isActive && remainingMs > 0L) {
                delay(TICK_MS)
                remainingMs = (remainingMs - TICK_MS).coerceAtLeast(0L)
                _timerState.value = SleepTimerState.Active(
                    remainingMs = remainingMs,
                    preset = preset,
                )
            }
            if (isActive) {
                playerController.pause()
                _timerState.value = SleepTimerState.Inactive
            }
        }
    }

    /**
     * Launches an end-of-track watcher. Observes [PlayerController.playerState] for the
     * first video-ID change that occurs AFTER this timer was set, then pauses playback.
     *
     * The snapshot of the video ID at timer-set time is captured inside the launched
     * coroutine so it is guaranteed to be observed before any state emission.
     *
     * Any videoId change — whether from auto-advance, queue manipulation, or user-initiated
     * skip — triggers the End of track pause. This is intentional: the timer fires at any
     * track boundary.
     */
    private fun launchEndOfTrackTimer(scope: CoroutineScope, preset: SleepTimerPreset): Job {
        _timerState.value = SleepTimerState.Active(
            remainingMs = 0L,
            preset = preset,
        )
        return scope.launch {
            // Capture the current video ID at the moment the timer was set.
            val initialVideoId = playerController.playerState.value.currentTrack?.videoId

            playerController.playerState
                .map { state -> state.currentTrack?.videoId }
                .distinctUntilChanged()
                .onEach { newVideoId ->
                    // Fire when the video ID changes away from the snapshot — this covers
                    // both auto-advance to the next queue entry AND the user manually
                    // skipping. A null initial ID means no track was playing when the timer
                    // was set; the first non-null emission (a track starts) is treated as
                    // a track change so the timer fires rather than dangling forever.
                    if (newVideoId != initialVideoId) {
                        playerController.pause()
                        cancelInternal()
                    }
                }
                .launchIn(this)
        }
    }

    companion object {
        internal const val TICK_MS = 1_000L
    }
}

