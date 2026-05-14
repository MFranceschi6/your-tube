package com.yourtube.core.database.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yourtube.core.database.PlaylistDatabase
import com.yourtube.core.database.entity.PlayerSnapshotEntity
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlayerSnapshotDaoTest {

    private lateinit var db: PlaylistDatabase
    private lateinit var dao: PlayerSnapshotDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            PlaylistDatabase::class.java,
        )
            .allowMainThreadQueries()
            .build()
        dao = db.playerSnapshotDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `getSnapshot emits null before any upsert`() = runTest {
        assertNull(dao.getSnapshot().first())
    }

    @Test
    fun `upsert then getSnapshot returns the same values`() = runTest {
        val snapshot = PlayerSnapshotEntity(
            id = 1,
            currentVideoId = "abc",
            queue = """[{"queueId":"q1","videoId":"abc"}]""",
            queueIndex = 0,
            positionMs = 42_000L,
            repeatMode = 1,
            shuffleOn = true,
            playbackSpeed = 1.5f,
            savedAt = "2026-05-09T12:00:00Z",
        )

        dao.upsert(snapshot)

        val result = dao.getSnapshot().first()
        assertEquals(snapshot, result)
    }

    @Test
    fun `upsert with track display fields round-trips title, channelName, and thumbnailUrl`() = runTest {
        val snapshot = PlayerSnapshotEntity(
            id = 1,
            currentVideoId = "vid1",
            queue = """[{"queueId":"q1","videoId":"vid1"}]""",
            queueIndex = 0,
            positionMs = 5_000L,
            repeatMode = 0,
            shuffleOn = false,
            playbackSpeed = 1.0f,
            savedAt = "2026-05-09T13:00:00Z",
            title = "My Song",
            channelName = "My Channel",
            thumbnailUrl = "https://i.ytimg.com/vi/vid1/hqdefault.jpg",
        )

        dao.upsert(snapshot)

        val result = dao.getSnapshot().first()
        assertEquals(snapshot, result)
        assertEquals("My Song", result?.title)
        assertEquals("My Channel", result?.channelName)
        assertEquals("https://i.ytimg.com/vi/vid1/hqdefault.jpg", result?.thumbnailUrl)
    }

    @Test
    fun `upsert with null track fields stores and retrieves nulls`() = runTest {
        val snapshot = PlayerSnapshotEntity(
            id = 1,
            currentVideoId = null,
            queue = "[]",
            queueIndex = -1,
            positionMs = 0L,
            repeatMode = 0,
            shuffleOn = false,
            playbackSpeed = 1.0f,
            savedAt = "2026-05-09T14:00:00Z",
            title = null,
            channelName = null,
            thumbnailUrl = null,
        )

        dao.upsert(snapshot)

        val result = dao.getSnapshot().first()
        assertEquals(snapshot, result)
        assertNull(result?.title)
        assertNull(result?.channelName)
        assertNull(result?.thumbnailUrl)
    }

    @Test
    fun `second upsert overwrites the first`() = runTest {
        val first = PlayerSnapshotEntity(
            id = 1,
            currentVideoId = "abc",
            queue = "[]",
            queueIndex = 0,
            positionMs = 0L,
            repeatMode = 0,
            shuffleOn = false,
            playbackSpeed = 1.0f,
            savedAt = "2026-05-09T10:00:00Z",
        )
        val second = PlayerSnapshotEntity(
            id = 1,
            currentVideoId = "xyz",
            queue = """[{"queueId":"q2","videoId":"xyz"}]""",
            queueIndex = 0,
            positionMs = 90_000L,
            repeatMode = 2,
            shuffleOn = true,
            playbackSpeed = 0.75f,
            savedAt = "2026-05-09T11:00:00Z",
        )

        dao.upsert(first)
        dao.upsert(second)

        val result = dao.getSnapshot().first()
        assertEquals(second, result)
        assertEquals("xyz", result?.currentVideoId)
        assertEquals(90_000L, result?.positionMs)
    }
}
