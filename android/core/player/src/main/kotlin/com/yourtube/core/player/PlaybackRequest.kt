package com.yourtube.core.player

import com.yourtube.core.common.model.Track
import com.yourtube.core.network.YoutubeService

data class PlaybackRequest(
    val track: Track,
    val preferredMaxBitrateKbps: Int = YoutubeService.DEFAULT_PREFERRED_MAX_BITRATE_KBPS,
    // YT-0291 — restored position passed through to ExoPlayer so buffering starts
    // at the right offset rather than seeking post-load.
    val startPositionMs: Long = 0L,
)
