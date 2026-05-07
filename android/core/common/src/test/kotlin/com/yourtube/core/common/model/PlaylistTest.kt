package com.yourtube.core.common.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class PlaylistTest {

    private val track = Track(
        videoId = "v1",
        title = "Track One",
        channel = "Channel A",
        durationSec = 180,
        thumbnailUrl = "https://example.com/t1.jpg",
    )

    private val playlist = Playlist(
        id = "playlist-uuid-1",
        name = "My Playlist",
        createdAt = "2026-01-01T00:00:00Z",
        updatedAt = "2026-01-02T00:00:00Z",
        tracks = listOf(track),
    )

    @Test
    fun `equality holds for identical playlists`() {
        val other = playlist.copy()
        assertEquals(playlist, other)
    }

    @Test
    fun `playlists with different ids are not equal`() {
        val other = playlist.copy(id = "playlist-uuid-2")
        assertNotEquals(playlist, other)
    }

    @Test
    fun `playlist tracks list is correct`() {
        assertEquals(1, playlist.tracks.size)
        assertEquals(track, playlist.tracks.first())
    }
}
