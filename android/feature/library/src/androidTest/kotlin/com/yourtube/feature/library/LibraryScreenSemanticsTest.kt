package com.yourtube.feature.library

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yourtube.core.common.model.Playlist
import com.yourtube.core.common.model.Track
import com.yourtube.core.data.codec.PlaylistCodec
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * YT-0063a M1 — Library v2 Q6 long-press context menu.
 *
 * Long-pressing a `PlaylistRow` (outside edit mode — there is no edit mode at
 * the Library destination) must open the contextual `ModalBottomSheet` with
 * exactly the five v2 actions: Play next, Add to queue, Rename, Delete, Share.
 */
@RunWith(AndroidJUnit4::class)
class LibraryScreenSemanticsTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun long_press_playlist_row_opens_v2_q6_context_menu() {
        val repository = FakePlaylistRepository(
            initialPlaylists = listOf(
                Playlist(
                    id = "p1",
                    name = "Chill",
                    createdAt = "now",
                    updatedAt = "now",
                    tracks = listOf(sampleTrack("v1", "Track A")),
                ),
            ),
        )
        val viewModel = LibraryViewModel(repository = repository, codec = NoopCodec())

        composeRule.setContent {
            LibraryScreen(
                onOpenRecentlyPlayed = {},
                onOpenPlaylistDetail = {},
                viewModel = viewModel,
            )
        }

        // Long-press the playlist row (PlaylistRow exposes a stable
        // contentDescription that includes the playlist name + track count).
        composeRule
            .onNodeWithContentDescription("Chill, 1 tracks")
            .performTouchInput { longClick() }

        // The sheet renders the five v2 actions; assert each one exists.
        composeRule.onNodeWithText("Play next").assertIsDisplayed()
        composeRule.onNodeWithText("Add to queue").assertIsDisplayed()
        composeRule.onNodeWithText("Rename").assertIsDisplayed()
        composeRule.onNodeWithText("Delete").assertIsDisplayed()
        composeRule.onNodeWithText("Share").assertIsDisplayed()
    }

    private fun sampleTrack(id: String, title: String) = Track(
        videoId = id,
        title = title,
        channel = "Channel",
        durationSec = 180,
        thumbnailUrl = "",
    )

    private class NoopCodec : PlaylistCodec {
        override fun export(playlist: Playlist): String = ""
        override fun `import`(payload: String): Playlist = error("unused")
    }
}
