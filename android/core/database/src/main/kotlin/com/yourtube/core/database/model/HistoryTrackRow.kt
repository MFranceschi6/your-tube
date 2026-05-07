package com.yourtube.core.database.model

data class HistoryTrackRow(
    val entryId: String,
    val playedAt: String,
    val videoId: String,
    val title: String,
    val channel: String,
    val durationSec: Int,
    val thumbnailUrl: String,
)
