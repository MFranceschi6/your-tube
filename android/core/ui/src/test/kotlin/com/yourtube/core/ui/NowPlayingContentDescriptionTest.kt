package com.yourtube.core.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import com.yourtube.core.common.model.QueueItem
import com.yourtube.core.common.model.Track
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** YT-0254 — Compose UI test that renders NowPlayingChrome and asserts state-aware
 *  shuffle/repeat contentDescriptions resolve via R.string resources, catching any
 *  divergence between code and spec that the previous literal-duplicating test could not.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NowPlayingContentDescriptionTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val stubTrack = Track(
        videoId = "id",
        title = "Title",
        channel = "Channel",
        durationSec = 200,
        thumbnailUrl = "",
    )
    private val stubQueue = emptyList<QueueItem>()

    private fun renderChrome(shuffleOn: Boolean, repeatMode: Int) {
        composeRule.setContent {
            NowPlayingChrome(
                track = stubTrack,
                isPlaying = false,
                isBuffering = false,
                progressFraction = 0f,
                shuffleOn = shuffleOn,
                repeatMode = repeatMode,
                onPlayPauseClick = {},
                onSkipNextClick = {},
                onSkipPreviousClick = {},
                onCollapseClick = {},
                onSeek = {},
                onShuffleModeChange = {},
                onRepeatModeChange = {},
                onShareTrack = {},
                onAddToPlaylist = {},
                queue = stubQueue,
                currentQueueIndex = 0,
                onRemoveQueueItem = {},
                onMoveQueueItem = { _, _ -> },
                chromeAlphaProvider = { 1f },
                transportRowAlphaProvider = { 1f },
                onDragDelta = {},
                onDragStopped = {},
                onSpeedChange = {},
                onArtworkSlotPositioned = { _: Rect -> },
            )
        }
    }

    @Test
    fun shuffle_off_label() {
        renderChrome(shuffleOn = false, repeatMode = 0)
        composeRule.onNodeWithContentDescription("Shuffle off").assertExists()
    }

    @Test
    fun shuffle_on_label() {
        renderChrome(shuffleOn = true, repeatMode = 0)
        composeRule.onNodeWithContentDescription("Shuffle on").assertExists()
    }

    @Test
    fun repeat_off_label() {
        renderChrome(shuffleOn = false, repeatMode = 0)
        composeRule.onNodeWithContentDescription("Repeat off").assertExists()
    }

    @Test
    fun repeat_one_label() {
        renderChrome(shuffleOn = false, repeatMode = 1)
        composeRule.onNodeWithContentDescription("Repeat one").assertExists()
    }

    @Test
    fun repeat_all_label() {
        renderChrome(shuffleOn = false, repeatMode = 2)
        composeRule.onNodeWithContentDescription("Repeat all").assertExists()
    }
}
