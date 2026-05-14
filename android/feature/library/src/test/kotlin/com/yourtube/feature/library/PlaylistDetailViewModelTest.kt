package com.yourtube.feature.library

import androidx.lifecycle.SavedStateHandle
import com.yourtube.core.common.model.Playlist
import com.yourtube.core.common.model.Track
import com.yourtube.core.data.codec.PlaylistCodec
import com.yourtube.core.data.model.PlaybackHistoryEntry
import com.yourtube.core.data.repository.AddTrackResult
import com.yourtube.core.data.repository.PlaylistRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
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
import kotlinx.coroutines.test.advanceTimeBy
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
        override suspend fun addTrackToPlaylist(playlistId: String, track: Track): AddTrackResult = AddTrackResult.Added
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
        override suspend fun addTrackToPlaylist(playlistId: String, track: Track): AddTrackResult = AddTrackResult.Added
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

    // ── YT-0063a v2 Q3 — reorder tests ───────────────────────────────────────

    @Test
    fun `reorderTracks calls repository with correct from and to args`() = runTest(dispatcher) {
        val playlist = Playlist("p", "P", "now", "now", listOf(trackOne, trackTwo, trackThree))
        val repo = RecordingRepository(playlist)
        val viewModel = PlaylistDetailViewModel(
            savedStateHandle = SavedStateHandle(mapOf("playlistId" to "p")),
            repository = repo,
            codec = NoopPlaylistCodec(),
        )
        viewModel.reorderTracks(0, 2)
        advanceUntilIdle()
        assertEquals(listOf(Triple("p", 0, 2)), repo.reorderCalls)
    }

    @Test
    fun `queueRemoval records oldPosition and undoRemoval cancels the pending delete`() =
        runTest(dispatcher) {
            val playlist = Playlist("p", "P", "now", "now", listOf(trackOne, trackTwo, trackThree))
            val repo = RecordingRepository(playlist)
            val viewModel = PlaylistDetailViewModel(
                savedStateHandle = SavedStateHandle(mapOf("playlistId" to "p")),
                repository = repo,
                codec = NoopPlaylistCodec(),
            )

            // Queue a removal at position 1 (trackTwo)
            viewModel.queueRemoval(trackTwo, oldPosition = 1, commitDelayMs = 5_000L)

            // Verify pending removal is recorded with the right oldPosition (state updates synchronously)
            val pending = viewModel.pendingRemovals.value[trackTwo.videoId]
            assertNotNull(pending)
            assertEquals(1, pending.oldPosition)
            assertEquals(trackTwo.videoId, pending.trackId)

            // Undo: the commit job should be cancelled and the track NOT deleted
            viewModel.undoRemoval(trackTwo.videoId)
            advanceUntilIdle()

            assertNull(viewModel.pendingRemovals.value[trackTwo.videoId])
            // Repository should NOT have been called for removal since we undid it
            assertFalse(repo.removedVideoIds.contains(trackTwo.videoId))
        }

    @Test
    fun `snackbar dismissal commits Room delete after delay`() = runTest(dispatcher) {
        val playlist = Playlist("p", "P", "now", "now", listOf(trackOne, trackTwo, trackThree))
        val repo = RecordingRepository(playlist)
        val viewModel = PlaylistDetailViewModel(
            savedStateHandle = SavedStateHandle(mapOf("playlistId" to "p")),
            repository = repo,
            codec = NoopPlaylistCodec(),
        )

        // Simulate a 4 s snackbar window with no Undo tap
        viewModel.queueRemoval(trackOne, oldPosition = 0, commitDelayMs = 4_000L)

        // Before delay: not yet committed
        advanceTimeBy(3_999L)
        assertFalse(repo.removedVideoIds.contains(trackOne.videoId))

        // After delay: committed
        advanceTimeBy(2L)
        advanceUntilIdle()
        assertTrue(repo.removedVideoIds.contains(trackOne.videoId))
        assertNull(viewModel.pendingRemovals.value[trackOne.videoId])
    }

    /**
     * Recording repository for v2 Q3 tests: tracks reorder calls and video-id removals.
     */
    private class RecordingRepository(initial: Playlist) : PlaylistRepository {
        private val state = MutableStateFlow(listOf(initial))
        val reorderCalls = mutableListOf<Triple<String, Int, Int>>()
        val removedVideoIds = mutableListOf<String>()

        override fun observePlaylists(): Flow<List<Playlist>> = state
        override fun observePlaylist(playlistId: String): Flow<Playlist?> =
            state.map { it.firstOrNull { p -> p.id == playlistId } }
        override fun observeHistory(): Flow<List<PlaybackHistoryEntry>> =
            MutableStateFlow(emptyList())
        override suspend fun createPlaylist(name: String, tracks: List<Track>, playlistId: String?): Playlist =
            error("unused")
        override suspend fun renamePlaylist(playlistId: String, newName: String) = Unit
        override suspend fun deletePlaylist(playlistId: String) = Unit
        override suspend fun addTrackToPlaylist(playlistId: String, track: Track): AddTrackResult = AddTrackResult.Added
        override suspend fun removeTrackFromPlaylist(playlistId: String, position: Int) = Unit
        override suspend fun removeTrackFromPlaylist(playlistId: String, trackVideoId: String) {
            removedVideoIds.add(trackVideoId)
            state.value = state.value.map { p ->
                if (p.id == playlistId) p.copy(tracks = p.tracks.filterNot { it.videoId == trackVideoId }) else p
            }
        }
        override suspend fun reorderTracks(playlistId: String, fromIndex: Int, toIndex: Int) {
            reorderCalls.add(Triple(playlistId, fromIndex, toIndex))
        }
        override suspend fun importPlaylist(playlist: Playlist) = Unit
        override suspend fun recordPlayback(track: Track, playedAt: String?): PlaybackHistoryEntry =
            error("unused")
        override suspend fun clearHistory() = Unit
        override suspend fun removeHistoryEntry(entryId: String) = Unit
    }

    // ── M2 — batched-undo snackbar ────────────────────────────────────────────

    @Test
    fun `two removals both commit when not undone`() = runTest(dispatcher) {
        val playlist = Playlist("p", "P", "now", "now", listOf(trackOne, trackTwo, trackThree))
        val repo = RecordingRepository(playlist)
        val viewModel = PlaylistDetailViewModel(
            savedStateHandle = SavedStateHandle(mapOf("playlistId" to "p")),
            repository = repo,
            codec = NoopPlaylistCodec(),
        )

        viewModel.queueRemoval(trackOne, oldPosition = 0, commitDelayMs = 4_000L)
        viewModel.queueRemoval(trackTwo, oldPosition = 1, commitDelayMs = 4_000L)

        // Neither committed before delay
        advanceTimeBy(3_999L)
        assertFalse(repo.removedVideoIds.contains(trackOne.videoId))
        assertFalse(repo.removedVideoIds.contains(trackTwo.videoId))

        // Both committed after delay
        advanceTimeBy(2L)
        advanceUntilIdle()
        assertTrue(repo.removedVideoIds.contains(trackOne.videoId))
        assertTrue(repo.removedVideoIds.contains(trackTwo.videoId))
    }

    @Test
    fun `two removals both restored when undoRemoval called for each`() = runTest(dispatcher) {
        val playlist = Playlist("p", "P", "now", "now", listOf(trackOne, trackTwo, trackThree))
        val repo = RecordingRepository(playlist)
        val viewModel = PlaylistDetailViewModel(
            savedStateHandle = SavedStateHandle(mapOf("playlistId" to "p")),
            repository = repo,
            codec = NoopPlaylistCodec(),
        )

        viewModel.queueRemoval(trackOne, oldPosition = 0, commitDelayMs = 4_000L)
        viewModel.queueRemoval(trackTwo, oldPosition = 1, commitDelayMs = 4_000L)

        viewModel.undoRemoval(trackOne.videoId)
        viewModel.undoRemoval(trackTwo.videoId)

        advanceUntilIdle()

        assertFalse(repo.removedVideoIds.contains(trackOne.videoId))
        assertFalse(repo.removedVideoIds.contains(trackTwo.videoId))
        assertNull(viewModel.pendingRemovals.value[trackOne.videoId])
        assertNull(viewModel.pendingRemovals.value[trackTwo.videoId])
    }

    // ── M1 VM part — commitAllPendingRemovals race ────────────────────────────

    @Test
    fun `commitAllPendingRemovals does not drop concurrently queued removal`() =
        runTest(dispatcher) {
            val playlist = Playlist("p", "P", "now", "now", listOf(trackOne, trackTwo, trackThree))
            val repo = RecordingRepository(playlist)
            val viewModel = PlaylistDetailViewModel(
                savedStateHandle = SavedStateHandle(mapOf("playlistId" to "p")),
                repository = repo,
                codec = NoopPlaylistCodec(),
            )

            // Queue A, then call commitAllPendingRemovals (snapshots {A}),
            // then queue B before the clear propagates.
            viewModel.queueRemoval(trackOne, oldPosition = 0, commitDelayMs = 4_000L)
            viewModel.commitAllPendingRemovals()
            viewModel.queueRemoval(trackTwo, oldPosition = 1, commitDelayMs = 4_000L)

            // Advance past B's commit delay so both are committed
            advanceTimeBy(4_001L)
            advanceUntilIdle()

            assertTrue(repo.removedVideoIds.contains(trackOne.videoId))
            assertTrue(repo.removedVideoIds.contains(trackTwo.videoId))
        }

    // ── NF2 — straddling-deadline batched-undo regression ────────────────────

    @Test
    fun `undo after straddled deadline restores both A and B`() = runTest(dispatcher) {
        val playlist = Playlist("p", "P", "now", "now", listOf(trackOne, trackTwo, trackThree))
        val repo = RecordingRepository(playlist)
        val viewModel = PlaylistDetailViewModel(
            savedStateHandle = SavedStateHandle(mapOf("playlistId" to "p")),
            repository = repo,
            codec = NoopPlaylistCodec(),
        )
        // Remove A at t=0 (deadline t=4s)
        viewModel.queueRemoval(trackOne, 0, commitDelayMs = 4_000L)
        // Advance 2s — A is still pending
        advanceTimeBy(2_000L)
        // Remove B at t=2s — re-arms A to t=6s, B also at t=6s
        viewModel.queueRemoval(trackTwo, 1, commitDelayMs = 4_000L)
        // Advance to t=5s — under old code A would have committed; under new code both still pending
        advanceTimeBy(3_000L)
        // Undo both
        viewModel.undoRemoval(trackOne.videoId)
        viewModel.undoRemoval(trackTwo.videoId)
        advanceUntilIdle()
        // Neither should have been deleted
        assertFalse(repo.removedVideoIds.contains(trackOne.videoId))
        assertFalse(repo.removedVideoIds.contains(trackTwo.videoId))
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
