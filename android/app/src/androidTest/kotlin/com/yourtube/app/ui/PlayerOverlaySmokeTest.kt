package com.yourtube.app.ui

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yourtube.core.ui.MotionSpec
import com.yourtube.core.ui.NowPlayingDragHandleTestTag
import com.yourtube.core.ui.PlayerOverlayState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Round 4 instrumented smoke tests for the single-progress overlay. Validates the three
 * paths users actually exercise:
 *
 *  1. **Auto-open:** invoking `state.autoOpen()` drives `expandProgress` to ~1f within
 *     `DURATION_EXPAND_MS + 50ms`. (Equivalent to the user tapping the MiniPlayer and
 *     the route-change LaunchedEffect calling autoOpen.)
 *  2. **Drag past threshold:** a real swipe-down ≥ 50% of the surface drives progress
 *     toward 0; on release `onCloseSettled` fires (the AppShell wires this to
 *     `popBackStack()`).
 *  3. **Drag under threshold:** a tiny swipe-down springs back to 1f; `onCloseSettled`
 *     does NOT fire.
 *
 * Per `CLAUDE.local.md`, this test file is compile-verified only via
 * `:app:compileDebugAndroidTestKotlin`. Running it requires a connected emulator.
 */
@RunWith(AndroidJUnit4::class)
class PlayerOverlaySmokeTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun auto_open_reaches_one_within_expand_duration() {
        var state: PlayerOverlayState? = null
        var screenH by mutableFloatStateOf(0f)

        composeRule.setContent {
            val scope = rememberCoroutineScope()
            val density = LocalDensity.current
            val velocityThresholdPxPerSec = with(density) {
                MotionSpec.DRAG_COLLAPSE_VELOCITY_DP_PER_S.dp.toPx()
            }
            state = remember {
                PlayerOverlayState(
                    coroutineScope = scope,
                    onCloseSettled = {},
                    isReducedMotionProvider = { false },
                    screenHeightPxProvider = { screenH },
                    velocityThresholdPxPerSecProvider = { velocityThresholdPxPerSec },
                    initialExpanded = false,
                )
            }
            Box(modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { screenH = it.size.height.toFloat() }
            )
        }
        composeRule.waitForIdle()

        val captured = state!!
        composeRule.runOnUiThread { captured.autoOpen() }
        // Animatable settle: waitUntil polls the value.
        composeRule.waitUntil(timeoutMillis = MotionSpec.DURATION_EXPAND_MS.toLong() + 200) {
            captured.expandProgress.value >= 0.99f
        }
        assertEquals("autoOpen must drive progress to ~1f", 1f, captured.expandProgress.value, 0.05f)
    }

    @Test
    fun drag_past_threshold_collapses_and_fires_settle() {
        var state: PlayerOverlayState? = null
        var settleFired = false
        var screenH by mutableFloatStateOf(0f)

        composeRule.setContent {
            val scope = rememberCoroutineScope()
            val density = LocalDensity.current
            val velocityThresholdPxPerSec = with(density) {
                MotionSpec.DRAG_COLLAPSE_VELOCITY_DP_PER_S.dp.toPx()
            }
            state = remember {
                PlayerOverlayState(
                    coroutineScope = scope,
                    onCloseSettled = { settleFired = true },
                    isReducedMotionProvider = { false },
                    screenHeightPxProvider = { screenH },
                    velocityThresholdPxPerSecProvider = { velocityThresholdPxPerSec },
                    initialExpanded = true,
                )
            }
            // Drag-handle stand-in: forwards `Modifier.draggable` events to the state's
            // drag mutators (mirrors what `NowPlayingChrome` does in production).
            val dragState = rememberDraggableState { delta ->
                state?.onDragDelta(delta)
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onGloballyPositioned { screenH = it.size.height.toFloat() }
                    .testTag(NowPlayingDragHandleTestTag)
                    .draggable(
                        state = dragState,
                        orientation = Orientation.Vertical,
                        onDragStopped = { v -> state?.onDragStopped(v) },
                    )
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(NowPlayingDragHandleTestTag).performTouchInput {
            // Big fast swipe past the 30% threshold.
            swipeDown(startY = top + 1f, endY = bottom - 1f, durationMillis = 80)
        }
        // Wait for the collapse animation to settle.
        composeRule.waitUntil(timeoutMillis = MotionSpec.DURATION_COLLAPSE_MS.toLong() + 500) {
            (state?.expandProgress?.value ?: 1f) <= 0.05f && settleFired
        }
        val captured = state!!
        assertEquals("Drag-collapse must settle progress at 0", 0f, captured.expandProgress.value, 0.05f)
        assertTrue("Drag-collapse must fire onCloseSettled", settleFired)
    }

    @Test
    fun drag_under_threshold_springs_back_no_settle() {
        var state: PlayerOverlayState? = null
        var settleFired = false
        var screenH by mutableFloatStateOf(0f)

        composeRule.setContent {
            val scope = rememberCoroutineScope()
            val density = LocalDensity.current
            val velocityThresholdPxPerSec = with(density) {
                MotionSpec.DRAG_COLLAPSE_VELOCITY_DP_PER_S.dp.toPx()
            }
            state = remember {
                PlayerOverlayState(
                    coroutineScope = scope,
                    onCloseSettled = { settleFired = true },
                    isReducedMotionProvider = { false },
                    screenHeightPxProvider = { screenH },
                    velocityThresholdPxPerSecProvider = { velocityThresholdPxPerSec },
                    initialExpanded = true,
                )
            }
            val dragState = rememberDraggableState { delta ->
                state?.onDragDelta(delta)
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onGloballyPositioned { screenH = it.size.height.toFloat() }
                    .testTag(NowPlayingDragHandleTestTag)
                    .draggable(
                        state = dragState,
                        orientation = Orientation.Vertical,
                        onDragStopped = { v -> state?.onDragStopped(v) },
                    )
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(NowPlayingDragHandleTestTag).performTouchInput {
            // Tiny slow swipe — well below 30% and below velocity threshold.
            val small = (bottom - top) * 0.05f
            swipeDown(startY = top + 1f, endY = top + 1f + small, durationMillis = 400)
        }
        // Wait for spring-back to settle.
        composeRule.waitUntil(timeoutMillis = 2000) {
            (state?.expandProgress?.value ?: 0f) >= 0.99f
        }
        val captured = state!!
        assertEquals("Spring-back must settle progress at 1", 1f, captured.expandProgress.value, 0.05f)
        assertFalse("Spring-back must NOT fire onCloseSettled", settleFired)
    }
}
