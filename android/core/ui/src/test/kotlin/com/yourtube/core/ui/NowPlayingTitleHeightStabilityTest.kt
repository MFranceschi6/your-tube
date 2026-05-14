package com.yourtube.core.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import com.yourtube.core.common.model.QueueItem
import com.yourtube.core.common.model.Track
import kotlin.math.abs
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * YT-0303 — pins the layout stability contract: the Y position of the secondary-controls
 * row (shuffle button) must be identical for tracks with 1-line titles and 4-line titles.
 *
 * Regression guard against removing `minLines = 2` from the title Text or removing
 * `maxLines = 1` from the channel Text, either of which would let the title/channel
 * Column grow and shift the transport + secondary controls downward.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NowPlayingTitleHeightStabilityTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val shortTrack = Track(
        videoId = "short",
        title = "Lofi",
        channel = "A",
        durationSec = 200,
        thumbnailUrl = "",
    )

    private val longTrack = Track(
        videoId = "long",
        title = "A very long four-line YouTube official music video title " +
            "that wraps repeatedly across the screen width on any typical phone",
        channel = "An equally long auto-generated topic-channel name that also wraps",
        durationSec = 200,
        thumbnailUrl = "",
    )

    @Test
    fun shuffle_button_y_is_stable_across_title_lengths() {
        var track by mutableStateOf(shortTrack)

        composeRule.setContent {
            NowPlayingChrome(
                track = track,
                isPlaying = false,
                isBuffering = false,
                progressFraction = 0f,
                shuffleOn = false,
                repeatMode = 0,
                onPlayPauseClick = {},
                onSkipNextClick = {},
                onSkipPreviousClick = {},
                onCollapseClick = {},
                onSeek = {},
                onShuffleModeChange = {},
                onRepeatModeChange = {},
                onShareTrack = {},
                onAddToPlaylist = {},
                queue = emptyList<QueueItem>(),
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

        composeRule.waitForIdle()
        val shortY = composeRule
            .onNodeWithContentDescription("Shuffle off")
            .fetchSemanticsNode()
            .boundsInRoot
            .top

        // Switch to a track with a 4-line title + long channel.
        track = longTrack
        composeRule.waitForIdle()
        val longY = composeRule
            .onNodeWithContentDescription("Shuffle off")
            .fetchSemanticsNode()
            .boundsInRoot
            .top

        assertTrue(
            abs(shortY - longY) < 2f,
            "Shuffle button Y must not shift between short and long titles. " +
                "short=$shortY long=$longY diff=${abs(shortY - longY)}",
        )
    }
}
