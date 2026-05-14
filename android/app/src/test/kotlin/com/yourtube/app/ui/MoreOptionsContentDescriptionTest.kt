package com.yourtube.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import com.yourtube.core.common.model.Playlist
import com.yourtube.core.common.model.Track
import com.yourtube.core.ui.PlaylistRow
import com.yourtube.core.ui.TrackRow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * YT-0255 — MoreVert overflow buttons announce "More options" per symbol-map spec.
 * Verifies PlaylistRow and TrackRow; LibraryScreen / SearchScreen use the same string constant.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MoreOptionsContentDescriptionTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val samplePlaylist = Playlist(
        id = "p1",
        name = "Test Playlist",
        createdAt = "2026-01-01",
        updatedAt = "2026-01-01",
        tracks = emptyList(),
    )

    private val sampleTrack = Track(
        videoId = "v1",
        title = "Test Track",
        channel = "Test Channel",
        durationSec = 180,
        thumbnailUrl = "",
    )

    @Test
    fun playlist_row_overflow_button_is_labeled_more_options() {
        composeRule.setContent {
            PlaylistRow(
                playlist = samplePlaylist,
                onClick = {},
                onMoreClick = {},
            )
        }

        composeRule
            .onNodeWithContentDescription("More options")
            .assertIsDisplayed()
    }

    @Test
    fun track_row_overflow_button_is_labeled_more_options() {
        composeRule.setContent {
            TrackRow(
                track = sampleTrack,
                isPlaying = false,
                onClick = {},
                onMoreClick = {},
            )
        }

        composeRule
            .onNodeWithContentDescription("More options for ${sampleTrack.title}")
            .assertIsDisplayed()
    }
}
