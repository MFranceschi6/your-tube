package com.yourtube.app.pip

import com.yourtube.core.common.model.PlaybackStatus
import com.yourtube.core.common.model.PlayerState
import com.yourtube.core.common.model.Track

/**
 * YT-0104 — Pure helpers evaluated before [android.app.Activity.enterPictureInPictureMode]
 * and to decide when to dismiss an active PiP window.
 *
 * Extracted as top-level functions so they can be unit-tested without an Activity or
 * Android framework dependencies.
 */

/**
 * Returns `true` when all conditions for entering PiP are met:
 *  - A track is loaded ([PlayerState.currentTrack] is non-null).
 *  - Playback is active or paused — not idle/stopped/error.
 *  - Content carries a video stream ([Track.isVideo] == true).
 *
 * Audio-only tracks return `false` because there is no video surface to show in the PiP
 * window; the default value of [Track.isVideo] is `false`. The extractor layer must set
 * `isVideo = true` for streams that include a video component.
 */
fun shouldEnterPip(state: PlayerState): Boolean {
    val track = state.currentTrack ?: return false
    if (!track.isVideo) return false
    val activeStatus = state.playbackStatus in setOf(
        PlaybackStatus.PLAYING,
        PlaybackStatus.PAUSED,
        PlaybackStatus.LOADING,
        PlaybackStatus.BUFFERING,
    )
    return activeStatus
}

/**
 * Returns `true` when an active PiP window should be dismissed because playback stopped.
 *
 * The condition is:
 *  - There was a previous track ([prevTrack] non-null).
 *  - There is no longer a current track ([currTrack] is null).
 *  - The activity is currently in PiP mode ([isInPip] is true).
 *
 * Call [android.app.Activity.moveTaskToBack] when this returns `true` to gracefully
 * dismiss the stale PiP window instead of leaving it showing frozen artwork.
 */
fun shouldDismissPip(prevTrack: Track?, currTrack: Track?, isInPip: Boolean): Boolean =
    prevTrack != null && currTrack == null && isInPip
