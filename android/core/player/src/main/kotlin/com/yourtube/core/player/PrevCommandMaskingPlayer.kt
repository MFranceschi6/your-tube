package com.yourtube.core.player

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

/**
 * YT-0239 (review change-request, 2026-05-08) — wraps the underlying [Player] handed to the
 * [androidx.media3.session.MediaSession] so Media3's `DefaultMediaNotificationProvider` does not
 * render its built-in skip-prev arrow alongside our custom `SKIP_TO_PREV_QUEUE` `CommandButton`.
 *
 * Background:
 * [PlaybackPlayerAdapter] only ever holds one `MediaItem` on ExoPlayer's timeline at a time
 * (one item per resolved stream URL). On a one-item timeline, ExoPlayer still reports both
 * [Player.COMMAND_SEEK_TO_PREVIOUS] and [Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM] as
 * available — the former because Media3 falls back to `seekTo(0)` of the current item, and the
 * latter as an artefact of the flag set the engine ships. The default notification provider
 * therefore renders a prev arrow (mapped to `Player.seekToPrevious()` → `seekTo(0)` rewind on
 * the current track), which sits next to our queue-aware `SKIP_TO_PREV_QUEUE` button — two prev
 * arrows in the system shade.
 *
 * Symmetric problem on the next side does not exist because
 * [Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM] genuinely becomes unavailable on a one-item
 * timeline (no `seekToNext` fallback); the default provider hides its built-in skip-next.
 *
 * Fix:
 * Wrap the ExoPlayer handed to [androidx.media3.session.MediaSession.Builder] in this
 * [ForwardingPlayer] subclass; [getAvailableCommands] strips both prev commands from the
 * delegate's command set, and [isCommandAvailable] reports `false` for the masked pair. Every
 * other call forwards to the delegate unchanged. The unwrapped ExoPlayer continues to drive
 * the in-process audio path via [PlaybackPlayerAdapter.attach] in [PlaybackService.onCreate],
 * so playback fidelity is not affected.
 *
 * `ForwardingPlayer` is `@UnstableApi` in Media3 1.4.x — the opt-in is contained at this
 * subclass declaration, mirroring the pattern used elsewhere in the module.
 */
@UnstableApi
internal class PrevCommandMaskingPlayer(player: Player) : ForwardingPlayer(player) {

    override fun getAvailableCommands(): Player.Commands =
        super.getAvailableCommands()
            .buildUpon()
            .remove(Player.COMMAND_SEEK_TO_PREVIOUS)
            .remove(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            .build()

    override fun isCommandAvailable(@Player.Command command: Int): Boolean {
        if (command == Player.COMMAND_SEEK_TO_PREVIOUS ||
            command == Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM
        ) {
            return false
        }
        return super.isCommandAvailable(command)
    }
}
