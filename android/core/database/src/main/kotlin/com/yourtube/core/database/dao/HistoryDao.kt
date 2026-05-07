package com.yourtube.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.yourtube.core.database.entity.HistoryEntryEntity
import com.yourtube.core.database.entity.TrackEntity
import com.yourtube.core.database.model.HistoryTrackRow
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryDao {
    @Query(
        """
        SELECT
            h.id AS entryId,
            h.playedAt AS playedAt,
            t.videoId AS videoId,
            t.title AS title,
            t.channel AS channel,
            t.durationSec AS durationSec,
            t.thumbnailUrl AS thumbnailUrl
        FROM history_entries h
        INNER JOIN tracks t ON t.videoId = h.trackVideoId
        ORDER BY julianday(h.playedAt) DESC, h.id DESC
        """,
    )
    fun observeHistory(): Flow<List<HistoryTrackRow>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTrack(track: TrackEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistoryEntry(entry: HistoryEntryEntity)

    @Transaction
    suspend fun recordPlayback(
        track: TrackEntity,
        entry: HistoryEntryEntity,
    ) {
        upsertTrack(track)
        insertHistoryEntry(entry)
    }

    @Query("DELETE FROM history_entries WHERE id = :entryId")
    suspend fun deleteHistoryEntry(entryId: String)

    @Query("DELETE FROM history_entries")
    suspend fun clearHistory()
}
