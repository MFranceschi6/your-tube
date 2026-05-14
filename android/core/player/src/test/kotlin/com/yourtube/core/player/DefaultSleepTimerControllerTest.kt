package com.yourtube.core.player

import com.yourtube.core.common.model.PlaybackStatus
import com.yourtube.core.common.model.PlayerState
import com.yourtube.core.common.model.SleepTimerPreset
import com.yourtube.core.common.model.SleepTimerState
import com.yourtube.core.common.model.Track
import com.yourtube.core.database.entity.PlayerSnapshotEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultSleepTimerControllerTest {

    private val dispatcher = StandardTestDispatcher()

    // ── Fake PlayerController ──────────────────────────────────────────────────────────────

    private inner class FakePlayerController : PlayerController {
        val pauseCalls = mutableListOf<Unit>()

        private val _playerState = MutableStateFlow(PlayerState())
        override val playerState: StateFlow<PlayerState> = _playerState

        fun setVideoId(videoId: String?) {
            val track = videoId?.let { Track(it, "Track $it", "Ch", 180, "") }
            _playerState.value = _playerState.value.copy(
                currentTrack = track,
                playbackStatus = if (track != null) PlaybackStatus.PLAYING else PlaybackStatus.IDLE,
            )
        }

        override suspend fun pause() { pauseCalls += Unit }
        override suspend fun resume() = Unit
        override suspend fun playNow(track: Track) = Unit
        override suspend fun setQueueAndPlay(tracks: List<Track>, startIndex: Int) = Unit
        override suspend fun addToQueue(track: Track) = Unit
        override suspend fun playNext(track: Track) = Unit
        override suspend fun skipNext() = Unit
        override suspend fun skipPrevious() = Unit
        override suspend fun seekTo(positionMs: Long) = Unit
        override suspend fun removeQueueItem(queueId: String) = Unit
        override suspend fun moveQueueItem(fromIndex: Int, toIndex: Int) = Unit
        override suspend fun jumpToQueueItem(index: Int) = Unit
        override suspend fun setShuffleMode(enabled: Boolean) = Unit
        override suspend fun setRepeatMode(mode: Int) = Unit
        override suspend fun setPlaybackSpeed(speed: Float) = Unit
        override suspend fun restoreFromSnapshot(snapshot: PlayerSnapshotEntity) = Unit
        override suspend fun ensureRestored() = Unit
    }

    private fun makeController(
        playerController: PlayerController = FakePlayerController(),
        testScope: TestScope,
    ): DefaultSleepTimerController {
        val ctrl = DefaultSleepTimerController(playerController)
        // Use a scope bound to the test's dispatcher so advanceTimeBy / runCurrent
        // controls both the test body and the timer coroutines deterministically.
        val timerScope = CoroutineScope(testScope.coroutineContext + SupervisorJob())
        ctrl.attach(timerScope)
        return ctrl
    }

    // ── Tests ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `setTimer emits Active state with correct preset`() = runTest(dispatcher) {
        val controller = makeController(testScope = this)

        controller.setTimer(SleepTimerPreset.Min15)

        val state = controller.timerState.value
        assertIs<SleepTimerState.Active>(state)
        assertEquals(SleepTimerPreset.Min15, state.preset)
        assertEquals(SleepTimerPreset.Min15.durationMs, state.remainingMs)
    }

    @Test
    fun `cancel returns to Inactive`() = runTest(dispatcher) {
        val controller = makeController(testScope = this)

        controller.setTimer(SleepTimerPreset.Min30)
        assertIs<SleepTimerState.Active>(controller.timerState.value)

        controller.cancel()

        assertIs<SleepTimerState.Inactive>(controller.timerState.value)
    }

    @Test
    fun `after duration elapses playerController pause is called`() = runTest(dispatcher) {
        val fakePlayer = FakePlayerController()
        val controller = makeController(fakePlayer, testScope = this)

        controller.setTimer(SleepTimerPreset.Min15)

        // Advance past the full 15-minute duration.
        advanceTimeBy(SleepTimerPreset.Min15.durationMs + DefaultSleepTimerController.TICK_MS)
        runCurrent()

        assertEquals(1, fakePlayer.pauseCalls.size)
        assertIs<SleepTimerState.Inactive>(controller.timerState.value)
    }

    @Test
    fun `setting new timer while one is active cancels the old one`() = runTest(dispatcher) {
        val fakePlayer = FakePlayerController()
        val controller = makeController(fakePlayer, testScope = this)

        // Start a 60-minute timer.
        controller.setTimer(SleepTimerPreset.Min60)

        // Advance only partway — 5 minutes in, no fire yet.
        advanceTimeBy(5 * 60 * 1_000L)
        runCurrent()
        assertIs<SleepTimerState.Active>(controller.timerState.value)
        assertEquals(0, fakePlayer.pauseCalls.size)

        // Replace with a 15-minute timer at the 5-min mark.
        controller.setTimer(SleepTimerPreset.Min15)

        // Advance to just past the 60-minute original deadline but only
        // 14 minutes past the new timer's start — the old timer must NOT have fired
        // (it was cancelled) and the new 15-min timer must not have fired yet (14 min elapsed).
        advanceTimeBy(14 * 60 * 1_000L)
        runCurrent()
        // Neither old nor new timer should have fired yet.
        assertEquals(0, fakePlayer.pauseCalls.size)

        // Advance the remaining 1 second beyond the 15-minute mark.
        advanceTimeBy(60 * 1_000L + DefaultSleepTimerController.TICK_MS)
        runCurrent()

        // Only the new 15-min timer fires exactly once.
        assertEquals(1, fakePlayer.pauseCalls.size)
        assertIs<SleepTimerState.Inactive>(controller.timerState.value)
    }

    @Test
    fun `countdown updates remainingMs at each tick`() = runTest(dispatcher) {
        val controller = makeController(testScope = this)

        controller.setTimer(SleepTimerPreset.Min15)

        // Initial state: full duration.
        val initial = controller.timerState.value as SleepTimerState.Active
        assertEquals(SleepTimerPreset.Min15.durationMs, initial.remainingMs)

        // Advance two tick intervals.
        advanceTimeBy(DefaultSleepTimerController.TICK_MS * 2)
        runCurrent()

        val updated = controller.timerState.value as SleepTimerState.Active
        assertEquals(
            SleepTimerPreset.Min15.durationMs - DefaultSleepTimerController.TICK_MS * 2,
            updated.remainingMs,
        )
    }

    @Test
    fun `EndOfTrack preset fires when video ID changes`() = runTest(dispatcher) {
        val fakePlayer = FakePlayerController()
        fakePlayer.setVideoId("track-a")

        val controller = makeController(fakePlayer, testScope = this)

        controller.setTimer(SleepTimerPreset.EndOfTrack)
        // Allow the background coroutine + launchIn to start collecting.
        advanceUntilIdle()
        runCurrent()
        assertEquals(0, fakePlayer.pauseCalls.size)

        // Simulate track change: video ID changes → the flow's onEach should fire.
        fakePlayer.setVideoId("track-b")
        // Allow the flow emission to be dispatched and processed.
        advanceUntilIdle()
        runCurrent()

        assertEquals(1, fakePlayer.pauseCalls.size)
        assertIs<SleepTimerState.Inactive>(controller.timerState.value)
    }

    @Test
    fun `EndOfTrack preset fires on manual skipNext while armed`() = runTest(dispatcher) {
        // Pin the intended behavior: a user-initiated skip (videoId change from "trackA" to
        // "trackB") fires the EndOfTrack timer just like auto-advance does. The timer does not
        // distinguish between auto-advance, queue manipulation, and user-initiated skips —
        // any track boundary is sufficient.
        val fakePlayer = FakePlayerController()
        fakePlayer.setVideoId("trackA")

        val controller = makeController(fakePlayer, testScope = this)

        controller.setTimer(SleepTimerPreset.EndOfTrack)
        // Allow the background coroutine + launchIn to start collecting.
        advanceUntilIdle()
        runCurrent()
        assertEquals(0, fakePlayer.pauseCalls.size)
        assertIs<SleepTimerState.Active>(controller.timerState.value)

        // Simulate a manual skipNext by changing the videoId to a new track.
        fakePlayer.setVideoId("trackB")
        advanceUntilIdle()
        runCurrent()

        // Timer must fire: pause called once, state back to Inactive.
        assertEquals(1, fakePlayer.pauseCalls.size)
        assertIs<SleepTimerState.Inactive>(controller.timerState.value)
    }

    @Test
    fun `EndOfTrack preset is Inactive after cancel`() = runTest(dispatcher) {
        val fakePlayer = FakePlayerController()
        fakePlayer.setVideoId("track-a")

        val controller = makeController(fakePlayer, testScope = this)

        controller.setTimer(SleepTimerPreset.EndOfTrack)
        assertIs<SleepTimerState.Active>(controller.timerState.value)

        controller.cancel()

        assertIs<SleepTimerState.Inactive>(controller.timerState.value)
        // Changing the track after cancel must NOT call pause.
        fakePlayer.setVideoId("track-b")
        advanceUntilIdle()
        assertEquals(0, fakePlayer.pauseCalls.size)
    }

    @Test
    fun `detach cancels active timer and clears state`() = runTest(dispatcher) {
        val fakePlayer = FakePlayerController()
        val controller = makeController(fakePlayer, testScope = this)

        controller.setTimer(SleepTimerPreset.Min15)
        assertIs<SleepTimerState.Active>(controller.timerState.value)

        controller.detach()

        assertIs<SleepTimerState.Inactive>(controller.timerState.value)
        // Timer no longer fires after detach.
        advanceTimeBy(SleepTimerPreset.Min15.durationMs + DefaultSleepTimerController.TICK_MS)
        runCurrent()
        assertEquals(0, fakePlayer.pauseCalls.size)
    }
}
