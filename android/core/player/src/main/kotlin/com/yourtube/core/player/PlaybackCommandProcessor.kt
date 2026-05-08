package com.yourtube.core.player

import com.yourtube.core.network.YoutubeService
import javax.inject.Inject

class PlaybackCommandProcessor @Inject constructor(
    private val youtubeService: YoutubeService,
    private val preparedPlaybackFactory: PreparedPlaybackFactory,
    private val logger: Logger,
    private val perfTracer: PlaybackPerfTracer,
) {
    suspend fun playTrack(
        request: PlaybackRequest,
        playbackEngine: PlaybackEngine,
    ) {
        val videoId = request.track.videoId
        perfTracer.mark("EXTRACT_START", videoId)
        val resolvedAudioStream = try {
            youtubeService.resolveAudioStream(
                videoId = videoId,
                preferredMaxBitrateKbps = request.preferredMaxBitrateKbps,
            )
        } catch (throwable: Throwable) {
            perfTracer.markFail(videoId, throwable, "stage=extract")
            throw throwable
        }
        perfTracer.mark(
            "EXTRACT_DONE",
            videoId,
            "host=${PlaybackPerfTracer.safeHostOf(resolvedAudioStream.streamUrl)} " +
                "bitrate=${resolvedAudioStream.bitrateKbps}kbps " +
                "codec=${resolvedAudioStream.codec} container=${resolvedAudioStream.container}",
        )
        logger.debug(
            TAG,
            "resolved videoId=$videoId bitrate=${resolvedAudioStream.bitrateKbps}kbps " +
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
