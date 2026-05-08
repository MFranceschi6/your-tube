package com.yourtube.core.player

import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * YT-0239 (review change-request, 2026-05-08) — pins the command-masking contract that the
 * `PlaybackService` hands to `MediaSession.Builder(this, sessionPlayer)`.
 *
 * Robolectric is used for the same reason as the rest of `core/player`: `Player.Commands` and
 * its `Builder` walk Android framework class initializers via the `FlagSet` it wraps.
 *
 * The two prev commands ([Player.COMMAND_SEEK_TO_PREVIOUS] and
 * [Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM]) must be reported as unavailable by both
 * [Player.getAvailableCommands] and [Player.isCommandAvailable] so
 * `DefaultMediaNotificationProvider` stops rendering its built-in skip-prev arrow on top of
 * our custom `SKIP_TO_PREV_QUEUE` `CommandButton`. Every other command the underlying player
 * exposes must continue to pass through unchanged so the in-process audio path is unaffected.
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class PrevCommandMaskingPlayerTest {

    @Test
    fun `getAvailableCommands strips both prev commands while keeping the rest`() {
        // Build a "delegate is happy with everything" command set: every prev command is
        // available, plus a representative selection of unrelated commands that must pass
        // through. `addAllCommands()` covers the full enum surface in Media3 1.4.x; we then
        // assert specific entries to keep the test resilient to future flag additions.
        val delegateCommands = Player.Commands.Builder()
            .addAllCommands()
            .build()
        val delegate = mockk<Player>(relaxed = true) {
            every { availableCommands } returns delegateCommands
        }

        val masked = PrevCommandMaskingPlayer(delegate).availableCommands

        assertFalse(
            masked.contains(Player.COMMAND_SEEK_TO_PREVIOUS),
            "COMMAND_SEEK_TO_PREVIOUS must be stripped so the default provider hides its prev arrow.",
        )
        assertFalse(
            masked.contains(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM),
            "COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM must be stripped for the same reason.",
        )
        // Pass-through sanity — symmetric next commands and a couple of unrelated transport
        // commands stay available so playback fidelity is unaffected.
        assertTrue(masked.contains(Player.COMMAND_SEEK_TO_NEXT))
        assertTrue(masked.contains(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM))
        assertTrue(masked.contains(Player.COMMAND_PLAY_PAUSE))
        assertTrue(masked.contains(Player.COMMAND_SEEK_TO_DEFAULT_POSITION))
        assertTrue(masked.contains(Player.COMMAND_GET_CURRENT_MEDIA_ITEM))
    }

    @Test
    fun `isCommandAvailable returns false for the masked prev pair and forwards otherwise`() {
        val delegate = mockk<Player>(relaxed = true) {
            // Delegate would otherwise say "yes" to every command, including the prev pair.
            every { isCommandAvailable(any()) } returns true
        }

        val wrapper = PrevCommandMaskingPlayer(delegate)

        assertFalse(
            wrapper.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS),
            "Wrapper must report COMMAND_SEEK_TO_PREVIOUS unavailable even when the delegate would allow it.",
        )
        assertFalse(
            wrapper.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM),
            "Wrapper must report COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM unavailable for the same reason.",
        )
        // Every other command the delegate allows must pass through. Sample the symmetric next
        // pair (which we explicitly do not want to mask) plus a couple of unrelated commands.
        assertTrue(wrapper.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT))
        assertTrue(wrapper.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM))
        assertTrue(wrapper.isCommandAvailable(Player.COMMAND_PLAY_PAUSE))
        assertTrue(wrapper.isCommandAvailable(Player.COMMAND_SET_REPEAT_MODE))
    }

    @Test
    fun `isCommandAvailable reflects the delegate result for unmasked commands`() {
        // Belt-and-braces: when the delegate says "no" to a non-masked command, the wrapper
        // must propagate that "no" rather than always returning true.
        val delegate = mockk<Player>(relaxed = true) {
            every { isCommandAvailable(any()) } returns false
        }

        val wrapper = PrevCommandMaskingPlayer(delegate)

        assertFalse(wrapper.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT))
        assertFalse(wrapper.isCommandAvailable(Player.COMMAND_PLAY_PAUSE))
        // The masked pair stays unavailable irrespective of the delegate's answer.
        assertFalse(wrapper.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS))
        assertFalse(wrapper.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM))
    }

    @Test
    fun `command count drops by exactly two when delegate exposes everything`() {
        val delegateCommands = Player.Commands.Builder().addAllCommands().build()
        val delegate = mockk<Player>(relaxed = true) {
            every { availableCommands } returns delegateCommands
        }

        val masked = PrevCommandMaskingPlayer(delegate).availableCommands

        // The wrapper removes exactly two commands; nothing else from the full set is touched.
        assertEquals(
            delegateCommands.size() - 2,
            masked.size(),
            "Masking must remove exactly the two prev commands.",
        )
    }
}
