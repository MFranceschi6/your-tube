package com.yourtube.core.player

import android.os.Bundle
import androidx.core.os.bundleOf
import androidx.media3.common.MediaMetadata
import androidx.media3.session.SessionCommand
import com.yourtube.core.common.model.Track
import com.yourtube.core.network.YoutubeService

object PlaybackSessionCommand {
    const val PLAY_TRACK_ACTION = "com.yourtube.core.player.PLAY_TRACK"

    /**
     * `MediaMetadata.extras` key that stashes the YouTube video ID alongside the lock-screen
     * metadata (YT-0062a Q11). Useful for any controller that wants to round-trip back to the
     * source without re-parsing the artwork URI.
     */
    const val EXTRAS_KEY_VIDEO_ID = "com.yourtube.core.player.VIDEO_ID"

    private const val KEY_VIDEO_ID = "video_id"
    private const val KEY_TITLE = "title"
    private const val KEY_CHANNEL = "channel"
    private const val KEY_DURATION_SEC = "duration_sec"
    private const val KEY_THUMBNAIL_URL = "thumbnail_url"
    private const val KEY_PREFERRED_MAX_BITRATE_KBPS = "preferred_max_bitrate_kbps"

    val playTrack: SessionCommand = SessionCommand(PLAY_TRACK_ACTION, Bundle.EMPTY)

    fun toBundle(request: PlaybackRequest): Bundle = bundleOf(
        KEY_VIDEO_ID to request.track.videoId,
        KEY_TITLE to request.track.title,
        KEY_CHANNEL to request.track.channel,
        KEY_DURATION_SEC to request.track.durationSec,
        KEY_THUMBNAIL_URL to request.track.thumbnailUrl,
        KEY_PREFERRED_MAX_BITRATE_KBPS to request.preferredMaxBitrateKbps,
    )

    fun fromBundle(bundle: Bundle): PlaybackRequest? {
        val videoId = bundle.getString(KEY_VIDEO_ID)?.trim().orEmpty()
        val title = bundle.getString(KEY_TITLE)?.trim().orEmpty()
        val channel = bundle.getString(KEY_CHANNEL)?.trim().orEmpty()
        if (videoId.isEmpty() || title.isEmpty() || channel.isEmpty()) return null

        return PlaybackRequest(
            track = Track(
                videoId = videoId,
                title = title,
                channel = channel,
                durationSec = bundle.getInt(KEY_DURATION_SEC),
                thumbnailUrl = bundle.getString(KEY_THUMBNAIL_URL).orEmpty(),
            ),
            preferredMaxBitrateKbps = bundle.getInt(
                KEY_PREFERRED_MAX_BITRATE_KBPS,
                YoutubeService.DEFAULT_PREFERRED_MAX_BITRATE_KBPS,
            ),
        )
    }

    /**
     * Builds the lock-screen / system-media-controls metadata for a track. Uses the highest-res
     * YouTube thumbnail (`maxresdefault.jpg`) for the artwork URI; the in-app low-res
     * [Track.thumbnailUrl] stays untouched and continues to drive the in-app artwork rendering.
     *
     * Limitation (YT-0062a Q11): a real `maxresdefault.jpg` → `hqdefault.jpg` fallback would
     * require an HTTP probe at extraction time, which is out of scope here. Videos without
     * `maxresdefault.jpg` will fall through to no artwork on the lock screen rather than to
     * `hqdefault.jpg`. Tracked as a follow-up.
     *
     * `extras` carries [EXTRAS_KEY_VIDEO_ID] for round-trips that need the source ID.
     */
    fun toMediaMetadata(preparedPlayback: PreparedPlayback): MediaMetadata =
        toMediaMetadata(preparedPlayback.track)

    fun toMediaMetadata(track: Track): MediaMetadata {
        val builder = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.channel)
            .setIsPlayable(true)

        val artworkUri = track.maxResThumbnailUrl()
        if (artworkUri != null) {
            builder.setArtworkUri(android.net.Uri.parse(artworkUri))
        } else if (track.thumbnailUrl.isNotBlank()) {
            // Fallback to the in-app low-res URL when the videoId is absent (e.g. unit-test
            // tracks). Keeps existing test expectations intact.
            builder.setArtworkUri(android.net.Uri.parse(track.thumbnailUrl))
        }

        builder.setExtras(bundleOf(EXTRAS_KEY_VIDEO_ID to track.videoId))

        return builder.build()
    }

    /**
     * Highest-resolution YouTube thumbnail URL for a [Track] when a videoId is present.
     * `maxresdefault.jpg` is the 1280x720 master; not every video has it, but Media3's
     * `DefaultMediaNotificationProvider` will display nothing rather than crash when it 404s.
     * Returns `null` when the videoId is blank so callers can fall back to the in-app URL.
     */
    private fun Track.maxResThumbnailUrl(): String? {
        val id = videoId.trim()
        if (id.isEmpty()) return null
        return "https://i.ytimg.com/vi/$id/maxresdefault.jpg"
    }
}
