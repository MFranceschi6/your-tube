package com.yourtube.core.network

data class ResolvedAudioStream(
    val videoId: String,
    // Time-limited signed URL — do not log or serialise.
    val streamUrl: String,
    val bitrateKbps: Int,
    val codec: String?,
    val container: String?,
)
