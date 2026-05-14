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
    /**
     * YT-0236 — `true` once the underlying playback engine has a MediaItem loaded for
     * [currentTrack] (i.e. `playQueueItem(...)` returned [com.yourtube.core.player.PlaybackResult.Success]).
     *
     * Distinguishes "paused-after-playing" (engine timeline populated) from
     * "queued-never-played" (engine timeline empty — `addToQueue` / `playNext` from a
     * fully empty controller surface a paused current track WITHOUT pushing a MediaItem
     * onto the engine). Tap-play on the latter must bootstrap engine playback via
     * `playQueueItem(...)` — calling `MediaController.play()` against an empty timeline
     * pushes ExoPlayer to STATE_ENDED and routes through `handleTrackEnded()`, settling
     * to IDLE with `positionMs = durationMs` (audio never starts, slider jumps to end).
     */
    val engineLoaded: Boolean = false,
    /**
     * Current playback speed multiplier applied to ExoPlayer. Range 0.5–2.0; 1.0 = normal.
     * Updated synchronously on [PlayerController.setPlaybackSpeed] so the UI reflects the
     * user's selection without waiting on the engine round-trip.
     */
    val playbackSpeed: Float = 1.0f,
)
