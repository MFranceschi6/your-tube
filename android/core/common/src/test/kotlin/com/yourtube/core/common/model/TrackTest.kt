package com.yourtube.core.common.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class TrackTest {

    private val track = Track(
        videoId = "abc123",
        title = "Test Title",
        channel = "Test Channel",
        durationSec = 240,
        thumbnailUrl = "https://example.com/thumb.jpg",
    )

    @Test
    fun `equality holds for identical tracks`() {
        val other = track.copy()
        assertEquals(track, other)
    }

    @Test
    fun `tracks with different videoId are not equal`() {
        val other = track.copy(videoId = "xyz789")
        assertNotEquals(track, other)
    }

    @Test
    fun `copy with changed title produces updated track`() {
        val updated = track.copy(title = "New Title")
        assertEquals("New Title", updated.title)
        assertEquals(track.videoId, updated.videoId)
    }
}
