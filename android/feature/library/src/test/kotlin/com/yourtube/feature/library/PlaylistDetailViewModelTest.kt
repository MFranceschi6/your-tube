package com.yourtube.feature.library

import androidx.lifecycle.SavedStateHandle
import com.yourtube.core.common.model.Playlist
import com.yourtube.core.common.model.Track
import com.yourtube.core.data.codec.PlaylistCodec
import com.yourtube.core.data.model.PlaybackHistoryEntry
import com.yourtube.core.data.repository.PlaylistRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before

@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistDetailViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // YT-0164 catalog C9 → C10/C11/C12 mapping: initial null is Loading; first
    // emission with empty tracks → Empty(playlist); first emission with tracks
    // → Content(playlist); a flow exception → Error.
    @Test
    fun `state stays Loading while observePlaylist primes with null`() = runTest(dispatcher) {
        val repo = StaticPlaylistRepository(playlistFlow = flowOf(null))
        val viewModel = newViewModel(repo)
        advanceUntilIdle()
        assertIs<PlaylistDetailUiState.Loading>(viewModel.state.value)
    }

    @Test
    fun `state is Empty when first emission has zero tracks`() = runTest(dispatcher) {
        val empty = Playlist(
            id = "p", name = "P", createdAt = "now", updatedAt = "now",
            tracks = emptyList(),
        )
        val repo = StaticPlaylistRepository(playlistFlow = flowOf(empty))
        val viewModel = newViewModel(repo)
        advanceUntilIdle()
        val s = viewModel.state.value
        assertIs<PlaylistDetailUiState.Empty>(s)
        assertEquals(empty, s.playlist)
    }

    @Test
    fun `state is Content when first emission has tracks`() = runTest(dispatcher) {
        val full = Playlist(
            id = "p", name = "P", createdAt = "now", updatedAt = "now",
            tracks = listOf(trackOne),
        )
        val repo = StaticPlaylistRepository(playlistFlow = flowOf(full))
        val viewModel = newViewModel(repo)
        advanceUntilIdle()
        val s = viewModel.state.value
        assertIs<PlaylistDetailUiState.Content>(s)
        assertEquals(full, s.playlist)
    }

    @Test
    fun `state is Error when a non-null emission is followed by null`() = runTest(dispatcher) {
        val full = Playlist(
            id = "p", name = "P", createdAt = "now", updatedAt = "now",
            tracks = listOf(trackOne),
        )
        // Simulate the row vanishing after we'd seen it (e.g. deleted from
        // another screen). Catalog rule: this surfaces C11 Error.
        val repo = StaticPlaylistRepository(playlistFlow = flowOf<Playlist?>(full, null))
        val viewModel = newViewModel(repo)
        advanceUntilIdle()
        assertIs<PlaylistDetailUiState.Error>(viewModel.state.value)
    }

    @Test
    fun `state is Error when the playlist flow throws`() = runTest(dispatcher) {
        val repo = StaticPlaylistRepository(playlistFlow = flow { throw RuntimeException("db boom") })
        val viewModel = newViewModel(repo)
        advanceUntilIdle()
        assertIs<PlaylistDetailUiState.Error>(viewModel.state.value)
    }

    private fun newViewModel(repository: PlaylistRepository): PlaylistDetailViewModel =
        PlaylistDetailViewModel(
            savedStateHandle = SavedStateHandle(mapOf("playlistId" to "p")),
            repository = repository,
            codec = NoopPlaylistCodec(),
        )

    /**
     * Minimal repository fake for state-mapping tests: only exposes the
     * `observePlaylist` flow under test. Other operations error so accidental
     * dependencies show up loudly in the test name.
     */
    private class StaticPlaylistRepository(
        private val playlistFlow: Flow<Playlist?>,
    ) : PlaylistRepository {
        override fun observePlaylists(): Flow<List<Playlist>> = MutableStateFlow(emptyList())
        override fun observePlaylist(playlistId: String): Flow<Playlist?> = playlistFlow
        override fun observeHistory(): Flow<List<PlaybackHistoryEntry>> = MutableStateFlow(emptyList())
        override suspend fun createPlaylist(
            name: String,
            tracks: List<Track>,
            playlistId: String?,
        ): Playlist = error("unused")
        override suspend fun renamePlaylist(playlistId: String, newName: String) = Unit
        override suspend fun deletePlaylist(playlistId: String) = Unit
        override suspend fun addTrackToPlaylist(playlistId: String, track: Track) = Unit
        override suspend fun removeTrackFromPlaylist(playlistId: String, position: Int) = Unit
        override suspend fun removeTrackFromPlaylist(playlistId: String, trackVideoId: String) = Unit
        override suspend fun reorderTracks(playlistId: String, fromIndex: Int, toIndex: Int) = Unit
        override suspend fun importPlaylist(playlist: Playlist) = Unit
        override suspend fun recordPlayback(track: Track, playedAt: String?): PlaybackHistoryEntry =
            error("unused")
        override suspend fun clearHistory() = Unit
        override suspend fun removeHistoryEntry(entryId: String) = Unit
    }

    // YT-0184: dispatching two `removeTrack(track)` calls from independent coroutines
    // must serialize through the repository and leave a consistent final state with
    // both tracks gone. The fake mirrors the production mutex.
    @Test
    fun `interleaved videoId removals leave both tracks removed`() = runTest(dispatcher) {
        val tracks = listOf(trackOne, trackTwo, trackThree)
        val playlist = Playlist(
            id = "p",
            name = "P",
            createdAt = "now",
            updatedAt = "now",
            tracks = tracks,
        )
        val repository = SerializingFakePlaylistRepository(playlist)
        val viewModel = PlaylistDetailViewModel(
            savedStateHandle = SavedStateHandle(mapOf("playlistId" to "p")),
            repository = repository,
            codec = NoopPlaylistCodec(),
        )

        // Two independent coroutines fire near-simultaneously, mimicking two-finger
        // swipe-to-dismiss on rows 0 and 2.
        launch { viewModel.removeTrack(trackOne) }
        launch { viewModel.removeTrack(trackThree) }
        advanceUntilIdle()

        val final = repository.observePlaylist("p").first()
        requireNotNull(final)
        assertEquals(listOf(trackTwo), final.tracks)
    }

    private class SerializingFakePlaylistRepository(
        initial: Playlist,
    ) : PlaylistRepository {
        private val state = MutableStateFlow(listOf(initial))
        private val mutex = Mutex()

        override fun observePlaylists(): Flow<List<Playlist>> = state
        override fun observePlaylist(playlistId: String): Flow<Playlist?> =
            state.map { list -> list.firstOrNull { it.id == playlistId } }
        override fun observeHistory(): Flow<List<PlaybackHistoryEntry>> =
            MutableStateFlow(emptyList())
        override suspend fun createPlaylist(
            name: String,
            tracks: List<Track>,
            playlistId: String?,
        ): Playlist = error("unused")
        override suspend fun renamePlaylist(playlistId: String, newName: String) = Unit
        override suspend fun deletePlaylist(playlistId: String) = Unit
        override suspend fun addTrackToPlaylist(playlistId: String, track: Track) = Unit
        override suspend fun removeTrackFromPlaylist(playlistId: String, position: Int) = Unit
        override suspend fun removeTrackFromPlaylist(playlistId: String, trackVideoId: String) {
            mutex.withLock {
                state.value = state.value.map { p ->
                    if (p.id == playlistId) p.copy(tracks = p.tracks.filterNot { it.videoId == trackVideoId }) else p
                }
            }
        }
        override suspend fun reorderTracks(playlistId: String, fromIndex: Int, toIndex: Int) = Unit
        override suspend fun importPlaylist(playlist: Playlist) = Unit
        override suspend fun recordPlayback(track: Track, playedAt: String?): PlaybackHistoryEntry =
            error("unused")
        override suspend fun clearHistory() = Unit
        override suspend fun removeHistoryEntry(entryId: String) = Unit
    }

    private class NoopPlaylistCodec : PlaylistCodec {
        override fun export(playlist: Playlist): String = ""
        override fun `import`(payload: String): Playlist = error("unused")
    }

    companion object {
        private val trackOne = Track("v1", "One", "C", 60, "")
        private val trackTwo = Track("v2", "Two", "C", 60, "")
        private val trackThree = Track("v3", "Three", "C", 60, "")
    }
}
