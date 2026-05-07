package com.yourtube.core.data.model

import com.yourtube.core.common.model.Track

data class PlaybackHistoryEntry(
    val id: String,
    val track: Track,
    val playedAt: String,
)
