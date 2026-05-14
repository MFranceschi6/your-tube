package com.yourtube.core.data.repository

import com.yourtube.core.common.model.PlayerState
import com.yourtube.core.common.model.QueueItem
import com.yourtube.core.common.model.Track
import com.yourtube.core.database.dao.PlayerSnapshotDao
import com.yourtube.core.database.entity.PlayerSnapshotEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest

// YT-0272 — unit tests for DefaultPlayerSnapshotRepository.loadSnapshot().
// Uses a pure fake DAO so no Robolectric / Room setup is required.
class DefaultPlayerSnapshotRepositoryTest {

    private class FakePlayerSnapshotDao : PlayerSnapshotDao {
        private val state = MutableStateFlow<PlayerSnapshotEntity?>(null)

        override suspend fun upsert(snapshot: PlayerSnapshotEntity) {
            state.value = snapshot
        }

        override fun getSnapshot(): Flow<PlayerSnapshotEntity?> = state
    }

    private fun makeRepo(dao: PlayerSnapshotDao = FakePlayerSnapshotDao()) =
        DefaultPlayerSnapshotRepository(dao)

    // Empty DB → loadSnapshot() returns null.
    @Test
    fun `loadSnapshot returns null when no snapshot has been written`() = runTest {
        val repo = makeRepo()
        assertNull(repo.loadSnapshot())
    }

    // After save() → loadSnapshot() returns the last written snapshot's fields.
    @Test
    fun `loadSnapshot returns last written snapshot after save`() = runTest {
        val dao = FakePlayerSnapshotDao()
        val repo = makeRepo(dao)

        val track = Track(
            videoId = "vid-1",
            title = "Track One",
            channel = "Chan",
            durationSec = 180,
            thumbnailUrl = "",
        )
        val state = PlayerState(
            currentTrack = track,
            queue = listOf(QueueItem(track = track, queueId = "q0")),
            currentQueueIndex = 0,
            positionMs = 60_000L,
            repeatMode = 1,
            shuffleOn = true,
            playbackSpeed = 1.5f,
        )
        repo.save(state)

        val snap = repo.loadSnapshot()
        assertEquals("vid-1", snap?.currentVideoId)
        assertEquals(0, snap?.queueIndex)
        assertEquals(60_000L, snap?.positionMs)
        assertEquals(1, snap?.repeatMode)
        assertEquals(true, snap?.shuffleOn)
        assertEquals(1.5f, snap?.playbackSpeed)
    }

    // A second upsert overwrites the first — loadSnapshot returns the latest write.
    @Test
    fun `loadSnapshot returns latest write when saved twice`() = runTest {
        val dao = FakePlayerSnapshotDao()
        val repo = makeRepo(dao)

        val trackA = Track("a", "A", "Ch", 120, "")
        val trackB = Track("b", "B", "Ch", 240, "")

        repo.save(
            PlayerState(
                currentTrack = trackA,
                queue = listOf(QueueItem(track = trackA, queueId = "q0")),
                currentQueueIndex = 0,
                positionMs = 10_000L,
            ),
        )
        repo.save(
            PlayerState(
                currentTrack = trackB,
                queue = listOf(QueueItem(track = trackB, queueId = "q1")),
                currentQueueIndex = 0,
                positionMs = 99_000L,
            ),
        )

        val snap = repo.loadSnapshot()
        assertEquals("b", snap?.currentVideoId)
        assertEquals(99_000L, snap?.positionMs)
    }
}
