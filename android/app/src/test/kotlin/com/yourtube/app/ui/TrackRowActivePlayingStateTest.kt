package com.yourtube.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import com.yourtube.core.common.model.Track
import com.yourtube.core.ui.TrackRow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression coverage for YT-0066: callers (SearchScreen, PlaylistDetailScreen,
 * RecentlyPlayedScreen) now compute `isPlaying = (currentTrackVideoId == track.videoId)`
 * and pass it to [TrackRow]. This test pins the contract those callers rely on:
 * the row's accessibility/contentDescription flips between the "Now playing" and
 * non-playing forms based purely on the [TrackRow.isPlaying] flag, so any future
 * refactor of TrackRow's active-row treatment will fail loudly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrackRowActivePlayingStateTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val matchingTrack = Track(
        videoId = "vid-match",
        title = "Match Title",
        channel = "Match Channel",
        durationSec = 200,
        thumbnailUrl = "",
    )

    private val otherTrack = Track(
        videoId = "vid-other",
        title = "Other Title",
        channel = "Other Channel",
        durationSec = 200,
        thumbnailUrl = "",
    )

    @Test
    fun row_marked_playing_uses_now_playing_content_description() {
        composeRule.setContent {
            TrackRow(
                track = matchingTrack,
                isPlaying = true,
                onClick = {},
            )
        }

        composeRule
            .onNodeWithContentDescription("Now playing: Match Title by Match Channel")
            .assertIsDisplayed()
    }

    @Test
    fun row_with_non_matching_video_id_uses_idle_content_description() {
        // Simulates the wired call sites where currentTrackVideoId != track.videoId.
        val currentVideoId: String = matchingTrack.videoId
        val isPlaying = currentVideoId == otherTrack.videoId

        composeRule.setContent {
            TrackRow(
                track = otherTrack,
                isPlaying = isPlaying,
                onClick = {},
            )
        }

        composeRule
            .onNodeWithContentDescription("Other Title by Other Channel")
            .assertIsDisplayed()
    }

    @Test
    fun list_with_two_rows_only_marks_the_matching_one_as_playing() {
        // End-to-end shape of the wiring: a list where one row's videoId matches
        // currentTrackVideoId. Only that row should expose the "Now playing" semantics.
        val currentVideoId: String? = matchingTrack.videoId

        composeRule.setContent {
            Column {
                listOf(matchingTrack, otherTrack).forEach { track ->
                    TrackRow(
                        track = track,
                        isPlaying = currentVideoId == track.videoId,
                        onClick = {},
                    )
                }
            }
        }

        composeRule
            .onNodeWithContentDescription("Now playing: Match Title by Match Channel")
            .assertIsDisplayed()
        composeRule
            .onNodeWithContentDescription("Other Title by Other Channel")
            .assertIsDisplayed()
    }
}
