package com.yourtube.core.data.repository

import com.yourtube.core.common.model.Playlist
import com.yourtube.core.common.model.Track
import com.yourtube.core.data.model.PlaybackHistoryEntry
import kotlinx.coroutines.flow.Flow

interface PlaylistRepository {
    fun observePlaylists(): Flow<List<Playlist>>
    fun observePlaylist(playlistId: String): Flow<Playlist?>
    fun observeHistory(): Flow<List<PlaybackHistoryEntry>>

    suspend fun createPlaylist(
        name: String,
        tracks: List<Track> = emptyList(),
        playlistId: String? = null,
    ): Playlist

    suspend fun renamePlaylist(playlistId: String, newName: String)
    suspend fun deletePlaylist(playlistId: String)
    suspend fun addTrackToPlaylist(playlistId: String, track: Track)
    suspend fun removeTrackFromPlaylist(playlistId: String, position: Int)
    suspend fun reorderTracks(playlistId: String, fromIndex: Int, toIndex: Int)
    suspend fun importPlaylist(playlist: Playlist)
    suspend fun recordPlayback(track: Track, playedAt: String? = null): PlaybackHistoryEntry
    suspend fun clearHistory()
    suspend fun removeHistoryEntry(entryId: String)
}
