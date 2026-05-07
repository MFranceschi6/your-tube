package com.yourtube.core.player

sealed interface PlaybackResult {
    data object Success : PlaybackResult
    data class Failure(val message: String) : PlaybackResult
}

interface PlaybackTransport {
    suspend fun playTrack(request: PlaybackRequest): PlaybackResult

    suspend fun pause()

    suspend fun resume()

    suspend fun seekTo(positionMs: Long)

    /**
     * Stop any currently-playing audio and clear the underlying player's media items
     * BEFORE a new stream URL has been resolved (YT-0050).
     *
     * Called by [PlayerController] the moment the user requests a new track so the
     * previous audio falls silent and the position/duration counters reset
     * immediately, instead of leaking through the several hundred milliseconds it
     * can take to resolve a new YouTube stream URL.
     *
     * Implementations should pause the player, seek to 0, and clear queued media
     * items. The follow-up [playTrack] call will queue the new media item once the
     * stream URL is resolved.
     */
    suspend fun stopAndClearCurrent()

    /**
     * Forwards persistent shuffle to the underlying player (`Player.shuffleModeEnabled`).
     * No-op (or best-effort) when no player is attached; the [PlayerController] keeps its
     * own state-flow in lockstep so the UI reflects intent regardless.
     */
    suspend fun setShuffleMode(enabled: Boolean)

    /**
     * Forwards persistent repeat mode to the underlying player (`Player.repeatMode`). Modes
     * map to `androidx.media3.common.Player` constants (`REPEAT_MODE_OFF` = 0,
     * `REPEAT_MODE_ONE` = 1, `REPEAT_MODE_ALL` = 2).
     */
    suspend fun setRepeatMode(mode: Int)
}
