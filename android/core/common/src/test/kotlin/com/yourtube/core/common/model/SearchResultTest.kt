package com.yourtube.core.common.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class SearchResultTest {

    private val result = SearchResult(
        videoId = "abc123",
        title = "Test Title",
        channel = "Test Channel",
        durationSec = 180,
        thumbnailUrl = "https://example.com/thumb.jpg",
    )

    @Test
    fun `equality holds for identical results`() {
        assertEquals(result, result.copy())
    }

    @Test
    fun `results with different videoId are not equal`() {
        assertNotEquals(result, result.copy(videoId = "xyz789"))
    }

    @Test
    fun `copy with changed title produces updated result`() {
        val updated = result.copy(title = "New Title")
        assertEquals("New Title", updated.title)
        assertEquals(result.videoId, updated.videoId)
    }
}
