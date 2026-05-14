package com.yourtube.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Single-row table that persists the last-known player state across process deaths.
 *
 * `id` is always 1 — upsert semantics keep the table to a single row.
 * `queue` is stored as a JSON string (serialized by the data layer, not here,
 * so this module stays free of kotlinx.serialization as a compile dep).
 *
 * DB version: introduced in migration 1 → 2 (see [PlaylistDatabase.MIGRATION_1_2]).
 * YT-0273: `title`, `channelName`, `thumbnailUrl` added in migration 3 → 4.
 */
@Entity(tableName = "player_snapshot")
data class PlayerSnapshotEntity(
    @PrimaryKey val id: Int = 1,
    val currentVideoId: String?,
    val queue: String,
    val queueIndex: Int,
    val positionMs: Long,
    val repeatMode: Int,
    val shuffleOn: Boolean,
    val playbackSpeed: Float,
    val savedAt: String,
    val title: String? = null,
    val channelName: String? = null,
    val thumbnailUrl: String? = null,
)
