package com.yourtube.app.widget

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.action.Action
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasText
import com.yourtube.core.database.entity.PlayerSnapshotEntity
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])

/**
 * YT-0274 — unit tests for [NowPlayingWidgetContent].
 *
 * Uses [runGlanceAppWidgetUnitTest] (from glance-appwidget-testing) to render the
 * composable in a unit-test context without a real AppWidget host, then asserts on
 * rendered text nodes.
 *
 * Three cases:
 *   1. Snapshot is null → "Nothing playing" is rendered.
 *   2. Snapshot has currentVideoId, title, channelName → title and channel are rendered.
 *   3. Snapshot has no currentVideoId (null) → "Nothing playing" is rendered.
 *
 * The [Action] parameter accepted by [NowPlayingWidgetContent] is not exercised in
 * these tests; [runGlanceAppWidgetUnitTest] renders the RemoteViews tree without
 * dispatching click events, so any valid [Action] placeholder is sufficient.
 */
class NowPlayingWidgetContentTest {

    // A no-op placeholder action — the tap path is not exercised in unit tests.
    private val noOpAction: Action = actionStartActivity(
        android.content.Intent("com.yourtube.TEST_ACTION"),
    )

    /** Empty state: snapshot == null → "Nothing playing" text appears. */
    @Test
    fun nullSnapshot_rendersNothingPlaying() = runGlanceAppWidgetUnitTest {
        setAppWidgetSize(DpSize(250.dp, 84.dp))
        provideComposable {
            NowPlayingWidgetContent(
                snapshot = null,
                onTap = noOpAction,
            )
        }
        onNode(hasText("Nothing playing")).assertExists()
    }

    /** Populated state: title and channel are rendered from snapshot fields. */
    @Test
    fun snapshotWithTitleAndChannel_rendersTitleAndChannel() = runGlanceAppWidgetUnitTest {
        setAppWidgetSize(DpSize(250.dp, 84.dp))
        val snapshot = makeSnapshot(
            videoId = "abc123",
            title = "My Awesome Song",
            channelName = "Great Artist",
        )
        provideComposable {
            NowPlayingWidgetContent(
                snapshot = snapshot,
                onTap = noOpAction,
            )
        }
        onNode(hasText("My Awesome Song")).assertExists()
        onNode(hasText("Great Artist")).assertExists()
    }

    /** No-videoId state: snapshot exists but currentVideoId == null → "Nothing playing". */
    @Test
    fun snapshotWithNullVideoId_rendersNothingPlaying() = runGlanceAppWidgetUnitTest {
        setAppWidgetSize(DpSize(250.dp, 84.dp))
        val snapshot = makeSnapshot(
            videoId = null,
            title = "A Title",
            channelName = "A Channel",
        )
        provideComposable {
            NowPlayingWidgetContent(
                snapshot = snapshot,
                onTap = noOpAction,
            )
        }
        onNode(hasText("Nothing playing")).assertExists()
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun makeSnapshot(
        videoId: String?,
        title: String? = null,
        channelName: String? = null,
    ) = PlayerSnapshotEntity(
        id = 1,
        currentVideoId = videoId,
        queue = "[]",
        queueIndex = 0,
        positionMs = 0L,
        repeatMode = 0,
        shuffleOn = false,
        playbackSpeed = 1f,
        savedAt = "2026-01-01T00:00:00Z",
        title = title,
        channelName = channelName,
        thumbnailUrl = null,
    )
}
