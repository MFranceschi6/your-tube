package com.yourtube.core.common.haptics

/** Triggers platform haptic feedback for user-initiated playback actions. */
interface HapticsController {

    /** Play/pause button tapped by the user. */
    fun onPlayPause()

    /** Skip-next or skip-previous tapped by the user. */
    fun onSkip()

    /** Queue-add tapped by the user (adds track to end of queue). */
    fun onQueueAdd()

    /**
     * Play-next tapped by the user (inserts track immediately after the current position).
     * Uses a distinct effect from [onQueueAdd] so users can feel the difference.
     */
    fun onPlayNext()
}
