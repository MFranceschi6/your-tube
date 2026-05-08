package com.yourtube.core.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing

/**
 * Canonical motion tokens from `design-system/handoff/YT-0074/motion-spec.md` §1, mirrored
 * verbatim. Per-platform mapping (Compose) is in §5; do not redefine these locally — read
 * them from this object so a token bump propagates to every site.
 */
object MotionSpec {

    // §1 Tokens — durations
    const val DURATION_EXPAND_MS = 320
    const val DURATION_COLLAPSE_MS = 260
    const val DURATION_REDUCE_MOTION_MS = 120

    // §1 Tokens — easings
    /** Material *emphasized-decelerate* / iOS *easeOut* equivalent. */
    val Standard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** Accelerate; used on collapse for the artwork only. Everything else stays on [Standard]. */
    val Exit: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    // §1 Tokens — choreography offsets
    const val DELAY_CONTROLS_MS = 200

    // Expand sub-intervals — see §2 ASCII chart.
    const val MINI_CHROME_FADE_OUT_MS = 60
    const val NP_CHROME_FADE_IN_MS = 200
    const val NP_CHROME_FADE_IN_DELAY_MS = 80
    const val TRANSPORT_FADE_IN_MS = 120
    const val TRANSPORT_FADE_IN_DELAY_MS = 200
    const val TAB_BAR_SLIDE_OUT_MS = 200
    const val SCRIM_FADE_MS = 240

    // Collapse sub-intervals — see §3 ASCII chart.
    const val NP_CHROME_FADE_OUT_MS = 60
    const val TRANSPORT_FADE_OUT_MS = 120
    const val MINI_CHROME_FADE_IN_MS = 130
    const val MINI_CHROME_FADE_IN_DELAY_MS = 130
    const val TAB_BAR_SLIDE_IN_MS = 195
    const val TAB_BAR_SLIDE_IN_DELAY_MS = 65

    // §3 — drag-collapse thresholds.
    const val DRAG_COLLAPSE_DISTANCE_FRACTION = 0.30f
    const val DRAG_COLLAPSE_VELOCITY_DP_PER_S = 800f
    const val DRAG_SPRING_BACK_MAX_MS = 200

    // §6 — track-switch + artwork-failure cross-fade durations.
    const val TRACK_SWITCH_CROSSFADE_MS = 200
    const val ARTWORK_FAILURE_CROSSFADE_MS = 160

    // §6 — cold-open / deep-link fade.
    const val COLD_OPEN_FADE_MS = 240

    // §1 Tokens — artwork radii (from --radius-xs / --radius-md tokens).
    const val ARTWORK_START_RADIUS_DP = 4
    const val ARTWORK_END_RADIUS_DP = 12

    /**
     * Shared-element artwork bounds key. Test contract (spec §8 + YT-0166 AC tests): both
     * the MiniPlayer artwork and the NowPlaying artwork must register a `sharedContentState`
     * containing this exact substring so reviewers can grep for parity.
     */
    const val ARTWORK_SHARED_KEY_PREFIX = "np-artwork"
}
