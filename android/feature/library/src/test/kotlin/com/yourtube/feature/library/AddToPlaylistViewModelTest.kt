package com.yourtube.feature.library

import app.cash.turbine.test
import com.yourtube.core.common.model.Playlist
import com.yourtube.core.common.model.Track
import com.yourtube.core.data.model.PlaybackHistoryEntry
import com.yourtube.core.data.repository.AddTrackResult
import com.yourtube.core.data.repository.PlaylistRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class AddToPlaylistViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private val sampleTrack = Track(
        videoId = "video-1",
        title = "Sample Track",
        channel = "Sample Channel",
        durationSec = 200,
        thumbnailUrl = "",
    )

    @Test
    fun `addTrack increments target playlist track count and emits TrackAdded`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val repo = FakePlaylistRepository(
            initialPlaylists = listOf(
                Playlist(
                    id = "p1",
                    name = "Favorites",
                    createdAt = "2026-01-01T00:00:00Z",
                    updatedAt = "2026-01-01T00:00:00Z",
                    tracks = emptyList(),
                ),
            ),
        )
        try {
            val vm = AddToPlaylistViewModel(repo)
            vm.events.test {
                vm.addTrack("p1", sampleTrack)
                advanceUntilIdle()

                val updated = repo.observePlaylist("p1").first()
                assertEquals(1, updated?.tracks?.size)
                assertEquals(sampleTrack, updated?.tracks?.firstOrNull())
                assertEquals(AddToPlaylistEvent.TrackAdded, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `addTrack duplicate emits AlreadyInPlaylist and does not add`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val repo = FakePlaylistRepository(
            initialPlaylists = listOf(
                Playlist(
                    id = "p1",
                    name = "Favorites",
                    createdAt = "2026-01-01T00:00:00Z",
                    updatedAt = "2026-01-01T00:00:00Z",
                    tracks = listOf(sampleTrack),
                ),
            ),
        )
        try {
            val vm = AddToPlaylistViewModel(repo)
            vm.events.test {
                vm.addTrack("p1", sampleTrack)
                advanceUntilIdle()

                // Track count must remain at 1 (no duplicate inserted).
                val updated = repo.observePlaylist("p1").first()
                assertEquals(1, updated?.tracks?.size)
                assertEquals(AddToPlaylistEvent.AlreadyInPlaylist, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `createAndAdd creates playlist with the track`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val repo = FakePlaylistRepository()
        try {
            val vm = AddToPlaylistViewModel(repo)
            vm.createAndAdd("Drive Mix", sampleTrack)
            advanceUntilIdle()

            val all = repo.observePlaylists().first()
            assertEquals(1, all.size)
            assertEquals("Drive Mix", all.first().name)
            assertEquals(listOf(sampleTrack), all.first().tracks)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `createAndAdd ignores blank names`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val repo = FakePlaylistRepository()
        try {
            val vm = AddToPlaylistViewModel(repo)
            vm.createAndAdd("   ", sampleTrack)
            advanceUntilIdle()
            assertEquals(emptyList(), repo.observePlaylists().first())
        } finally {
            Dispatchers.resetMain()
        }
    }

    /**
     * Hand-written fake — favours simplicity over the full Room+Robolectric stack used in
     * `core:data` repository tests. Captures only the surface area exercised by
     * [AddToPlaylistViewModel].
     *
     * Returns [AddTrackResult.AlreadyPresent] when the track [videoId] is already present in the
     * playlist (mirrors the production check in [OfflineFirstPlaylistRepository]).
     */
    private class FakePlaylistRepository(
        initialPlaylists: List<Playlist> = emptyList(),
    ) : PlaylistRepository {
        private val state = MutableStateFlow(initialPlaylists)

        override fun observePlaylists(): Flow<List<Playlist>> = state

        override fun observePlaylist(playlistId: String): Flow<Playlist?> =
            state.map { list -> list.firstOrNull { it.id == playlistId } }

        override fun observeHistory(): Flow<List<PlaybackHistoryEntry>> =
            MutableStateFlow(emptyList())

        override suspend fun createPlaylist(
            name: String,
            tracks: List<Track>,
            playlistId: String?,
        ): Playlist {
            val playlist = Playlist(
                id = playlistId ?: "id-${state.value.size + 1}",
                name = name,
                createdAt = "now",
                updatedAt = "now",
                tracks = tracks,
            )
            state.value = state.value + playlist
            return playlist
        }

        override suspend fun renamePlaylist(playlistId: String, newName: String) {
            state.value = state.value.map { p ->
                if (p.id == playlistId) p.copy(name = newName) else p
            }
        }

        override suspend fun deletePlaylist(playlistId: String) {
            state.value = state.value.filterNot { it.id == playlistId }
        }

        override suspend fun addTrackToPlaylist(playlistId: String, track: Track): AddTrackResult {
            val playlist = state.value.firstOrNull { it.id == playlistId }
                ?: return AddTrackResult.Added
            if (playlist.tracks.any { it.videoId == track.videoId }) {
                return AddTrackResult.AlreadyPresent
            }
            state.value = state.value.map { p ->
                if (p.id == playlistId) p.copy(tracks = p.tracks + track) else p
            }
            return AddTrackResult.Added
        }

        override suspend fun removeTrackFromPlaylist(playlistId: String, position: Int) {
            state.value = state.value.map { p ->
                if (p.id == playlistId) {
                    p.copy(tracks = p.tracks.toMutableList().apply { removeAt(position) })
                } else p
            }
        }

        override suspend fun reorderTracks(playlistId: String, fromIndex: Int, toIndex: Int) {
            state.value = state.value.map { p ->
                if (p.id == playlistId) {
                    val mutable = p.tracks.toMutableList()
                    val moved = mutable.removeAt(fromIndex)
                    mutable.add(toIndex, moved)
                    p.copy(tracks = mutable)
                } else p
            }
        }

        override suspend fun importPlaylist(playlist: Playlist) {
            state.value = state.value.filterNot { it.id == playlist.id } + playlist
        }

        override suspend fun recordPlayback(track: Track, playedAt: String?) =
            PlaybackHistoryEntry(id = "h", track = track, playedAt = playedAt ?: "now")

        override suspend fun clearHistory() = Unit
        override suspend fun removeHistoryEntry(entryId: String) = Unit
    }
}
