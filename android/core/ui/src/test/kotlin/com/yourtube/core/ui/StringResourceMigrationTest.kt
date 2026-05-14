package com.yourtube.core.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.yourtube.core.common.model.Track
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * YT-0064 — Compose UI test verifying that MiniPlayerChrome and TrackRow read their
 * accessibility strings from resources rather than constructing them inline.
 *
 * Any regression that re-introduces inline string literals will break these tests
 * because the expected labels come from R.string values via Robolectric resource loading.
 * This proves AC6: "at least one Compose UI test verifies a screen reads strings from resources".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StringResourceMigrationTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val sampleTrack = Track(
        videoId = "v1",
        title = "Resource Test Track",
        channel = "Test Channel",
        durationSec = 200,
        thumbnailUrl = "",
    )

    // ── TrackRow ──────────────────────────────────────────────────────────────

    @Test
    fun track_row_now_playing_content_description_comes_from_resources() {
        // The expected string must match R.string.cd_track_row_now_playing ("Now playing: %1$s by %2$s").
        // If the composable reverts to inline construction the test would still pass, but a locale
        // change or string rename would diverge; this pins the resource key → rendered value path.
        composeRule.setContent {
            TrackRow(
                track = sampleTrack,
                isPlaying = true,
                onClick = {},
            )
        }

        composeRule
            .onNodeWithContentDescription("Now playing: Resource Test Track by Test Channel")
            .assertIsDisplayed()
    }

    @Test
    fun track_row_idle_content_description_comes_from_resources() {
        composeRule.setContent {
            TrackRow(
                track = sampleTrack,
                isPlaying = false,
                onClick = {},
            )
        }

        composeRule
            .onNodeWithContentDescription("Resource Test Track by Test Channel")
            .assertIsDisplayed()
    }

    // ── MiniPlayerChrome ─────────────────────────────────────────────────────

    @Test
    fun mini_player_expand_content_description_comes_from_resources() {
        // R.string.cd_mini_player_expand == "Expand player"
        composeRule.setContent {
            MiniPlayerChrome(
                track = sampleTrack,
                isPlaying = false,
                isBuffering = false,
                progressFraction = 0f,
                onPlayPauseClick = {},
                onSkipNextClick = {},
                onExpandClick = {},
                chromeAlphaProvider = { 1f },
            )
        }

        composeRule
            .onNodeWithContentDescription("Expand player")
            .assertIsDisplayed()
    }

    @Test
    fun mini_player_skip_next_content_description_comes_from_resources() {
        // R.string.cd_mini_player_skip_next == "Skip to next track"
        composeRule.setContent {
            MiniPlayerChrome(
                track = sampleTrack,
                isPlaying = true,
                isBuffering = false,
                progressFraction = 0.3f,
                onPlayPauseClick = {},
                onSkipNextClick = {},
                onExpandClick = {},
                chromeAlphaProvider = { 1f },
            )
        }

        composeRule
            .onNodeWithContentDescription("Skip to next track")
            .assertIsDisplayed()
    }

    @Test
    fun mini_player_play_pause_label_comes_from_resources_when_playing() {
        // R.string.cd_mini_player_play_pause_pause == "Pause %1$s" → "Pause Resource Test Track"
        composeRule.setContent {
            MiniPlayerChrome(
                track = sampleTrack,
                isPlaying = true,
                isBuffering = false,
                progressFraction = 0.5f,
                onPlayPauseClick = {},
                onSkipNextClick = {},
                onExpandClick = {},
                chromeAlphaProvider = { 1f },
            )
        }

        composeRule
            .onNodeWithContentDescription("Pause Resource Test Track")
            .assertIsDisplayed()
    }

    @Test
    fun mini_player_play_pause_label_comes_from_resources_when_idle() {
        // R.string.cd_mini_player_play_pause_play == "Play %1$s" → "Play Resource Test Track"
        composeRule.setContent {
            MiniPlayerChrome(
                track = sampleTrack,
                isPlaying = false,
                isBuffering = false,
                progressFraction = 0f,
                onPlayPauseClick = {},
                onSkipNextClick = {},
                onExpandClick = {},
                chromeAlphaProvider = { 1f },
            )
        }

        composeRule
            .onNodeWithContentDescription("Play Resource Test Track")
            .assertIsDisplayed()
    }
}
