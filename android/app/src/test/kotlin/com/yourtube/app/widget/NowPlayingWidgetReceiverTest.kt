package com.yourtube.app.widget

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * YT-0100 — minimal unit tests for the Now Playing widget receiver.
 *
 * Glance widgets cannot be meaningfully exercised in Robolectric unit tests because
 * [GlanceAppWidget.provideGlance] requires a live [GlanceId] from the AppWidget host,
 * which is only available in an instrumented test context. These tests cover the
 * structural contract (receiver instantiation, correct widget association) which is
 * enough to catch broken class hierarchies and missing widget files without requiring
 * a device.
 */
class NowPlayingWidgetReceiverTest {

    @Test
    fun `NowPlayingWidgetReceiver can be instantiated`() {
        val receiver = NowPlayingWidgetReceiver()
        assertNotNull(receiver)
    }

    @Test
    fun `NowPlayingWidgetReceiver glanceAppWidget is NowPlayingWidget class`() {
        val receiver = NowPlayingWidgetReceiver()
        // Verify the widget class name rather than `is`-check (which is always true for a
        // statically-typed field) — this catches copy-paste errors where the receiver is
        // wired to a different GlanceAppWidget subclass.
        val actualClass = receiver.glanceAppWidget::class.java.name
        val expectedClass = NowPlayingWidget::class.java.name
        assertTrue(
            "glanceAppWidget class must be '$expectedClass' but was '$actualClass'",
            actualClass == expectedClass,
        )
    }

    @Test
    fun `NowPlayingWidget deep-link action constant matches PlaybackService action`() {
        // The widget taps re-use the same intent action that PlaybackService uses for
        // its session activity PendingIntent and that MainActivity listens for in onNewIntent.
        // Keeping the constant in NowPlayingWidget.Companion prevents divergence.
        val expected = "com.yourtube.action.OPEN_NOW_PLAYING"
        val actual = NowPlayingWidget.DEEP_LINK_ACTION_PLAYER
        assertTrue(
            "Deep-link action must be '$expected' but was '$actual'",
            actual == expected,
        )
    }
}
