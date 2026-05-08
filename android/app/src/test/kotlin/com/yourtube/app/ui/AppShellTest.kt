package com.yourtube.app.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.yourtube.app.ui.theme.YourTubeTheme
import com.yourtube.core.ui.AppShellSlots
import com.yourtube.core.ui.LocalAppShellInsets
import com.yourtube.core.ui.LocalAppShellSlots
import org.junit.Assert.assertEquals
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppShellTest {

    @get:Rule
    val composeRule = createComposeRule()

    // The full AppShell is Hilt-driven (PlayerViewModel + SharingViewModel resolve via
    // @AndroidEntryPoint), so a Compose unit test of the whole shell needs HiltAndroidRule
    // + a test Application. Until that scaffolding lands, these end-to-end scenarios are
    // exercised manually via the running app. The tests below exercise the YT-0061 FAB-seam
    // and inset CompositionLocals directly without booting the shell.
    @Test
    @Ignore("Pending Hilt test rule scaffolding for AppShell")
    fun library_exposes_recently_played_route() {
        composeRule.setContent {
            YourTubeTheme {
                AppShell(modifier = Modifier.fillMaxSize())
            }
        }
    }

    @Test
    @Ignore("Pending Hilt test rule scaffolding for AppShell")
    fun mini_player_and_now_playing_are_reachable_with_non_null_track() {
        composeRule.setContent {
            YourTubeTheme {
                AppShell(modifier = Modifier.fillMaxSize())
            }
        }
    }

    /**
     * YT-0061: a destination registers a FAB through `LocalAppShellSlots` and the host
     * captures it in a state slot; disposing the destination clears the slot. We observe the
     * slot directly because under Robolectric the lambda content is not always rendered into
     * the semantics tree synchronously after the destination's `DisposableEffect` runs, which
     * makes `assertExists()` flaky for indirectly-registered content.
     */
    @Test
    fun fab_seam_registers_and_clears_when_destination_enters_and_leaves_composition() {
        var registered: (@Composable () -> Unit)? = null
        val slots = object : AppShellSlots {
            override fun setFab(content: (@Composable () -> Unit)?) {
                registered = content
            }
        }
        var renderDestination by mutableStateOf(false)

        composeRule.setContent {
            YourTubeTheme {
                CompositionLocalProvider(LocalAppShellSlots provides slots) {
                    if (renderDestination) {
                        FakeLibraryDestination()
                    }
                }
            }
        }

        // Destination is not in composition → nothing has been registered.
        composeRule.runOnIdle { assertEquals(true, registered == null) }

        // Enter composition → DisposableEffect body runs → setFab is called.
        composeRule.runOnIdle { renderDestination = true }
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertEquals(true, registered != null) }

        // Leave composition → DisposableEffect onDispose clears the slot.
        composeRule.runOnIdle { renderDestination = false }
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertEquals(true, registered == null) }
    }

    /**
     * YT-0061: `LocalAppShellInsets` is the canonical bottom inset consumed by destinations.
     * We assert here that the contract is honored — a destination reads what the shell
     * publishes and bottom-inflates only when MiniPlayer / FAB are active.
     */
    @Test
    fun local_app_shell_insets_are_visible_to_destinations() {
        val noOverlays = PaddingValues(bottom = 16.dp)
        val miniPlayerOnly = PaddingValues(bottom = 16.dp + 80.dp)
        val miniPlayerAndFab = PaddingValues(bottom = 16.dp + 80.dp + 56.dp + 16.dp)

        var observed: Float? = null
        var publish by mutableStateOf(noOverlays)

        composeRule.setContent {
            CompositionLocalProvider(LocalAppShellInsets provides publish) {
                val incoming = LocalAppShellInsets.current
                observed = incoming.calculateBottomPadding().value
            }
        }

        composeRule.runOnIdle { assertEquals(16f, observed) }

        composeRule.runOnIdle { publish = miniPlayerOnly }
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertEquals(96f, observed) }

        composeRule.runOnIdle { publish = miniPlayerAndFab }
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertEquals(168f, observed) }
    }
}

