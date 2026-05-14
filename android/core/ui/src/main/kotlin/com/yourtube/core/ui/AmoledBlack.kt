package com.yourtube.core.ui

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * YT-0102 — theme-scoped AMOLED-black flag observed at the `YourTubeTheme` root.
 *
 * When `true` AND the system is in dark mode, `YourTubeTheme` overrides the
 * Material 3 `background` and `surface` color tokens to pure black (#000000).
 * This flag has no effect in light mode.
 *
 * Provided by `app:theme` from [AmoledPreferences]. Lives in `core:ui` (rather
 * than `app:theme`) so reusable composables can read it without depending on
 * the application module — matching the same convention used by [LocalReduceMotion].
 */
val LocalAmoledBlack = staticCompositionLocalOf<Boolean> { false }
