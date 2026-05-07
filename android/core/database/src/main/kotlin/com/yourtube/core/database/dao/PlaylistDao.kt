package com.yourtube.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.yourtube.core.database.entity.PlaylistEntity
import com.yourtube.core.database.entity.PlaylistTrackEntity
import com.yourtube.core.database.entity.TrackEntity
import com.yourtube.core.database.model.PlaylistSnapshotRow
import com.yourtube.core.database.model.PlaylistTrackRow
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {
    @Query(
        """
        SELECT
            p.id AS playlistId,
            p.name AS playlistName,
            p.createdAt AS playlistCreatedAt,
            p.updatedAt AS playlistUpdatedAt,
            pt.position AS position,
            t.videoId AS videoId,
            t.title AS title,
            t.channel AS channel,
            t.durationSec AS durationSec,
            t.thumbnailUrl AS thumbnailUrl
        FROM playlists p
        LEFT JOIN playlist_tracks pt ON pt.playlistId = p.id
        LEFT JOIN tracks t ON t.videoId = pt.trackVideoId
        ORDER BY julianday(p.updatedAt) DESC, julianday(p.createdAt) DESC, p.id, pt.position
        """,
    )
    fun observePlaylistSnapshots(): Flow<List<PlaylistSnapshotRow>>

    @Query(
        """
        SELECT
            p.id AS playlistId,
            p.name AS playlistName,
            p.createdAt AS playlistCreatedAt,
            p.updatedAt AS playlistUpdatedAt,
            pt.position AS position,
            t.videoId AS videoId,
            t.title AS title,
            t.channel AS channel,
            t.durationSec AS durationSec,
            t.thumbnailUrl AS thumbnailUrl
        FROM playlists p
        LEFT JOIN playlist_tracks pt ON pt.playlistId = p.id
        LEFT JOIN tracks t ON t.videoId = pt.trackVideoId
        WHERE p.id = :playlistId
        ORDER BY pt.position
        """,
    )
    fun observePlaylistSnapshot(playlistId: String): Flow<List<PlaylistSnapshotRow>>

    @Query("SELECT * FROM playlists WHERE id = :playlistId")
    suspend fun getPlaylist(playlistId: String): PlaylistEntity?

    @Query(
        """
        SELECT
            pt.playlistId AS playlistId,
            pt.position AS position,
            t.videoId AS videoId,
            t.title AS title,
            t.channel AS channel,
            t.durationSec AS durationSec,
            t.thumbnailUrl AS thumbnailUrl
        FROM playlist_tracks pt
        INNER JOIN tracks t ON t.videoId = pt.trackVideoId
        ORDER BY pt.playlistId, pt.position
        """,
    )
    fun observePlaylistTrackRows(): Flow<List<PlaylistTrackRow>>

    @Query(
        """
        SELECT
            pt.playlistId AS playlistId,
            pt.position AS position,
            t.videoId AS videoId,
            t.title AS title,
            t.channel AS channel,
            t.durationSec AS durationSec,
            t.thumbnailUrl AS thumbnailUrl
        FROM playlist_tracks pt
        INNER JOIN tracks t ON t.videoId = pt.trackVideoId
        WHERE pt.playlistId = :playlistId
        ORDER BY pt.position
        """,
    )
    fun observePlaylistTrackRows(playlistId: String): Flow<List<PlaylistTrackRow>>

    @Query(
        """
        SELECT
            pt.playlistId AS playlistId,
            pt.position AS position,
            t.videoId AS videoId,
            t.title AS title,
            t.channel AS channel,
            t.durationSec AS durationSec,
            t.thumbnailUrl AS thumbnailUrl
        FROM playlist_tracks pt
        INNER JOIN tracks t ON t.videoId = pt.trackVideoId
        WHERE pt.playlistId = :playlistId
        ORDER BY pt.position
        """,
    )
    suspend fun getPlaylistTrackRows(playlistId: String): List<PlaylistTrackRow>

    @Upsert
    suspend fun upsertPlaylist(playlist: PlaylistEntity)

    @Upsert
    suspend fun upsertTracks(tracks: List<TrackEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlaylistTracks(tracks: List<PlaylistTrackEntity>)

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId")
    suspend fun deletePlaylistTracks(playlistId: String)

    @Query("DELETE FROM playlists WHERE id = :playlistId")
    suspend fun deletePlaylist(playlistId: String)

    @Transaction
    suspend fun upsertPlaylistSnapshot(
        playlist: PlaylistEntity,
        tracks: List<TrackEntity>,
    ) {
        upsertPlaylist(playlist)
        replacePlaylistTracks(playlist.id, tracks)
    }

    @Transaction
    suspend fun replacePlaylistTracks(
        playlistId: String,
        tracks: List<TrackEntity>,
    ) {
        upsertTracks(tracks)
        deletePlaylistTracks(playlistId)
        upsertPlaylistTracks(
            tracks.mapIndexed { index, track ->
                PlaylistTrackEntity(
                    playlistId = playlistId,
                    trackVideoId = track.videoId,
                    position = index,
                )
            },
        )
    }
}
