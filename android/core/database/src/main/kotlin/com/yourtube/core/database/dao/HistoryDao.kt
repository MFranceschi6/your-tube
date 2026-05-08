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
    /**
     * YT-0154 / YT-0158 — read-side dedup. The underlying `history_entries` table
     * keeps one row per play event for any future analytics surface, but the
     * "Recently Played" view shows each `videoId` at most once, picking the
     * latest play. The correlated subquery selects the single most recent
     * history entry per track (tiebreak on `id DESC` to be deterministic when
     * two play events share an instant). The outer ORDER BY then sorts the
     * surviving one-per-track rows by recency, again with `id DESC` to keep
     * stable ordering across snapshots.
     */
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
        WHERE h.id = (
            SELECT h2.id FROM history_entries h2
            WHERE h2.trackVideoId = h.trackVideoId
            ORDER BY julianday(h2.playedAt) DESC, h2.id DESC
            LIMIT 1
        )
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
