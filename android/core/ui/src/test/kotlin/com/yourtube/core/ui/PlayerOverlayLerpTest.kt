package com.yourtube.core.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Round 4 — pure-math tests for the per-frame lerp formulas in `PlayerOverlay`. These pin
 * the contract that every visual property is a deterministic function of `progress` and
 * (where applicable) `isPlaying`. Every test is a single arithmetic identity — no
 * Compose, no Animatable.
 */
class PlayerOverlayLerpTest {

    /** Pure replica of `PlayerOverlay`'s artwork visible-scale formula. */
    private fun visibleScale(progress: Float, isPlaying: Boolean): Float {
        val target = if (isPlaying) 1.0f else 0.85f
        return lerp(1.0f, target, progress)
    }

    @Test
    fun visible_scale_at_progress_zero_paused_equals_one() {
        assertEquals(1.0f, visibleScale(progress = 0f, isPlaying = false), 0f)
    }

    @Test
    fun visible_scale_at_progress_one_paused_equals_paused_target() {
        assertEquals(0.85f, visibleScale(progress = 1f, isPlaying = false), 0f)
    }

    @Test
    fun visible_scale_at_progress_one_playing_equals_one() {
        assertEquals(1.0f, visibleScale(progress = 1f, isPlaying = true), 0f)
    }

    @Test
    fun visible_scale_at_progress_zero_playing_equals_one() {
        assertEquals(1.0f, visibleScale(progress = 0f, isPlaying = true), 0f)
    }

    @Test
    fun mini_chrome_alpha_is_one_at_progress_zero() {
        // miniChromeAlpha = 1 - clampNorm(progress, 0, 60/320)
        val alpha = 1f - clampNorm(0f, 0f, 60f / 320f)
        assertEquals(1f, alpha, 0f)
    }

    @Test
    fun mini_chrome_alpha_is_zero_at_or_past_60_320() {
        val edgeAlpha = 1f - clampNorm(60f / 320f, 0f, 60f / 320f)
        val pastEdgeAlpha = 1f - clampNorm(0.5f, 0f, 60f / 320f)
        assertEquals("At p=60/320 alpha must be 0", 0f, edgeAlpha, 0f)
        assertEquals("Past p=60/320 alpha stays 0", 0f, pastEdgeAlpha, 0f)
    }

    @Test
    fun now_playing_chrome_alpha_zero_below_window() {
        // nowPlayingChromeAlpha = clampNorm(progress, 80/320, 280/320)
        val alpha = clampNorm(0f, 80f / 320f, 280f / 320f)
        assertEquals(0f, alpha, 0f)
        // Just below the window start.
        val justBelow = clampNorm(70f / 320f, 80f / 320f, 280f / 320f)
        assertEquals(0f, justBelow, 0f)
    }

    @Test
    fun now_playing_chrome_alpha_one_at_or_past_window_end() {
        val atEnd = clampNorm(280f / 320f, 80f / 320f, 280f / 320f)
        val pastEnd = clampNorm(1f, 80f / 320f, 280f / 320f)
        assertEquals(1f, atEnd, 0.0001f)
        assertEquals(1f, pastEnd, 0f)
    }

    @Test
    fun transport_row_alpha_starts_at_200_320() {
        // transportRowAlpha = clampNorm(progress, 200/320, 1)
        val belowWindow = clampNorm(0.5f, 200f / 320f, 1f)
        val atWindowStart = clampNorm(200f / 320f, 200f / 320f, 1f)
        val atWindowEnd = clampNorm(1f, 200f / 320f, 1f)
        assertEquals(0f, belowWindow, 0f)
        assertEquals(0f, atWindowStart, 0.0001f)
        assertEquals(1f, atWindowEnd, 0f)
    }

    @Test
    fun scrim_alpha_window_is_0_to_240_320() {
        // scrimAlpha = clampNorm(progress, 0, 240/320)
        val atZero = clampNorm(0f, 0f, 240f / 320f)
        val atHalf = clampNorm(120f / 320f, 0f, 240f / 320f)
        val atEnd = clampNorm(240f / 320f, 0f, 240f / 320f)
        val past = clampNorm(1f, 0f, 240f / 320f)
        assertEquals(0f, atZero, 0f)
        assertEquals(0.5f, atHalf, 0.0001f)
        assertEquals(1f, atEnd, 0.0001f)
        assertEquals(1f, past, 0f)
    }

    @Test
    fun lerp_is_linear() {
        assertEquals(0f, lerp(0f, 100f, 0f), 0f)
        assertEquals(50f, lerp(0f, 100f, 0.5f), 0f)
        assertEquals(100f, lerp(0f, 100f, 1f), 0f)
        assertEquals(-25f, lerp(0f, 100f, -0.25f), 0f)
    }
}
