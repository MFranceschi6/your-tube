package com.yourtube.app.pip

import com.yourtube.core.common.model.PlaybackStatus
import com.yourtube.core.common.model.PlayerState
import com.yourtube.core.common.model.Track
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * YT-0104 — Unit tests for [shouldEnterPip] and [shouldDismissPip].
 *
 * Both functions are pure (no Android dependencies) so plain JUnit4 is sufficient — no
 * Robolectric or Hilt scaffolding needed.
 */
class PipHelperTest {

    /** A video track — isVideo = true. Used for shouldEnterPip "true" cases. */
    private val videoTrack = Track(
        videoId = "abc123",
        title = "Test Video",
        channel = "Test Channel",
        durationSec = 300,
        thumbnailUrl = "https://example.com/thumb.jpg",
        isVideo = true,
    )

    /** An audio-only track — isVideo = false (the default). */
    private val audioTrack = Track(
        videoId = "def456",
        title = "Test Audio",
        channel = "Test Channel",
        durationSec = 180,
        thumbnailUrl = "https://example.com/thumb2.jpg",
        isVideo = false,
    )

    // ── shouldEnterPip — true cases ───────────────────────────────────────────────────────

    @Test
    fun returns_true_when_video_track_playing() {
        val state = PlayerState(
            currentTrack = videoTrack,
            playbackStatus = PlaybackStatus.PLAYING,
            isPlaying = true,
        )
        assertTrue(shouldEnterPip(state))
    }

    @Test
    fun returns_true_when_video_track_paused() {
        val state = PlayerState(
            currentTrack = videoTrack,
            playbackStatus = PlaybackStatus.PAUSED,
            isPlaying = false,
        )
        assertTrue(shouldEnterPip(state))
    }

    @Test
    fun returns_true_when_video_track_loading() {
        val state = PlayerState(
            currentTrack = videoTrack,
            playbackStatus = PlaybackStatus.LOADING,
            isPlaying = false,
        )
        assertTrue(shouldEnterPip(state))
    }

    @Test
    fun returns_true_when_video_track_buffering() {
        val state = PlayerState(
            currentTrack = videoTrack,
            playbackStatus = PlaybackStatus.BUFFERING,
            isPlaying = false,
        )
        assertTrue(shouldEnterPip(state))
    }

    // ── shouldEnterPip — false cases ──────────────────────────────────────────────────────

    @Test
    fun returns_false_when_no_current_track() {
        val state = PlayerState(
            currentTrack = null,
            playbackStatus = PlaybackStatus.IDLE,
            isPlaying = false,
        )
        assertFalse(shouldEnterPip(state))
    }

    @Test
    fun returns_false_when_audio_only_track_playing() {
        // PiP must not be entered for audio-only content — there is no video surface.
        val state = PlayerState(
            currentTrack = audioTrack,
            playbackStatus = PlaybackStatus.PLAYING,
            isPlaying = true,
        )
        assertFalse(shouldEnterPip(state))
    }

    @Test
    fun returns_false_when_audio_only_track_paused() {
        val state = PlayerState(
            currentTrack = audioTrack,
            playbackStatus = PlaybackStatus.PAUSED,
            isPlaying = false,
        )
        assertFalse(shouldEnterPip(state))
    }

    @Test
    fun returns_false_when_video_track_present_but_status_is_IDLE() {
        val state = PlayerState(
            currentTrack = videoTrack,
            playbackStatus = PlaybackStatus.IDLE,
            isPlaying = false,
        )
        assertFalse(shouldEnterPip(state))
    }

    @Test
    fun returns_false_when_video_track_present_but_status_is_ERROR() {
        val state = PlayerState(
            currentTrack = videoTrack,
            playbackStatus = PlaybackStatus.ERROR,
            isPlaying = false,
            errorMessage = "Stream not found",
        )
        assertFalse(shouldEnterPip(state))
    }

    @Test
    fun returns_false_when_no_track_regardless_of_status() {
        // Guard: even if playbackStatus claims PLAYING with no track, we reject.
        val state = PlayerState(
            currentTrack = null,
            playbackStatus = PlaybackStatus.PLAYING,
            isPlaying = true,
        )
        assertFalse(shouldEnterPip(state))
    }

    // ── shouldDismissPip ──────────────────────────────────────────────────────────────────

    @Test
    fun shouldDismissPip_returns_true_when_track_goes_null_while_in_pip() {
        assertTrue(shouldDismissPip(prevTrack = videoTrack, currTrack = null, isInPip = true))
    }

    @Test
    fun shouldDismissPip_returns_false_when_track_goes_null_but_not_in_pip() {
        assertFalse(shouldDismissPip(prevTrack = videoTrack, currTrack = null, isInPip = false))
    }

    @Test
    fun shouldDismissPip_returns_false_when_track_remains_non_null_in_pip() {
        assertFalse(shouldDismissPip(prevTrack = videoTrack, currTrack = audioTrack, isInPip = true))
    }

    @Test
    fun shouldDismissPip_returns_false_when_prev_track_was_already_null() {
        // Only dismiss when there was a real track before; ignore cold-start null→null.
        assertFalse(shouldDismissPip(prevTrack = null, currTrack = null, isInPip = true))
    }

    @Test
    fun shouldDismissPip_returns_false_when_all_conditions_absent() {
        assertFalse(shouldDismissPip(prevTrack = null, currTrack = null, isInPip = false))
    }
}
