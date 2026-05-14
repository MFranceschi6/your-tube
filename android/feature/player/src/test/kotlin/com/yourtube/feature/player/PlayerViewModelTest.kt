package com.yourtube.feature.player

import app.cash.turbine.test
import com.yourtube.core.common.haptics.HapticsController
import com.yourtube.core.common.model.PlaybackStatus
import com.yourtube.core.common.model.PlayerState
import com.yourtube.core.common.model.QueueItem
import com.yourtube.core.common.model.SleepTimerPreset
import com.yourtube.core.common.model.SleepTimerState
import com.yourtube.core.common.model.Track
import com.yourtube.core.database.entity.PlayerSnapshotEntity
import com.yourtube.core.player.Logger
import com.yourtube.core.player.PlaybackPerfTracer
import com.yourtube.core.player.PlayerController
import com.yourtube.core.player.SleepTimerController
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `playNow emits idle then loading then playing through the view model`() = runTest(dispatcher) {
        val controller = FakePlayerController()
        val viewModel = PlayerViewModel(controller, NoOpPerfTracer, NoOpHapticsController, NoOpSleepTimerController())

        viewModel.playerState.test {
            assertEquals(PlaybackStatus.IDLE, awaitItem().playbackStatus)

            viewModel.playNow(track)
            advanceUntilIdle()

            val loading = awaitItem()
            assertEquals(PlaybackStatus.LOADING, loading.playbackStatus)
            assertEquals(false, loading.isPlaying)
            assertEquals(track, loading.currentTrack)
            assertEquals(1, loading.queue.size)

            controller.completePlayNow()
            advanceUntilIdle()

            val playing = awaitItem()
            assertEquals(PlaybackStatus.PLAYING, playing.playbackStatus)
            assertEquals(true, playing.isPlaying)
            assertEquals(track.durationSec * 1000L, playing.durationMs)

            cancelAndIgnoreRemainingEvents()
        }
    }

    // YT-0196 — the play/pause button on MiniPlayer + NowPlaying renders a spinner while
    // `playbackStatus == LOADING`. This test pins that contract: `playerState.playbackStatus`
    // remains LOADING for as long as the controller's resolve is in flight (i.e. before the
    // transport reports `STATE_READY`/`isPlaying = true`), then flips to PLAYING the moment
    // the transport completes. Without this guarantee the button would render `Pause` the
    // instant the user taps a track — implying playback already started — even though audio
    // takes ~3.5 s to start on Android per `YT_PERF`.
    @Test
    fun `playbackStatus stays LOADING across the resolve window then flips to PLAYING`() =
        runTest(dispatcher) {
            val controller = FakePlayerController()
            val viewModel = PlayerViewModel(controller, NoOpPerfTracer, NoOpHapticsController, NoOpSleepTimerController())

            viewModel.playerState.test {
                assertEquals(PlaybackStatus.IDLE, awaitItem().playbackStatus)

                viewModel.playNow(track)
                advanceUntilIdle()

                // First state after `playNow` MUST be LOADING — playback has not yet started.
                val loading = awaitItem()
                assertEquals(PlaybackStatus.LOADING, loading.playbackStatus)
                assertEquals(false, loading.isPlaying)
                assertEquals(track, loading.currentTrack)

                // The state must NOT silently flip to PLAYING while the transport is still
                // resolving the stream. Drain the dispatcher and re-check — no new emission
                // beyond LOADING should appear before the transport reports ready.
                advanceUntilIdle()
                assertEquals(PlaybackStatus.LOADING, viewModel.playerState.value.playbackStatus)

                // Transport reports `STATE_READY` → controller flips to PLAYING.
                controller.completePlayNow()
                advanceUntilIdle()

                val playing = awaitItem()
                assertEquals(PlaybackStatus.PLAYING, playing.playbackStatus)
                assertEquals(true, playing.isPlaying)

                cancelAndIgnoreRemainingEvents()
            }
        }

    // YT-0196 — fast pause→play on a track that's already loaded must NOT re-enter LOADING.
    // The button transitions PAUSED → PLAYING directly with no spinner flash, matching iOS.
    // Per `DefaultPlayerController.resume()` the path is: `playbackTransport.resume()` then
    // PLAYING — never via LOADING.
    @Test
    fun `resume on a loaded track skips LOADING and goes PAUSED to PLAYING directly`() =
        runTest(dispatcher) {
            val pausedState = PlayerState(
                currentTrack = track,
                queue = listOf(QueueItem(track = track, queueId = "queue-1")),
                currentQueueIndex = 0,
                playbackStatus = PlaybackStatus.PAUSED,
                isPlaying = false,
                durationMs = track.durationSec * 1000L,
            )
            val controller = FakePlayerController().apply { startInState(pausedState) }
            val viewModel = PlayerViewModel(controller, NoOpPerfTracer, NoOpHapticsController, NoOpSleepTimerController())

            viewModel.playerState.test {
                assertEquals(PlaybackStatus.PAUSED, awaitItem().playbackStatus)

                viewModel.resume()
                advanceUntilIdle()
                controller.completeResume()
                advanceUntilIdle()

                // Next emission MUST be PLAYING — never LOADING. If a regression routes resume
                // through the resolve path, the LOADING state would appear here and this
                // assertion would fail.
                val resumed = awaitItem()
                assertEquals(PlaybackStatus.PLAYING, resumed.playbackStatus)
                assertEquals(true, resumed.isPlaying)

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `pause then resume toggles playback status`() = runTest(dispatcher) {
        val controller = FakePlayerController().apply { startInState(playingState) }
        val viewModel = PlayerViewModel(controller, NoOpPerfTracer, NoOpHapticsController, NoOpSleepTimerController())

        viewModel.playerState.test {
            assertEquals(PlaybackStatus.PLAYING, awaitItem().playbackStatus)

            viewModel.pause()
            advanceUntilIdle()
            controller.completePause()
            advanceUntilIdle()

            val paused = awaitItem()
            assertEquals(PlaybackStatus.PAUSED, paused.playbackStatus)
            assertEquals(false, paused.isPlaying)

            viewModel.resume()
            advanceUntilIdle()
            controller.completeResume()
            advanceUntilIdle()

            val resumed = awaitItem()
            assertEquals(PlaybackStatus.PLAYING, resumed.playbackStatus)
            assertEquals(true, resumed.isPlaying)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `seekTo updates the position on the state stream`() = runTest(dispatcher) {
        val controller = FakePlayerController().apply { startInState(playingState) }
        val viewModel = PlayerViewModel(controller, NoOpPerfTracer, NoOpHapticsController, NoOpSleepTimerController())

        viewModel.seekTo(positionMs = 12_345L)
        advanceUntilIdle()
        controller.completeSeek()
        advanceUntilIdle()

        assertEquals(12_345L, viewModel.playerState.value.positionMs)
        assertEquals(listOf(12_345L), controller.seekRequests)
    }

    @Test
    fun `skipNext and skipPrevious move the queue cursor`() = runTest(dispatcher) {
        val controller = FakePlayerController().apply { startInState(playingState) }
        val viewModel = PlayerViewModel(controller, NoOpPerfTracer, NoOpHapticsController, NoOpSleepTimerController())

        viewModel.skipNext()
        advanceUntilIdle()
        assertEquals(1, viewModel.playerState.value.currentQueueIndex)
        assertEquals(secondTrack, viewModel.playerState.value.currentTrack)

        viewModel.skipPrevious()
        advanceUntilIdle()
        assertEquals(0, viewModel.playerState.value.currentQueueIndex)
        assertEquals(track, viewModel.playerState.value.currentTrack)

        assertEquals(1, controller.skipNextCalls)
        assertEquals(1, controller.skipPreviousCalls)
    }

    @Test
    fun `playList plays first track and queues the rest in order`() = runTest(dispatcher) {
        val controller = FakePlayerController()
        val viewModel = PlayerViewModel(controller, NoOpPerfTracer, NoOpHapticsController, NoOpSleepTimerController())

        viewModel.playList(listOf(track, secondTrack, thirdTrack), shuffle = false)
        advanceUntilIdle()
        controller.completePlayNow()
        advanceUntilIdle()

        val state = viewModel.playerState.value
        assertEquals(track, state.currentTrack)
        assertEquals(3, state.queue.size)
        assertEquals(track, state.queue[0].track)
        assertEquals(secondTrack, state.queue[1].track)
        assertEquals(thirdTrack, state.queue[2].track)
    }

    @Test
    fun `playList shuffle randomises order but keeps the full set of tracks`() = runTest(dispatcher) {
        val controller = FakePlayerController()
        val viewModel = PlayerViewModel(controller, NoOpPerfTracer, NoOpHapticsController, NoOpSleepTimerController())
        val all = listOf(track, secondTrack, thirdTrack)

        viewModel.playList(all, shuffle = true)
        advanceUntilIdle()
        controller.completePlayNow()
        advanceUntilIdle()

        val queueTracks = viewModel.playerState.value.queue.map { it.track }
        assertEquals(all.size, queueTracks.size)
        assertEquals(all.toSet(), queueTracks.toSet())
        // The first track in the queue is the same one that's currently playing.
        assertEquals(viewModel.playerState.value.currentTrack, queueTracks.first())
    }

    @Test
    fun `playList no-ops on empty input`() = runTest(dispatcher) {
        val controller = FakePlayerController()
        val viewModel = PlayerViewModel(controller, NoOpPerfTracer, NoOpHapticsController, NoOpSleepTimerController())

        viewModel.playList(emptyList(), shuffle = false)
        advanceUntilIdle()

        val state = viewModel.playerState.value
        assertEquals(null, state.currentTrack)
        assertEquals(0, state.queue.size)
        assertEquals(PlaybackStatus.IDLE, state.playbackStatus)
    }

    // YT-0062a Q11: persistent shuffle / repeat mode wrappers route through the controller
    // so UI taps and any future system-media-controls writes share a single call path.
    @Test
    fun `setShuffleMode wrapper forwards to controller and is reflected in state`() =
        runTest(dispatcher) {
            val controller = FakePlayerController()
            val viewModel = PlayerViewModel(controller, NoOpPerfTracer, NoOpHapticsController, NoOpSleepTimerController())

            viewModel.setShuffleMode(enabled = true)
            advanceUntilIdle()

            assertEquals(listOf(true), controller.shuffleCalls)
            assertEquals(true, viewModel.playerState.value.shuffleOn)
        }

    @Test
    fun `setRepeatMode wrapper forwards to controller and is reflected in state`() =
        runTest(dispatcher) {
            val controller = FakePlayerController()
            val viewModel = PlayerViewModel(controller, NoOpPerfTracer, NoOpHapticsController, NoOpSleepTimerController())

            // REPEAT_MODE_ALL = 2 (matches androidx.media3.common.Player.REPEAT_MODE_ALL).
            viewModel.setRepeatMode(mode = 2)
            advanceUntilIdle()

            assertEquals(listOf(2), controller.repeatCalls)
            assertEquals(2, viewModel.playerState.value.repeatMode)
        }

    @Test
    fun `queue mutations remove and reorder items in player state`() = runTest(dispatcher) {
        val controller = FakePlayerController().apply { startInState(playingState) }
        val viewModel = PlayerViewModel(controller, NoOpPerfTracer, NoOpHapticsController, NoOpSleepTimerController())

        viewModel.removeQueueItem(queueId = "queue-1")
        advanceUntilIdle()
        assertEquals(listOf("queue-2"), viewModel.playerState.value.queue.map { it.queueId })

        viewModel.addToQueue(thirdTrack)
        advanceUntilIdle()
        assertEquals(
            listOf("queue-2", "queue-fake-3"),
            viewModel.playerState.value.queue.map { it.queueId },
        )

        viewModel.moveQueueItem(fromIndex = 0, toIndex = 1)
        advanceUntilIdle()
        assertEquals(
            listOf("queue-fake-3", "queue-2"),
            viewModel.playerState.value.queue.map { it.queueId },
        )
    }

    // CR3 — Haptics recording tests using RecordingHapticsController.
    // Each test asserts the exact number of haptic method invocations so that
    // accidental double-fires or wrong-method calls are caught at the unit level.

    @Test
    fun `pause invokes onPlayPause exactly once`() = runTest(dispatcher) {
        val haptics = RecordingHapticsController()
        val controller = FakePlayerController().apply { startInState(playingState) }
        val viewModel = PlayerViewModel(controller, NoOpPerfTracer, haptics, NoOpSleepTimerController())

        viewModel.pause()
        advanceUntilIdle()

        assertEquals(1, haptics.playPauseCalls)
        assertEquals(0, haptics.skipCalls)
        assertEquals(0, haptics.queueAddCalls)
        assertEquals(0, haptics.playNextCalls)
    }

    @Test
    fun `resume invokes onPlayPause exactly once`() = runTest(dispatcher) {
        val haptics = RecordingHapticsController()
        val pausedState = PlayerState(
            currentTrack = track,
            queue = listOf(QueueItem(track = track, queueId = "queue-1")),
            currentQueueIndex = 0,
            playbackStatus = PlaybackStatus.PAUSED,
            isPlaying = false,
            durationMs = track.durationSec * 1000L,
        )
        val controller = FakePlayerController().apply { startInState(pausedState) }
        val viewModel = PlayerViewModel(controller, NoOpPerfTracer, haptics, NoOpSleepTimerController())

        viewModel.resume()
        advanceUntilIdle()

        assertEquals(1, haptics.playPauseCalls)
        assertEquals(0, haptics.skipCalls)
        assertEquals(0, haptics.queueAddCalls)
        assertEquals(0, haptics.playNextCalls)
    }

    @Test
    fun `skipNext invokes onSkip exactly once`() = runTest(dispatcher) {
        val haptics = RecordingHapticsController()
        val controller = FakePlayerController().apply { startInState(playingState) }
        val viewModel = PlayerViewModel(controller, NoOpPerfTracer, haptics, NoOpSleepTimerController())

        viewModel.skipNext()
        advanceUntilIdle()

        assertEquals(0, haptics.playPauseCalls)
        assertEquals(1, haptics.skipCalls)
        assertEquals(0, haptics.queueAddCalls)
        assertEquals(0, haptics.playNextCalls)
    }

    @Test
    fun `skipPrevious invokes onSkip exactly once`() = runTest(dispatcher) {
        val haptics = RecordingHapticsController()
        val controller = FakePlayerController().apply { startInState(playingState) }
        val viewModel = PlayerViewModel(controller, NoOpPerfTracer, haptics, NoOpSleepTimerController())

        viewModel.skipPrevious()
        advanceUntilIdle()

        assertEquals(0, haptics.playPauseCalls)
        assertEquals(1, haptics.skipCalls)
        assertEquals(0, haptics.queueAddCalls)
        assertEquals(0, haptics.playNextCalls)
    }

    @Test
    fun `addToQueue invokes onQueueAdd exactly once`() = runTest(dispatcher) {
        val haptics = RecordingHapticsController()
        val controller = FakePlayerController().apply { startInState(playingState) }
        val viewModel = PlayerViewModel(controller, NoOpPerfTracer, haptics, NoOpSleepTimerController())

        viewModel.addToQueue(thirdTrack)
        advanceUntilIdle()

        assertEquals(0, haptics.playPauseCalls)
        assertEquals(0, haptics.skipCalls)
        assertEquals(1, haptics.queueAddCalls)
        assertEquals(0, haptics.playNextCalls)
    }

    @Test
    fun `playNext invokes onPlayNext exactly once`() = runTest(dispatcher) {
        val haptics = RecordingHapticsController()
        val controller = FakePlayerController().apply { startInState(playingState) }
        val viewModel = PlayerViewModel(controller, NoOpPerfTracer, haptics, NoOpSleepTimerController())

        viewModel.playNext(thirdTrack)
        advanceUntilIdle()

        assertEquals(0, haptics.playPauseCalls)
        assertEquals(0, haptics.skipCalls)
        assertEquals(0, haptics.queueAddCalls)
        assertEquals(1, haptics.playNextCalls)
    }

    @Test
    fun `system-driven transport listener callbacks do not trigger any haptics`() =
        runTest(dispatcher) {
            val haptics = RecordingHapticsController()
            val controller = FakePlayerController()
            val viewModel = PlayerViewModel(controller, NoOpPerfTracer, haptics, NoOpSleepTimerController())

            // Simulate system-driven state changes by pushing updates directly through
            // FakePlayerController.startInState — this mirrors what PlaybackTransportListener
            // does (it updates playerState without going through ViewModel action methods).
            controller.startInState(playingState)
            advanceUntilIdle()
            controller.startInState(playingState.copy(isPlaying = false, playbackStatus = PlaybackStatus.PAUSED))
            advanceUntilIdle()
            controller.startInState(playingState)
            advanceUntilIdle()

            // No ViewModel action methods were called — zero haptic invocations expected.
            assertEquals(0, haptics.playPauseCalls)
            assertEquals(0, haptics.skipCalls)
            assertEquals(0, haptics.queueAddCalls)
            assertEquals(0, haptics.playNextCalls)
        }

    // YT-0093 — sleep timer ViewModel surface tests.

    @Test
    fun `setSleepTimer with Min15 transitions sleepTimerState to Active`() =
        runTest(dispatcher) {
            val sleepTimer = RecordingSleepTimerController()
            val controller = FakePlayerController()
            val viewModel = PlayerViewModel(controller, NoOpPerfTracer, NoOpHapticsController, sleepTimer)

            viewModel.sleepTimerState.test {
                // Initial state is Inactive.
                assertEquals(SleepTimerState.Inactive, awaitItem())

                viewModel.setSleepTimer(SleepTimerPreset.Min15)
                advanceUntilIdle()

                val active = awaitItem()
                check(active is SleepTimerState.Active) { "Expected Active but was $active" }
                assertEquals(SleepTimerPreset.Min15, active.preset)

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `cancelSleepTimer transitions sleepTimerState back to Inactive`() =
        runTest(dispatcher) {
            val sleepTimer = RecordingSleepTimerController()
            val controller = FakePlayerController()
            val viewModel = PlayerViewModel(controller, NoOpPerfTracer, NoOpHapticsController, sleepTimer)

            // Arm the timer first so cancellation has something to cancel.
            viewModel.setSleepTimer(SleepTimerPreset.Min30)
            advanceUntilIdle()

            viewModel.sleepTimerState.test {
                // Drain the current Active emission before testing cancel.
                val initial = awaitItem()
                check(initial is SleepTimerState.Active) { "Expected Active but was $initial" }

                viewModel.cancelSleepTimer()
                advanceUntilIdle()

                assertEquals(SleepTimerState.Inactive, awaitItem())

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `setSleepTimer replaces an already-active timer`() =
        runTest(dispatcher) {
            val sleepTimer = RecordingSleepTimerController()
            val controller = FakePlayerController()
            val viewModel = PlayerViewModel(controller, NoOpPerfTracer, NoOpHapticsController, sleepTimer)

            viewModel.setSleepTimer(SleepTimerPreset.Min15)
            advanceUntilIdle()

            viewModel.sleepTimerState.test {
                val first = awaitItem()
                check(first is SleepTimerState.Active && first.preset == SleepTimerPreset.Min15)

                viewModel.setSleepTimer(SleepTimerPreset.Min60)
                advanceUntilIdle()

                val replaced = awaitItem()
                check(replaced is SleepTimerState.Active) { "Expected Active but was $replaced" }
                assertEquals(SleepTimerPreset.Min60, replaced.preset)

                cancelAndIgnoreRemainingEvents()
            }
        }

    /**
     * A [SleepTimerController] fake that records [setTimer] and [cancel] calls and
     * immediately reflects them in [timerState], so ViewModel-level tests can assert on the
     * exposed [StateFlow] without needing a real timer implementation.
     */
    private class RecordingSleepTimerController : SleepTimerController {
        private val _timerState = MutableStateFlow<SleepTimerState>(SleepTimerState.Inactive)
        override val timerState: StateFlow<SleepTimerState> = _timerState

        override fun attach(scope: kotlinx.coroutines.CoroutineScope) = Unit
        override fun detach() = Unit

        override fun setTimer(preset: SleepTimerPreset) {
            _timerState.value = SleepTimerState.Active(
                remainingMs = preset.durationMs,
                preset = preset,
            )
        }

        override fun cancel() {
            _timerState.value = SleepTimerState.Inactive
        }
    }

    private class FakePlayerController : PlayerController {
        private val mutableState = MutableStateFlow(PlayerState())
        override val playerState: StateFlow<PlayerState> = mutableState

        private var playGate = CompletableDeferred<Unit>()
        private var pauseGate = CompletableDeferred<Unit>()
        private var resumeGate = CompletableDeferred<Unit>()
        private var seekGate = CompletableDeferred<Unit>()

        val seekRequests = mutableListOf<Long>()
        var skipNextCalls = 0
            private set
        var skipPreviousCalls = 0
            private set
        val shuffleCalls = mutableListOf<Boolean>()
        val repeatCalls = mutableListOf<Int>()

        fun startInState(state: PlayerState) {
            mutableState.value = state
        }

        fun completePlayNow() {
            playGate.complete(Unit)
        }

        fun completePause() {
            pauseGate.complete(Unit)
        }

        fun completeResume() {
            resumeGate.complete(Unit)
        }

        fun completeSeek() {
            seekGate.complete(Unit)
        }

        override suspend fun setQueueAndPlay(tracks: List<Track>, startIndex: Int) {
            if (tracks.isEmpty()) return
            val safe = startIndex.coerceIn(0, tracks.lastIndex)
            val items = tracks.map { QueueItem(track = it, queueId = "queue-${it.videoId}") }
            mutableState.value = PlayerState(
                currentTrack = tracks[safe],
                queue = items,
                currentQueueIndex = safe,
                playbackStatus = PlaybackStatus.PLAYING,
                isPlaying = true,
                durationMs = tracks[safe].durationSec * 1000L,
            )
        }

        override suspend fun playNow(track: Track) {
            val queueItem = QueueItem(track = track, queueId = "queue-${track.videoId}")
            mutableState.value = PlayerState(
                currentTrack = track,
                queue = listOf(queueItem),
                currentQueueIndex = 0,
                playbackStatus = PlaybackStatus.LOADING,
                isPlaying = false,
                durationMs = track.durationSec * 1000L,
            )
            playGate.await()
            mutableState.value = mutableState.value.copy(
                playbackStatus = PlaybackStatus.PLAYING,
                isPlaying = true,
            )
        }

        override suspend fun addToQueue(track: Track) {
            val queueItem = QueueItem(track = track, queueId = "queue-fake-3")
            mutableState.value = mutableState.value.copy(
                queue = mutableState.value.queue + queueItem,
            )
        }

        override suspend fun playNext(track: Track) {
            val queueItem = QueueItem(track = track, queueId = "queue-next")
            val current = mutableState.value
            val insertAt = (current.currentQueueIndex + 1).coerceAtLeast(0)
            val next = current.queue.toMutableList().apply { add(insertAt, queueItem) }
            mutableState.value = current.copy(queue = next)
        }

        override suspend fun skipNext() {
            skipNextCalls += 1
            val current = mutableState.value
            val nextIndex = (current.currentQueueIndex + 1).coerceAtMost(current.queue.lastIndex)
            mutableState.value = current.copy(
                currentQueueIndex = nextIndex,
                currentTrack = current.queue.getOrNull(nextIndex)?.track,
            )
        }

        override suspend fun skipPrevious() {
            skipPreviousCalls += 1
            val current = mutableState.value
            val prevIndex = (current.currentQueueIndex - 1).coerceAtLeast(0)
            mutableState.value = current.copy(
                currentQueueIndex = prevIndex,
                currentTrack = current.queue.getOrNull(prevIndex)?.track,
            )
        }

        override suspend fun resume() {
            resumeGate.await()
            mutableState.value = mutableState.value.copy(
                playbackStatus = PlaybackStatus.PLAYING,
                isPlaying = true,
            )
        }

        override suspend fun pause() {
            pauseGate.await()
            mutableState.value = mutableState.value.copy(
                playbackStatus = PlaybackStatus.PAUSED,
                isPlaying = false,
            )
        }

        override suspend fun seekTo(positionMs: Long) {
            seekRequests += positionMs
            seekGate.await()
            mutableState.value = mutableState.value.copy(positionMs = positionMs)
        }

        override suspend fun removeQueueItem(queueId: String) {
            val current = mutableState.value
            mutableState.value = current.copy(
                queue = current.queue.filterNot { it.queueId == queueId },
            )
        }

        override suspend fun moveQueueItem(fromIndex: Int, toIndex: Int) {
            val current = mutableState.value
            val mutableQueue = current.queue.toMutableList()
            val item = mutableQueue.removeAt(fromIndex)
            mutableQueue.add(toIndex, item)
            mutableState.value = current.copy(queue = mutableQueue)
        }

        override suspend fun jumpToQueueItem(index: Int) {
            val current = mutableState.value
            if (index !in current.queue.indices || index == current.currentQueueIndex) return
            mutableState.value = current.copy(
                currentQueueIndex = index,
                currentTrack = current.queue[index].track,
            )
        }

        override suspend fun setShuffleMode(enabled: Boolean) {
            shuffleCalls += enabled
            mutableState.value = mutableState.value.copy(shuffleOn = enabled)
        }

        override suspend fun setRepeatMode(mode: Int) {
            repeatCalls += mode
            mutableState.value = mutableState.value.copy(repeatMode = mode)
        }

        override suspend fun setPlaybackSpeed(speed: Float) {
            mutableState.value = mutableState.value.copy(playbackSpeed = speed)
        }

        override suspend fun restoreFromSnapshot(snapshot: PlayerSnapshotEntity) = Unit
        override suspend fun ensureRestored() = Unit
    }

    /**
     * Records each haptic method invocation count so CR3 tests can assert
     * exact call counts without relying on a mocking library.
     */
    private class RecordingHapticsController : HapticsController {
        var playPauseCalls = 0
            private set
        var skipCalls = 0
            private set
        var queueAddCalls = 0
            private set
        var playNextCalls = 0
            private set

        override fun onPlayPause() { playPauseCalls++ }
        override fun onSkip() { skipCalls++ }
        override fun onQueueAdd() { queueAddCalls++ }
        override fun onPlayNext() { playNextCalls++ }
    }

    private object NoOpHapticsController : HapticsController {
        override fun onPlayPause() = Unit
        override fun onSkip() = Unit
        override fun onQueueAdd() = Unit
        override fun onPlayNext() = Unit
    }

    private class NoOpSleepTimerController : SleepTimerController {
        override val timerState = MutableStateFlow<SleepTimerState>(SleepTimerState.Inactive)
        override fun attach(scope: kotlinx.coroutines.CoroutineScope) = Unit
        override fun detach() = Unit
        override fun setTimer(preset: SleepTimerPreset) = Unit
        override fun cancel() = Unit
    }

    private object NoOpLogger : Logger {
        override fun debug(tag: String, message: String) = Unit
        override fun info(tag: String, message: String) = Unit
        override fun warn(tag: String, message: String, throwable: Throwable?) = Unit
        override fun error(tag: String, message: String, throwable: Throwable?) = Unit
    }

    /**
     * The perf tracer is a behavioural side-channel for `YT_PERF` log lines —
     * tests don't assert on those, so we route through a no-op logger to
     * keep test output clean while preserving the production call shape.
     */
    private val NoOpPerfTracer: PlaybackPerfTracer = PlaybackPerfTracer(NoOpLogger)

    companion object {
        private val track = Track(
            videoId = "alpha123",
            title = "Late Night Coding Mix",
            channel = "Open Waves",
            durationSec = 3723,
            thumbnailUrl = "",
        )
        private val secondTrack = Track(
            videoId = "beta456",
            title = "Focus Session",
            channel = "Desk Radio",
            durationSec = 1800,
            thumbnailUrl = "",
        )
        private val thirdTrack = Track(
            videoId = "gamma789",
            title = "Sunset Drive",
            channel = "Highway Tape",
            durationSec = 1200,
            thumbnailUrl = "",
        )

        private val playingState = PlayerState(
            currentTrack = track,
            queue = listOf(
                QueueItem(track = track, queueId = "queue-1"),
                QueueItem(track = secondTrack, queueId = "queue-2"),
            ),
            currentQueueIndex = 0,
            playbackStatus = PlaybackStatus.PLAYING,
            isPlaying = true,
            durationMs = track.durationSec * 1000L,
        )
    }
}
