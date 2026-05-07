package com.yourtube.core.common.model

data class SearchResult(
    val videoId: String,
    val title: String,
    val channel: String,
    val durationSec: Int,
    val thumbnailUrl: String,
)
