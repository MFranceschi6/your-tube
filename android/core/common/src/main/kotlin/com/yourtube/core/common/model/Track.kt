package com.yourtube.core.common.model

data class Track(
    val videoId: String,
    val title: String,
    val channel: String,
    val durationSec: Int,
    val thumbnailUrl: String,
)
