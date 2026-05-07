package com.yourtube.core.common.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class QueueItemTest {

    private val track = Track(
        videoId = "abc123",
        title = "Test Title",
        channel = "Test Channel",
        durationSec = 240,
        thumbnailUrl = "https://example.com/thumb.jpg",
    )

    @Test
    fun `equality holds for identical queue items`() {
        val item = QueueItem(track = track, queueId = "q1")
        assertEquals(item, item.copy())
    }

    @Test
    fun `items with same track but different queueId are not equal`() {
        val item1 = QueueItem(track = track, queueId = "q1")
        val item2 = QueueItem(track = track, queueId = "q2")
        assertNotEquals(item1, item2)
    }
}
