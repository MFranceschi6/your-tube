package com.yourtube.core.ui

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp

/**
 * Slot registry exposed by `AppShell` so destination composables can inject a
 * floating action button into the bottom-end of the shell without taking
 * ownership of the shell layout.
 *
 * The intended usage from a destination is:
 *
 * ```kotlin
 * val slots = LocalAppShellSlots.current
 * LaunchedEffect(Unit) {
 *     slots.setFab { ExtendedFloatingActionButton(...) }
 * }
 * DisposableEffect(Unit) { onDispose { slots.setFab(null) } }
 * ```
 *
 * `setFab(null)` clears the slot. The shell is responsible for placing the FAB
 * above the MiniPlayer and navigation bar (see `AppShell`).
 */
interface AppShellSlots {
    fun setFab(content: (@Composable () -> Unit)?)
}

/**
 * No-op fallback used when an `AppShellSlots` consumer is rendered outside of
 * `AppShell` (e.g. previews, isolated tests). Calls are silently ignored.
 */
internal object NoOpAppShellSlots : AppShellSlots {
    override fun setFab(content: (@Composable () -> Unit)?) = Unit
}

val LocalAppShellSlots: androidx.compose.runtime.ProvidableCompositionLocal<AppShellSlots> =
    staticCompositionLocalOf { NoOpAppShellSlots }

/**
 * Canonical bottom inset for the app shell. Computed once in `AppShell` from
 * `WindowInsets.navigationBars` plus the live MiniPlayer height (when a track
 * is loaded) plus the live FAB height (when a destination has registered one).
 *
 * Destinations should consume this for their `LazyColumn`'s `contentPadding`
 * so scrollable content does not slide under the MiniPlayer / FAB / nav bar.
 */
val LocalAppShellInsets: androidx.compose.runtime.ProvidableCompositionLocal<PaddingValues> =
    compositionLocalOf { PaddingValues(0.dp) }

/**
 * Height of the visible MiniPlayer chrome only (no FAB, no nav bar).
 * Use this when positioning a [SnackbarHost] on screens that have no FAB of their own,
 * so the snackbar appears just above the MiniPlayer regardless of whether a sibling
 * destination registered a FAB via [LocalAppShellSlots].
 */
val LocalMiniPlayerHeight: androidx.compose.runtime.ProvidableCompositionLocal<androidx.compose.ui.unit.Dp> =
    compositionLocalOf { 0.dp }

/**
 * Shared-transition scope hoisted at the `AppShell` root. Destination
 * composables can read this to attach `Modifier.sharedBounds(...)` against the
 * MiniPlayer artwork without owning the `SharedTransitionLayout`.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedTransitionScope: androidx.compose.runtime.ProvidableCompositionLocal<SharedTransitionScope?> =
    staticCompositionLocalOf { null }

/**
 * `AnimatedVisibilityScope` of the currently-rendering Navigation destination.
 * Used together with `LocalSharedTransitionScope` to attach
 * `Modifier.sharedBounds(...)` to artwork or other shared elements. `null` when
 * no destination scope is active (e.g. the persistent MiniPlayer overlay).
 */
val LocalNavAnimatedVisibilityScope: androidx.compose.runtime.ProvidableCompositionLocal<AnimatedVisibilityScope?> =
    staticCompositionLocalOf { null }

/**
 * YT-0328 — Single, global `SnackbarHostState` exposed by `AppShell`. The shell renders
 * one `SnackbarHost` above the `PlayerOverlay` in its Box stack so every screen's
 * snackbar (Undo confirmations, errors, etc.) sits above the persistent MiniPlayer
 * and clears it via the AppShell's measured bottom inset.
 *
 * Destinations must pull this and call `showSnackbar(...)` on it rather than create
 * their own local `SnackbarHostState` + `SnackbarHost`. Per-destination `Scaffold`s
 * should leave `snackbarHost` unset (default no-op) — the shell owns the host slot.
 *
 * Sheet-scoped snackbars (e.g. inside a `ModalBottomSheet`) are an intentional
 * exception and may keep their own local host.
 *
 * The fallback host renders nothing — used by previews/tests that don't wrap the
 * tree in `AppShell`. Snackbar calls against it complete without rendering.
 */
val LocalSnackbarHostState: androidx.compose.runtime.ProvidableCompositionLocal<SnackbarHostState> =
    staticCompositionLocalOf { SnackbarHostState() }
