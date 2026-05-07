package com.yourtube.core.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yourtube.core.common.model.Playlist
import com.yourtube.core.common.model.Track
import com.yourtube.core.database.PlaylistDatabase
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PlaylistRepositoryTest {

    private val initialInstant = Instant.parse("2026-05-02T10:15:30Z")
    private val updatedInstant = Instant.parse("2026-05-03T08:00:00Z")

    private val trackOne = Track(
        videoId = "video-1",
        title = "Track One",
        channel = "Channel One",
        durationSec = 180,
        thumbnailUrl = "https://example.com/1.jpg",
    )
    private val trackTwo = Track(
        videoId = "video-2",
        title = "Track Two",
        channel = "Channel Two",
        durationSec = 240,
        thumbnailUrl = "https://example.com/2.jpg",
    )

    private lateinit var database: PlaylistDatabase
    private lateinit var clock: MutableClock

    @BeforeTest
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            PlaylistDatabase::class.java,
        )
            .allowMainThreadQueries()
            .build()
        clock = MutableClock(initialInstant)
    }

    @AfterTest
    fun tearDown() {
        database.close()
    }

    @Test
    fun `playlist repository supports crud and reorder behavior`() = runTest {
        val repository = repositoryAt(initialInstant)
        val created = repository.createPlaylist(
            name = "Favorites",
            tracks = listOf(trackOne),
            playlistId = "playlist-1",
        )

        clock.current = updatedInstant
        repository.renamePlaylist(created.id, "Road Trip")
        repository.addTrackToPlaylist(created.id, trackTwo)
        assertEquals(
            listOf(trackOne, trackTwo),
            repository.observePlaylist(created.id).first()?.tracks,
        )
        repository.reorderTracks(created.id, fromIndex = 1, toIndex = 0)
        repository.removeTrackFromPlaylist(created.id, position = 1)

        val playlist = repository.observePlaylist(created.id).first()
        requireNotNull(playlist)

        assertEquals("Road Trip", playlist.name)
        assertEquals(listOf(trackTwo), playlist.tracks)
        assertEquals(updatedInstant.toString(), playlist.updatedAt)

        repository.deletePlaylist(created.id)
        assertNull(repository.observePlaylist(created.id).first())
    }

    @Test
    fun `playlist updates do not emit intermediate empty track lists`() = runTest {
        val repository = repositoryAt(initialInstant)
        val created = repository.createPlaylist(
            name = "Favorites",
            tracks = listOf(trackOne),
            playlistId = "playlist-atomic",
        )

        val emissions = mutableListOf<List<Track>>()
        val collectionJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            repository.observePlaylist(created.id)
                .filterNotNull()
                .map { playlist -> playlist.tracks }
                .take(2)
                .toList(emissions)
        }

        advanceUntilIdle()

        repository.addTrackToPlaylist(created.id, trackTwo)
        advanceUntilIdle()
        collectionJob.join()

        assertEquals(
            listOf(
                listOf(trackOne),
                listOf(trackOne, trackTwo),
            ),
            emissions,
        )
    }

    @Test
    fun `import keeps newer playlist on conflict and replaces older one`() = runTest {
        val repository = repositoryAt(initialInstant)
        repository.importPlaylist(
            Playlist(
                id = "playlist-42",
                name = "Original",
                createdAt = "2026-01-01T00:00:00Z",
                updatedAt = "2026-01-02T00:00:00Z",
                tracks = listOf(trackOne),
            ),
        )

        repository.importPlaylist(
            Playlist(
                id = "playlist-42",
                name = "Older Import",
                createdAt = "2026-01-01T00:00:00Z",
                updatedAt = "2026-01-01T12:00:00Z",
                tracks = listOf(trackTwo),
            ),
        )

        var playlist = repository.observePlaylist("playlist-42").first()
        requireNotNull(playlist)
        assertEquals("Original", playlist.name)
        assertEquals(listOf(trackOne), playlist.tracks)

        repository.importPlaylist(
            Playlist(
                id = "playlist-42",
                name = "Fresh Import",
                createdAt = "2026-01-01T00:00:00Z",
                updatedAt = "2026-01-03T00:00:00Z",
                tracks = listOf(trackTwo, trackOne),
            ),
        )

        playlist = repository.observePlaylist("playlist-42").first()
        requireNotNull(playlist)
        assertEquals("Fresh Import", playlist.name)
        assertEquals(listOf(trackTwo, trackOne), playlist.tracks)
    }

    @Test
    fun `import with equal updatedAt is deterministic and the incoming snapshot wins`() = runTest {
        val repository = repositoryAt(initialInstant)
        repository.importPlaylist(
            Playlist(
                id = "playlist-tie",
                name = "Original",
                createdAt = "2026-01-01T00:00:00Z",
                updatedAt = "2026-01-02T00:00:00Z",
                tracks = listOf(trackOne),
            ),
        )

        repository.importPlaylist(
            Playlist(
                id = "playlist-tie",
                name = "Tied Import",
                createdAt = "2026-01-01T00:00:00Z",
                updatedAt = "2026-01-02T00:00:00Z",
                tracks = listOf(trackTwo),
            ),
        )

        val playlist = repository.observePlaylist("playlist-tie").first()
        requireNotNull(playlist)
        // Tie-breaker: the predicate uses strict greater-than, so equal updatedAt
        // is not considered "older" and the incoming import overwrites.
        assertEquals("Tied Import", playlist.name)
        assertEquals(listOf(trackTwo), playlist.tracks)
    }

    @Test
    fun `playlist flow orders by actual updated instant when offsets differ`() = runTest {
        val repository = repositoryAt(initialInstant)

        repository.importPlaylist(
            Playlist(
                id = "playlist-older",
                name = "Offset Older",
                createdAt = "2026-05-02T09:30:00+02:00",
                updatedAt = "2026-05-02T10:00:00+02:00",
                tracks = listOf(trackOne),
            ),
        )
        repository.importPlaylist(
            Playlist(
                id = "playlist-newer",
                name = "UTC Newer",
                createdAt = "2026-05-02T08:15:00Z",
                updatedAt = "2026-05-02T08:30:00Z",
                tracks = listOf(trackTwo),
            ),
        )

        val playlists = repository.observePlaylists().first()
        assertEquals(listOf("playlist-newer", "playlist-older"), playlists.map { it.id })
    }

    @Test
    fun `history flow returns newest entries first`() = runTest {
        val repository = repositoryAt(initialInstant)

        repository.recordPlayback(trackOne, playedAt = "2026-05-02T10:00:00+02:00")
        repository.recordPlayback(trackTwo, playedAt = "2026-05-02T08:30:00Z")

        val history = repository.observeHistory().first()
        assertEquals(listOf(trackTwo, trackOne), history.map { it.track })
    }

    private fun repositoryAt(now: Instant): PlaylistRepository =
        OfflineFirstPlaylistRepository(
            playlistDao = database.playlistDao(),
            historyDao = database.historyDao(),
            clock = clock.apply { current = now },
        )

    private class MutableClock(
        var current: Instant,
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = current
    }
}
