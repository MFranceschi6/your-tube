package com.yourtube.core.player

import com.yourtube.core.network.YoutubeService
import javax.inject.Inject

class PlaybackCommandProcessor @Inject constructor(
    private val youtubeService: YoutubeService,
    private val preparedPlaybackFactory: PreparedPlaybackFactory,
    private val logger: Logger,
) {
    suspend fun playTrack(
        request: PlaybackRequest,
        playbackEngine: PlaybackEngine,
    ) {
        val resolvedAudioStream = youtubeService.resolveAudioStream(
            videoId = request.track.videoId,
            preferredMaxBitrateKbps = request.preferredMaxBitrateKbps,
        )
        logger.debug(
            TAG,
            "resolved videoId=${request.track.videoId} bitrate=${resolvedAudioStream.bitrateKbps}kbps " +
                "codec=${resolvedAudioStream.codec} container=${resolvedAudioStream.container} " +
                "urlHost=${resolvedAudioStream.streamUrl.substringBefore('?').substringBeforeLast('/')}",
        )
        playbackEngine.queue(preparedPlaybackFactory.create(request, resolvedAudioStream))
        playbackEngine.prepare()
        playbackEngine.play()
    }

    private companion object {
        private const val TAG = "YT-PlaybackCmd"
    }
}
