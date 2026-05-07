package com.yourtube.core.ui

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Theme-scoped reduce-motion flag observed at the `YourTubeTheme` root. Animations should
 * gate on `LocalReduceMotion.current` and either snap to the target value or use a `tween`
 * with the OS-imposed scale rather than a spring when this is `true`.
 *
 * Provided by `app:theme` from `Settings.Global.TRANSITION_ANIMATION_SCALE`. Lives in
 * `core:ui` (rather than `app:theme`) so reusable composables in `core:ui` and feature
 * modules can read it without taking a dependency on the application module.
 */
val LocalReduceMotion = staticCompositionLocalOf<Boolean> { false }
