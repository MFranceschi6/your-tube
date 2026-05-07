package com.yourtube.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "playlist_tracks",
    primaryKeys = ["playlistId", "position"],
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["videoId"],
            childColumns = ["trackVideoId"],
            onDelete = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        Index("playlistId"),
        Index("trackVideoId"),
    ],
)
data class PlaylistTrackEntity(
    val playlistId: String,
    val trackVideoId: String,
    val position: Int,
)
