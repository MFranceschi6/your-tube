package com.yourtube.core.data.repository

import com.yourtube.core.common.model.Playlist
import com.yourtube.core.common.model.Track
import com.yourtube.core.data.model.PlaybackHistoryEntry
import com.yourtube.core.database.dao.HistoryDao
import com.yourtube.core.database.dao.PlaylistDao
import com.yourtube.core.database.entity.HistoryEntryEntity
import com.yourtube.core.database.entity.PlaylistEntity
import com.yourtube.core.database.entity.TrackEntity
import com.yourtube.core.database.model.HistoryTrackRow
import com.yourtube.core.database.model.PlaylistSnapshotRow
import com.yourtube.core.database.model.PlaylistTrackRow
import java.time.Clock
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class OfflineFirstPlaylistRepository(
    private val playlistDao: PlaylistDao,
    private val historyDao: HistoryDao,
    private val clock: Clock = Clock.systemUTC(),
) : PlaylistRepository {

    override fun observePlaylists(): Flow<List<Playlist>> =
        playlistDao.observePlaylistSnapshots().map { rows ->
            rows.toDomainPlaylists()
        }

    override fun observePlaylist(playlistId: String): Flow<Playlist?> =
        playlistDao.observePlaylistSnapshot(playlistId).map { rows ->
            rows.firstOrNull()?.let { rows.toDomainPlaylist() }
        }

    override fun observeHistory(): Flow<List<PlaybackHistoryEntry>> =
        historyDao.observeHistory().map { rows ->
            rows.map { row -> row.toDomain() }
        }

    override suspend fun createPlaylist(
        name: String,
        tracks: List<Track>,
        playlistId: String?,
    ): Playlist {
        val now = clock.instant().toString()
        val playlist = Playlist(
            id = playlistId ?: UUID.randomUUID().toString(),
            name = name,
            createdAt = now,
            updatedAt = now,
            tracks = tracks,
        )
        upsertPlaylistSnapshot(playlist)
        return playlist
    }

    override suspend fun renamePlaylist(playlistId: String, newName: String) {
        val existing = requirePlaylist(playlistId)
        playlistDao.upsertPlaylist(
            existing.copy(
                name = newName,
                updatedAt = clock.instant().toString(),
            ),
        )
    }

    override suspend fun deletePlaylist(playlistId: String) {
        playlistDao.deletePlaylist(playlistId)
    }

    override suspend fun addTrackToPlaylist(playlistId: String, track: Track) {
        val existing = requirePlaylist(playlistId)
        val updatedTracks = playlistDao.getPlaylistTrackRows(playlistId).toDomainTracks() + track
        upsertPlaylistSnapshot(existing.toUpdatedPlaylist(updatedTracks))
    }

    override suspend fun removeTrackFromPlaylist(playlistId: String, position: Int) {
        val existing = requirePlaylist(playlistId)
        val updatedTracks = playlistDao.getPlaylistTrackRows(playlistId)
            .toDomainTracks()
            .toMutableList()
            .apply { removeAt(position) }

        upsertPlaylistSnapshot(existing.toUpdatedPlaylist(updatedTracks))
    }

    override suspend fun reorderTracks(playlistId: String, fromIndex: Int, toIndex: Int) {
        val existing = requirePlaylist(playlistId)
        val updatedTracks = playlistDao.getPlaylistTrackRows(playlistId)
            .toDomainTracks()
            .toMutableList()

        val moved = updatedTracks.removeAt(fromIndex)
        updatedTracks.add(toIndex, moved)

        upsertPlaylistSnapshot(existing.toUpdatedPlaylist(updatedTracks))
    }

    override suspend fun importPlaylist(playlist: Playlist) {
        val existing = playlistDao.getPlaylist(playlist.id)
        if (existing != null && Instant.parse(existing.updatedAt) > Instant.parse(playlist.updatedAt)) {
            return
        }
        upsertPlaylistSnapshot(playlist)
    }

    override suspend fun recordPlayback(track: Track, playedAt: String?): PlaybackHistoryEntry {
        val playedTimestamp = playedAt ?: clock.instant().toString()
        val entry = PlaybackHistoryEntry(
            id = UUID.randomUUID().toString(),
            track = track,
            playedAt = playedTimestamp,
        )

        historyDao.recordPlayback(
            track = track.toEntity(),
            entry = HistoryEntryEntity(
                id = entry.id,
                trackVideoId = track.videoId,
                playedAt = playedTimestamp,
            ),
        )

        return entry
    }

    override suspend fun clearHistory() {
        historyDao.clearHistory()
    }

    override suspend fun removeHistoryEntry(entryId: String) {
        historyDao.deleteHistoryEntry(entryId)
    }

    private suspend fun requirePlaylist(playlistId: String): PlaylistEntity =
        requireNotNull(playlistDao.getPlaylist(playlistId)) {
            "Playlist $playlistId does not exist."
        }

    private suspend fun upsertPlaylistSnapshot(playlist: Playlist) {
        val entity =
            PlaylistEntity(
                id = playlist.id,
                name = playlist.name,
                createdAt = playlist.createdAt,
                updatedAt = playlist.updatedAt,
            )
        val trackEntities = playlist.tracks.map { track -> track.toEntity() }
        playlistDao.upsertPlaylistSnapshot(
            playlist = entity,
            tracks = trackEntities,
        )
    }

    private fun PlaylistEntity.toUpdatedPlaylist(updatedTracks: List<Track>): Playlist =
        Playlist(
            id = id,
            name = name,
            createdAt = createdAt,
            updatedAt = clock.instant().toString(),
            tracks = updatedTracks,
        )

    private fun List<PlaylistSnapshotRow>.toDomainPlaylists(): List<Playlist> =
        groupBy(PlaylistSnapshotRow::playlistId).values.map { rows ->
            rows.toDomainPlaylist()
        }

    private fun List<PlaylistSnapshotRow>.toDomainPlaylist(): Playlist {
        val firstRow = first()
        return Playlist(
            id = firstRow.playlistId,
            name = firstRow.playlistName,
            createdAt = firstRow.playlistCreatedAt,
            updatedAt = firstRow.playlistUpdatedAt,
            tracks = mapNotNull { row -> row.toDomainTrack() },
        )
    }

    private fun PlaylistSnapshotRow.toDomainTrack(): Track? {
        val trackId = videoId ?: return null
        return Track(
            videoId = trackId,
            title = checkNotNull(title),
            channel = checkNotNull(channel),
            durationSec = checkNotNull(durationSec),
            thumbnailUrl = checkNotNull(thumbnailUrl),
        )
    }

    private fun List<PlaylistTrackRow>.toDomainTracks(): List<Track> =
        sortedBy(PlaylistTrackRow::position).map { row ->
            Track(
                videoId = row.videoId,
                title = row.title,
                channel = row.channel,
                durationSec = row.durationSec,
                thumbnailUrl = row.thumbnailUrl,
            )
        }

    private fun HistoryTrackRow.toDomain(): PlaybackHistoryEntry =
        PlaybackHistoryEntry(
            id = entryId,
            track = Track(
                videoId = videoId,
                title = title,
                channel = channel,
                durationSec = durationSec,
                thumbnailUrl = thumbnailUrl,
            ),
            playedAt = playedAt,
        )

    private fun Track.toEntity(): TrackEntity =
        TrackEntity(
            videoId = videoId,
            title = title,
            channel = channel,
            durationSec = durationSec,
            thumbnailUrl = thumbnailUrl,
        )
}