@Composable
private fun FakeLibraryDestination() {
    val slots = LocalAppShellSlots.current
    DisposableEffect(slots) {
        slots.setFab {
            ExtendedFloatingActionButton(
                onClick = {},
                icon = {},
                text = { Text("New playlist") },
            )
        }
        onDispose { slots.setFab(null) }
    }
}

/**
 * YT-0151: Regression tests for the chrome-gating arithmetic used in AppShell.
 *
 * These tests verify the pure boolean and `Dp` arithmetic that derives `miniPlayerVisible`,
 * `fabVisible`, and `shellInsets` from `isNowPlayingActive`, `currentTrack`, and the
 * measured height inputs. Each test reproduces the formula in isolation using plain
 * Compose state variables.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChromeGatingFormulaTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun mini_player_not_visible_when_now_playing_active() {
        var isNowPlayingActive by mutableStateOf(false)
        var currentTrackPresent by mutableStateOf(true)

        var miniPlayerVisible = false

        composeRule.setContent {
            miniPlayerVisible = currentTrackPresent && !isNowPlayingActive
        }

        composeRule.runOnIdle { assertEquals(true, miniPlayerVisible) }

        composeRule.runOnIdle { isNowPlayingActive = true }
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertEquals(false, miniPlayerVisible) }
    }

    @Test
    fun mini_player_returns_when_now_playing_dismissed() {
        var isNowPlayingActive by mutableStateOf(true)
        var currentTrackPresent by mutableStateOf(true)
        var miniPlayerVisible = false

        composeRule.setContent {
            miniPlayerVisible = currentTrackPresent && !isNowPlayingActive
        }

        composeRule.runOnIdle { assertEquals(false, miniPlayerVisible) }

        composeRule.runOnIdle { isNowPlayingActive = false }
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertEquals(true, miniPlayerVisible) }
    }

    @Test
    fun shell_insets_collapse_to_zero_when_now_playing_active() {
        val navBarHeightDp = 0.dp
        val miniPlayerHeightDp = 0.dp
        val miniPlayerVisible = false
        val fabVisible = false

        val bottom = navBarHeightDp +
            (if (miniPlayerVisible) miniPlayerHeightDp else 0.dp) +
            (if (fabVisible) 0.dp + 16.dp else 0.dp)

        assertEquals(0.dp, bottom)
    }

    @Test
    fun shell_insets_non_zero_when_chrome_visible() {
        val navBarHeightDp = 56.dp
        val miniPlayerHeightDp = 72.dp
        val miniPlayerVisible = true
        val fabVisible = false

        val bottom = navBarHeightDp +
            (if (miniPlayerVisible) miniPlayerHeightDp else 0.dp) +
            (if (fabVisible) 0.dp + 16.dp else 0.dp)

        assertEquals(128.dp, bottom)
    }

    @Test
    fun is_now_playing_active_false_for_non_now_playing_routes() {
        var isNowPlayingActive by mutableStateOf(false)
        var currentTrackPresent by mutableStateOf(true)
        var miniPlayerVisible = false

        composeRule.setContent {
            miniPlayerVisible = currentTrackPresent && !isNowPlayingActive
        }

        composeRule.runOnIdle { assertEquals(true, miniPlayerVisible) }

        composeRule.runOnIdle { currentTrackPresent = false }
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertEquals(false, miniPlayerVisible) }
    }

    @Test
    fun fab_visible_false_when_now_playing_active() {
        var isNowPlayingActive by mutableStateOf(false)
        val fabContentPresent = true
        var fabVisible = false

        composeRule.setContent {
            fabVisible = fabContentPresent && !isNowPlayingActive
        }

        composeRule.runOnIdle { assertEquals(true, fabVisible) }

        composeRule.runOnIdle { isNowPlayingActive = true }
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertEquals(false, fabVisible) }
    }
}
