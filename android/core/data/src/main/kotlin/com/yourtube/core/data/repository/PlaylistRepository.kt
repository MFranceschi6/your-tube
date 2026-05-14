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
    suspend fun addTrackToPlaylist(playlistId: String, track: Track): AddTrackResult
    suspend fun removeTrackFromPlaylist(playlistId: String, position: Int)

    /**
     * YT-0184 — content-keyed removal. Stable under concurrent swipe-to-dismiss
     * gestures because callers do not need to reconcile shifting indices when
     * two rows are swiped in parallel. Implementations must be safe to invoke
     * from independent coroutines and must no-op (rather than throw) when the
     * track is no longer present.
     *
     * Default delegates to nothing so existing test fakes keep compiling; the
     * production [OfflineFirstPlaylistRepository] overrides it.
     *
     * After the YT-0264 migration each `(playlistId, trackVideoId)` pair is
     * guaranteed unique, so this method removes exactly one row — or is a
     * no-op if the track is not present in the playlist.
     */
    suspend fun removeTrackFromPlaylist(playlistId: String, trackVideoId: String) = Unit

    suspend fun reorderTracks(playlistId: String, fromIndex: Int, toIndex: Int)
    suspend fun importPlaylist(playlist: Playlist)
    suspend fun recordPlayback(track: Track, playedAt: String? = null): PlaybackHistoryEntry
    suspend fun clearHistory()
    suspend fun removeHistoryEntry(entryId: String)
}
