package com.yourtube.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tracks")
data class TrackEntity(
    @PrimaryKey val videoId: String,
    val title: String,
    val channel: String,
    val durationSec: Int,
    val thumbnailUrl: String,
)
