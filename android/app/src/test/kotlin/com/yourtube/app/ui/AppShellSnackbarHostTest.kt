package com.yourtube.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.yourtube.app.ui.theme.YourTubeTheme
import com.yourtube.core.ui.LocalSnackbarHostState
import kotlin.test.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * YT-0328 — Pins the contract that snackbars dispatched against [LocalSnackbarHostState]
 * render even when a fullscreen "PlayerOverlay" surface is composed alongside them in the
 * AppShell-style Box stack. The shell renders its [SnackbarHost] AFTER the PlayerOverlay
 * in the outer Box, so draw order places snackbars on top of the persistent MiniPlayer.
 *
 * These tests don't boot the full Hilt-driven [AppShell] (see the existing Ignored
 * end-to-end test in [AppShellTest]). Instead they exercise the layering contract in
 * isolation: a fake fullscreen overlay below + the global snackbar host above.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppShellSnackbarHostTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun local_snackbar_host_state_is_the_provided_instance() {
        val provided = SnackbarHostState()
        var observed: SnackbarHostState? = null
        composeRule.setContent {
            CompositionLocalProvider(LocalSnackbarHostState provides provided) {
                observed = LocalSnackbarHostState.current
            }
        }
        composeRule.runOnIdle { assertSame(provided, observed) }
    }

    @Test
    fun snackbar_renders_when_dispatched_through_local_above_player_overlay() {
        val hostState = SnackbarHostState()
        composeRule.setContent {
            YourTubeTheme {
                CompositionLocalProvider(LocalSnackbarHostState provides hostState) {
                    Box(Modifier.fillMaxSize()) {
                        // Fake PlayerOverlay rendered FIRST → drawn below.
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(Color.Red)
                                .testTag("playerOverlay"),
                        )
                        // Global snackbar host rendered AFTER overlay → drawn on top.
                        SnackbarHost(
                            hostState = hostState,
                            modifier = Modifier.testTag("globalSnackbarHost"),
                        )
                    }
                    LaunchedEffect(Unit) {
                        hostState.showSnackbar("Removed from recent searches")
                    }
                }
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("playerOverlay").assertExists()
        composeRule.onNodeWithText("Removed from recent searches").assertIsDisplayed()
    }

    @Test
    fun snackbar_bounds_overlap_player_overlay_when_both_visible() {
        val hostState = SnackbarHostState()
        composeRule.setContent {
            YourTubeTheme {
                CompositionLocalProvider(LocalSnackbarHostState provides hostState) {
                    Box(Modifier.fillMaxSize()) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .testTag("playerOverlay"),
                        )
                        SnackbarHost(
                            hostState = hostState,
                            modifier = Modifier.testTag("globalSnackbarHost"),
                        )
                    }
                    LaunchedEffect(Unit) { hostState.showSnackbar("hello") }
                }
            }
        }

        composeRule.waitForIdle()
        val overlayBounds = composeRule.onNodeWithTag("playerOverlay").getUnclippedBoundsInRoot()
        val snackbarBounds = composeRule.onNodeWithText("hello").getUnclippedBoundsInRoot()

        // Sanity: snackbar lives inside the overlay's rectangle — the rendering order
        // (snackbar declared AFTER overlay in the Box) is what keeps it visible. If the
        // bounds didn't intersect we'd be testing the wrong thing.
        val intersects = snackbarBounds.left < overlayBounds.right &&
            snackbarBounds.right > overlayBounds.left &&
            snackbarBounds.top < overlayBounds.bottom &&
            snackbarBounds.bottom > overlayBounds.top
        check(intersects) {
            "Snackbar bounds $snackbarBounds expected to overlap PlayerOverlay $overlayBounds"
        }

        composeRule.onNodeWithText("hello").assertIsDisplayed()
    }

    /**
     * YT-0328 AC7/AC8 — pins the duration regression. Without an explicit
     * `SnackbarDuration` argument the M3 host defaults to `Indefinite` whenever
     * an `actionLabel` is non-null, which leaves Undo toasts on screen forever.
     * Call sites under `feature/` must pass `SnackbarDuration.Short` (or `Long`)
     * so the snackbar auto-dismisses; this test asserts the behaviour on the
     * Robolectric virtual clock.
     */
    @Test
    fun snackbar_with_short_duration_and_action_label_auto_dismisses() {
        val hostState = SnackbarHostState()
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            YourTubeTheme {
                Box(Modifier.fillMaxSize()) {
                    SnackbarHost(hostState = hostState)
                }
                LaunchedEffect(Unit) {
                    hostState.showSnackbar(
                        message = "Removed from recent searches",
                        actionLabel = "Undo",
                        duration = SnackbarDuration.Short,
                    )
                }
            }
        }

        // Drive the composition forward enough for the snackbar to mount.
        composeRule.mainClock.advanceTimeBy(500)
        composeRule.onNodeWithText("Removed from recent searches").assertIsDisplayed()

        // M3 Short = 4000 ms. Advance past it (with margin) and the snackbar must
        // be gone from the tree — Indefinite would still be there.
        composeRule.mainClock.advanceTimeBy(6_000)
        composeRule.onNodeWithText("Removed from recent searches").assertDoesNotExist()
    }
}
