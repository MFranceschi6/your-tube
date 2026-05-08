package com.yourtube.core.player

import androidx.media3.session.SessionCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * YT-0183 — locks the new `SKIP_TO_NEXT_QUEUE_ACTION` SessionCommand action string
 * and the `playbackSkipNextButton()` `CommandButton` shape that powers the
 * lock-screen / notification skip-next button.
 *
 * Robolectric is required because `CommandButton.Builder.build()` walks
 * `android.os.Bundle` and `android.net.Uri` static initializers; the existing
 * `core/player` tests use the same runner for the same reason.
 *
 * The `@UnstableApi` opt-in is contained at the call site inside
 * [PlaybackSessionCommand.playbackSkipNextButton]; the public properties read
 * here (`CommandButton.sessionCommand`, `CommandButton.isEnabled`,
 * `SessionCommand.customAction`) are not flagged unstable in Media3 1.4.x, so
 * no file-level opt-in is needed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class PlaybackSessionCommandTest {

    @Test
    fun `skip to next queue action string is stable across releases`() {
        // Stable across releases — any change to this constant breaks the wire
        // protocol that controllers (notification, future external clients) rely
        // on. Lock the string explicitly, not via referential equality.
        assertEquals(
            "com.yourtube.action.SKIP_TO_NEXT_QUEUE",
            PlaybackSessionCommand.SKIP_TO_NEXT_QUEUE_ACTION,
        )
    }

    @Test
    fun `skipToNextQueue session command is distinct from playTrack`() {
        val skipNext: SessionCommand = PlaybackSessionCommand.skipToNextQueue
        assertEquals(
            PlaybackSessionCommand.SKIP_TO_NEXT_QUEUE_ACTION,
            skipNext.customAction,
        )
        assertNotEquals(
            PlaybackSessionCommand.PLAY_TRACK_ACTION,
            skipNext.customAction,
            "skipToNextQueue and playTrack must not collide on customAction.",
        )
    }

    @Test
    fun `playbackSkipNextButton binds the skipToNextQueue session command`() {
        val button = PlaybackSessionCommand.playbackSkipNextButton()
        val sessionCommand = button.sessionCommand
        assertNotNull(sessionCommand, "Skip-next button must carry a SessionCommand.")
        assertEquals(
            PlaybackSessionCommand.SKIP_TO_NEXT_QUEUE_ACTION,
            sessionCommand.customAction,
        )
        // The button is enabled by default — gating happens at the
        // `setCustomLayout` call site (empty list when no next item exists).
        assertTrue(button.isEnabled, "Skip-next button must be enabled when published.")
    }
}
