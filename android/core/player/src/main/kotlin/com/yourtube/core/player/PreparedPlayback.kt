package com.yourtube.core.player

import com.yourtube.core.common.model.Track

data class PreparedPlayback(
    val track: Track,
    val streamUrl: String,
    val bitrateKbps: Int,
    val codec: String?,
    val container: String?,
    val startPositionMs: Long = 0L,
)
