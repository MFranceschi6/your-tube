package com.yourtube.core.player

import com.yourtube.core.common.model.PlayerState
import com.yourtube.core.common.model.Track
import com.yourtube.core.database.entity.PlayerSnapshotEntity
import kotlinx.coroutines.flow.StateFlow

interface PlayerController {
    val playerState: StateFlow<PlayerState>

    suspend fun playNow(track: Track)

    /**
     * YT-0155: replace the current queue with [tracks] and start playback at [startIndex].
     * Used by `PlaylistDetail`'s Play / Shuffle header and tap-to-play row interactions
     * so the user gets the Spotify-style "the playlist becomes my queue" behaviour. The
     * queue swap is a single state-flow update so the UI never sees an intermediate
     * empty queue. [startIndex] is clamped to the list's indices; the empty list is a
     * no-op (the controller stays in its current state).
     */
    suspend fun setQueueAndPlay(tracks: List<Track>, startIndex: Int)

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
     * YT-0307 — jump to a specific queue index without clearing the queue.
     * Sets [PlayerState.currentQueueIndex] to [index] and starts playback of the entry
     * at that position. Preceding and following entries stay in the queue.
     *
     * No-op when [index] is already [PlayerState.currentQueueIndex] (do not restart).
     * No-op when [index] is out of bounds.
     */
    suspend fun jumpToQueueItem(index: Int)

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

    /**
     * Set the playback speed multiplier. Range 0.5–2.0; 1.0 = normal.
     * Updates [PlayerState.playbackSpeed] synchronously and forwards to the transport.
     * The persisted preference is written separately via [com.yourtube.core.data.preferences.PlaybackSpeedPreferences].
     */
    suspend fun setPlaybackSpeed(speed: Float)

    /**
     * YT-0272 — cold-launch state restoration. Deserializes the persisted [snapshot] and
     * populates [playerState] with the saved queue, current track, seek position, repeat
     * mode, shuffle, and speed — WITHOUT triggering stream-URL resolution or auto-play.
     * The restored state is always PAUSED; the user must explicitly tap play.
     *
     * Validation rules (any failure → silent no-op, empty state preserved):
     * - Empty queue in snapshot → no-op.
     * - [snapshot.queueIndex] out of bounds → no-op.
     * - `queue[queueIndex].videoId != snapshot.currentVideoId` → no-op.
     * - JSON parse error in [snapshot.queue] → no-op.
     * - `null` snapshot (caller guards, but documented for completeness) → no-op.
     *
     * ExoPlayer is NOT loaded with a source URI; [PlayerState.engineLoaded] stays `false`
     * so the first [resume] call routes through `playQueueItem(...)` which triggers the
     * standard stream-URL extractor path, starting from [PlayerState.positionMs].
     */
    suspend fun restoreFromSnapshot(snapshot: PlayerSnapshotEntity)

    /**
     * YT-0285 — Activity-side explicit restore trigger.
     *
     * Called from [MainActivity.onCreate] / [onStart] via `lifecycleScope.launch` so the
     * persisted snapshot is loaded and applied to [playerState] immediately on every
     * Activity start, WITHOUT requiring Media3 service binding or any user interaction.
     *
     * This closes the "onBind guard never fires" race: [MediaControllerPlaybackClient]
     * builds the MediaController lazily on the first transport call, so if the user
     * relaunches the app and never interacts with the player, [PlaybackService.onBind]
     * never executes and the [PlaybackService.onCreate] restore path is never triggered.
     * By calling [ensureRestored] from the Activity we guarantee the singleton controller
     * holds the persisted [playerState.currentTrack] before the first Compose frame.
     *
     * Idempotent: if [playerState.currentTrack] is already non-null (the singleton
     * controller is live — e.g. the process survived a background trip), this is a no-op.
     * No snapshot I/O is performed when the controller is already in a live state.
     *
     * Failure bucket: this call targets the **controller rebind** bucket — the process is
     * alive and the singleton controller exists, but its in-memory [playerState] has not
     * yet been populated from the persisted snapshot because no transport call occurred.
     */
    suspend fun ensureRestored()
}
