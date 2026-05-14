package com.yourtube.feature.library

import com.yourtube.core.common.model.Playlist
import com.yourtube.core.common.model.Track
import com.yourtube.core.data.model.PlaybackHistoryEntry
import com.yourtube.core.data.repository.AddTrackResult
import com.yourtube.core.data.repository.PlaylistRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * YT-0063a M1 — instrumented-test fake. Backs Library and PlaylistDetail
 * Compose semantics tests with a controllable in-memory playlist set.
 *
 * Lives in `androidTest/` so the test runner can construct it directly when
 * wiring real ViewModels into the screens under test without standing up Hilt
 * for these UI-level assertions.
 */
internal class FakePlaylistRepository(
    initialPlaylists: List<Playlist> = emptyList(),
) : PlaylistRepository {

    private val playlists = MutableStateFlow(initialPlaylists)

    override fun observePlaylists(): Flow<List<Playlist>> = playlists

    override fun observePlaylist(playlistId: String): Flow<Playlist?> =
        playlists.map { list -> list.firstOrNull { it.id == playlistId } }

    override fun observeHistory(): Flow<List<PlaybackHistoryEntry>> =
        MutableStateFlow(emptyList())

    override suspend fun createPlaylist(
        name: String,
        tracks: List<Track>,
        playlistId: String?,
    ): Playlist {
        val id = playlistId ?: "p${playlists.value.size + 1}"
        val created = Playlist(id, name, "now", "now", tracks)
        playlists.value = playlists.value + created
        return created
    }

    override suspend fun renamePlaylist(playlistId: String, newName: String) {
        playlists.value = playlists.value.map {
            if (it.id == playlistId) it.copy(name = newName) else it
        }
    }

    override suspend fun deletePlaylist(playlistId: String) {
        playlists.value = playlists.value.filterNot { it.id == playlistId }
    }

    override suspend fun addTrackToPlaylist(
        playlistId: String,
        track: Track,
    ): AddTrackResult {
        playlists.value = playlists.value.map { p ->
            if (p.id == playlistId) p.copy(tracks = p.tracks + track) else p
        }
        return AddTrackResult.Added
    }

    override suspend fun removeTrackFromPlaylist(playlistId: String, position: Int) {
        playlists.value = playlists.value.map { p ->
            if (p.id == playlistId && position in p.tracks.indices) {
                p.copy(tracks = p.tracks.toMutableList().apply { removeAt(position) })
            } else {
                p
            }
        }
    }

    override suspend fun removeTrackFromPlaylist(
        playlistId: String,
        trackVideoId: String,
    ) {
        playlists.value = playlists.value.map { p ->
            if (p.id == playlistId) {
                p.copy(tracks = p.tracks.filterNot { it.videoId == trackVideoId })
            } else {
                p
            }
        }
    }

    override suspend fun reorderTracks(
        playlistId: String,
        fromIndex: Int,
        toIndex: Int,
    ) {
        playlists.value = playlists.value.map { p ->
            if (p.id == playlistId &&
                fromIndex in p.tracks.indices &&
                toIndex in p.tracks.indices
            ) {
                val mutable = p.tracks.toMutableList()
                val moved = mutable.removeAt(fromIndex)
                mutable.add(toIndex, moved)
                p.copy(tracks = mutable)
            } else {
                p
            }
        }
    }

    override suspend fun importPlaylist(playlist: Playlist) {
        playlists.value = playlists.value + playlist
    }

    override suspend fun recordPlayback(
        track: Track,
        playedAt: String?,
    ): PlaybackHistoryEntry = error("not used in UI tests")

    override suspend fun clearHistory() = Unit
    override suspend fun removeHistoryEntry(entryId: String) = Unit
}
