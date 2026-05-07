package com.yourtube.core.player

import com.yourtube.core.common.model.PlayerState
import com.yourtube.core.common.model.Track
import kotlinx.coroutines.flow.StateFlow

interface PlayerController {
    val playerState: StateFlow<PlayerState>

    suspend fun playNow(track: Track)

    suspend fun addToQueue(track: Track)

    suspend fun playNext(track: Track)

    suspend fun skipNext()

    suspend fun skipPrevious()

    suspend fun pause()

    suspend fun resume()

    suspend fun seekTo(positionMs: Long)

    suspend fun removeQueueItem(queueId: String)

    suspend fun moveQueueItem(fromIndex: Int, toIndex: Int)

    /**
     * Toggle persistent shuffle mode. Routes through the [PlaybackTransport] (and ultimately
     * `MediaController` / `Player.shuffleModeEnabled`) so the same call path serves both UI
     * taps and `MediaSession.Callback` writes coming from system media controls.
     *
     * The state-flow update is synchronous so the UI reflects the user's intent even if the
     * underlying player is not yet attached; the engine reconciles when it next attaches.
     */
    suspend fun setShuffleMode(enabled: Boolean)

    /**
     * Cycle persistent repeat mode. Mode integers match `androidx.media3.common.Player`:
     *  - `0` = `Player.REPEAT_MODE_OFF`
     *  - `1` = `Player.REPEAT_MODE_ONE`
     *  - `2` = `Player.REPEAT_MODE_ALL`
     *
     * Routes through the [PlaybackTransport] so UI taps and `MediaSession.Callback` writes
     * share a single call path.
     */
    suspend fun setRepeatMode(mode: Int)
}
