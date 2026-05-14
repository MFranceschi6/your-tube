package com.yourtube.core.data.repository

import com.yourtube.core.common.model.PlayerState
import com.yourtube.core.database.entity.PlayerSnapshotEntity

/**
 * Persistence contract for the last-known player state. The implementing class
 * handles serialization of the queue (List<QueueItem>) to and from the JSON string
 * stored in the database.
 */
interface PlayerSnapshotRepository {
    /** Persist [state] as the current snapshot. Fire-and-forget from the call site. */
    suspend fun save(state: PlayerState)

    /**
     * Load the most recent persisted snapshot, or `null` if no snapshot has been written yet
     * or the underlying row cannot be read. Used by [PlaybackService] at cold launch to
     * restore the queue and position into [PlayerController] paused.
     */
    suspend fun loadSnapshot(): PlayerSnapshotEntity?
}
