package com.yourtube.app.sharing

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.yourtube.core.common.model.Playlist
import com.yourtube.core.common.model.Track
import com.yourtube.core.data.codec.KotlinxPlaylistCodec
import com.yourtube.core.data.model.PlaybackHistoryEntry
import com.yourtube.core.data.repository.PlaylistRepository
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SharingViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `import emits Success event when payload is valid`() = runTest(dispatcher) {
        val codec = KotlinxPlaylistCodec()
        val payload = codec.export(samplePlaylist)
        val uri = writePayloadToCacheUri("valid.ytplaylist.json", payload)
        val repository = RecordingPlaylistRepository()
        val importer = PlaylistImporter(
            ApplicationProvider.getApplicationContext(),
            codec,
            repository,
        )
        val viewModel = SharingViewModel(importer)

        viewModel.events.test {
            viewModel.import(uri)
            advanceUntilIdle()

            val event = awaitItem()
            val success = assertIs<PlaylistImportResult.Success>(event)
            assertEquals(samplePlaylist, success.playlist)
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals(listOf(samplePlaylist), repository.imported)
    }

    @Test
    fun `import emits InvalidPayload event when payload is malformed`() = runTest(dispatcher) {
        val uri = writePayloadToCacheUri("garbage.ytplaylist.json", "not json at all")
        val repository = RecordingPlaylistRepository()
        val importer = PlaylistImporter(
            ApplicationProvider.getApplicationContext(),
            KotlinxPlaylistCodec(),
            repository,
        )
        val viewModel = SharingViewModel(importer)

        viewModel.events.test {
            viewModel.import(uri)
            advanceUntilIdle()

            assertEquals(PlaylistImportResult.InvalidPayload, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals(emptyList(), repository.imported)
    }

    @Test
    fun `import emits Unreadable event when uri cannot be opened`() = runTest(dispatcher) {
        val repository = RecordingPlaylistRepository()
        val importer = PlaylistImporter(
            ApplicationProvider.getApplicationContext(),
            KotlinxPlaylistCodec(),
            repository,
        )
        val viewModel = SharingViewModel(importer)
        val missing = Uri.parse("file:///does/not/exist.ytplaylist.json")

        viewModel.events.test {
            viewModel.import(missing)
            advanceUntilIdle()

            assertEquals(PlaylistImportResult.Unreadable, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals(emptyList(), repository.imported)
    }

    @Test
    fun `each import call emits its own event in order`() = runTest(dispatcher) {
        val codec = KotlinxPlaylistCodec()
        val goodUri = writePayloadToCacheUri("good.ytplaylist.json", codec.export(samplePlaylist))
        val badUri = writePayloadToCacheUri("bad.ytplaylist.json", "nope")
        val importer = PlaylistImporter(
            ApplicationProvider.getApplicationContext(),
            codec,
            RecordingPlaylistRepository(),
        )
        val viewModel = SharingViewModel(importer)

        viewModel.events.test {
            viewModel.import(goodUri)
            advanceUntilIdle()
            assertIs<PlaylistImportResult.Success>(awaitItem())

            viewModel.import(badUri)
            advanceUntilIdle()
            assertEquals(PlaylistImportResult.InvalidPayload, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun writePayloadToCacheUri(fileName: String, payload: String): Uri {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = java.io.File(context.cacheDir, fileName).apply { writeText(payload) }
        return Uri.fromFile(file)
    }

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
