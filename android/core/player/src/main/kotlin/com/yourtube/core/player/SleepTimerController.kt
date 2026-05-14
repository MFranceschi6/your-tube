package com.yourtube.core.player

import com.yourtube.core.common.model.SleepTimerPreset
import com.yourtube.core.common.model.SleepTimerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * YT-0093 — Sleep timer. Allows the user to schedule an automatic pause after a fixed
 * duration or at the end of the current track. The timer runs in the PlaybackService's
 * CoroutineScope so it survives app backgrounding.
 *
 * [attach] must be called from [PlaybackService.onCreate] before any timer is started,
 * and [detach] must be called from [PlaybackService.onDestroy] to release the scope
 * reference and cancel any active timer.
 *
 * [SleepTimerState] and [SleepTimerPreset] live in `:core:common` so both `:core:player`
 * and `:core:ui` can reference them without a cross-module dependency.
 */
interface SleepTimerController {
    val timerState: StateFlow<SleepTimerState>

    /**
     * Bind this controller to [scope] (the service's CoroutineScope). Must be called
     * before [setTimer]. Calling [attach] again while a timer is active cancels it first.
     */
    fun attach(scope: CoroutineScope)

    /** Release the scope reference and cancel any active timer. */
    fun detach()

    /** Set (or replace) the active timer with [preset]. Cancels any in-flight timer. */
    fun setTimer(preset: SleepTimerPreset)

    /** Cancel the active timer and return to [SleepTimerState.Inactive]. */
    fun cancel()
}
