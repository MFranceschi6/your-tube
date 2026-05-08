package com.yourtube.core.ui

import androidx.compose.runtime.MonotonicFrameClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.plus
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Round 4 — `PlayerOverlayState` driver tests.
 *
 * Pins the four behaviors at the heart of the single-progress overlay architecture:
 *
 *  1. `autoOpen()` drives `expandProgress` to `1f` within the spec'd expand duration.
 *  2. `autoClose()` drives `expandProgress` to `0f` within the collapse duration AND
 *     fires `onCloseSettled` exactly once.
 *  3. `onDragDelta(dy)` snaps progress to `(progress - dy/screenHeightPx).coerceIn(0,1)`.
 *  4. `onDragStopped(velocity)` past the §3 threshold runs `animateTo(0)` and fires
 *     `onCloseSettled`. Sub-threshold runs `animateTo(1)` and DOES NOT fire `onCloseSettled`.
 *  5. Reduce-motion: `onDragDelta` is a no-op.
 *
 * Compose's `Animatable.animateTo` requires a `MonotonicFrameClock` in the coroutine
 * context to dispatch frame ticks. The hand-rolled [VirtualMonotonicFrameClock] below
 * supplies one whose `withFrameNanos` cooperates with the test scheduler's virtual
 * time — `advanceTimeBy(durationMs)` then drives the animation to completion.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerOverlayStateTest {

    private val screenHeightPx = 2000f
    private val velocityThresholdPxPerSec = 8000f

    @Test
    fun auto_open_reaches_one_within_expand_duration() = runTest(UnconfinedTestDispatcher()) {
        var settledCount = 0
        val state = PlayerOverlayState(
            coroutineScope = backgroundScope + VirtualMonotonicFrameClock(),
            onCloseSettled = { settledCount++ },
            isReducedMotionProvider = { false },
            screenHeightPxProvider = { screenHeightPx },
            velocityThresholdPxPerSecProvider = { velocityThresholdPxPerSec },
            initialExpanded = false,
        )
        assertEquals("Initial progress must be 0f", 0f, state.expandProgress.value, 0f)

        state.autoOpen()
        // The frame clock advances in 16ms ticks via `delay(16)`. Advance enough to
        // settle the spec'd expand duration plus a comfortable margin for animation
        // dispatcher overhead.
        advanceTimeBy(MotionSpec.DURATION_EXPAND_MS.toLong() + 200)
        advanceUntilIdle()

        assertEquals("autoOpen must drive progress to 1f", 1f, state.expandProgress.value, 0.01f)
        assertEquals("autoOpen must NOT call onCloseSettled", 0, settledCount)
    }

    @Test
    fun auto_close_reaches_zero_and_invokes_settle_once() = runTest(UnconfinedTestDispatcher()) {
        var settledCount = 0
        val state = PlayerOverlayState(
            coroutineScope = backgroundScope + VirtualMonotonicFrameClock(),
            onCloseSettled = { settledCount++ },
            isReducedMotionProvider = { false },
            screenHeightPxProvider = { screenHeightPx },
            velocityThresholdPxPerSecProvider = { velocityThresholdPxPerSec },
            initialExpanded = true,
        )
        assertEquals(1f, state.expandProgress.value, 0f)

        state.autoClose()
        advanceTimeBy(MotionSpec.DURATION_COLLAPSE_MS.toLong() + 200)
        advanceUntilIdle()

        assertEquals("autoClose must drive progress to 0f", 0f, state.expandProgress.value, 0.01f)
        assertEquals("autoClose must call onCloseSettled exactly once", 1, settledCount)
    }

    @Test
    fun auto_close_idempotent_when_progress_already_zero() = runTest(UnconfinedTestDispatcher()) {
        var settledCount = 0
        val state = PlayerOverlayState(
            coroutineScope = backgroundScope + VirtualMonotonicFrameClock(),
            onCloseSettled = { settledCount++ },
            isReducedMotionProvider = { false },
            screenHeightPxProvider = { screenHeightPx },
            velocityThresholdPxPerSecProvider = { velocityThresholdPxPerSec },
            initialExpanded = false,
        )
        assertEquals(0f, state.expandProgress.value, 0f)

        state.autoClose()
        advanceUntilIdle()

        assertEquals("autoClose at progress=0 must NOT fire onCloseSettled", 0, settledCount)
    }

    @Test
    fun on_drag_delta_snaps_progress_clamped() = runTest(UnconfinedTestDispatcher()) {
        val state = PlayerOverlayState(
            coroutineScope = backgroundScope + VirtualMonotonicFrameClock(),
            onCloseSettled = {},
            isReducedMotionProvider = { false },
            screenHeightPxProvider = { screenHeightPx },
            velocityThresholdPxPerSecProvider = { velocityThresholdPxPerSec },
            initialExpanded = true,
        )
        assertEquals(1f, state.expandProgress.value, 0f)

        state.onDragDelta(screenHeightPx * 0.25f)
        advanceUntilIdle()
        assertEquals(0.75f, state.expandProgress.value, 0.001f)

        state.onDragDelta(screenHeightPx)
        advanceUntilIdle()
        assertEquals(0f, state.expandProgress.value, 0.001f)

        state.onDragDelta(-screenHeightPx * 2f)
        advanceUntilIdle()
        assertEquals(1f, state.expandProgress.value, 0.001f)
    }

    @Test
    fun on_drag_stopped_past_threshold_collapses_and_settles() = runTest(UnconfinedTestDispatcher()) {
        var settledCount = 0
        val state = PlayerOverlayState(
            coroutineScope = backgroundScope + VirtualMonotonicFrameClock(),
            onCloseSettled = { settledCount++ },
            isReducedMotionProvider = { false },
            screenHeightPxProvider = { screenHeightPx },
            velocityThresholdPxPerSecProvider = { velocityThresholdPxPerSec },
            initialExpanded = true,
        )
        // Drag past the 30% distance threshold (50% drag → progress = 0.5).
        state.onDragDelta(screenHeightPx * 0.5f)
        advanceUntilIdle()

        state.onDragStopped(velocityPxPerSec = 0f)
        advanceTimeBy(MotionSpec.DURATION_COLLAPSE_MS.toLong() + 200)
        advanceUntilIdle()

        assertEquals(0f, state.expandProgress.value, 0.01f)
        assertEquals(1, settledCount)
    }

    @Test
    fun on_drag_stopped_past_velocity_threshold_collapses() = runTest(UnconfinedTestDispatcher()) {
        var settledCount = 0
        val state = PlayerOverlayState(
            coroutineScope = backgroundScope + VirtualMonotonicFrameClock(),
            onCloseSettled = { settledCount++ },
            isReducedMotionProvider = { false },
            screenHeightPxProvider = { screenHeightPx },
            velocityThresholdPxPerSecProvider = { velocityThresholdPxPerSec },
            initialExpanded = true,
        )
        // Tiny drag (5%) — below distance threshold but above velocity threshold.
        state.onDragDelta(screenHeightPx * 0.05f)
        advanceUntilIdle()

        state.onDragStopped(velocityPxPerSec = velocityThresholdPxPerSec * 1.5f)
        advanceTimeBy(MotionSpec.DURATION_COLLAPSE_MS.toLong() + 200)
        advanceUntilIdle()

        assertEquals(0f, state.expandProgress.value, 0.01f)
        assertEquals("Velocity past threshold must trigger collapse", 1, settledCount)
    }

    @Test
    fun on_drag_stopped_under_threshold_springs_back_no_settle() = runTest(UnconfinedTestDispatcher()) {
        var settledCount = 0
        val state = PlayerOverlayState(
            coroutineScope = backgroundScope + VirtualMonotonicFrameClock(),
            onCloseSettled = { settledCount++ },
            isReducedMotionProvider = { false },
            screenHeightPxProvider = { screenHeightPx },
            velocityThresholdPxPerSecProvider = { velocityThresholdPxPerSec },
            initialExpanded = true,
        )
        state.onDragDelta(screenHeightPx * 0.1f)
        advanceUntilIdle()

        state.onDragStopped(velocityPxPerSec = 0f)
        // Spring-back may take longer than a tween; advance generously.
        advanceTimeBy(3000)
        advanceUntilIdle()

        assertEquals(
            "Sub-threshold release must spring back to 1f",
            1f,
            state.expandProgress.value,
            0.01f,
        )
        assertEquals(
            "Sub-threshold release must NOT call onCloseSettled",
            0,
            settledCount,
        )
    }

    @Test
    fun reduce_motion_drag_is_no_op() = runTest(UnconfinedTestDispatcher()) {
        val state = PlayerOverlayState(
            coroutineScope = backgroundScope + VirtualMonotonicFrameClock(),
            onCloseSettled = {},
            isReducedMotionProvider = { true },
            screenHeightPxProvider = { screenHeightPx },
            velocityThresholdPxPerSecProvider = { velocityThresholdPxPerSec },
            initialExpanded = true,
        )

        state.onDragDelta(screenHeightPx * 0.5f)
        advanceUntilIdle()
        assertEquals("Reduce-motion onDragDelta must NOT mutate progress",
            1f, state.expandProgress.value, 0f)

        state.onDragStopped(velocityPxPerSec = velocityThresholdPxPerSec * 5f)
        advanceUntilIdle()
        assertEquals("Reduce-motion onDragStopped must NOT collapse",
            1f, state.expandProgress.value, 0f)
    }

    @Test
    fun reduce_motion_auto_open_uses_reduce_motion_duration() = runTest(UnconfinedTestDispatcher()) {
        val state = PlayerOverlayState(
            coroutineScope = backgroundScope + VirtualMonotonicFrameClock(),
            onCloseSettled = {},
            isReducedMotionProvider = { true },
            screenHeightPxProvider = { screenHeightPx },
            velocityThresholdPxPerSecProvider = { velocityThresholdPxPerSec },
            initialExpanded = false,
        )

        state.autoOpen()
        advanceTimeBy(MotionSpec.DURATION_REDUCE_MOTION_MS.toLong() + 100)
        advanceUntilIdle()

        assertEquals(
            "Reduce-motion autoOpen must complete within DURATION_REDUCE_MOTION_MS",
            1f,
            state.expandProgress.value,
            0.01f,
        )
    }
}

/**
 * Hand-rolled `MonotonicFrameClock`. Compose's suspend animation API requires a frame
 * clock in the coroutine context; we add this to the [PlayerOverlayState]'s coroutine
 * scope (`backgroundScope + VirtualMonotonicFrameClock()`) so `Animatable.animateTo`
 * pumps frames cooperatively with the kotlinx-coroutines-test scheduler.
 *
 * `Animatable` computes elapsed time from the delta between consecutive `withFrameNanos`
 * timestamps, so we maintain a monotonic counter that advances 16ms per frame (~60fps).
 * Using `System.nanoTime()` directly would couple the animation to real wall-time which
 * does not move under `runTest`'s virtual scheduler.
 */
private class VirtualMonotonicFrameClock : MonotonicFrameClock {
    private var frameNanos = 0L
    override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
        delay(16)
        frameNanos += 16_000_000L
        return onFrame(frameNanos)
    }
}
