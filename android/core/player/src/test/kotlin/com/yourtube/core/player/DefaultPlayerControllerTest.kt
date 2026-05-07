package com.yourtube.core.player

import com.yourtube.core.common.model.PlaybackStatus
import com.yourtube.core.common.model.Playlist
import com.yourtube.core.common.model.Track
import com.yourtube.core.data.model.PlaybackHistoryEntry
import com.yourtube.core.data.preferences.AudioQualityPreferences
import com.yourtube.core.data.repository.PlaylistRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultPlayerControllerTest {

    private val dispatcher = StandardTestDispatcher()
    private val audioQualityPreferences = FakeAudioQualityPreferences()
    private val playlistRepository = FakePlaylistRepository()

    private fun makeController(transport: PlaybackTransport = FakePlaybackTransport()) =
        DefaultPlayerController(
            playbackTransport = transport,
            dispatcher = dispatcher,
            audioQualityPreferences = audioQualityPreferences,
            playlistRepository = playlistRepository,
        )

    @Test
    fun `queue supports append next remove reorder and navigation`() = runTest(dispatcher) {
        val playbackTransport = FakePlaybackTransport()
        val controller = makeController(playbackTransport)

        controller.playNow(trackOne)
        controller.addToQueue(trackTwo)
        controller.playNext(trackThree)

        assertEquals(
            listOf(trackOne, trackThree, trackTwo),
            controller.playerState.value.queue.map { it.track },
        )

        controller.skipNext()
        assertEquals(trackThree, controller.playerState.value.currentTrack)

        controller.moveQueueItem(fromIndex = 2, toIndex = 1)
        assertEquals(
            listOf(trackOne, trackTwo, trackThree),
            controller.playerState.value.queue.map { it.track },
        )
        assertEquals(2, controller.playerState.value.currentQueueIndex)

        val firstQueueId = controller.playerState.value.queue.first().queueId
        controller.removeQueueItem(firstQueueId)
        assertEquals(
            listOf(trackTwo, trackThree),
            controller.playerState.value.queue.map { it.track },
        )
        assertEquals(1, controller.playerState.value.currentQueueIndex)

        controller.skipPrevious()
        assertEquals(trackTwo, controller.playerState.value.currentTrack)
    }

    @Test
    fun `progress updates and pause resume seek drive state`() = runTest(dispatcher) {
        val playbackTransport = FakePlaybackTransport()
        val controller = makeController(playbackTransport)

        controller.playNow(trackOne)
        assertEquals(PlaybackStatus.PLAYING, controller.playerState.value.playbackStatus)

        advanceTimeBy(3_000L)
        runCurrent()
        assertEquals(3_000L, controller.playerState.value.positionMs)

        controller.pause()
        assertEquals(PlaybackStatus.PAUSED, controller.playerState.value.playbackStatus)

        controller.seekTo(7_000L)
        assertEquals(7_000L, controller.playerState.value.positionMs)

        controller.resume()
        assertEquals(PlaybackStatus.PLAYING, controller.playerState.value.playbackStatus)
    }

    @Test
    fun `removing currently playing item transitions to PAUSED with positionMs reset`() = runTest(dispatcher) {
        val playbackTransport = FakePlaybackTransport()
        val controller = makeController(playbackTransport)

        controller.playNow(trackOne)
        controller.addToQueue(trackTwo)
        advanceTimeBy(5_000L)
        runCurrent()

        val playingId = controller.playerState.value.queue.first().queueId
        controller.removeQueueItem(playingId)

        val state = controller.playerState.value
        assertEquals(PlaybackStatus.PAUSED, state.playbackStatus)
        assertEquals(false, state.isPlaying)
        assertEquals(0L, state.positionMs)
        assertEquals(trackTwo, state.currentTrack)
    }

    @Test
    fun `error state set when transport returns failure`() = runTest(dispatcher) {
        val failingTransport = object : PlaybackTransport {
            override suspend fun playTrack(request: PlaybackRequest) =
                PlaybackResult.Failure("injected failure")
            override suspend fun pause() = Unit
            override suspend fun resume() = Unit
            override suspend fun seekTo(positionMs: Long) = Unit
            override suspend fun stopAndClearCurrent() = Unit
            override suspend fun setShuffleMode(enabled: Boolean) = Unit
            override suspend fun setRepeatMode(mode: Int) = Unit
        }
        val controller = makeController(failingTransport)

        controller.playNow(trackOne)

        val state = controller.playerState.value
        assertEquals(PlaybackStatus.ERROR, state.playbackStatus)
        assertEquals(false, state.isPlaying)
        assertEquals(trackOne, state.currentTrack)
    }

    @Test
    fun `playNext when currentQueueIndex is -1 appends to end`() = runTest(dispatcher) {
        val controller = makeController()

        // Queue is empty, no track playing — index is -1
        controller.playNext(trackOne)

        val state = controller.playerState.value
        assertEquals(listOf(trackOne), state.queue.map { it.track })
        assertEquals(-1, state.currentQueueIndex)
    }

    @Test
    fun `moveQueueItem updates currentQueueIndex when moving the playing item`() = runTest(dispatcher) {
        val controller = makeController()

        controller.playNow(trackOne)
        controller.addToQueue(trackTwo)
        controller.addToQueue(trackThree)
        // Queue: [trackOne(idx=0), trackTwo, trackThree], playing index=0

        controller.moveQueueItem(fromIndex = 0, toIndex = 2)
        // Queue: [trackTwo, trackThree, trackOne], playing item (trackOne) is now at index=2

        val state = controller.playerState.value
        assertEquals(trackOne, state.currentTrack)
        assertEquals(2, state.currentQueueIndex)
        assertEquals(listOf(trackTwo, trackThree, trackOne), state.queue.map { it.track })
    }

    @Test
    fun `playQueueItem reads stored bitrate preference and passes it to PlaybackRequest`() =
        runTest(dispatcher) {
            audioQualityPreferences.setBitrateKbps(64)
            advanceUntilIdle()

            val capturingTransport = CapturingPlaybackTransport()
            val controller = makeController(capturingTransport)

            controller.playNow(trackOne)
            advanceUntilIdle()

            assertEquals(64, capturingTransport.lastRequest?.preferredMaxBitrateKbps)
        }

    @Test
    fun `playQueueItem falls back to default bitrate when preference unset`() =
        runTest(dispatcher) {
            // FakeAudioQualityPreferences default = AudioQualityPreferences.DEFAULT_BITRATE_KBPS
            val capturingTransport = CapturingPlaybackTransport()
            val controller = makeController(capturingTransport)

            controller.playNow(trackOne)
            advanceUntilIdle()

            assertEquals(
                AudioQualityPreferences.DEFAULT_BITRATE_KBPS,
                capturingTransport.lastRequest?.preferredMaxBitrateKbps,
            )
        }

    @Test
    fun `successful playback records history via PlaylistRepository`() = runTest(dispatcher) {
        val controller = makeController()

        controller.playNow(trackOne)
        advanceUntilIdle()

        assertEquals(listOf(trackOne), playlistRepository.recorded)
    }

    // YT-0050: when the user requests a new track, the transport must be told to
    // stop the previous audio and clear its media items BEFORE we suspend on
    // network resolution. The state-flow already exposes positionMs=0 and
    // durationMs=newTrack.durationMs synchronously inside playNow, so the
    // ViewModel observes the reset immediately.
    @Test
    fun `playNow stops and clears transport before stream resolution suspends`() =
        runTest(dispatcher) {
            val transport = SuspendingPlaybackTransport()
            val controller = makeController(transport)

            // Launch playNow so the suspending playTrack does not block runTest.
            val job = launch { controller.playNow(trackOne) }
            advanceUntilIdle()

            // playTrack is awaiting on the deferred — but stopAndClearCurrent
            // has already been issued and must precede the playTrack call.
            assertEquals(true, transport.playTrackStarted)
            assertEquals(
                listOf("stopAndClearCurrent", "playTrack"),
                transport.events,
            )
            assertEquals(0L, controller.playerState.value.positionMs)
            assertEquals(trackOne.durationSec * 1000L, controller.playerState.value.durationMs)
            assertEquals(trackOne, controller.playerState.value.currentTrack)
            assertEquals(PlaybackStatus.LOADING, controller.playerState.value.playbackStatus)

            // Let the resolve complete so the launched coroutine finishes cleanly.
            transport.completePlayTrack(PlaybackResult.Success)
            job.join()
        }

    // YT-0062a Q11: shuffle/repeat writes update PlayerState synchronously and forward
    // to the transport so the same call path serves both UI taps and any session-callback
    // routing that lands on PlayerController.
    @Test
    fun `setShuffleMode updates PlayerState shuffleOn and forwards to transport`() =
        runTest(dispatcher) {
            val transport = CapturingPlaybackTransport()
            val controller = makeController(transport)

            controller.setShuffleMode(enabled = true)
            assertEquals(true, controller.playerState.value.shuffleOn)
            assertEquals(listOf(true), transport.shuffleCalls)

            controller.setShuffleMode(enabled = false)
            assertEquals(false, controller.playerState.value.shuffleOn)
            assertEquals(listOf(true, false), transport.shuffleCalls)
        }

    @Test
    fun `setRepeatMode updates PlayerState repeatMode and forwards to transport`() =
        runTest(dispatcher) {
            val transport = CapturingPlaybackTransport()
            val controller = makeController(transport)

            // REPEAT_MODE_ONE = 1
            controller.setRepeatMode(mode = 1)
            assertEquals(1, controller.playerState.value.repeatMode)
            assertEquals(listOf(1), transport.repeatCalls)

            // REPEAT_MODE_ALL = 2
            controller.setRepeatMode(mode = 2)
            assertEquals(2, controller.playerState.value.repeatMode)

            // REPEAT_MODE_OFF = 0
            controller.setRepeatMode(mode = 0)
            assertEquals(0, controller.playerState.value.repeatMode)
            assertEquals(listOf(1, 2, 0), transport.repeatCalls)
        }

    @Test
    fun `setShuffleMode is idempotent when called twice with the same value`() =
        runTest(dispatcher) {
            val transport = CapturingPlaybackTransport()
            val controller = makeController(transport)

            controller.setShuffleMode(enabled = true)
            controller.setShuffleMode(enabled = true)

            assertEquals(true, controller.playerState.value.shuffleOn)
            // Two calls forwarded — controller does not de-dupe; the transport / Player
            // is the right place to no-op on equal values, and Media3 already does so.
            assertEquals(listOf(true, true), transport.shuffleCalls)
        }

    @Test
    fun `failed playback does not record history`() = runTest(dispatcher) {
        val failingTransport = object : PlaybackTransport {
            override suspend fun playTrack(request: PlaybackRequest) =
                PlaybackResult.Failure("nope")
            override suspend fun pause() = Unit
            override suspend fun resume() = Unit
            override suspend fun seekTo(positionMs: Long) = Unit
            override suspend fun stopAndClearCurrent() = Unit
            override suspend fun setShuffleMode(enabled: Boolean) = Unit
            override suspend fun setRepeatMode(mode: Int) = Unit
        }
        val controller = makeController(failingTransport)

        controller.playNow(trackOne)
        advanceUntilIdle()

        assertEquals(emptyList<Track>(), playlistRepository.recorded)
    }

    private class FakePlaybackTransport : PlaybackTransport {
        override suspend fun playTrack(request: PlaybackRequest): PlaybackResult =
            PlaybackResult.Success

        override suspend fun pause() = Unit

        override suspend fun resume() = Unit

        override suspend fun seekTo(positionMs: Long) = Unit

        override suspend fun stopAndClearCurrent() = Unit

        override suspend fun setShuffleMode(enabled: Boolean) = Unit

        override suspend fun setRepeatMode(mode: Int) = Unit
    }

    /**
     * Records every transport call in order and suspends `playTrack` until the
     * test completes the deferred. Used to assert that
     * `stopAndClearCurrent` is invoked BEFORE the transport blocks on stream
     * resolution (YT-0050).
     */
    private class SuspendingPlaybackTransport : PlaybackTransport {
        val events = mutableListOf<String>()
        var playTrackStarted: Boolean = false
            private set
        private val playTrackResult = CompletableDeferred<PlaybackResult>()

        fun completePlayTrack(result: PlaybackResult) {
            playTrackResult.complete(result)
        }

        override suspend fun playTrack(request: PlaybackRequest): PlaybackResult {
            events += "playTrack"
            playTrackStarted = true
            return playTrackResult.await()
        }

        override suspend fun pause() {
            events += "pause"
        }

        override suspend fun resume() {
            events += "resume"
        }

        override suspend fun seekTo(positionMs: Long) {
            events += "seekTo($positionMs)"
        }

        override suspend fun stopAndClearCurrent() {
            events += "stopAndClearCurrent"
        }

        override suspend fun setShuffleMode(enabled: Boolean) {
            events += "setShuffleMode($enabled)"
        }

        override suspend fun setRepeatMode(mode: Int) {
            events += "setRepeatMode($mode)"
        }
    }

    private class CapturingPlaybackTransport : PlaybackTransport {
        var lastRequest: PlaybackRequest? = null
        val shuffleCalls = mutableListOf<Boolean>()
        val repeatCalls = mutableListOf<Int>()

        override suspend fun playTrack(request: PlaybackRequest): PlaybackResult {
            lastRequest = request
            return PlaybackResult.Success
        }

        override suspend fun pause() = Unit

        override suspend fun resume() = Unit

        override suspend fun seekTo(positionMs: Long) = Unit

        override suspend fun stopAndClearCurrent() = Unit

        override suspend fun setShuffleMode(enabled: Boolean) {
            shuffleCalls += enabled
        }

        override suspend fun setRepeatMode(mode: Int) {
            repeatCalls += mode
        }
    }

    private class FakeAudioQualityPreferences(
        initial: Int = AudioQualityPreferences.DEFAULT_BITRATE_KBPS,
    ) : AudioQualityPreferences {
        private val state = MutableStateFlow(initial)
        override val bitrateKbps: Flow<Int> = state
        override suspend fun setBitrateKbps(kbps: Int) {
            state.value = kbps
        }
    }

    private class FakePlaylistRepository : PlaylistRepository {
        val recorded = mutableListOf<Track>()
        override fun observePlaylists(): Flow<List<Playlist>> = flowOf(emptyList())
        override fun observePlaylist(playlistId: String): Flow<Playlist?> = flowOf(null)
        override fun observeHistory(): Flow<List<PlaybackHistoryEntry>> = flowOf(emptyList())
        override suspend fun createPlaylist(
            name: String,
            tracks: List<Track>,
            playlistId: String?,
        ): Playlist = Playlist(
            id = playlistId ?: "fake",
            name = name,
            tracks = tracks,
            createdAt = "2026-05-04",
            updatedAt = "2026-05-04",
        )
        override suspend fun renamePlaylist(playlistId: String, newName: String) = Unit
        override suspend fun deletePlaylist(playlistId: String) = Unit
        override suspend fun addTrackToPlaylist(playlistId: String, track: Track) = Unit
        override suspend fun removeTrackFromPlaylist(playlistId: String, position: Int) = Unit
        override suspend fun reorderTracks(playlistId: String, fromIndex: Int, toIndex: Int) = Unit
        override suspend fun importPlaylist(playlist: Playlist) = Unit
        override suspend fun recordPlayback(track: Track, playedAt: String?): PlaybackHistoryEntry {
            recorded += track
            return PlaybackHistoryEntry(
                id = "entry-${recorded.size}",
                track = track,
                playedAt = playedAt ?: "2026-05-04T00:00:00Z",
            )
        }
        override suspend fun clearHistory() = Unit
        override suspend fun removeHistoryEntry(entryId: String) = Unit
    }

    companion object {
        private val trackOne = Track("one", "Track One", "Channel A", 180, "")
        private val trackTwo = Track("two", "Track Two", "Channel B", 240, "")
        private val trackThree = Track("three", "Track Three", "Channel C", 200, "")
    }
}
