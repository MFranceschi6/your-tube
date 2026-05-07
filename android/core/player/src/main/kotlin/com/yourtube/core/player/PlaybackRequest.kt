package com.yourtube.core.player

import com.yourtube.core.common.model.Track
import com.yourtube.core.network.YoutubeService

data class PlaybackRequest(
    val track: Track,
    val preferredMaxBitrateKbps: Int = YoutubeService.DEFAULT_PREFERRED_MAX_BITRATE_KBPS,
)
