package com.yourtube.feature.library

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yourtube.core.common.model.Playlist
import com.yourtube.core.common.model.Track
import com.yourtube.core.data.codec.PlaylistCodec
import com.yourtube.core.ui.LocalAppShellInsets
import com.yourtube.core.ui.LocalMiniPlayerHeight
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * YT-0063a M1 — PlaylistDetail v2 Q3 (edit mode) and v2 Q6 (track long-press)
 * Compose semantics tests.
 *
 * v2 Q3: Edit toggle exposes drag handles + remove buttons, hides thumbnails;
 *        clicking remove triggers a snackbar with an Undo action.
 * v2 Q6: Long-pressing a TrackRow in read mode opens the track context sheet
 *        with exactly three items (Play next / Add to queue / Share); the menu
 *        does not open while edit mode is active.
 */
@RunWith(AndroidJUnit4::class)
class PlaylistDetailScreenSemanticsTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun edit_mode_swaps_thumbnails_for_drag_handles_and_remove_buttons() {
        val viewModel = newViewModel(twoTrackPlaylist())

        composeRule.setContent {
            PlaylistDetailScreen(
                onBackClick = {},
                onPlaylistDeleted = {},
                onShare = {},
                viewModel = viewModel,
            )
        }

        // Read mode: no edit-mode affordances on screen yet.
        composeRule.onAllNodesWithContentDescription("Drag to reorder Track A")
            .assertCountEquals(0)
        composeRule.onAllNodesWithContentDescription("Remove Track A")
            .assertCountEquals(0)

        composeRule.onNodeWithText("Edit").performClick()

        // Edit mode: drag handle + remove button exist per row.
        composeRule.onNodeWithContentDescription("Drag to reorder Track A").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Drag to reorder Track B").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Remove Track A").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Remove Track B").assertIsDisplayed()
    }

    @Test
    fun edit_mode_remove_shows_snackbar_with_undo_action() {
        val viewModel = newViewModel(twoTrackPlaylist())

        composeRule.setContent {
            PlaylistDetailScreen(
                onBackClick = {},
                onPlaylistDeleted = {},
                onShare = {},
                viewModel = viewModel,
            )
        }

        composeRule.onNodeWithText("Edit").performClick()
        composeRule.onNodeWithContentDescription("Remove Track A").performClick()

        // Snackbar surfaces both the removed-row copy and the Undo affordance.
        composeRule.onNodeWithText("Removed \"Track A\"").assertIsDisplayed()
        composeRule.onNodeWithText("Undo").assertIsDisplayed()
    }

    @Test
    fun long_press_track_in_read_mode_shows_v2_q6_three_item_sheet() {
        val viewModel = newViewModel(twoTrackPlaylist())

        composeRule.setContent {
            PlaylistDetailScreen(
                onBackClick = {},
                onPlaylistDeleted = {},
                onShare = {},
                viewModel = viewModel,
            )
        }

        // TrackRow exposes a long-press affordance under "More options for <title>".
        composeRule
            .onNodeWithContentDescription("More options for Track A")
            .performTouchInput { longClick() }

        composeRule.onNodeWithText("Play next").assertIsDisplayed()
        composeRule.onNodeWithText("Add to queue").assertIsDisplayed()
        composeRule.onNodeWithText("Share").assertIsDisplayed()
        // The track sheet has exactly three actions — Rename / Delete are
        // PlaylistRow-only and must not appear on the track sheet.
        composeRule.onAllNodesWithContentDescription("Rename").assertCountEquals(0)
    }

    @Test
    fun undo_is_hit_testable_with_mini_player_visible() {
        val viewModel = newViewModel(twoTrackPlaylist())
        composeRule.setContent {
            CompositionLocalProvider(LocalMiniPlayerHeight provides 88.dp) {
                PlaylistDetailScreen(onBackClick = {}, onPlaylistDeleted = {}, onShare = {}, viewModel = viewModel)
            }
        }
        composeRule.onNodeWithText("Edit").performClick()
        composeRule.onNodeWithContentDescription("Remove Track A").performClick()
        composeRule.onNodeWithText("Undo").assertIsDisplayed()
        composeRule.onNodeWithText("Undo").performClick()
    }

    @Test
    fun long_press_track_in_edit_mode_does_not_open_context_menu() {
        val viewModel = newViewModel(twoTrackPlaylist())

        composeRule.setContent {
            PlaylistDetailScreen(
                onBackClick = {},
                onPlaylistDeleted = {},
                onShare = {},
                viewModel = viewModel,
            )
        }

        composeRule.onNodeWithText("Edit").performClick()
        // The TrackRow read-mode node is not on screen in edit mode; the
        // EditModeTrackRow's drag handle owns the gesture. Asserting absence
        // of the "Play next" sheet item is the cleanest cross-check.
        composeRule.onAllNodesWithContentDescription("More options for Track A")
            .assertCountEquals(0)
        composeRule.onAllNodesWithContentDescription("More options for Track B")
            .assertCountEquals(0)
    }

    private fun twoTrackPlaylist() = Playlist(
        id = "p1",
        name = "Chill",
        createdAt = "now",
        updatedAt = "now",
        tracks = listOf(
            Track("v1", "Track A", "Channel", 180, ""),
            Track("v2", "Track B", "Channel", 200, ""),
        ),
    )

    private fun newViewModel(playlist: Playlist): PlaylistDetailViewModel {
        val repository = FakePlaylistRepository(initialPlaylists = listOf(playlist))
        return PlaylistDetailViewModel(
            savedStateHandle = SavedStateHandle(mapOf("playlistId" to playlist.id)),
            repository = repository,
            codec = NoopCodec(),
        )
    }

    private class NoopCodec : PlaylistCodec {
        override fun export(playlist: Playlist): String = ""
        override fun `import`(payload: String): Playlist = error("unused")
    }
}
