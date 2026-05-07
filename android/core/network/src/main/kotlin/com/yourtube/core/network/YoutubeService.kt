package com.yourtube.core.network

import com.yourtube.core.common.model.SearchResult

interface YoutubeService {
    suspend fun searchVideos(query: String): List<SearchResult>

    suspend fun resolveAudioStream(
        videoId: String,
        preferredMaxBitrateKbps: Int = DEFAULT_PREFERRED_MAX_BITRATE_KBPS,
    ): ResolvedAudioStream

    companion object {
        const val DEFAULT_PREFERRED_MAX_BITRATE_KBPS = 160
    }
}
