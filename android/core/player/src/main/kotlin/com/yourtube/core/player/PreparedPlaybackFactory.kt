package com.yourtube.core.player

import com.yourtube.core.network.ResolvedAudioStream
import javax.inject.Inject

class PreparedPlaybackFactory @Inject constructor() {
    fun create(
        request: PlaybackRequest,
        resolvedAudioStream: ResolvedAudioStream,
    ): PreparedPlayback = PreparedPlayback(
        track = request.track,
        streamUrl = resolvedAudioStream.streamUrl,
        bitrateKbps = resolvedAudioStream.bitrateKbps,
        codec = resolvedAudioStream.codec,
        container = resolvedAudioStream.container,
        startPositionMs = request.startPositionMs,
    )
}
