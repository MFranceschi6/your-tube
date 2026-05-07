package com.yourtube.core.database.model

data class PlaylistSnapshotRow(
    val playlistId: String,
    val playlistName: String,
    val playlistCreatedAt: String,
    val playlistUpdatedAt: String,
    val position: Int?,
    val videoId: String?,
    val title: String?,
    val channel: String?,
    val durationSec: Int?,
    val thumbnailUrl: String?,
)
