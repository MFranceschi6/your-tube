package com.yourtube.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.yourtube.core.database.entity.PlayerSnapshotEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PlayerSnapshotDao {

    /** Persist the current player snapshot. Overwrites any prior row (id = 1 sentinel). */
    @Upsert
    suspend fun upsert(snapshot: PlayerSnapshotEntity)

    /** Observe the persisted snapshot. Emits `null` before the first upsert. */
    @Query("SELECT * FROM player_snapshot WHERE id = 1")
    fun getSnapshot(): Flow<PlayerSnapshotEntity?>
}
