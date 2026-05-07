package com.yourtube.core.player

import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import javax.inject.Inject

/**
 * Thin queueing seam over an ExoPlayer-shaped target (YT-0163 rework round 2,
 * gating item #3). Production wiring goes through [ExoPlayerMediaItemQueueing];
 * unit tests provide a hand-rolled fake so the HLS-routing branch can be
 * asserted without instantiating a real `ExoPlayer` (final class with
 * Looper/HandlerThread requirements that are awkward in a JVM unit test).
 *
 * We expose `setMediaItem` / `setMediaSource` / `prepare` / `play` only — the
 * narrowest surface that lets [PlaybackPlayerAdapter] route HLS manifests
 * through `HlsMediaSource.Factory` while keeping progressive playback on the
 * default media-item path. Anything broader risks duplicating the (frequently
 * rotated) `Player` interface.
 */
internal interface MediaItemQueueing {
    fun setMediaItem(mediaItem: MediaItem)
    fun setMediaSource(mediaSource: MediaSource)
    fun prepare()
    fun play()
}

internal class ExoPlayerMediaItemQueueing(
    private val player: ExoPlayer,
) : MediaItemQueueing {
    override fun setMediaItem(mediaItem: MediaItem) = player.setMediaItem(mediaItem)
    override fun setMediaSource(mediaSource: MediaSource) = player.setMediaSource(mediaSource)
    override fun prepare() = player.prepare()
    override fun play() = player.play()
}

class PlaybackPlayerAdapter internal constructor(
    private val hlsMediaSourceFactory: (MediaItem) -> MediaSource,
) : PlaybackEngine {

    @Inject
    constructor() : this(
        hlsMediaSourceFactory = { mediaItem ->
            HlsMediaSource.Factory(DefaultHttpDataSource.Factory())
                .createMediaSource(mediaItem)
        },
    )

    private var queueing: MediaItemQueueing? = null

    fun attach(player: ExoPlayer) {
        this.queueing = ExoPlayerMediaItemQueueing(player)
    }

    /** Test seam — bypasses real `ExoPlayer` so JVM unit tests can assert the HLS branch. */
    internal fun attach(queueing: MediaItemQueueing) {
        this.queueing = queueing
    }

    fun detach() {
        queueing = null
    }

    override fun queue(preparedPlayback: PreparedPlayback) {
        val mediaItem = MediaItem.Builder()
            .setMediaId(preparedPlayback.track.videoId)
            .setUri(preparedPlayback.streamUrl)
            .setMediaMetadata(PlaybackSessionCommand.toMediaMetadata(preparedPlayback))
            .build()
        val target = requireNotNull(queueing) {
            "PlaybackPlayerAdapter is not attached to an ExoPlayer."
        }
        // Livestream URLs are HLS manifests (`.m3u8`) emitted by the InnerTube
        // `/player` endpoint as `streamingData.hlsManifestUrl`. ExoPlayer's
        // default progressive `MediaSource` cannot parse them — route via
        // `HlsMediaSource.Factory` instead. The InnerTube layer marks these
        // resolutions with `container = "hls"` (PlayerResolution.AudioFormat.Livestream).
        if (preparedPlayback.container.equals("hls", ignoreCase = true)) {
            target.setMediaSource(hlsMediaSourceFactory(mediaItem))
        } else {
            target.setMediaItem(mediaItem)
        }
    }

    override fun prepare() {
        requireNotNull(queueing) { "PlaybackPlayerAdapter is not attached to an ExoPlayer." }
            .prepare()
    }

    override fun play() {
        requireNotNull(queueing) { "PlaybackPlayerAdapter is not attached to an ExoPlayer." }
            .play()
    }
}
