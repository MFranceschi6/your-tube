package com.yourtube.core.common.model

data class Track(
    val videoId: String,
    val title: String,
    val channel: String,
    val durationSec: Int,
    val thumbnailUrl: String,
    /**
     * True when the track carries a video stream (as opposed to audio-only content).
     *
     * Defaults to `false` — the current extraction path does not supply a video surface,
     * so all content is audio-only until the extractor (e.g. InnerTubePlayerClient or
     * equivalent) explicitly sets this field to `true` for streams with a video component.
     *
     * Callers that need to enable video-specific features (e.g. Picture-in-Picture) must
     * gate on this flag.
     */
    val isVideo: Boolean = false,
)
