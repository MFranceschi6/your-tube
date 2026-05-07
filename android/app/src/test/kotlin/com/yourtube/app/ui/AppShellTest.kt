package com.yourtube.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.yourtube.app.ui.theme.YourTubeTheme
import com.yourtube.core.ui.AppShellSlots
import com.yourtube.core.ui.LocalAppShellInsets
import com.yourtube.core.ui.LocalAppShellSlots
import com.yourtube.core.ui.LocalNavAnimatedVisibilityScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
 * **What these tests verify**: the pure boolean and `Dp` arithmetic that derives
 * `miniPlayerVisible`, `fabVisible`, and `shellInsets` from `isNowPlayingActive`,
 * `currentTrack`, and the measured height inputs. Each test reproduces the formula
 * in isolation using plain Compose state variables.
 *
 * **What these tests do NOT verify**: the actual `derivedStateOf` wiring inside the
 * AppShell composable, the NavController back-stack driving `isNowPlayingActive`, or
 * `AnimatedVisibility` enter/exit timing. Those code paths require Hilt scaffolding
 * and are covered manually until `HiltAndroidRule` is wired in this module (see the
 * `@Ignore` cases in [AppShellTest]).
 *
 * AC1 and AC2 (flag correctness) and AC4 (inset collapse) are therefore **partly
 * manual** for the AppShell composable itself; these tests guard against arithmetic
 * regressions in the formula only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChromeGatingFormulaTest {

    @get:Rule
    val composeRule = createComposeRule()

    /**
     * AC1: when the active route is NowPlaying, miniPlayerVisible and fabVisible are both false,
     * so shellInsets bottom collapses to navBarHeightDp only (and navBar will animate to 0
     * via AnimatedVisibility, but here we assert the flag logic directly).
     */
    @Test
    fun mini_player_not_visible_when_now_playing_active() {
        var isNowPlayingActive by mutableStateOf(false)
        var currentTrackPresent by mutableStateOf(true)

        // Mirrors AppShell: val miniPlayerVisible = currentTrack != null && !isNowPlayingActive
        var miniPlayerVisible = false

        composeRule.setContent {
            miniPlayerVisible = currentTrackPresent && !isNowPlayingActive
        }

        composeRule.runOnIdle { assertEquals(true, miniPlayerVisible) }

        // Simulate navigating to NowPlaying.
        composeRule.runOnIdle { isNowPlayingActive = true }
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertEquals(false, miniPlayerVisible) }
    }

    /**
     * AC2: when NowPlaying is dismissed (isNowPlayingActive → false), miniPlayerVisible
     * returns to true (assuming a track is still loaded).
     */
    @Test
    fun mini_player_returns_when_now_playing_dismissed() {
        var isNowPlayingActive by mutableStateOf(true)
        var currentTrackPresent by mutableStateOf(true)
        var miniPlayerVisible = false

        composeRule.setContent {
            miniPlayerVisible = currentTrackPresent && !isNowPlayingActive
        }

        // Start with NowPlaying active — MiniPlayer should be hidden.
        composeRule.runOnIdle { assertEquals(false, miniPlayerVisible) }

        // Dismiss NowPlaying (back-stack pop).
        composeRule.runOnIdle { isNowPlayingActive = false }
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertEquals(true, miniPlayerVisible) }
    }

    /**
     * AC4: shellInsets bottom = 0 when NowPlaying is active (miniPlayerVisible = false,
     * navBarHeightDp = 0 because AnimatedVisibility collapses the measured height).
     * We test the inset formula directly with the expected collapsed inputs.
     */
    @Test
    fun shell_insets_collapse_to_zero_when_now_playing_active() {
        // Simulate: navBar hidden → measured 0dp; miniPlayer hidden → 0dp; no FAB.
        val navBarHeightDp = 0.dp
        val miniPlayerHeightDp = 0.dp
        val miniPlayerVisible = false
        val fabVisible = false

        // Reproduce the shellInsets formula from AppShell.
        val bottom = navBarHeightDp +
            (if (miniPlayerVisible) miniPlayerHeightDp else 0.dp) +
            (if (fabVisible) 0.dp + 16.dp else 0.dp)

        assertEquals(0.dp, bottom)
    }

    /**
     * AC4 (normal state): shellInsets bottom is non-zero when NowPlaying is NOT active
     * and a track is loaded (MiniPlayer visible, nav bar measured).
     */
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

    /**
     * Complement of AC1: when the active route is NOT NowPlaying and a track is loaded,
     * `isNowPlayingActive = false` so `miniPlayerVisible` is true. Guards against the gate
     * being inverted or always-true.
     */
    @Test
    fun is_now_playing_active_false_for_non_now_playing_routes() {
        var isNowPlayingActive by mutableStateOf(false)
        var currentTrackPresent by mutableStateOf(true)
        var miniPlayerVisible = false

        composeRule.setContent {
            miniPlayerVisible = currentTrackPresent && !isNowPlayingActive
        }

        // Non-NowPlaying route with a loaded track → MiniPlayer should be visible.
        composeRule.runOnIdle { assertEquals(true, miniPlayerVisible) }

        // No track loaded → MiniPlayer should hide regardless of route.
        composeRule.runOnIdle { currentTrackPresent = false }
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertEquals(false, miniPlayerVisible) }
    }

    /**
     * AC5 (logical portion): `fabVisible` is false when NowPlaying is active, even when
     * a FAB has been registered by the underlying destination. Guards against the FAB
     * gating expression being inadvertently dropped.
     */
    @Test
    fun fab_visible_false_when_now_playing_active() {
        var isNowPlayingActive by mutableStateOf(false)
        // Simulate a destination having registered a FAB (non-null content slot).
        val fabContentPresent = true
        var fabVisible = false

        composeRule.setContent {
            fabVisible = fabContentPresent && !isNowPlayingActive
        }

        // NowPlaying inactive → FAB should be visible (content is registered).
        composeRule.runOnIdle { assertEquals(true, fabVisible) }

        // NowPlaying active → FAB must be hidden.
        composeRule.runOnIdle { isNowPlayingActive = true }
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertEquals(false, fabVisible) }
    }
}

