package com.yourtube.core.database.model

data class PlaylistTrackRow(
    val playlistId: String,
    val position: Int,
    val videoId: String,
    val title: String,
    val channel: String,
    val durationSec: Int,
    val thumbnailUrl: String,
)
