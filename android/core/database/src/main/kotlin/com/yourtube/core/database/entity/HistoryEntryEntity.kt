package com.yourtube.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "history_entries",
    foreignKeys = [
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["videoId"],
            childColumns = ["trackVideoId"],
            onDelete = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [Index("trackVideoId")],
)
data class HistoryEntryEntity(
    @PrimaryKey val id: String,
    val trackVideoId: String,
    val playedAt: String,
)