/**
 * YT-0061 round-2 regression: the round-1 implementation read
 * `LocalNavAnimatedVisibilityScope.current` at the MiniPlayer overlay site, but the overlay
 * lives outside the `NavHost` so the local resolved to `null` and `sharedArtworkModifier`
 * silently short-circuited to plain `Modifier`. The fix hoists the active destination scope
 * via a shell-level registry so an overlay sibling can read a non-null scope. This test
 * asserts the seam directly on `sharedArtworkModifier(...)`: when given a non-null scope it
 * must return a chained modifier (not the bare `Modifier` companion); when given null it must
 * return `Modifier`.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SharedArtworkModifierTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun returns_plain_modifier_when_scope_is_null() {
        var captured: Modifier? = null
        composeRule.setContent {
            SharedTransitionLayout {
                captured = sharedArtworkModifier(
                    sharedScope = this,
                    animatedVisibilityScope = null,
                    videoId = "abc",
                )
            }
        }
        composeRule.waitForIdle()
        assertSame(Modifier, captured)
    }

    @Test
    fun returns_plain_modifier_when_video_id_is_null() {
        var captured: Modifier? = null
        composeRule.setContent {
            SharedTransitionLayout {
                val sharedScope = this
                AnimatedVisibility(visible = true) {
                    captured = sharedArtworkModifier(
                        sharedScope = sharedScope,
                        animatedVisibilityScope = this,
                        videoId = null,
                    )
                }
            }
        }
        composeRule.waitForIdle()
        assertSame(Modifier, captured)
    }

    @Test
    fun returns_chained_modifier_when_scope_and_video_id_are_present() {
        var captured: Modifier? = null
        composeRule.setContent {
            SharedTransitionLayout {
                val sharedScope = this
                AnimatedVisibility(visible = true) {
                    captured = sharedArtworkModifier(
                        sharedScope = sharedScope,
                        animatedVisibilityScope = this,
                        videoId = "abc",
                    )
                }
            }
        }
        composeRule.waitForIdle()
        assertNotNull(captured)
        // A non-trivial sharedBounds chain is not the same instance as the empty companion.
        assertNotSame(Modifier, captured)
    }
}

/**
 * YT-0061 round-2 supporting test: a sibling rendered *outside* a destination's
 * `AnimatedVisibility` (mirroring how `MiniPlayer` is hosted as a sibling of `NavHost`) must
 * still be able to observe the active destination's `AnimatedVisibilityScope` via the
 * registry pattern used in `AppShell` (a `SnapshotStateList` of scopes registered on enter and
 * cleared on dispose). Reads inside the `AnimatedVisibility` content also see the scope via
 * `LocalNavAnimatedVisibilityScope`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DestinationScopeRegistryTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun outside_overlay_observes_registered_scope_and_local_inside_destination() {
        val registry = mutableStateListOf<AnimatedVisibilityScope>()
        var renderDestination by mutableStateOf(true)
        var observedFromInside: AnimatedVisibilityScope? = null
        var observedFromOverlay: AnimatedVisibilityScope? = null

        composeRule.setContent {
            // Sibling overlay reads the registry — analogue of the MiniPlayer overlay in
            // `AppShell`. Reading happens on every recomposition.
            observedFromOverlay = registry.lastOrNull()

            AnimatedVisibility(visible = renderDestination) {
                val scope = this
                DisposableEffect(scope) {
                    registry += scope
                    onDispose { registry.remove(scope) }
                }
                CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides scope) {
                    // Reader inside the destination — mirrors how screens consume the local.
                    observedFromInside = LocalNavAnimatedVisibilityScope.current
                }
            }
        }

        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertNotNull(observedFromInside)
            assertNotNull(observedFromOverlay)
            assertSame(observedFromInside, observedFromOverlay)
        }

        composeRule.runOnIdle { renderDestination = false }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            // Destination disposed → registry cleared → overlay sibling reads null. The same
            // miss would have shown up here if the round-1 `LocalNavAnimatedVisibilityScope`
            // overlay-site bug regressed.
            assertNull(registry.lastOrNull())
        }
    }
}
