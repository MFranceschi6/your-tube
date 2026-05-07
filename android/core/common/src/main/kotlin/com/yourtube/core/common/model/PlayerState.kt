package com.yourtube.core.common.model

/**
 * Snapshot of the current playback state. New fields appended at the end so existing
 * positional/copy callsites in tests stay stable.
 *
 * `repeatMode` uses the integer values defined by `androidx.media3.common.Player`:
 *  - `0` = `Player.REPEAT_MODE_OFF` (default)
 *  - `1` = `Player.REPEAT_MODE_ONE`
 *  - `2` = `Player.REPEAT_MODE_ALL`
 *
 * The literal is used here rather than importing Media3 because `core:common` must remain a
 * pure-Kotlin module with no Media3 dependency. Callers in `:core:player` and `:feature:player`
 * may use the symbolic Media3 constants when invoking [com.yourtube.core.player.PlayerController]
 * methods.
 */
data class PlayerState(
    val currentTrack: Track? = null,
    val queue: List<QueueItem> = emptyList(),
    val currentQueueIndex: Int = -1,
    val playbackStatus: PlaybackStatus = PlaybackStatus.IDLE,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val errorMessage: String? = null,
    val shuffleOn: Boolean = false,
    val repeatMode: Int = 0,
)
