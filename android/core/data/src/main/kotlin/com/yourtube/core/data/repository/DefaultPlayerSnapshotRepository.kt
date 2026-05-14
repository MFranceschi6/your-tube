package com.yourtube.core.data.repository

import com.yourtube.core.common.model.PlayerState
import com.yourtube.core.common.model.QueueItem
import com.yourtube.core.database.dao.PlayerSnapshotDao
import com.yourtube.core.database.entity.PlayerSnapshotEntity
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class DefaultPlayerSnapshotRepository @Inject constructor(
    private val dao: PlayerSnapshotDao,
) : PlayerSnapshotRepository {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun save(state: PlayerState) {
        dao.upsert(state.toEntity())
    }

    /**
     * YT-0272 — one-shot read of the persisted snapshot. Uses [Flow.first] on the
     * DAO's `getSnapshot()` flow so the coroutine suspends until the Room query
     * delivers exactly one emission. Returns `null` when the table is empty (no
     * prior session). The caller ([DefaultPlayerController.restoreFromSnapshot]) is
     * responsible for all validation and recovery.
     */
    override suspend fun loadSnapshot(): PlayerSnapshotEntity? =
        runCatching { dao.getSnapshot().first() }.getOrNull()

    private fun PlayerState.toEntity(): PlayerSnapshotEntity =
        PlayerSnapshotEntity(
            id = 1,
            currentVideoId = currentTrack?.videoId,
            queue = json.encodeToString(queue.map { it.toPayload() }),
            queueIndex = currentQueueIndex,
            positionMs = positionMs,
            repeatMode = repeatMode,
            shuffleOn = shuffleOn,
            playbackSpeed = playbackSpeed,
            savedAt = Instant.now().toString(),
            title = currentTrack?.title,
            channelName = currentTrack?.channel,
            thumbnailUrl = currentTrack?.thumbnailUrl,
        )

    private fun QueueItem.toPayload(): QueueItemPayload =
        QueueItemPayload(
            queueId = queueId,
            videoId = track.videoId,
            title = track.title,
            channel = track.channel,
            durationSec = track.durationSec,
            thumbnailUrl = track.thumbnailUrl,
        )

    @Serializable
    private data class QueueItemPayload(
        val queueId: String,
        val videoId: String,
        val title: String,
        val channel: String,
        val durationSec: Int,
        val thumbnailUrl: String,
    )
}
