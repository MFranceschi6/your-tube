package com.yourtube.app.sharing

import androidx.test.core.app.ApplicationProvider
import com.yourtube.core.common.model.Playlist
import com.yourtube.core.common.model.Track
import com.yourtube.core.data.codec.KotlinxPlaylistCodec
import com.yourtube.core.data.codec.PlaylistCodec
import com.yourtube.core.data.model.PlaybackHistoryEntry
import com.yourtube.core.data.repository.PlaylistRepository
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaylistImporterTest {

    private val codec: PlaylistCodec = KotlinxPlaylistCodec()
    private val repository = RecordingPlaylistRepository()
    private val importer = PlaylistImporter(
        ApplicationProvider.getApplicationContext(),
        codec,
        repository,
    )

    private val samplePlaylist = Playlist(
        id = "00000000-0000-4000-8000-0000000000aa",
        name = "Round Trip",
        createdAt = "2026-05-04T10:00:00Z",
        updatedAt = "2026-05-04T10:30:00Z",
        tracks = listOf(
            Track(
                videoId = "abc12345678",
                title = "Track One",
                channel = "Channel",
                durationSec = 200,
                thumbnailUrl = "https://example.com/a.jpg",
            ),
        ),
    )

    @Test
    fun `valid payload imports and is recorded in repository`() = runTest {
        val payload = codec.export(samplePlaylist)

        val result = importer.importFromPayload(payload)

        val success = assertIs<PlaylistImportResult.Success>(result)
        assertEquals(samplePlaylist, success.playlist)
        assertEquals(listOf(samplePlaylist), repository.imported)
    }

    @Test
    fun `invalid json yields InvalidPayload and skips repository`() = runTest {
        val result = importer.importFromPayload("not json at all")

        assertEquals(PlaylistImportResult.InvalidPayload, result)
        assertEquals(emptyList(), repository.imported)
    }

    @Test
    fun `future schema version yields UnsupportedSchema`() = runTest {
        val payload = """
            {
              "schemaVersion": 999,
              "id": "00000000-0000-4000-8000-0000000000bb",
              "name": "Future",
              "createdAt": "2026-05-04T10:00:00Z",
              "updatedAt": "2026-05-04T10:30:00Z",
              "tracks": []
            }
        """.trimIndent()

        val result = importer.importFromPayload(payload)

        assertEquals(PlaylistImportResult.UnsupportedSchema, result)
        assertEquals(emptyList(), repository.imported)
    }

    private class RecordingPlaylistRepository : PlaylistRepository {
        val imported = mutableListOf<Playlist>()

        override suspend fun importPlaylist(playlist: Playlist) {
            imported += playlist
        }

        override fun observePlaylists(): Flow<List<Playlist>> = flowOf(emptyList())
        override fun observePlaylist(playlistId: String): Flow<Playlist?> = flowOf(null)
        override fun observeHistory(): Flow<List<PlaybackHistoryEntry>> = flowOf(emptyList())
        override suspend fun createPlaylist(
            name: String,
            tracks: List<Track>,
            playlistId: String?,
        ): Playlist = error("unused")
        override suspend fun renamePlaylist(playlistId: String, newName: String) = error("unused")
        override suspend fun deletePlaylist(playlistId: String) = error("unused")
        override suspend fun addTrackToPlaylist(playlistId: String, track: Track) = error("unused")
        override suspend fun removeTrackFromPlaylist(playlistId: String, position: Int) = error("unused")
        override suspend fun reorderTracks(playlistId: String, fromIndex: Int, toIndex: Int) = error("unused")
        override suspend fun recordPlayback(track: Track, playedAt: String?): PlaybackHistoryEntry =
            error("unused")
        override suspend fun clearHistory() = error("unused")
        override suspend fun removeHistoryEntry(entryId: String) = error("unused")
    }
}
