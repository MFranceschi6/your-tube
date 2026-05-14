package com.yourtube.core.ui

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import com.yourtube.core.common.model.Track
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * YT-0063a M3 — PlaylistCover renders an accessibility-friendly
 * `contentDescription` on its outer `Box`, with copy that adapts to track count
 * and coerces the announced cover-source count to at most 4 (the 4-up cap).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaylistCoverContentDescriptionTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun empty_playlist_uses_no_tracks_yet_copy() {
        composeRule.setContent {
            PlaylistCover(
                tracks = emptyList(),
                name = "My Playlist",
                modifier = Modifier.size(240.dp),
            )
        }
        composeRule
            .onNodeWithContentDescription("My Playlist, no tracks yet")
            .assertExists()
    }

    @Test
    fun four_track_playlist_announces_four_source_tiles() {
        composeRule.setContent {
            PlaylistCover(
                tracks = trackList(4),
                name = "Four Tracks",
                modifier = Modifier.size(240.dp),
            )
        }
        composeRule
            .onNodeWithContentDescription("Four Tracks, cover from first 4 tracks")
            .assertExists()
    }

    @Test
    fun seven_track_playlist_coerces_announced_count_to_four() {
        composeRule.setContent {
            PlaylistCover(
                tracks = trackList(7),
                name = "Seven Tracks",
                modifier = Modifier.size(240.dp),
            )
        }
        // Cover renders the first 4 thumbnails; the contentDescription must
        // match what the user actually sees, not the underlying playlist size.
        composeRule
            .onNodeWithContentDescription("Seven Tracks, cover from first 4 tracks")
            .assertExists()
    }

    private fun trackList(count: Int): List<Track> =
        (1..count).map { i ->
            Track(
                videoId = "v$i",
                title = "Track $i",
                channel = "Channel $i",
                durationSec = 180,
                thumbnailUrl = "",
            )
        }
}
