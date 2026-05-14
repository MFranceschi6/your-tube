package com.yourtube.core.player

import com.yourtube.core.common.model.PlaybackStatus
import com.yourtube.core.common.model.Playlist
import com.yourtube.core.common.model.PlayerState
import com.yourtube.core.common.model.SearchResult
import com.yourtube.core.common.model.SleepTimerPreset
import com.yourtube.core.common.model.SleepTimerState
import com.yourtube.core.common.model.Track
import com.yourtube.core.data.model.PlaybackHistoryEntry
import com.yourtube.core.data.preferences.AudioQualityPreferences
import com.yourtube.core.data.preferences.PlaybackSpeedPreferences
import com.yourtube.core.data.repository.PlayerSnapshotRepository
import com.yourtube.core.data.repository.AddTrackResult
import com.yourtube.core.data.repository.PlaylistRepository
import com.yourtube.core.database.entity.PlayerSnapshotEntity
import com.yourtube.core.network.MixPage
import com.yourtube.core.network.ResolvedAudioStream
import com.yourtube.core.network.YoutubeService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
    private val playbackSpeedPreferences = FakePlaybackSpeedPreferences()
    private val playlistRepository = FakePlaylistRepository()
    private val snapshotRepository = FakePlayerSnapshotRepository()
    private val perfTracer = PlaybackPerfTracer(NoOpLogger)

    private fun makeController(
        transport: PlaybackTransport = FakePlaybackTransport(),
        autoplayController: AutoplayController = NoOpAutoplayController(),
        sleepTimer: SleepTimerController = FakeSleepTimerController(),
        youtubeService: YoutubeService = NoOpYoutubeService(),
    ) =
        DefaultPlayerController(
            playbackTransport = transport,
            dispatcher = dispatcher,
            audioQualityPreferences = audioQualityPreferences,
            playlistRepository = playlistRepository,
            perfTracer = perfTracer,
            playbackSpeedPreferences = playbackSpeedPreferences,
            playerSnapshotRepository = snapshotRepository,
            autoplayController = autoplayController,
            sleepTimerController = dagger.Lazy { sleepTimer },
            youtubeService = youtubeService,
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

    // YT-0311 supersedes YT-0239 round-3 on the idx=0 sub-threshold case.
    // Spec: position ≤ SKIP_BACK_RESTART_THRESHOLD_MS + currentQueueIndex == 0 → restart from 0.
    // (No previous track to jump to; restart is more useful than a silent no-op — YT-0311 smoke.)
    @Test
    fun `skipPrevious at idx zero with sub threshold position restarts current track`() =
        runTest(dispatcher) {
            val transport = FakePlaybackTransport()
            val controller = makeController(transport)

            // queue=[A], currentQueueIndex=0. Seek to 500 ms — well below
            // SKIP_BACK_RESTART_THRESHOLD_MS (3_000 ms). The idx=0 sub-threshold path
            // must restart the current track (seekTo 0) per user spec change in smoke.
            controller.playNow(trackOne)
            runCurrent()
            controller.seekTo(500L)
            advanceTimeBy(DefaultPlayerController.SEEK_DEBOUNCE_MS + 5L)
            runCurrent()
            assertEquals(500L, controller.playerState.value.positionMs)
            assertEquals(0, controller.playerState.value.currentQueueIndex)
            transport.events.clear()

            controller.skipPrevious()
            advanceTimeBy(DefaultPlayerController.SEEK_DEBOUNCE_MS + 5L)
            runCurrent()

            // Restart: seekTo(0), index unchanged, no playTrack.
            assertEquals(0L, controller.playerState.value.positionMs)
            assertTrue(transport.seekCalls.contains(0L))
            assertTrue(transport.events.none { it.startsWith("playTrack") })
            assertEquals(trackOne, controller.playerState.value.currentTrack)
            assertEquals(0, controller.playerState.value.currentQueueIndex)
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

    // YT-0193: a single seek issues one transport `seekTo(...)` and NO `pause()` / `resume()`.
    // This protects the bug surface user-reported as "moving the seek-bar slider stops audio":
    // any regression that adds a pause/resume bracket around the seek will fail this assertion.
    @Test
    fun `seekTo issues a single transport seekTo with no pause or resume`() = runTest(dispatcher) {
        val transport = FakePlaybackTransport()
        val controller = makeController(transport)

        controller.playNow(trackOne)
        runCurrent()
        // Drop the playTrack/stopAndClearCurrent bookkeeping so we focus on the seek path.
        transport.events.clear()

        controller.seekTo(7_000L)
        // Position updates synchronously for the slider thumb; transport dispatch is debounced.
        assertEquals(7_000L, controller.playerState.value.positionMs)
        advanceTimeBy(DefaultPlayerController.SEEK_DEBOUNCE_MS + 5L)
        runCurrent()

        assertEquals(listOf(7_000L), transport.seekCalls)
        assertEquals(0, transport.pauseCalls)
        assertEquals(0, transport.resumeCalls)
    }

    // YT-0193: rapid scrubber-driven seeks (NowPlaying slider's `onValueChange` fires every
    // drag-tick at up to ~60 Hz) must coalesce into a SINGLE engine-side dispatch with the
    // latest target. Otherwise we flood `MediaController.seekTo(...)`, push ExoPlayer into
    // `STATE_BUFFERING`, and the user perceives audio dropping out.
    @Test
    fun `rapid seekTo calls collapse to a single transport seekTo with the latest position`() =
        runTest(dispatcher) {
            val transport = FakePlaybackTransport()
            val controller = makeController(transport)

            controller.playNow(trackOne)
            runCurrent()
            transport.events.clear()

            // Simulate a drag burst: 6 frame-rate ticks within the debounce window.
            controller.seekTo(1_000L)
            advanceTimeBy(8L); runCurrent()
            controller.seekTo(2_000L)
            advanceTimeBy(8L); runCurrent()
            controller.seekTo(3_000L)
            advanceTimeBy(8L); runCurrent()
            controller.seekTo(4_000L)
            advanceTimeBy(8L); runCurrent()
            controller.seekTo(5_000L)
            advanceTimeBy(8L); runCurrent()
            controller.seekTo(6_000L)

            // Slider thumb tracks the user's finger every tick — positionMs reflects the
            // latest call SYNCHRONOUSLY so the UI does not lag the gesture.
            assertEquals(6_000L, controller.playerState.value.positionMs)

            // Drain the trailing debounce window. Engine-side, only one dispatch fires and
            // it carries the user's settle target.
            advanceTimeBy(DefaultPlayerController.SEEK_DEBOUNCE_MS + 5L)
            runCurrent()

            assertEquals(listOf(6_000L), transport.seekCalls)
            assertEquals(0, transport.pauseCalls)
            assertEquals(0, transport.resumeCalls)
        }

    // YT-0193: a fresh seek issued AFTER the debounce window of a previous seek has elapsed
    // is dispatched separately. The debounce must not "swallow" subsequent intentional seeks
    // (e.g. the user scrubs, releases, then later taps a different position on the bar).
    @Test
    fun `seeks separated by more than the debounce window dispatch independently`() =
        runTest(dispatcher) {
            val transport = FakePlaybackTransport()
            val controller = makeController(transport)

            controller.playNow(trackOne)
            runCurrent()
            transport.events.clear()

            controller.seekTo(2_000L)
            advanceTimeBy(DefaultPlayerController.SEEK_DEBOUNCE_MS + 5L)
            runCurrent()

            controller.seekTo(9_000L)
            advanceTimeBy(DefaultPlayerController.SEEK_DEBOUNCE_MS + 5L)
            runCurrent()

            assertEquals(listOf(2_000L, 9_000L), transport.seekCalls)
            assertEquals(0, transport.pauseCalls)
            assertEquals(0, transport.resumeCalls)
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
            override fun setListener(listener: PlaybackTransportListener?) = Unit
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
        // YT-0309 round-6: transport Failure now routes to PAUSED (same as timeout) per
        // AC#2. The Play button is re-tappable; engineLoaded=false re-enters playQueueItem.
        assertEquals(PlaybackStatus.PAUSED, state.playbackStatus)
        assertEquals(false, state.isPlaying)
        assertEquals(trackOne, state.currentTrack)
        assertEquals(false, state.engineLoaded)
    }

    // YT-0236: `playNext` from a fully empty controller (no current track AND empty
    // queue) surfaces the inserted track as the current PAUSED entry at index 0 so
    // the MiniPlayer (gated on `currentTrack != null`) appears. This replaces the
    // previous behavior where `currentQueueIndex` stayed at -1 and the user got no
    // visible feedback. See the dedicated empty-state tests below for the full
    // PAUSED-state assertions.
    @Test
    fun `playNext when currentQueueIndex is -1 surfaces track as paused current entry`() = runTest(dispatcher) {
        val controller = makeController()

        // Queue is empty, no track playing — index is -1
        controller.playNext(trackOne)

        val state = controller.playerState.value
        assertEquals(listOf(trackOne), state.queue.map { it.track })
        assertEquals(0, state.currentQueueIndex)
        assertEquals(trackOne, state.currentTrack)
        assertEquals(PlaybackStatus.PAUSED, state.playbackStatus)
    }

    // YT-0236: `addToQueue` from a fully empty controller (no current track AND empty
    // queue) surfaces the queued entry as the current PAUSED track at index 0 so the
    // MiniPlayer (gated on `currentTrack != null`) appears with the user's selection,
    // ready for an explicit play tap. We MUST NOT call playbackTransport.playTrack —
    // the semantic of "Add to Queue" is "queue this, don't play it now."
    @Test
    fun `addToQueue from empty queue and null currentTrack surfaces paused current track`() =
        runTest(dispatcher) {
            val transport = FakePlaybackTransport()
            val controller = makeController(transport)

            controller.addToQueue(trackOne)

            val state = controller.playerState.value
            assertEquals(trackOne, state.currentTrack)
            assertEquals(0, state.currentQueueIndex)
            assertEquals(PlaybackStatus.PAUSED, state.playbackStatus)
            assertEquals(false, state.isPlaying)
            assertEquals(trackOne.durationSec * 1000L, state.durationMs)
            assertEquals(0L, state.positionMs)
            assertEquals(listOf(trackOne), state.queue.map { it.track })
            // No transport playback was triggered — "Add to Queue" never autoplays.
            assertEquals(false, transport.events.contains("playTrack"))
        }

    // YT-0236: `addToQueue` with an already-playing current track is the established
    // path — must keep its current behavior (append-only, no disturbance to the
    // currently playing entry / playbackStatus).
    @Test
    fun `addToQueue with existing playing current track only appends`() = runTest(dispatcher) {
        val transport = FakePlaybackTransport()
        val controller = makeController(transport)

        controller.playNow(trackOne)
        runCurrent()
        // Sanity: trackOne is current and PLAYING before we exercise the append path.
        assertEquals(trackOne, controller.playerState.value.currentTrack)
        assertEquals(PlaybackStatus.PLAYING, controller.playerState.value.playbackStatus)
        assertEquals(0, controller.playerState.value.currentQueueIndex)
        val queueSizeBefore = controller.playerState.value.queue.size

        controller.addToQueue(trackTwo)

        val state = controller.playerState.value
        // Current track / index / status untouched by the append.
        assertEquals(trackOne, state.currentTrack)
        assertEquals(0, state.currentQueueIndex)
        assertEquals(PlaybackStatus.PLAYING, state.playbackStatus)
        // Queue grew by exactly one and the new entry is at the end.
        assertEquals(queueSizeBefore + 1, state.queue.size)
        assertEquals(trackTwo, state.queue.last().track)
    }

    // YT-0236: `playNext` empty-controller edge case — same paused surfacing as
    // `addToQueue` so the user sees the inserted entry in the MiniPlayer.
    @Test
    fun `playNext from empty queue and null currentTrack surfaces paused current track`() =
        runTest(dispatcher) {
            val transport = FakePlaybackTransport()
            val controller = makeController(transport)

            controller.playNext(trackOne)

            val state = controller.playerState.value
            assertEquals(trackOne, state.currentTrack)
            assertEquals(0, state.currentQueueIndex)
            assertEquals(PlaybackStatus.PAUSED, state.playbackStatus)
            assertEquals(false, state.isPlaying)
            assertEquals(trackOne.durationSec * 1000L, state.durationMs)
            assertEquals(0L, state.positionMs)
            assertEquals(listOf(trackOne), state.queue.map { it.track })
            // No transport playback was triggered — `playNext` queues, never autoplays.
            assertEquals(false, transport.events.contains("playTrack"))
        }

    // YT-0236: `playNext` with an already-playing current track is the established
    // insert-after-current path — must keep its current behavior (currentTrack /
    // currentQueueIndex / playbackStatus untouched, queue grows by one).
    @Test
    fun `playNext with existing playing current track only inserts after current`() =
        runTest(dispatcher) {
            val transport = FakePlaybackTransport()
            val controller = makeController(transport)

            controller.playNow(trackOne)
            runCurrent()
            assertEquals(trackOne, controller.playerState.value.currentTrack)
            assertEquals(PlaybackStatus.PLAYING, controller.playerState.value.playbackStatus)
            assertEquals(0, controller.playerState.value.currentQueueIndex)
            val queueSizeBefore = controller.playerState.value.queue.size

            controller.playNext(trackTwo)

            val state = controller.playerState.value
            assertEquals(trackOne, state.currentTrack)
            assertEquals(0, state.currentQueueIndex)
            assertEquals(PlaybackStatus.PLAYING, state.playbackStatus)
            assertEquals(queueSizeBefore + 1, state.queue.size)
            // playNext inserts AFTER the current entry — i.e. at index 1 here.
            assertEquals(trackTwo, state.queue[1].track)
        }

    // YT-0236 (re-fix 2026-05-08T22:35): regression for the reviewer-led manual smoke
    // bug. When `addToQueue` from a fully empty controller surfaces a paused current
    // track, the underlying ExoPlayer timeline is empty — there is NO MediaItem loaded
    // for the staged track. Tap-play (`controller.resume()`) MUST bootstrap engine
    // playback via `playQueueItem(...)` rather than forwarding to
    // `playbackTransport.resume()` (which would run `MediaController.play()` against
    // an empty timeline → STATE_ENDED → `handleTrackEnded()` → IDLE with
    // `positionMs = durationMs`; audio never starts and the slider jumps to the end).
    //
    // We use `runCurrent()` rather than `advanceUntilIdle()`: the bootstrap path starts
    // the YT-0182 progress loop (`while (isActive) delay(1000)`), so unbounded virtual
    // time advance would tick `positionMs` past `durationMs`, settle to PAUSED, and
    // mask the regression.
    @Test
    fun `resume after addToQueue from empty bootstraps engine via playTrack not resume`() =
        runTest(dispatcher) {
            val transport = FakePlaybackTransport()
            val controller = makeController(transport)

            controller.addToQueue(trackOne)
            runCurrent()
            // Sanity: empty-state edge surfaced the paused current entry without any
            // engine-side play call.
            assertEquals(PlaybackStatus.PAUSED, controller.playerState.value.playbackStatus)
            assertEquals(false, transport.events.contains("playTrack"))
            transport.events.clear()

            controller.resume()
            runCurrent()

            val state = controller.playerState.value
            assertEquals(PlaybackStatus.PLAYING, state.playbackStatus)
            assertEquals(true, state.isPlaying)
            assertEquals(trackOne, state.currentTrack)
            // Position MUST stay at 0 — the broken path settled to durationMs via
            // `handleTrackEnded()` after STATE_ENDED on an empty timeline.
            assertEquals(0L, state.positionMs)
            // Engine bootstrap path: `playQueueItem(...)` issues stopAndClearCurrent
            // followed by playTrack, NOT a bare transport.resume().
            // YT-0095: after a successful load, setPlaybackSpeed is also called with the
            // persisted speed (default 1.0) to re-apply the global speed preference.
            assertEquals(
                listOf("stopAndClearCurrent", "playTrack", "setPlaybackSpeed(1.0)"),
                transport.events,
            )
            assertEquals(0, transport.resumeCalls)
        }

    // YT-0236 (re-fix 2026-05-08T22:35): same regression on the `playNext` empty-state
    // edge. Both paths surface a paused current track without pushing a MediaItem onto
    // the engine, so tap-play must take the bootstrap branch.
    @Test
    fun `resume after playNext from empty bootstraps engine via playTrack not resume`() =
        runTest(dispatcher) {
            val transport = FakePlaybackTransport()
            val controller = makeController(transport)

            controller.playNext(trackOne)
            runCurrent()
            assertEquals(PlaybackStatus.PAUSED, controller.playerState.value.playbackStatus)
            assertEquals(false, transport.events.contains("playTrack"))
            transport.events.clear()

            controller.resume()
            runCurrent()

            val state = controller.playerState.value
            assertEquals(PlaybackStatus.PLAYING, state.playbackStatus)
            assertEquals(true, state.isPlaying)
            assertEquals(trackOne, state.currentTrack)
            assertEquals(0L, state.positionMs)
            // YT-0095: setPlaybackSpeed(1.0) follows playTrack on successful load.
            assertEquals(
                listOf("stopAndClearCurrent", "playTrack", "setPlaybackSpeed(1.0)"),
                transport.events,
            )
            assertEquals(0, transport.resumeCalls)
        }

    // YT-0236 (re-fix 2026-05-08T22:35): once the engine has loaded a MediaItem for
    // the current track (i.e. after a successful `playQueueItem(...)` on `playNow`),
    // `pause()` followed by `resume()` MUST forward to `playbackTransport.resume()`
    // and NOT re-bootstrap via playTrack. This pins the "paused-after-playing" branch
    // and protects the YT-0193 / YT-0050 contracts from being clobbered by the YT-0236
    // re-fix.
    @Test
    fun `resume after pause on engine-loaded track forwards to transport resume`() =
        runTest(dispatcher) {
            val transport = FakePlaybackTransport()
            val controller = makeController(transport)

            controller.playNow(trackOne)
            runCurrent()
            controller.pause()
            runCurrent()
            assertEquals(PlaybackStatus.PAUSED, controller.playerState.value.playbackStatus)
            transport.events.clear()

            controller.resume()
            runCurrent()

            assertEquals(PlaybackStatus.PLAYING, controller.playerState.value.playbackStatus)
            // Engine already had the MediaItem loaded — bare resume is correct here.
            assertEquals(listOf("resume"), transport.events)
            assertEquals(false, transport.events.contains("playTrack"))
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
    //
    // NOTE: uses runCurrent() rather than advanceUntilIdle() so virtual time is NOT
    // advanced past the YT-0309 STREAM_RESOLVE_TIMEOUT_MS (15 s). advanceUntilIdle()
    // would drain all time-scheduled tasks including the withTimeoutOrNull deadline,
    // transitioning the status from LOADING to PAUSED before the assertion below runs.
    @Test
    fun `playNow stops and clears transport before stream resolution suspends`() =
        runTest(dispatcher) {
            val transport = SuspendingPlaybackTransport()
            val controller = makeController(transport)

            // Launch playNow so the suspending playTrack does not block runTest.
            val job = launch { controller.playNow(trackOne) }
            runCurrent()

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

    // YT-0155: setQueueAndPlay replaces the queue atomically and starts at startIndex.
    @Test
    fun `setQueueAndPlay starts at the requested index with full queue installed`() =
        runTest(dispatcher) {
            val controller = makeController()

            controller.setQueueAndPlay(listOf(trackOne, trackTwo, trackThree), startIndex = 1)
            runCurrent()

            val state = controller.playerState.value
            assertEquals(
                listOf(trackOne, trackTwo, trackThree),
                state.queue.map { it.track },
            )
            assertEquals(1, state.currentQueueIndex)
            assertEquals(trackTwo, state.currentTrack)
        }

    @Test
    fun `setQueueAndPlay clamps an out-of-range startIndex into the list`() =
        runTest(dispatcher) {
            val controller = makeController()

            controller.setQueueAndPlay(listOf(trackOne, trackTwo, trackThree), startIndex = 99)
            runCurrent()

            val state = controller.playerState.value
            assertEquals(2, state.currentQueueIndex)
            assertEquals(trackThree, state.currentTrack)
        }

    @Test
    fun `setQueueAndPlay with empty input is a no-op`() = runTest(dispatcher) {
        val controller = makeController()
        controller.playNow(trackOne)
        runCurrent()

        controller.setQueueAndPlay(emptyList(), startIndex = 0)
        runCurrent()

        val state = controller.playerState.value
        assertEquals(trackOne, state.currentTrack)
        assertEquals(1, state.queue.size)
    }

    // YT-0238: skipNext must advance the queue index by EXACTLY ONE position, regardless
    // of queue size. Regression test for the cascade where `stopAndClearCurrent` ->
    // `clearMediaItems()` would drop `mediaItemCount` to 0, fire STATE_ENDED, route to
    // `onTrackEnded`, and recursively call `playQueueItemAt(currentIndex + 1)` — taking
    // the user from idx=0 straight to the LAST queue entry on a single tap.
    @Test
    fun `skipNext from idx 0 in a queue of three lands on idx 1 not the last entry`() =
        runTest(dispatcher) {
            val transport = FakePlaybackTransport()
            val controller = makeController(transport)

            controller.setQueueAndPlay(
                listOf(trackOne, trackTwo, trackThree),
                startIndex = 0,
            )
            advanceUntilIdle()

            controller.skipNext()
            advanceUntilIdle()

            val state = controller.playerState.value
            assertEquals(1, state.currentQueueIndex)
            assertEquals(trackTwo, state.currentTrack)
        }

    // YT-0238: same guarantee on a longer queue. A single skipNext from idx=0 must land on
    // idx=1, NOT idx=4 (the cascade end-state on a 5-track queue).
    @Test
    fun `skipNext from idx 0 in a queue of five lands on idx 1 not idx 4`() =
        runTest(dispatcher) {
            val transport = FakePlaybackTransport()
            val controller = makeController(transport)

            val trackFour = Track("four", "Track Four", "Channel D", 210, "")
            val trackFive = Track("five", "Track Five", "Channel E", 220, "")
            controller.setQueueAndPlay(
                listOf(trackOne, trackTwo, trackThree, trackFour, trackFive),
                startIndex = 0,
            )
            advanceUntilIdle()

            controller.skipNext()
            advanceUntilIdle()

            val state = controller.playerState.value
            assertEquals(1, state.currentQueueIndex)
            assertEquals(trackTwo, state.currentTrack)
        }

    // YT-0238: `stopAndClearCurrent` must NOT clear the timeline. We assert via the
    // transport-level event log: across a full skipNext, the controller never asks the
    // transport for a `clearMediaItems`-style operation. The fake's `clearMediaItemsCount`
    // is wired to 0 by construction — this test exists to fail loudly if a future change
    // re-introduces a clear-timeline call on the skip path.
    @Test
    fun `skipNext does not trigger clearMediaItems on the transport`() =
        runTest(dispatcher) {
            val transport = FakePlaybackTransport()
            val controller = makeController(transport)

            controller.setQueueAndPlay(
                listOf(trackOne, trackTwo, trackThree),
                startIndex = 0,
            )
            advanceUntilIdle()

            controller.skipNext()
            advanceUntilIdle()

            assertEquals(0, transport.clearMediaItemsCount)
            assertEquals(
                false,
                transport.events.any { it.contains("clearMediaItems", ignoreCase = true) },
            )
        }

    // YT-0182: STATE_ENDED with a next queue item must auto-advance the controller.
    @Test
    fun `transport onTrackEnded auto-advances to next queue item`() = runTest(dispatcher) {
        val transport = FakePlaybackTransport()
        val controller = makeController(transport)

        controller.playNow(trackOne)
        controller.addToQueue(trackTwo)
        controller.addToQueue(trackThree)
        runCurrent()

        transport.listener?.onTrackEnded()
        runCurrent()

        val state = controller.playerState.value
        assertEquals(1, state.currentQueueIndex)
        assertEquals(trackTwo, state.currentTrack)
    }

    // YT-0182: STATE_ENDED on the last queue item must settle into IDLE/non-playing.
    @Test
    fun `transport onTrackEnded with no next item flips to IDLE and stops`() = runTest(dispatcher) {
        val transport = FakePlaybackTransport()
        val controller = makeController(transport)

        controller.playNow(trackOne)
        runCurrent()

        transport.listener?.onTrackEnded()
        runCurrent()

        val state = controller.playerState.value
        assertEquals(false, state.isPlaying)
        assertEquals(PlaybackStatus.IDLE, state.playbackStatus)
    }

    // YT-0150: a Player-side media transition (lock-screen / notification skip) must
    // resync `currentQueueIndex` + `currentTrack` to the queue entry whose `videoId`
    // matches the transition's `mediaId`. The lookup matches by `mediaId == videoId`,
    // not timeline index — the queue can be re-ordered while the timeline lags.
    @Test
    fun `transport onMediaItemTransition resyncs queue index and currentTrack`() = runTest(dispatcher) {
        val transport = FakePlaybackTransport()
        val controller = makeController(transport)

        controller.playNow(trackOne)
        controller.addToQueue(trackTwo)
        controller.addToQueue(trackThree)
        runCurrent()
        assertEquals(0, controller.playerState.value.currentQueueIndex)
        assertEquals(trackOne, controller.playerState.value.currentTrack)

        // Lock-screen skip-next: player advances to the next media item; the
        // transport forwards the new `mediaId` (= trackTwo.videoId).
        transport.listener?.onMediaItemTransition(trackTwo.videoId)
        runCurrent()

        var state = controller.playerState.value
        assertEquals(1, state.currentQueueIndex)
        assertEquals(trackTwo, state.currentTrack)
        assertEquals(0L, state.positionMs)

        // Lock-screen skip-next again — landing on the third entry.
        transport.listener?.onMediaItemTransition(trackThree.videoId)
        runCurrent()

        state = controller.playerState.value
        assertEquals(2, state.currentQueueIndex)
        assertEquals(trackThree, state.currentTrack)
    }

    // YT-0150: a transition whose `mediaId` matches the current queue entry (e.g. the
    // feedback echo from an in-app skip that already mutated PlayerState) must be a
    // no-op so it does not clobber freshly-written fields like `positionMs`.
    @Test
    fun `transport onMediaItemTransition is idempotent for current queue index`() = runTest(dispatcher) {
        val transport = FakePlaybackTransport()
        val controller = makeController(transport)

        controller.playNow(trackOne)
        controller.addToQueue(trackTwo)
        runCurrent()
        // Progress ticks every 1_000 ms; advance two ticks so positionMs has drifted.
        advanceTimeBy(2_000L)
        runCurrent()
        val positionBeforeEcho = controller.playerState.value.positionMs
        assertEquals(2_000L, positionBeforeEcho)

        transport.listener?.onMediaItemTransition(trackOne.videoId)
        runCurrent()

        val state = controller.playerState.value
        assertEquals(0, state.currentQueueIndex)
        assertEquals(trackOne, state.currentTrack)
        // Position is preserved — idempotent transition must not reset progress.
        assertEquals(2_000L, state.positionMs)
    }

    // YT-0150: transitions for unknown `mediaId` (or null) leave PlayerState unchanged.
    @Test
    fun `transport onMediaItemTransition ignores unknown mediaId`() = runTest(dispatcher) {
        val transport = FakePlaybackTransport()
        val controller = makeController(transport)

        controller.playNow(trackOne)
        controller.addToQueue(trackTwo)
        runCurrent()

        transport.listener?.onMediaItemTransition("not-in-queue")
        transport.listener?.onMediaItemTransition(null)
        runCurrent()

        val state = controller.playerState.value
        assertEquals(0, state.currentQueueIndex)
        assertEquals(trackOne, state.currentTrack)
    }

    // YT-0150: an external seek (lock-screen slider, system shell) drives
    // `Player.Listener.onPositionDiscontinuity` which the transport forwards via
    // `onPositionChanged(...)`. The controller must update `PlayerState.positionMs`
    // so the in-app NowPlaying scrubber re-syncs with the engine on unlock.
    @Test
    fun `transport onPositionChanged updates PlayerState positionMs`() = runTest(dispatcher) {
        val transport = FakePlaybackTransport()
        val controller = makeController(transport)

        controller.playNow(trackOne)
        runCurrent()
        // Sanity: trackOne.durationSec = 180, so durationMs = 180_000 — the
        // target is well within the clamp window.
        assertEquals(0L, controller.playerState.value.positionMs)

        transport.listener?.onPositionChanged(45_000L)
        runCurrent()

        assertEquals(45_000L, controller.playerState.value.positionMs)
    }

    // YT-0150: a transport echo carrying the same position already in state must
    // be a no-op so the in-app `seekTo(...)` feedback echo does not clobber a
    // still-active drag (in-app seek writes positionMs synchronously, then the
    // engine echoes back the identical value via `onPositionDiscontinuity`).
    @Test
    fun `transport onPositionChanged is idempotent for the current position`() = runTest(dispatcher) {
        val transport = FakePlaybackTransport()
        val controller = makeController(transport)

        controller.playNow(trackOne)
        runCurrent()
        // Establish a non-default current position via the in-app seek path —
        // this also exercises the realistic case where the echo comes back
        // carrying the value the user just dragged to.
        controller.seekTo(12_000L)
        runCurrent()
        assertEquals(12_000L, controller.playerState.value.positionMs)

        transport.listener?.onPositionChanged(12_000L)
        runCurrent()

        // Same positionMs reference — no state churn for downstream collectors.
        assertEquals(12_000L, controller.playerState.value.positionMs)
    }

    // YT-0150: out-of-range positions reported by the engine (e.g. negative or
    // past the end of the resolved track during a reconcile window) must be
    // clamped to `[0, durationMs]` before being written back into state.
    @Test
    fun `transport onPositionChanged clamps out of range positions`() = runTest(dispatcher) {
        val transport = FakePlaybackTransport()
        val controller = makeController(transport)

        controller.playNow(trackOne)
        runCurrent()
        val durationMs = controller.playerState.value.durationMs
        // Sanity: trackOne.durationSec = 180, so durationMs = 180_000. The two
        // out-of-range probes below straddle that window.
        assertEquals(180_000L, durationMs)

        transport.listener?.onPositionChanged(-5_000L)
        runCurrent()
        assertEquals(0L, controller.playerState.value.positionMs)

        transport.listener?.onPositionChanged(durationMs + 30_000L)
        runCurrent()
        assertEquals(durationMs, controller.playerState.value.positionMs)
    }

    // YT-0185: lock-screen pause flips PlayerState to paused; resume restores PLAYING.
    @Test
    fun `transport onIsPlayingChanged mirrors paused and resumed states`() = runTest(dispatcher) {
        val transport = FakePlaybackTransport()
        val controller = makeController(transport)

        controller.playNow(trackOne)
        runCurrent()
        assertEquals(true, controller.playerState.value.isPlaying)

        transport.listener?.onIsPlayingChanged(false)
        var state = controller.playerState.value
        assertEquals(false, state.isPlaying)
        assertEquals(PlaybackStatus.PAUSED, state.playbackStatus)

        transport.listener?.onIsPlayingChanged(true)
        state = controller.playerState.value
        assertEquals(true, state.isPlaying)
        assertEquals(PlaybackStatus.PLAYING, state.playbackStatus)
    }

    @Test
    fun `failed playback does not record history`() = runTest(dispatcher) {
        val failingTransport = object : PlaybackTransport {
            override fun setListener(listener: PlaybackTransportListener?) = Unit
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

    // YT-0244: engine STATE_BUFFERING must flip status PLAYING → BUFFERING, not PAUSED.
    @Test
    fun `onBufferingStateChanged true flips PLAYING to BUFFERING`() = runTest(dispatcher) {
        val transport = FakePlaybackTransport()
        val controller = makeController(transport)

        controller.playNow(trackOne)
        runCurrent()
        assertEquals(PlaybackStatus.PLAYING, controller.playerState.value.playbackStatus)

        transport.listener?.onBufferingStateChanged(true)
        // isPlaying stays true — the engine hasn't fired onIsPlayingChanged yet, and the
        // guard in handleIsPlayingChanged suppresses the spurious false flip during BUFFERING.
        assertEquals(PlaybackStatus.BUFFERING, controller.playerState.value.playbackStatus)
    }

    // YT-0244: after buffering ends while audio was playing, status returns to PLAYING.
    @Test
    fun `onBufferingStateChanged false returns BUFFERING to PLAYING`() = runTest(dispatcher) {
        val transport = FakePlaybackTransport()
        val controller = makeController(transport)

        controller.playNow(trackOne)
        runCurrent()

        transport.listener?.onBufferingStateChanged(true)
        assertEquals(PlaybackStatus.BUFFERING, controller.playerState.value.playbackStatus)

        transport.listener?.onIsPlayingChanged(true)
        transport.listener?.onBufferingStateChanged(false)
        assertEquals(PlaybackStatus.PLAYING, controller.playerState.value.playbackStatus)
        assertEquals(true, controller.playerState.value.isPlaying)
    }

    // YT-0244: ExoPlayer fires onIsPlayingChanged(false) during STATE_BUFFERING. The
    // controller must NOT clobber BUFFERING → PAUSED during that window.
    @Test
    fun `handleIsPlayingChanged false is no-op while currently BUFFERING`() =
        runTest(dispatcher) {
            val transport = FakePlaybackTransport()
            val controller = makeController(transport)

            controller.playNow(trackOne)
            runCurrent()

            transport.listener?.onBufferingStateChanged(true)
            assertEquals(PlaybackStatus.BUFFERING, controller.playerState.value.playbackStatus)

            transport.listener?.onIsPlayingChanged(false)
            assertEquals(PlaybackStatus.BUFFERING, controller.playerState.value.playbackStatus)
        }

    // YT-0245: after isPlaying flips false→true the progress job must restart and tick.
    @Test
    fun `progress job restarts when isPlaying flips false then true`() = runTest(dispatcher) {
        val transport = FakePlaybackTransport()
        val controller = makeController(transport)

        controller.playNow(trackOne)
        runCurrent()
        assertEquals(PlaybackStatus.PLAYING, controller.playerState.value.playbackStatus)

        // One tick advances positionMs by 1 s.
        advanceTimeBy(1_000L); runCurrent()
        val posAfterOneTick = controller.playerState.value.positionMs
        assertEquals(1_000L, posAfterOneTick)

        // Simulate lockscreen / audio-focus pause.
        transport.listener?.onIsPlayingChanged(false)
        runCurrent()
        assertEquals(PlaybackStatus.PAUSED, controller.playerState.value.playbackStatus)

        // Drain the progress loop's pending tick so the job terminates.
        advanceTimeBy(1_100L); runCurrent()
        assertEquals(posAfterOneTick, controller.playerState.value.positionMs)

        // Resume — the restart branch fires.
        transport.listener?.onIsPlayingChanged(true)
        runCurrent()
        assertEquals(PlaybackStatus.PLAYING, controller.playerState.value.playbackStatus)

        // Two ticks advance positionMs by 2 s from the snap.
        advanceTimeBy(2_000L); runCurrent()
        assertEquals(posAfterOneTick + 2_000L, controller.playerState.value.positionMs)
    }

    // YT-0245: repeated true→true flips must not stack extra progress jobs.
    @Test
    fun `progress job stays single-instance when isPlaying flips true then true`() =
        runTest(dispatcher) {
            val transport = FakePlaybackTransport()
            val controller = makeController(transport)

            controller.playNow(trackOne)
            runCurrent()
            assertEquals(PlaybackStatus.PLAYING, controller.playerState.value.playbackStatus)

            // Two consecutive "still playing" callbacks — no stacked jobs.
            transport.listener?.onIsPlayingChanged(true)
            runCurrent()
            transport.listener?.onIsPlayingChanged(true)
            runCurrent()

            // Advance 2 s: should see exactly 2 ticks, not 4 or 6 from stacked jobs.
            advanceTimeBy(2_000L); runCurrent()
            assertEquals(2_000L, controller.playerState.value.positionMs)
        }

    // YT-0245: progress tick must NOT advance positionMs while BUFFERING; ticking resumes
    // cleanly once buffering ends without needing the YT-0245 restart branch to fire.
    @Test
    fun `progress tick is suppressed while playbackStatus is BUFFERING`() =
        runTest(dispatcher) {
            val transport = FakePlaybackTransport()
            val controller = makeController(transport)

            controller.playNow(trackOne)
            runCurrent()
            assertEquals(PlaybackStatus.PLAYING, controller.playerState.value.playbackStatus)

            // Enter BUFFERING — isPlaying stays true (YT-0244 guard).
            transport.listener?.onBufferingStateChanged(true)
            assertEquals(PlaybackStatus.BUFFERING, controller.playerState.value.playbackStatus)
            assertEquals(true, controller.playerState.value.isPlaying)

            // Advance 2 s while BUFFERING — positionMs must not move.
            advanceTimeBy(2_000L); runCurrent()
            assertEquals(0L, controller.playerState.value.positionMs)

            // Buffering ends — status returns to PLAYING.
            transport.listener?.onBufferingStateChanged(false)
            assertEquals(PlaybackStatus.PLAYING, controller.playerState.value.playbackStatus)

            // Advance 2 s after BUFFERING clears — positionMs must tick forward.
            advanceTimeBy(2_000L); runCurrent()
            assertEquals(2_000L, controller.playerState.value.positionMs)
        }

    // YT-0249: three rapid skipNext taps during a loading window must cancel intermediate
    // resolves and land on the third-forward queue index. Each skip is launched in a
    // separate coroutine (as system/lockscreen callbacks would do) because skipNext is
    // suspend and blocks on job.join() until its resolve settles or is pre-empted.
    @Test
    fun `rapid skipNext bursts cancel in-flight resolve and land on latest index`() =
        runTest(dispatcher) {
            val transport = MultiSuspendingPlaybackTransport(immediateCalls = 1)
            val controller = makeController(transport)

            val trackFour = Track("four", "Track Four", "Ch D", 210, "")
            controller.setQueueAndPlay(
                listOf(trackOne, trackTwo, trackThree, trackFour),
                startIndex = 0,
            )
            // Initial play completes immediately (immediateCalls=1). Drain remaining tasks.
            runCurrent()
            assertEquals(0, controller.playerState.value.currentQueueIndex)

            // Simulate rapid tap bursts: each tap is an independent coroutine, mirroring
            // how serviceScope.launch { controller.skipNext() } fires from lockscreen.
            launch { controller.skipNext() }; runCurrent() // idx → 1, inner1 suspends
            launch { controller.skipNext() }; runCurrent() // cancels inner1, idx → 2
            launch { controller.skipNext() }; runCurrent() // cancels inner2, idx → 3

            // Only the final resolve completes.
            transport.completeLatest(PlaybackResult.Success)
            runCurrent()

            val state = controller.playerState.value
            assertEquals(3, state.currentQueueIndex)
            assertEquals(trackFour, state.currentTrack)
        }

    // YT-0249: same guarantee for skipPrevious rapid taps.
    @Test
    fun `rapid skipPrevious bursts collapse to the latest index`() = runTest(dispatcher) {
        val transport = MultiSuspendingPlaybackTransport(immediateCalls = 1)
        val controller = makeController(transport)

        val trackFour = Track("four", "Track Four", "Ch D", 210, "")
        val trackFive = Track("five", "Track Five", "Ch E", 220, "")
        controller.setQueueAndPlay(
            listOf(trackOne, trackTwo, trackThree, trackFour, trackFive),
            startIndex = 4,
        )
        runCurrent()
        assertEquals(4, controller.playerState.value.currentQueueIndex)

        // positionMs=0 on fresh play — below SKIP_BACK_RESTART_THRESHOLD_MS — so each tap goes back.
        launch { controller.skipPrevious() }; runCurrent() // idx → 3
        launch { controller.skipPrevious() }; runCurrent() // cancel, idx → 2
        launch { controller.skipPrevious() }; runCurrent() // cancel, idx → 1

        transport.completeLatest(PlaybackResult.Success)
        runCurrent()

        val state = controller.playerState.value
        assertEquals(1, state.currentQueueIndex)
        assertEquals(trackTwo, state.currentTrack)
    }

    // YT-0249: a stale Success from a cancelled resolve must not clobber state.
    @Test
    fun `cancelled resolve does not clobber state with stale Success`() = runTest(dispatcher) {
        val transport = MultiSuspendingPlaybackTransport(immediateCalls = 1)
        val controller = makeController(transport)

        controller.setQueueAndPlay(
            listOf(trackOne, trackTwo, trackThree),
            startIndex = 0,
        )
        runCurrent()

        launch { controller.skipNext() }; runCurrent() // resolve #1 pending (trackTwo)
        launch { controller.skipNext() }; runCurrent() // cancel #1, resolve #2 (trackThree)

        // Complete only the latest (trackThree's resolve, at deferreds[2]).
        transport.completeLatest(PlaybackResult.Success)
        runCurrent()

        // Completing the stale deferred from resolve #1 (deferreds[1], coroutine already cancelled).
        transport.completePending(1, PlaybackResult.Success)
        runCurrent()

        // State must reflect trackThree at idx=2, not trackTwo.
        val state = controller.playerState.value
        assertEquals(2, state.currentQueueIndex)
        assertEquals(trackThree, state.currentTrack)
    }

    // YT-0095 CR3: setPlaybackSpeed — value below minimum (0.5f) is clamped to 0.5f.
    // PlayerState reflects the clamped value and the transport is called with 0.5f.
    @Test
    fun `setPlaybackSpeed clamps below 0_5f to 0_5f in state and transport`() =
        runTest(dispatcher) {
            val transport = FakePlaybackTransport()
            val controller = makeController(transport)

            controller.setPlaybackSpeed(0.1f)

            assertEquals(0.5f, controller.playerState.value.playbackSpeed)
            assertEquals(
                listOf("setPlaybackSpeed(0.5)"),
                transport.events.filter { it.startsWith("setPlaybackSpeed") },
            )
        }

    // YT-0095 CR3: setPlaybackSpeed — value above maximum (2.0f) is clamped to 2.0f.
    // PlayerState reflects the clamped value and the transport is called with 2.0f.
    @Test
    fun `setPlaybackSpeed clamps above 2_0f to 2_0f in state and transport`() =
        runTest(dispatcher) {
            val transport = FakePlaybackTransport()
            val controller = makeController(transport)

            controller.setPlaybackSpeed(5.0f)

            assertEquals(2.0f, controller.playerState.value.playbackSpeed)
            assertEquals(
                listOf("setPlaybackSpeed(2.0)"),
                transport.events.filter { it.startsWith("setPlaybackSpeed") },
            )
        }

    // YT-0095 CR3: setPlaybackSpeed — in-range value mutates playerState.playbackSpeed,
    // FakePlaybackTransport records the call, and FakePlaybackSpeedPreferences records
    // the persisted value.
    @Test
    fun `setPlaybackSpeed in range updates state transport and persisted preference`() =
        runTest(dispatcher) {
            val transport = FakePlaybackTransport()
            val controller = makeController(transport)

            controller.setPlaybackSpeed(1.5f)

            assertEquals(1.5f, controller.playerState.value.playbackSpeed)
            assertEquals(
                listOf("setPlaybackSpeed(1.5)"),
                transport.events.filter { it.startsWith("setPlaybackSpeed") },
            )
            // FakePlaybackSpeedPreferences.speed.first() should now reflect the persisted value.
            assertEquals(1.5f, playbackSpeedPreferences.speed.first())
        }

    // YT-0095 CR3: new track load via runPlayQueueItem must re-apply the persisted speed to
    // the transport on every successful load (global persistence mode). The persisted speed is
    // read from PlaybackSpeedPreferences.speed.first() and forwarded via transport.setPlaybackSpeed.
    @Test
    fun `runPlayQueueItem re-applies persisted speed to transport on each successful load`() =
        runTest(dispatcher) {
            // Persist a non-default speed before the first play so we can assert it propagates.
            playbackSpeedPreferences.setSpeed(1.5f)

            val transport = FakePlaybackTransport()
            val controller = makeController(transport)

            // First track load.
            controller.playNow(trackOne)
            runCurrent()

            // setPlaybackSpeed must have been called with the persisted value after playTrack succeeded.
            val speedCallsAfterFirstLoad = transport.events.filter { it.startsWith("setPlaybackSpeed") }
            assertEquals(listOf("setPlaybackSpeed(1.5)"), speedCallsAfterFirstLoad)

            // Simulate a second track load (skip to next queued track).
            controller.addToQueue(trackTwo)
            controller.skipNext()
            runCurrent()

            // After the second successful load, setPlaybackSpeed must have been called again
            // with the same persisted value.
            val allSpeedCalls = transport.events.filter { it.startsWith("setPlaybackSpeed") }
            assertEquals(
                listOf("setPlaybackSpeed(1.5)", "setPlaybackSpeed(1.5)"),
                allSpeedCalls,
            )
        }

    // YT-0091: addToQueue must persist the updated queue via saveSnapshot().
    @Test
    fun `addToQueue persists snapshot with appended queue`() = runTest(dispatcher) {
        val controller = makeController()

        controller.playNow(trackOne)
        advanceUntilIdle()
        val savesAfterPlay = snapshotRepository.saves.size

        controller.addToQueue(trackTwo)
        advanceUntilIdle()

        assertEquals(true, snapshotRepository.saves.size > savesAfterPlay)
        val snap = snapshotRepository.saves.last()
        assertEquals(
            listOf(trackOne, trackTwo),
            snap.queue.map { it.track },
        )
    }

    // YT-0091: playNext must persist the updated queue via saveSnapshot(). The inserted
    // track lands immediately after the current index, not at the tail.
    @Test
    fun `playNext persists snapshot with insert-after-current ordering`() = runTest(dispatcher) {
        val controller = makeController()

        controller.playNow(trackOne)
        advanceUntilIdle()
        val savesAfterPlay = snapshotRepository.saves.size

        controller.playNext(trackTwo)
        advanceUntilIdle()

        assertEquals(true, snapshotRepository.saves.size > savesAfterPlay)
        val snap = snapshotRepository.saves.last()
        // playNext inserts at index 1 (after current idx 0) — queue is [trackOne, trackTwo].
        assertEquals(
            listOf(trackOne, trackTwo),
            snap.queue.map { it.track },
        )
    }

    // YT-0091: removeQueueItem must persist the shrunk queue via saveSnapshot().
    @Test
    fun `removeQueueItem persists snapshot with shrunk queue`() = runTest(dispatcher) {
        val controller = makeController()

        controller.playNow(trackOne)
        controller.addToQueue(trackTwo)
        advanceUntilIdle()
        val savesBeforeRemove = snapshotRepository.saves.size

        val idToRemove = controller.playerState.value.queue.last().queueId
        controller.removeQueueItem(idToRemove)
        advanceUntilIdle()

        assertEquals(true, snapshotRepository.saves.size > savesBeforeRemove)
        val snap = snapshotRepository.saves.last()
        assertEquals(listOf(trackOne), snap.queue.map { it.track })
    }

    // YT-0091: moveQueueItem must persist the reordered queue via saveSnapshot().
    @Test
    fun `moveQueueItem persists snapshot with reordered queue`() = runTest(dispatcher) {
        val controller = makeController()

        controller.playNow(trackOne)
        controller.addToQueue(trackTwo)
        controller.addToQueue(trackThree)
        advanceUntilIdle()
        val savesBeforeMove = snapshotRepository.saves.size

        // Move trackOne (idx=0) to idx=2 → [trackTwo, trackThree, trackOne].
        controller.moveQueueItem(fromIndex = 0, toIndex = 2)
        advanceUntilIdle()

        assertEquals(true, snapshotRepository.saves.size > savesBeforeMove)
        val snap = snapshotRepository.saves.last()
        assertEquals(
            listOf(trackTwo, trackThree, trackOne),
            snap.queue.map { it.track },
        )
    }

    private class FakePlaybackTransport : PlaybackTransport {
        var listener: PlaybackTransportListener? = null
            private set

        // YT-0193 — record every transport call in invocation order so seek-debounce
        // tests can assert that scrubber drags coalesce into a single `seekTo(...)` and
        // never call `pause()` / `resume()` / `play()` along the seek path.
        val events = mutableListOf<String>()
        val seekCalls: List<Long>
            get() = events.mapNotNull { event ->
                if (event.startsWith("seekTo(")) {
                    event.removePrefix("seekTo(").removeSuffix(")").toLong()
                } else {
                    null
                }
            }
        val pauseCalls: Int get() = events.count { it == "pause" }
        val resumeCalls: Int get() = events.count { it == "resume" }

        // YT-0238 — counter stays at 0 because the controller-facing transport contract
        // no longer issues a clear-timeline call. The fake exposes the counter so a future
        // regression that adds a `clearMediaItems` event to this log fails the assertion in
        // `skipNext does not trigger clearMediaItems on the transport`.
        val clearMediaItemsCount: Int get() = events.count { it == "clearMediaItems" }

        override fun setListener(listener: PlaybackTransportListener?) {
            this.listener = listener
        }

        override suspend fun playTrack(request: PlaybackRequest): PlaybackResult {
            // YT-0291 — record startPositionMs in the event label so restore-seek tests
            // can assert the position is carried in the request rather than via seekTo().
            events += if (request.startPositionMs > 0L) "playTrack(startPos=${request.startPositionMs})" else "playTrack"
            return PlaybackResult.Success
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

        override suspend fun setShuffleMode(enabled: Boolean) = Unit

        override suspend fun setRepeatMode(mode: Int) = Unit

        override suspend fun setPlaybackSpeed(speed: Float) {
            events += "setPlaybackSpeed($speed)"
        }
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

        override fun setListener(listener: PlaybackTransportListener?) = Unit

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

        override fun setListener(listener: PlaybackTransportListener?) = Unit

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

    private class FakePlaybackSpeedPreferences(
        initial: Float = PlaybackSpeedPreferences.DEFAULT_SPEED,
    ) : PlaybackSpeedPreferences {
        private val state = MutableStateFlow(initial)
        override val speed: Flow<Float> = state
        override suspend fun setSpeed(speed: Float) { state.value = speed }
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
        override suspend fun addTrackToPlaylist(playlistId: String, track: Track): AddTrackResult = AddTrackResult.Added
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

    // YT-0249: transport whose playTrack suspends independently per call so tests can
    // complete specific invocations and verify that earlier in-flight resolves are discarded.
    // The first [immediateCalls] invocations return PlaybackResult.Success synchronously so
    // the initial setQueueAndPlay/playNow can complete without manual deferred management;
    // all subsequent calls suspend until explicitly completed via [completeLatest] / [completePending].
    private class MultiSuspendingPlaybackTransport(
        private val immediateCalls: Int = 1,
    ) : PlaybackTransport {
        var listener: PlaybackTransportListener? = null
            private set
        private val deferreds = mutableListOf<CompletableDeferred<PlaybackResult>>()
        val callCount get() = deferreds.size

        fun completeLatest(result: PlaybackResult) {
            deferreds.lastOrNull()?.complete(result)
        }

        fun completePending(index: Int, result: PlaybackResult) {
            deferreds.getOrNull(index)?.complete(result)
        }

        override fun setListener(l: PlaybackTransportListener?) { listener = l }

        override suspend fun playTrack(request: PlaybackRequest): PlaybackResult {
            val d = CompletableDeferred<PlaybackResult>()
            deferreds += d
            if (deferreds.size <= immediateCalls) {
                d.complete(PlaybackResult.Success)
                return PlaybackResult.Success
            }
            return d.await()
        }

        override suspend fun pause() = Unit
        override suspend fun resume() = Unit
        override suspend fun seekTo(positionMs: Long) = Unit
        override suspend fun stopAndClearCurrent() = Unit
        override suspend fun setShuffleMode(enabled: Boolean) = Unit
        override suspend fun setRepeatMode(mode: Int) = Unit
    }

    private object NoOpLogger : Logger {
        override fun debug(tag: String, message: String) = Unit
        override fun info(tag: String, message: String) = Unit
        override fun warn(tag: String, message: String, throwable: Throwable?) = Unit
        override fun error(tag: String, message: String, throwable: Throwable?) = Unit
    }

    private class FakePlayerSnapshotRepository : PlayerSnapshotRepository {
        val saves = mutableListOf<PlayerState>()
        var snapshot: PlayerSnapshotEntity? = null

        override suspend fun save(state: PlayerState) {
            saves += state
        }

        override suspend fun loadSnapshot(): PlayerSnapshotEntity? = snapshot
    }

    // YT-0271: snapshot written after a successful track load.
    @Test
    fun `snapshot is saved after successful track load`() = runTest(dispatcher) {
        val controller = makeController()

        controller.playNow(trackOne)
        advanceUntilIdle()

        assertEquals(true, snapshotRepository.saves.isNotEmpty())
        val snap = snapshotRepository.saves.last()
        assertEquals(trackOne.videoId, snap.currentTrack?.videoId)
    }

    // YT-0271: snapshot written when the player is paused.
    @Test
    fun `snapshot is saved on pause`() = runTest(dispatcher) {
        val controller = makeController()

        controller.playNow(trackOne)
        advanceUntilIdle()
        val savesBeforePause = snapshotRepository.saves.size

        controller.pause()
        advanceUntilIdle()

        assertEquals(true, snapshotRepository.saves.size > savesBeforePause)
        val snap = snapshotRepository.saves.last()
        assertEquals(false, snap.isPlaying)
    }

    // YT-0271: periodic snapshot written during active playback (every SNAPSHOT_INTERVAL_TICKS seconds).
    @Test
    fun `snapshot is saved periodically while playing`() = runTest(dispatcher) {
        val controller = makeController()

        // Use runCurrent() (not advanceUntilIdle()) to settle the initial load without
        // also draining the entire progress loop. advanceUntilIdle() would run all 180
        // progress ticks for trackOne (durationMs = 180 s), producing many saves before
        // the assertion baseline is captured.
        controller.playNow(trackOne)
        runCurrent()
        val savesAfterLoad = snapshotRepository.saves.size

        // Advance SNAPSHOT_INTERVAL_TICKS 1-second ticks. The progress loop writes a
        // snapshot after the Nth tick.
        advanceTimeBy(DefaultPlayerController.SNAPSHOT_INTERVAL_TICKS * 1_000L)
        runCurrent()

        assertEquals(
            true,
            snapshotRepository.saves.size > savesAfterLoad,
        )
    }

    // ── YT-0272: restoreFromSnapshot ──────────────────────────────────────────────────────

    // A minimal helper that serializes a list of tracks into the JSON format written by
    // DefaultPlayerSnapshotRepository, so these tests do not depend on that class directly.
    private fun encodeQueue(vararg tracks: Track): String = buildString {
        append("[")
        tracks.forEachIndexed { index, t ->
            append(
                """{"queueId":"q${index}","videoId":"${t.videoId}","title":"${t.title}",""" +
                """"channel":"${t.channel}","durationSec":${t.durationSec},"thumbnailUrl":"${t.thumbnailUrl}"}""",
            )
            if (index < tracks.lastIndex) append(",")
        }
        append("]")
    }

    private fun makeSnapshot(
        currentVideoId: String?,
        queueJson: String,
        queueIndex: Int,
        positionMs: Long = 0L,
        repeatMode: Int = 0,
        shuffleOn: Boolean = false,
        playbackSpeed: Float = 1.0f,
    ): PlayerSnapshotEntity = PlayerSnapshotEntity(
        id = 1,
        currentVideoId = currentVideoId,
        queue = queueJson,
        queueIndex = queueIndex,
        positionMs = positionMs,
        repeatMode = repeatMode,
        shuffleOn = shuffleOn,
        playbackSpeed = playbackSpeed,
        savedAt = "2026-05-09T00:00:00Z",
    )

    // YT-0272: valid snapshot → PlayerState populated correctly, paused, engineLoaded=false.
    @Test
    fun `restoreFromSnapshot valid snapshot populates playerState paused`() = runTest(dispatcher) {
        val controller = makeController()
        val snapshot = makeSnapshot(
            currentVideoId = trackOne.videoId,
            queueJson = encodeQueue(trackOne, trackTwo),
            queueIndex = 0,
            positionMs = 30_000L,
            repeatMode = 2,
            shuffleOn = true,
            playbackSpeed = 1.5f,
        )

        controller.restoreFromSnapshot(snapshot)

        val state = controller.playerState.value
        assertEquals(trackOne.videoId, state.currentTrack?.videoId)
        assertEquals(2, state.queue.size)
        assertEquals(trackTwo.videoId, state.queue[1].track.videoId)
        assertEquals(0, state.currentQueueIndex)
        assertEquals(30_000L, state.positionMs)
        assertEquals(PlaybackStatus.PAUSED, state.playbackStatus)
        assertEquals(false, state.isPlaying)
        assertEquals(false, state.engineLoaded)
        assertEquals(2, state.repeatMode)
        assertEquals(true, state.shuffleOn)
        assertEquals(1.5f, state.playbackSpeed)
    }

    // YT-0272: empty queue in snapshot → playerState unchanged (still default empty state).
    @Test
    fun `restoreFromSnapshot empty queue is a no-op`() = runTest(dispatcher) {
        val controller = makeController()
        val snapshot = makeSnapshot(
            currentVideoId = null,
            queueJson = "[]",
            queueIndex = -1,
        )

        controller.restoreFromSnapshot(snapshot)

        val state = controller.playerState.value
        assertEquals(null, state.currentTrack)
        assertEquals(emptyList(), state.queue)
    }

    // YT-0272: queueIndex out of bounds → no-op, empty state preserved.
    @Test
    fun `restoreFromSnapshot queueIndex OOB is a no-op`() = runTest(dispatcher) {
        val controller = makeController()
        val snapshot = makeSnapshot(
            currentVideoId = trackOne.videoId,
            queueJson = encodeQueue(trackOne),
            queueIndex = 5, // out of bounds for a 1-item queue
        )

        controller.restoreFromSnapshot(snapshot)

        val state = controller.playerState.value
        assertEquals(null, state.currentTrack)
    }

    // YT-0272: videoId mismatch (queue[index].videoId != currentVideoId) → no-op.
    @Test
    fun `restoreFromSnapshot videoId mismatch is a no-op`() = runTest(dispatcher) {
        val controller = makeController()
        val snapshot = makeSnapshot(
            currentVideoId = trackTwo.videoId, // mismatch: index 0 points to trackOne
            queueJson = encodeQueue(trackOne, trackTwo),
            queueIndex = 0,
        )

        controller.restoreFromSnapshot(snapshot)

        val state = controller.playerState.value
        assertEquals(null, state.currentTrack)
    }

    // YT-0272: JSON parse error → no-op, no crash.
    @Test
    fun `restoreFromSnapshot invalid JSON is a no-op and does not crash`() = runTest(dispatcher) {
        val controller = makeController()
        val snapshot = makeSnapshot(
            currentVideoId = trackOne.videoId,
            queueJson = "not valid json }{",
            queueIndex = 0,
        )

        controller.restoreFromSnapshot(snapshot) // must not throw

        val state = controller.playerState.value
        assertEquals(null, state.currentTrack)
    }

    // YT-0272: playbackSpeed outside [0.5, 2.0] is clamped per the contract.
    @Test
    fun `restoreFromSnapshot clamps playbackSpeed below minimum to 0_5f`() = runTest(dispatcher) {
        val controller = makeController()
        val snapshot = makeSnapshot(
            currentVideoId = trackOne.videoId,
            queueJson = encodeQueue(trackOne),
            queueIndex = 0,
            playbackSpeed = 0.1f,
        )

        controller.restoreFromSnapshot(snapshot)

        assertEquals(0.5f, controller.playerState.value.playbackSpeed)
    }

    // YT-0272: unknown repeatMode value coerced to 0 (off).
    @Test
    fun `restoreFromSnapshot coerces unknown repeatMode to 0`() = runTest(dispatcher) {
        val controller = makeController()
        val snapshot = makeSnapshot(
            currentVideoId = trackOne.videoId,
            queueJson = encodeQueue(trackOne),
            queueIndex = 0,
            repeatMode = 99, // unknown future value
        )

        controller.restoreFromSnapshot(snapshot)

        assertEquals(0, controller.playerState.value.repeatMode)
    }

    // YT-0272: after a valid restore, calling resume() bootstraps the engine via
    // playQueueItem (engineLoaded=false) AND seeks the engine to the restored positionMs.
    // The event sequence must end with seekTo(45000) after setPlaybackSpeed.
    @Test
    fun `restoreFromSnapshot followed by resume bootstraps engine not bare resume`() =
        runTest(dispatcher) {
            val transport = FakePlaybackTransport()
            val controller = makeController(transport)

            val snapshot = makeSnapshot(
                currentVideoId = trackOne.videoId,
                queueJson = encodeQueue(trackOne),
                queueIndex = 0,
                positionMs = 45_000L,
            )
            controller.restoreFromSnapshot(snapshot)
            // Sanity: paused, engineLoaded=false
            assertEquals(PlaybackStatus.PAUSED, controller.playerState.value.playbackStatus)
            assertEquals(false, controller.playerState.value.engineLoaded)
            transport.events.clear()

            controller.resume()
            runCurrent()

            val state = controller.playerState.value
            assertEquals(PlaybackStatus.PLAYING, state.playbackStatus)
            // Must bootstrap via playQueueItem — NOT a bare transport.resume() — and must
            // carry the restored offset in PlaybackRequest.startPositionMs (YT-0291).
            assertEquals(
                listOf("stopAndClearCurrent", "playTrack(startPos=45000)", "setPlaybackSpeed(1.0)"),
                transport.events,
            )
            assertEquals(0, transport.resumeCalls)
        }

    // YT-0272 CR fix #1: after restoreFromSnapshot with a non-zero positionMs, the first
    // play must seek the engine to the restored offset, not start from zero.
    @Test
    fun `after restoreFromSnapshot with positionMs, first play seeks to restored offset`() =
        runTest(dispatcher) {
            val transport = FakePlaybackTransport()
            val controller = makeController(transport)

            val snapshot = makeSnapshot(
                currentVideoId = trackOne.videoId,
                queueJson = encodeQueue(trackOne),
                queueIndex = 0,
                positionMs = 45_000L,
            )
            controller.restoreFromSnapshot(snapshot)
            assertEquals(45_000L, controller.playerState.value.positionMs)

            transport.events.clear()
            controller.resume()
            runCurrent()

            // YT-0291 — startPositionMs is now carried in PlaybackRequest, not via seekTo().
            // The transport must NOT receive a seekTo() call; the seek happens inside
            // PlaybackPlayerAdapter.queue() before prepare().
            assertEquals(emptyList<Long>(), transport.seekCalls)
            assertTrue(transport.events.any { it.contains("startPos=45000") })
            // PlayerState.positionMs preserved at the restored value after the play path.
            assertEquals(45_000L, controller.playerState.value.positionMs)
        }

    // YT-0272 CR fix #1: after a normal (non-restore) play, no seekTo is issued.
    @Test
    fun `normal playNow does not seek after load`() = runTest(dispatcher) {
        val transport = FakePlaybackTransport()
        val controller = makeController(transport)

        controller.playNow(trackOne)
        runCurrent()

        assertEquals(emptyList<Long>(), transport.seekCalls)
        assertEquals(0L, controller.playerState.value.positionMs)
    }

    // YT-0285: Activity-rebind path regression. `restoreFromSnapshot` runs asynchronously on
    // the service's coroutine scope; by the time a new Activity subscribes to `playerState`
    // the singleton StateFlow already holds the restored value. This test simulates the
    // service-side restore completing BEFORE a new UI subscriber arrives and asserts that
    // the subscriber immediately sees `currentTrack != null` without a further emit — i.e.,
    // the StateFlow's replay-1 buffer always surfaces the latest restored value on subscribe.
    @Test
    fun `restoreFromSnapshot emits non-null currentTrack visible to a late subscriber`() =
        runTest(dispatcher) {
            val controller = makeController()
            val snapshot = makeSnapshot(
                currentVideoId = trackOne.videoId,
                queueJson = encodeQueue(trackOne, trackTwo),
                queueIndex = 0,
                positionMs = 12_000L,
            )

            // Simulate the async service-side restore completing before the Activity subscribes.
            controller.restoreFromSnapshot(snapshot)

            // A new subscriber (e.g. a recreated Activity) collects the first value from the
            // already-hot StateFlow. The value must reflect the restored state, not the
            // initial empty PlayerState — the MiniPlayer must be visible on first composition.
            val observed = controller.playerState.value
            assertEquals(trackOne.videoId, observed.currentTrack?.videoId)
            assertEquals(PlaybackStatus.PAUSED, observed.playbackStatus)
            assertEquals(false, observed.isPlaying)
            assertEquals(false, observed.engineLoaded)
            assertEquals(12_000L, observed.positionMs)
            assertEquals(2, observed.queue.size)
        }

    // YT-0272 CR fix #2: after restoreFromSnapshot with repeatMode=2, the transport
    // must immediately receive setRepeatMode(2) (best-effort engine propagation).
    @Test
    fun `after restoreFromSnapshot with repeatMode=2, engine receives setRepeatMode(2)`() =
        runTest(dispatcher) {
            val transport = CapturingPlaybackTransport()
            val controller = makeController(transport)

            val snapshot = makeSnapshot(
                currentVideoId = trackOne.videoId,
                queueJson = encodeQueue(trackOne),
                queueIndex = 0,
                repeatMode = 2,
            )
            controller.restoreFromSnapshot(snapshot)

            assertEquals(listOf(2), transport.repeatCalls)
        }

    // YT-0272 CR fix #2: after restoreFromSnapshot with shuffleOn=true, the transport
    // must immediately receive setShuffleMode(true) (best-effort engine propagation).
    @Test
    fun `after restoreFromSnapshot with shuffleOn=true, engine receives setShuffleMode(true)`() =
        runTest(dispatcher) {
            val transport = CapturingPlaybackTransport()
            val controller = makeController(transport)

            val snapshot = makeSnapshot(
                currentVideoId = trackOne.videoId,
                queueJson = encodeQueue(trackOne),
                queueIndex = 0,
                shuffleOn = true,
            )
            controller.restoreFromSnapshot(snapshot)

            assertEquals(listOf(true), transport.shuffleCalls)
        }

    // ── YT-0285: ensureRestored ───────────────────────────────────────────────────────────

    // ensureRestored is a no-op when currentTrack is already non-null (controller is live).
    // The snapshot repository must NOT be read in this case so we assert saves stays empty
    // and the snapshot field was never touched.
    @Test
    fun `ensureRestored does nothing when currentTrack is non-null`() = runTest(dispatcher) {
        val controller = makeController()
        // Put the controller into a live state by playing a track then pausing so the
        // progress loop does not advance time during the assertion window.
        controller.playNow(trackOne)
        runCurrent()
        controller.pause()
        runCurrent()
        assertEquals(trackOne, controller.playerState.value.currentTrack)

        // Place a snapshot in the repo — it should not be loaded.
        snapshotRepository.snapshot = makeSnapshot(
            currentVideoId = trackTwo.videoId,
            queueJson = encodeQueue(trackTwo),
            queueIndex = 0,
        )
        val savesBeforeRestore = snapshotRepository.saves.size

        // ensureRestored with a non-null currentTrack returns immediately (synchronous
        // guard). Use runCurrent() so we do not advance the test clock and drain unrelated
        // background jobs (progress loop, etc.) that would inflate saves.size.
        controller.ensureRestored()
        runCurrent()

        // State must remain trackOne — the snapshot row for trackTwo was never applied.
        assertEquals(trackOne, controller.playerState.value.currentTrack)
        // No extra save was triggered by ensureRestored itself.
        assertEquals(savesBeforeRestore, snapshotRepository.saves.size)
    }

    // ensureRestored loads the snapshot and applies it when currentTrack is null.
    // This exercises the "controller rebind" failure bucket: the process is alive and
    // the singleton controller exists but its in-memory state is empty because no
    // transport call has occurred (Media3 lazy bind never fired).
    @Test
    fun `ensureRestored loads and restores snapshot when currentTrack is null`() =
        runTest(dispatcher) {
            val controller = makeController()
            // Sanity: controller starts with null currentTrack.
            assertEquals(null, controller.playerState.value.currentTrack)

            snapshotRepository.snapshot = makeSnapshot(
                currentVideoId = trackOne.videoId,
                queueJson = encodeQueue(trackOne, trackTwo),
                queueIndex = 0,
                positionMs = 45_000L,
            )

            controller.ensureRestored()
            advanceUntilIdle()

            val state = controller.playerState.value
            // Track and queue must be populated from the snapshot.
            assertEquals(trackOne.videoId, state.currentTrack?.videoId)
            assertEquals(2, state.queue.size)
            // Restored as PAUSED — no auto-play.
            assertEquals(PlaybackStatus.PAUSED, state.playbackStatus)
            assertEquals(false, state.isPlaying)
            // Seek offset carried through.
            assertEquals(45_000L, state.positionMs)
            // Engine is NOT loaded — first resume must bootstrap via playQueueItem.
            assertEquals(false, state.engineLoaded)
        }

    // ensureRestored is a no-op when no snapshot is persisted (null from repository).
    @Test
    fun `ensureRestored is a no-op when no snapshot exists`() = runTest(dispatcher) {
        val controller = makeController()
        snapshotRepository.snapshot = null

        controller.ensureRestored()
        advanceUntilIdle()

        // State remains empty — no track, no queue.
        assertEquals(null, controller.playerState.value.currentTrack)
        assertEquals(0, controller.playerState.value.queue.size)
    }

    /**
     * YT-0089 — no-op [AutoplayController] used as the default in [makeController].
     * Always returns false from [fetchAndEnqueue] so existing STATE_ENDED tests
     * that assert IDLE settling are unaffected by the autoplay hook.
     */
    private class NoOpAutoplayController : AutoplayController {
        override fun onTrackStarted(videoId: String) = Unit
        override suspend fun fetchAndEnqueue(finishedVideoId: String): Boolean = false
    }

    // ── YT-0283: FakeSleepTimerController ────────────────────────────────────────────────

    /**
     * Fake [SleepTimerController] for unit tests. [cancelCount] lets the test verify
     * the timer was cancelled by [handleTrackEnded].
     */
    private class FakeSleepTimerController(
        initialState: SleepTimerState = SleepTimerState.Inactive,
    ) : SleepTimerController {
        private val _timerState = MutableStateFlow(initialState)
        override val timerState: StateFlow<SleepTimerState> = _timerState.asStateFlow()
        var cancelCount = 0
            private set

        override fun attach(scope: CoroutineScope) = Unit
        override fun detach() = Unit
        override fun setTimer(preset: SleepTimerPreset) {
            _timerState.value = SleepTimerState.Active(remainingMs = 0L, preset = preset)
        }
        override fun cancel() {
            cancelCount++
            _timerState.value = SleepTimerState.Inactive
        }
    }

    // ── YT-0287 regression tests ──────────────────────────────────────────────────────────

    // Removing the currently-playing item must flip `engineLoaded` to `false` so that
    // the next `resume()` bootstraps the engine for the new current track rather than
    // calling `playbackTransport.resume()` against the stale (removed) MediaItem.
    @Test
    fun `removeQueueItem on current item sets engineLoaded false`() = runTest(dispatcher) {
        val controller = makeController()

        controller.playNow(trackOne)
        controller.addToQueue(trackTwo)
        runCurrent()

        // Sanity: engine is loaded after a successful play.
        assertEquals(true, controller.playerState.value.engineLoaded)

        val currentQueueId = controller.playerState.value.queue.first().queueId
        controller.removeQueueItem(currentQueueId)

        val state = controller.playerState.value
        assertEquals(false, state.engineLoaded)
        assertEquals(trackTwo, state.currentTrack)
        assertEquals(PlaybackStatus.PAUSED, state.playbackStatus)
    }

    // After removing the current item, `resume()` must bootstrap the engine via
    // `playQueueItem(...)` (stopAndClearCurrent + playTrack) — not a bare transport.resume().
    @Test
    fun `removeQueueItem on current item followed by resume loads next track not removed track`() =
        runTest(dispatcher) {
            val transport = FakePlaybackTransport()
            val controller = makeController(transport)

            controller.playNow(trackOne)
            controller.addToQueue(trackTwo)
            runCurrent()

            val currentQueueId = controller.playerState.value.queue.first().queueId
            controller.removeQueueItem(currentQueueId)
            runCurrent()
            // Paused on trackTwo, engineLoaded=false after removal.
            assertEquals(trackTwo, controller.playerState.value.currentTrack)
            assertEquals(false, controller.playerState.value.engineLoaded)
            transport.events.clear()

            controller.resume()
            runCurrent()

            val state = controller.playerState.value
            assertEquals(PlaybackStatus.PLAYING, state.playbackStatus)
            assertEquals(true, state.isPlaying)
            // trackTwo must be the active track — not trackOne (which was removed).
            assertEquals(trackTwo, state.currentTrack)
            // Bootstrap path: stopAndClearCurrent + playTrack, NOT a bare resume().
            assertEquals(
                listOf("stopAndClearCurrent", "playTrack", "setPlaybackSpeed(1.0)"),
                transport.events,
            )
            assertEquals(0, transport.resumeCalls)
        }

    // ── YT-0283 regression tests ──────────────────────────────────────────────────────────

    // When an EndOfTrack sleep timer is armed, `handleTrackEnded` must pause and cancel
    // the timer BEFORE advancing the queue — the next track must NOT audibly start.
    //
    // YT-0289 update: per the manual-override policy, `playNow` (and `setQueueAndPlay`)
    // always cancel any active sleep timer on entry. Realistic user flow is: user starts
    // a track, THEN sets a sleep timer. The timer is therefore armed AFTER `playNow`
    // settles, not before it. This test reflects that ordering.
    @Test
    fun `EndOfTrack sleep timer armed - handleTrackEnded pauses and does not advance queue`() =
        runTest(dispatcher) {
            val transport = FakePlaybackTransport()
            // Start with Inactive — the timer is armed AFTER playback starts (realistic flow).
            val fakeSleepTimer = FakeSleepTimerController(initialState = SleepTimerState.Inactive)
            val controller = makeController(transport, sleepTimer = fakeSleepTimer)

            controller.playNow(trackOne)
            controller.addToQueue(trackTwo)
            runCurrent()
            assertEquals(0, controller.playerState.value.currentQueueIndex)
            // playNow with Inactive timer: cancelCount must stay at 0.
            assertEquals(0, fakeSleepTimer.cancelCount)

            // User arms the EndOfTrack timer mid-playback — after the track has started.
            fakeSleepTimer.setTimer(SleepTimerPreset.EndOfTrack)
            transport.events.clear()

            // Simulate track end while EndOfTrack timer is active.
            transport.listener?.onTrackEnded()
            runCurrent()

            val state = controller.playerState.value
            // Must NOT have advanced to trackTwo.
            assertEquals(0, state.currentQueueIndex)
            assertEquals(trackOne, state.currentTrack)
            // Must be paused, not playing.
            assertEquals(PlaybackStatus.PAUSED, state.playbackStatus)
            assertEquals(false, state.isPlaying)
            // handleTrackEnded must have cancelled the timer exactly once.
            assertEquals(1, fakeSleepTimer.cancelCount)
            // Queue advance: no playTrack for the next track.
            assertEquals(false, transport.events.any { it == "playTrack" })
        }

    // Without an EndOfTrack timer, auto-advance must still work normally.
    @Test
    fun `no EndOfTrack sleep timer - handleTrackEnded advances queue normally`() =
        runTest(dispatcher) {
            val transport = FakePlaybackTransport()
            val fakeSleepTimer = FakeSleepTimerController(initialState = SleepTimerState.Inactive)
            val controller = makeController(transport, sleepTimer = fakeSleepTimer)

            controller.playNow(trackOne)
            controller.addToQueue(trackTwo)
            runCurrent()

            transport.listener?.onTrackEnded()
            runCurrent()

            val state = controller.playerState.value
            assertEquals(1, state.currentQueueIndex)
            assertEquals(trackTwo, state.currentTrack)
            assertEquals(0, fakeSleepTimer.cancelCount)
        }

    // A time-based (non-EndOfTrack) armed timer must NOT block auto-advance.
    //
    // YT-0289 update: per the manual-override policy, `playNow` cancels any active timer
    // on entry. Realistic flow: user starts a track, THEN arms a time-based timer. The
    // timer is therefore set AFTER `playNow` settles so it is still Active at track end.
    @Test
    fun `non-EndOfTrack sleep timer does not preempt handleTrackEnded auto-advance`() =
        runTest(dispatcher) {
            val transport = FakePlaybackTransport()
            // Start with Inactive — armed after playback starts (realistic flow).
            val fakeSleepTimer = FakeSleepTimerController(initialState = SleepTimerState.Inactive)
            val controller = makeController(transport, sleepTimer = fakeSleepTimer)

            controller.playNow(trackOne)
            controller.addToQueue(trackTwo)
            runCurrent()

            // User arms a 15-minute timer after the track has started.
            fakeSleepTimer.setTimer(SleepTimerPreset.Min15)
            assertEquals(0, fakeSleepTimer.cancelCount) // playNow saw Inactive → no cancel

            transport.listener?.onTrackEnded()
            runCurrent()

            // Min15 timer active but NOT EndOfTrack — queue must still advance.
            val state = controller.playerState.value
            assertEquals(1, state.currentQueueIndex)
            assertEquals(trackTwo, state.currentTrack)
            // handleTrackEnded must NOT cancel a non-EndOfTrack timer.
            assertEquals(0, fakeSleepTimer.cancelCount)
        }

    // ── YT-0284 regression tests ──────────────────────────────────────────────────────────

    /**
     * YT-0284 — [AutoplayController] that enqueues [trackToEnqueue] via addToQueue on the
     * controller under test. The reference is injected lazily after the controller is built.
     */
    private inner class EnqueuingAutoplayController(
        private val trackToEnqueue: Track,
    ) : AutoplayController {
        /** Set by the test after the controller is created. */
        var controller: PlayerController? = null

        override fun onTrackStarted(videoId: String) = Unit

        override suspend fun fetchAndEnqueue(finishedVideoId: String): Boolean {
            val ctrl = controller ?: return false
            ctrl.addToQueue(trackToEnqueue)
            return true
        }

        override suspend fun isAutoplayEnabled(): Boolean = true  // autoplay ON
    }

    // When the queue is empty and autoplay is ON, handleTrackEnded must start the
    // appended track by calling playQueueItemAt after fetchAndEnqueue returns true.
    @Test
    fun `handleTrackEnded with empty queue and autoplay ON calls playQueueItemAt after fetchAndEnqueue succeeds`() =
        runTest(dispatcher) {
            val transport = FakePlaybackTransport()
            val enqueuingAutoplay = EnqueuingAutoplayController(trackToEnqueue = trackTwo)
            val controller = makeController(transport, autoplayController = enqueuingAutoplay)
            // Wire the back-reference so fetchAndEnqueue can call addToQueue.
            enqueuingAutoplay.controller = controller

            controller.playNow(trackOne)
            runCurrent()
            // Single-item queue — no next track.
            assertEquals(1, controller.playerState.value.queue.size)
            assertEquals(0, controller.playerState.value.currentQueueIndex)
            transport.events.clear()

            transport.listener?.onTrackEnded()
            runCurrent()

            val state = controller.playerState.value
            // Autoplay appended trackTwo at index 1; playQueueItemAt(1) must have fired.
            assertEquals(1, state.currentQueueIndex)
            assertEquals(trackTwo, state.currentTrack)
            assertEquals(PlaybackStatus.PLAYING, state.playbackStatus)
            // Engine was bootstrapped for trackTwo.
            assertEquals(true, transport.events.contains("playTrack"))
        }

    // ── YT-0288 regression tests ──────────────────────────────────────────────────────────

    // When queue.size == 1 and currentQueueIndex == 0, STATE_ENDED must trigger the
    // autoplay branch (not settle to IDLE) and the guard must fire correctly.
    // This pins the guard condition `nextIndex < state.queue.size` for the single-item edge.
    @Test
    fun `handleTrackEnded guard fires when queue size is 1 and currentQueueIndex is 0`() =
        runTest(dispatcher) {
            val transport = FakePlaybackTransport()
            // NoOp autoplay so it falls to IDLE after the guard is confirmed to fire.
            val controller = makeController(transport, autoplayController = NoOpAutoplayController())

            controller.playNow(trackOne)
            runCurrent()
            // Sanity: single-item queue, index 0.
            assertEquals(1, controller.playerState.value.queue.size)
            assertEquals(0, controller.playerState.value.currentQueueIndex)

            transport.listener?.onTrackEnded()
            runCurrent()

            // Guard fired (nextIndex=1 >= queue.size=1 → no advance), autoplay returned false →
            // controller settles to IDLE (no next item, no autoplay candidate).
            val state = controller.playerState.value
            assertEquals(PlaybackStatus.IDLE, state.playbackStatus)
            assertEquals(false, state.isPlaying)
            // currentTrack is preserved at end-of-queue (Spotify-style keep-last-track).
            assertEquals(trackOne, state.currentTrack)
        }

    // After autoplay enqueues a track, playQueueItemAt must address queue.lastIndex (not
    // currentQueueIndex + 1). Both values are identical when `currentQueueIndex == 0` after
    // a single-item play, but `lastIndex` is robust against any index drift that could occur
    // between the STATE_ENDED callback and the addToQueue call inside fetchAndEnqueue.
    @Test
    fun `handleTrackEnded autoplay enqueues and plays at correct index via lastIndex`() =
        runTest(dispatcher) {
            val transport = FakePlaybackTransport()
            val enqueuingAutoplay = EnqueuingAutoplayController(trackToEnqueue = trackTwo)
            val controller = makeController(transport, autoplayController = enqueuingAutoplay)
            enqueuingAutoplay.controller = controller

            controller.playNow(trackOne)
            runCurrent()
            assertEquals(1, controller.playerState.value.queue.size)
            assertEquals(0, controller.playerState.value.currentQueueIndex)
            transport.events.clear()

            transport.listener?.onTrackEnded()
            runCurrent()

            val state = controller.playerState.value
            // autoplay appended trackTwo → queue.lastIndex == 1; must have played at 1.
            assertEquals(2, state.queue.size)
            assertEquals(1, state.currentQueueIndex)
            assertEquals(trackTwo, state.currentTrack)
            assertEquals(PlaybackStatus.PLAYING, state.playbackStatus)
            assertEquals(true, transport.events.contains("playTrack"))
        }

    // ── YT-0289 regression tests ──────────────────────────────────────────────────────────

    // playNow must cancel an active EndOfTrack sleep timer before starting the new track.
    @Test
    fun `playNow cancels active EndOfTrack sleep timer`() = runTest(dispatcher) {
        val fakeSleepTimer = FakeSleepTimerController(
            initialState = SleepTimerState.Active(
                remainingMs = 0L,
                preset = SleepTimerPreset.EndOfTrack,
            ),
        )
        val controller = makeController(sleepTimer = fakeSleepTimer)

        controller.playNow(trackOne)
        runCurrent()

        assertEquals(1, fakeSleepTimer.cancelCount)
        assertEquals(SleepTimerState.Inactive, fakeSleepTimer.timerState.value)
    }

    // playNow must cancel a time-based (Min15) sleep timer before starting the new track.
    @Test
    fun `playNow cancels active Min15 sleep timer`() = runTest(dispatcher) {
        val fakeSleepTimer = FakeSleepTimerController(
            initialState = SleepTimerState.Active(
                remainingMs = 15 * 60 * 1_000L,
                preset = SleepTimerPreset.Min15,
            ),
        )
        val controller = makeController(sleepTimer = fakeSleepTimer)

        controller.playNow(trackOne)
        runCurrent()

        assertEquals(1, fakeSleepTimer.cancelCount)
        assertEquals(SleepTimerState.Inactive, fakeSleepTimer.timerState.value)
    }

    // setQueueAndPlay must cancel an active sleep timer (any preset).
    @Test
    fun `setQueueAndPlay cancels active EndOfTrack sleep timer`() = runTest(dispatcher) {
        val fakeSleepTimer = FakeSleepTimerController(
            initialState = SleepTimerState.Active(
                remainingMs = 0L,
                preset = SleepTimerPreset.EndOfTrack,
            ),
        )
        val controller = makeController(sleepTimer = fakeSleepTimer)

        controller.setQueueAndPlay(listOf(trackOne, trackTwo), startIndex = 0)
        runCurrent()

        assertEquals(1, fakeSleepTimer.cancelCount)
        assertEquals(SleepTimerState.Inactive, fakeSleepTimer.timerState.value)
    }

    // playNow must NOT cancel the timer if no timer is active (Inactive is a no-op).
    @Test
    fun `playNow does not cancel when sleep timer is Inactive`() = runTest(dispatcher) {
        val fakeSleepTimer = FakeSleepTimerController(initialState = SleepTimerState.Inactive)
        val controller = makeController(sleepTimer = fakeSleepTimer)

        controller.playNow(trackOne)
        runCurrent()

        assertEquals(0, fakeSleepTimer.cancelCount)
    }

    // setQueueAndPlay must emit currentTrack and currentQueueIndex in a single atomic update —
    // no intermediate state where currentTrack is the new track but currentQueueIndex still
    // points to the old slot (or vice versa).
    @Test
    fun `setQueueAndPlay emits coherent currentTrack and currentQueueIndex in single update`() =
        runTest(dispatcher) {
            val controller = makeController()

            // Capture every distinct (currentTrack, currentQueueIndex) pair that flows through
            // the StateFlow. If a split write occurred, we would see a transient pair where
            // track and index point to different items.
            val observedPairs = mutableListOf<Pair<String?, Int>>()
            val collectJob = launch {
                controller.playerState.collect { state ->
                    observedPairs += (state.currentTrack?.videoId to state.currentQueueIndex)
                }
            }

            controller.setQueueAndPlay(listOf(trackOne, trackTwo, trackThree), startIndex = 2)
            runCurrent()

            collectJob.cancel()

            // Every observed state snapshot must have a consistent (track, index) pair.
            // The allowed pairs are:
            //   (null, -1) — initial empty state
            //   ("three", 2) — atomic write from setQueueAndPlay / playQueueItemAt
            val valid = setOf(
                null to -1,
                trackThree.videoId to 2,
            )
            val invalidPairs = observedPairs.filter { it !in valid }
            assertEquals(
                emptyList<Pair<String?, Int>>(),
                invalidPairs,
                "Observed transient incoherent (track, index) pairs: $invalidPairs",
            )
            // Final state is coherent.
            val finalState = controller.playerState.value
            assertEquals(trackThree, finalState.currentTrack)
            assertEquals(2, finalState.currentQueueIndex)
        }

    // ── YT-0290 regression tests ──────────────────────────────────────────────────────────

    // After restoreFromSnapshot, calling playNow on a DIFFERENT track must NOT seek the
    // engine to the restored position. restoredPositionMs must be cleared by playNow so
    // the new track always starts at 0, both in PlayerState and on the transport.
    @Test
    fun `restoreFromSnapshot then playNow different track does not seek to restored position`() =
        runTest(dispatcher) {
            val transport = FakePlaybackTransport()
            val controller = makeController(transport)

            val snapshot = makeSnapshot(
                currentVideoId = trackOne.videoId,
                queueJson = encodeQueue(trackOne),
                queueIndex = 0,
                positionMs = 45_000L,
            )
            controller.restoreFromSnapshot(snapshot)
            // Sanity: restored state carries the persisted offset.
            assertEquals(45_000L, controller.playerState.value.positionMs)

            transport.events.clear()

            // User taps a completely different track — playNow must clear restoredPositionMs
            // before the load chain runs so the restored offset is not consumed by trackTwo.
            controller.playNow(trackTwo)
            runCurrent()

            val state = controller.playerState.value
            // PlayerState.positionMs must be 0 for the new track.
            assertEquals(0L, state.positionMs)
            // No seekTo call must have been issued for the 45_000 offset.
            assertEquals(emptyList<Long>(), transport.seekCalls)
            // The new track is the active one.
            assertEquals(trackTwo, state.currentTrack)
            assertEquals(PlaybackStatus.PLAYING, state.playbackStatus)
        }

    // After restoreFromSnapshot, calling setQueueAndPlay with a different track must also
    // clear restoredPositionMs so the new queue's first track starts at 0.
    @Test
    fun `restoreFromSnapshot then setQueueAndPlay different track does not seek to restored position`() =
        runTest(dispatcher) {
            val transport = FakePlaybackTransport()
            val controller = makeController(transport)

            val snapshot = makeSnapshot(
                currentVideoId = trackOne.videoId,
                queueJson = encodeQueue(trackOne),
                queueIndex = 0,
                positionMs = 45_000L,
            )
            controller.restoreFromSnapshot(snapshot)
            assertEquals(45_000L, controller.playerState.value.positionMs)

            transport.events.clear()

            controller.setQueueAndPlay(listOf(trackTwo, trackThree), startIndex = 0)
            runCurrent()

            val state = controller.playerState.value
            assertEquals(0L, state.positionMs)
            assertEquals(emptyList<Long>(), transport.seekCalls)
            assertEquals(trackTwo, state.currentTrack)
            assertEquals(PlaybackStatus.PLAYING, state.playbackStatus)
        }

    // After restoreFromSnapshot, resume() on the SAME restored track MUST still seek to
    // the restored position. Clearing restoredPositionMs in playNow/setQueueAndPlay must
    // not affect the resume path.
    @Test
    fun `restoreFromSnapshot then resume on same track seeks to restored position`() =
        runTest(dispatcher) {
            val transport = FakePlaybackTransport()
            val controller = makeController(transport)

            val snapshot = makeSnapshot(
                currentVideoId = trackOne.videoId,
                queueJson = encodeQueue(trackOne),
                queueIndex = 0,
                positionMs = 45_000L,
            )
            controller.restoreFromSnapshot(snapshot)
            assertEquals(45_000L, controller.playerState.value.positionMs)

            transport.events.clear()

            // resume() routes through playQueueItem on the SAME restored track —
            // restoredPositionMs must NOT have been cleared before this point.
            controller.resume()
            runCurrent()

            val state = controller.playerState.value
            assertEquals(PlaybackStatus.PLAYING, state.playbackStatus)
            // YT-0291 — seek carried in PlaybackRequest.startPositionMs, not via transport.seekTo().
            assertEquals(emptyList<Long>(), transport.seekCalls)
            assertTrue(transport.events.any { it.contains("startPos=45000") })
            assertEquals(45_000L, state.positionMs)
        }

    // ── YT-0291: skipNext at end-of-queue with autoplay ──────────────────────────────────

    // skipNext() on the last queue item with autoplay ON must fetch a candidate, enqueue it,
    // and advance to it — same path as natural EOF autoplay.
    @Test
    fun `skipNext at last index with autoplay ON enqueues candidate and advances`() =
        runTest(dispatcher) {
            val transport = FakePlaybackTransport()
            val enqueuingAutoplay = EnqueuingAutoplayController(trackToEnqueue = trackTwo)
            val controller = makeController(transport, autoplayController = enqueuingAutoplay)
            enqueuingAutoplay.controller = controller

            controller.playNow(trackOne)
            runCurrent()
            assertEquals(1, controller.playerState.value.queue.size)
            assertEquals(0, controller.playerState.value.currentQueueIndex)
            transport.events.clear()

            // skipNext on the single (last) item.
            controller.skipNext()
            runCurrent()

            val state = controller.playerState.value
            // Autoplay appended trackTwo; player advanced to it.
            assertEquals(2, state.queue.size)
            assertEquals(1, state.currentQueueIndex)
            assertEquals(trackTwo, state.currentTrack)
            assertEquals(PlaybackStatus.PLAYING, state.playbackStatus)
            assertTrue(transport.events.any { it == "playTrack" || it.startsWith("playTrack") })
        }

    // skipNext() on the last queue item with autoplay OFF must stay a silent no-op —
    // no transport call, queue and index unchanged.
    @Test
    fun `skipNext at last index with autoplay OFF is silent no-op`() = runTest(dispatcher) {
        val transport = FakePlaybackTransport()
        val controller = makeController(transport, autoplayController = NoOpAutoplayController())

        controller.playNow(trackOne)
        runCurrent()
        assertEquals(1, controller.playerState.value.queue.size)
        transport.events.clear()

        controller.skipNext()
        runCurrent()

        val state = controller.playerState.value
        // Queue length and index unchanged; no audio play call issued.
        assertEquals(1, state.queue.size)
        assertEquals(0, state.currentQueueIndex)
        assertEquals(trackOne, state.currentTrack)
        assertTrue(transport.events.none { it.startsWith("playTrack") })
    }

    // YT-0291 R2: skip-next at end of queue with autoplay ON must publish LOADING state
    // during the fetch window so NowPlaying / MiniPlayer / lock-screen show an affordance.
    @Test
    fun `skipNext at last index with autoplay ON publishes loading state during fetch`() =
        runTest(dispatcher) {
            val capturedStatuses = mutableListOf<PlaybackStatus>()
            val recordingAutoplay = object : AutoplayController {
                var ctrl: PlayerController? = null
                override fun onTrackStarted(videoId: String) = Unit
                override suspend fun fetchAndEnqueue(finishedVideoId: String): Boolean {
                    // Record the status AT THE MOMENT the fetch runs — tryAutoplayAdvance
                    // must have published LOADING before calling fetchAndEnqueue.
                    capturedStatuses += ctrl!!.playerState.value.playbackStatus
                    ctrl!!.addToQueue(trackTwo)
                    return true
                }
            }
            val transport = FakePlaybackTransport()
            val controller = makeController(transport, autoplayController = recordingAutoplay)
            recordingAutoplay.ctrl = controller

            controller.playNow(trackOne)
            runCurrent()
            transport.events.clear()

            controller.skipNext()
            runCurrent()

            // LOADING was visible to the outside world while fetch was in flight.
            assertEquals(listOf(PlaybackStatus.LOADING), capturedStatuses)
            // After completion the player advanced to trackTwo.
            val state = controller.playerState.value
            assertEquals(PlaybackStatus.PLAYING, state.playbackStatus)
            assertEquals(trackTwo, state.currentTrack)
        }

    // YT-0291 R2: when tryAutoplayAdvance fails (no candidate / autoplay OFF), the loading
    // state must be cleared — no stuck spinner left on the UI.
    @Test
    fun `tryAutoplayAdvance failure clears loading state`() = runTest(dispatcher) {
        val transport = FakePlaybackTransport()
        val controller = makeController(transport, autoplayController = NoOpAutoplayController())

        controller.playNow(trackOne)
        runCurrent()
        transport.events.clear()

        controller.skipNext() // autoplay OFF → tryAutoplayAdvance returns false
        runCurrent()

        val state = controller.playerState.value
        // Loading state cleared on failure — must be IDLE, not stuck at LOADING.
        assertEquals(PlaybackStatus.IDLE, state.playbackStatus)
        assertEquals(false, state.isPlaying)
        assertTrue(transport.events.none { it.startsWith("playTrack") })
    }

    // Current track audio must stop immediately when skip-next triggers autoplay —
    // stopAndClearCurrent must appear before any playTrack event.
    @Test
    fun `skipNext at last index with autoplay ON stops current audio before fetch`() =
        runTest(dispatcher) {
            val transport = FakePlaybackTransport()
            val enqueuingAutoplay = EnqueuingAutoplayController(trackToEnqueue = trackTwo)
            val controller = makeController(transport, autoplayController = enqueuingAutoplay)
            enqueuingAutoplay.controller = controller

            controller.playNow(trackOne)
            runCurrent()
            transport.events.clear()

            controller.skipNext()
            runCurrent()

            val stopIdx = transport.events.indexOfFirst { it == "stopAndClearCurrent" }
            val playIdx = transport.events.indexOfFirst { it.startsWith("playTrack") }
            assertTrue(stopIdx >= 0, "stopAndClearCurrent missing: ${transport.events}")
            assertTrue(
                stopIdx < playIdx,
                "stopAndClearCurrent ($stopIdx) must precede playTrack ($playIdx): ${transport.events}",
            )
        }

    // ── YT-0294: Mix queue loading tests ─────────────────────────────────────────────────

    // playNow fires a mix queue load and extends the queue asynchronously when the
    // fetch returns. After runCurrent(), the fire-and-forget coroutine has run, the
    // mix tail is appended to the queue, and the current track is unchanged at index 0.
    @Test
    fun `playNow fires mix queue load and extends queue asynchronously`() =
        runTest(dispatcher) {
            val mixService = MixLoadingYoutubeService(
                seedVideoId = "one",
                mixResult = listOf(
                    searchResult("one", durationSec = 180),   // seed — will be dropped
                    searchResult("two", durationSec = 240),
                    searchResult("three", durationSec = 200),
                ),
            )
            val controller = makeController(youtubeService = mixService)

            controller.playNow(trackOne)
            runCurrent() // drain both playQueueItem and the fire-and-forget launch

            val state = controller.playerState.value
            // Queue must be: [seed, trackB, trackC] — seed already at 0, tail appended.
            assertEquals(3, state.queue.size)
            assertEquals("one", state.queue[0].track.videoId)
            assertEquals("two", state.queue[1].track.videoId)
            assertEquals("three", state.queue[2].track.videoId)
            // Current track and index unchanged.
            assertEquals(trackOne, state.currentTrack)
            assertEquals(0, state.currentQueueIndex)
        }

    // If the current track changes (e.g. a second playNow) before the Mix for the first
    // track resolves, the stale Mix must NOT be appended. Guard: currentTrack?.videoId != seedVideoId.
    @Test
    fun `playNow mix load does not extend queue if seed changed`() =
        runTest(dispatcher) {
            val mixService = MixLoadingYoutubeService(
                seedVideoId = "one",
                mixResult = listOf(
                    searchResult("one", durationSec = 180),
                    searchResult("two", durationSec = 240),
                ),
            )
            val controller = makeController(youtubeService = mixService)

            // First playNow — mix for "one" is queued but not yet dispatched.
            controller.playNow(trackOne)
            // Second playNow BEFORE the first mix resolves.
            controller.playNow(trackTwo)
            // Drain all coroutines, including the stale mix for "one".
            runCurrent()

            val state = controller.playerState.value
            // Queue must contain only trackTwo — stale mix for "one" was discarded.
            assertEquals(1, state.queue.size)
            assertEquals(trackTwo, state.currentTrack)
            assertEquals("two", state.queue[0].track.videoId)
        }

    // If the user adds an item to the queue between playNow and the Mix resolution,
    // the Mix must NOT be appended (queue.size != 1). Guard: state.queue.size != 1.
    @Test
    fun `playNow mix load does not extend queue if user already added items`() =
        runTest(dispatcher) {
            val mixService = MixLoadingYoutubeService(
                seedVideoId = "one",
                mixResult = listOf(
                    searchResult("one", durationSec = 180),
                    searchResult("three", durationSec = 200),
                ),
            )
            val controller = makeController(youtubeService = mixService)

            controller.playNow(trackOne)
            // User adds an item manually BEFORE the mix resolves.
            controller.addToQueue(trackTwo)
            runCurrent() // drain mix coroutine

            val state = controller.playerState.value
            // Queue must be [trackOne, trackTwo] — mix was discarded because queue.size != 1.
            assertEquals(2, state.queue.size)
            assertEquals("one", state.queue[0].track.videoId)
            assertEquals("two", state.queue[1].track.videoId)
        }

    /**
     * No-op [YoutubeService] used as the default in [makeController] so existing tests
     * are unaffected by the new [YoutubeService] constructor parameter.
     * getMixQueue returns emptyList() / MixPage.empty(), keeping queue unchanged after playNow.
     */
    private open class NoOpYoutubeService : YoutubeService {
        override suspend fun searchVideos(query: String, sp: String?): List<SearchResult> = emptyList()
        override suspend fun resolveAudioStream(videoId: String, preferredMaxBitrateKbps: Int): ResolvedAudioStream =
            throw UnsupportedOperationException("not used")
        override suspend fun getRelatedVideos(videoId: String): List<SearchResult> = emptyList()
        override suspend fun getMixQueue(videoId: String): List<SearchResult> = emptyList()
        override suspend fun getMixQueueWithContinuation(videoId: String): MixPage = MixPage.empty()
        override suspend fun getMixContinuation(token: String): MixPage = MixPage.empty()
    }

    /**
     * YT-0294 — Fake [YoutubeService] that returns a fixed Mix for a specific [seedVideoId].
     * All other seeds return emptyList() so cross-test interference is impossible.
     *
     * YT-0297 — also implements getMixQueueWithContinuation (no continuation token) and
     * getMixContinuation (always empty) so the existing YT-0294 tests remain unaffected.
     */
    private class MixLoadingYoutubeService(
        private val seedVideoId: String,
        private val mixResult: List<SearchResult>,
    ) : YoutubeService {
        override suspend fun searchVideos(query: String, sp: String?): List<SearchResult> = emptyList()
        override suspend fun resolveAudioStream(videoId: String, preferredMaxBitrateKbps: Int): ResolvedAudioStream =
            throw UnsupportedOperationException("not used")
        override suspend fun getRelatedVideos(videoId: String): List<SearchResult> = emptyList()
        override suspend fun getMixQueue(videoId: String): List<SearchResult> =
            if (videoId == seedVideoId) mixResult else emptyList()
        override suspend fun getMixQueueWithContinuation(videoId: String): MixPage =
            if (videoId == seedVideoId) MixPage(items = mixResult, nextToken = null) else MixPage.empty()
        override suspend fun getMixContinuation(token: String): MixPage = MixPage.empty()
    }

    /**
     * YT-0297 — Fake [YoutubeService] that supports Mix continuation pagination.
     *
     * [initialPage] is returned for [getMixQueueWithContinuation]; subsequent calls to
     * [getMixContinuation] return entries from [continuationPages] in order (keyed by
     * the token that [initialPage.nextToken] / each page's [nextToken] advertises).
     *
     * If [throwOnContinuation] is true, [getMixContinuation] throws [RuntimeException]
     * to simulate a transient network failure.
     */
    private class PaginatingYoutubeService(
        private val seedVideoId: String,
        private val initialPage: MixPage,
        private val continuationPages: Map<String, MixPage> = emptyMap(),
        private val throwOnContinuation: Boolean = false,
    ) : YoutubeService {
        override suspend fun searchVideos(query: String, sp: String?): List<SearchResult> = emptyList()
        override suspend fun resolveAudioStream(videoId: String, preferredMaxBitrateKbps: Int): ResolvedAudioStream =
            throw UnsupportedOperationException("not used")
        override suspend fun getRelatedVideos(videoId: String): List<SearchResult> = emptyList()
        override suspend fun getMixQueue(videoId: String): List<SearchResult> = initialPage.items
        override suspend fun getMixQueueWithContinuation(videoId: String): MixPage =
            if (videoId == seedVideoId) initialPage else MixPage.empty()
        override suspend fun getMixContinuation(token: String): MixPage {
            if (throwOnContinuation) throw RuntimeException("simulated network failure")
            return continuationPages[token] ?: MixPage.empty()
        }
    }

    // ── YT-0297: Mix continuation pagination tests ───────────────────────────────────────

    // Initial Mix load → skip-next near tail → continuation fetch fires → queue extends;
    // currentIndex unchanged after the skip that triggered the prefetch, and unchanged
    // again after the continuation append (the continuation does NOT auto-advance).
    @Test
    fun `near tail skip triggers continuation fetch and extends queue`() = runTest(dispatcher) {
        // Initial page: seed(one) + 2 tail entries (two, three). Token "tok1" pages to a
        // continuation with two more entries (four, five), terminal (nextToken=null).
        val service = PaginatingYoutubeService(
            seedVideoId = "one",
            initialPage = MixPage(
                items = listOf(
                    searchResult("one", durationSec = 180),
                    searchResult("two", durationSec = 200),
                    searchResult("three", durationSec = 210),
                ),
                nextToken = "tok1",
            ),
            continuationPages = mapOf(
                "tok1" to MixPage(
                    items = listOf(
                        searchResult("four", durationSec = 220),
                        searchResult("five", durationSec = 230),
                    ),
                    nextToken = null,
                ),
            ),
        )
        val controller = makeController(youtubeService = service)

        controller.playNow(trackOne)
        runCurrent()
        // After initial load: [one(0), two(1), three(2)] — continuation token "tok1" held.
        assertEquals(3, controller.playerState.value.queue.size)
        assertEquals(0, controller.playerState.value.currentQueueIndex)

        // Skip to index 1 — not yet near the tail (queue.size-2 = 1 exactly at threshold).
        controller.skipNext()
        runCurrent()
        assertEquals(1, controller.playerState.value.currentQueueIndex)

        // Skip to index 2 — this is the tail (size=3, threshold=2 → index 2 == size-1).
        // maybePrefetchMixContinuation fires here; drain the fetch coroutine.
        controller.skipNext()
        runCurrent()
        assertEquals(2, controller.playerState.value.currentQueueIndex)

        // The continuation fetch appended two new entries.
        val state = controller.playerState.value
        assertEquals(5, state.queue.size)
        assertEquals("four", state.queue[3].track.videoId)
        assertEquals("five", state.queue[4].track.videoId)
        // currentIndex unchanged by the append — still on "three".
        assertEquals(2, state.currentQueueIndex)
        assertEquals("three", state.currentTrack?.videoId)
    }

    // Continuation fetch failure → token retained → next near-tail skip retries.
    //
    // PREFETCH_THRESHOLD = 2, so with a 5-entry initial queue the prefetch triggers when
    // currentIndex >= 5-2 = 3. We use 5 entries so we can advance to index 2 without
    // triggering a prefetch, then advance to index 3 (triggers first prefetch / fail),
    // and verify the token is retained so the next trigger retries.
    @Test
    fun `continuation fetch failure retains token for retry`() = runTest(dispatcher) {
        var failCount = 0
        val service = object : YoutubeService {
            override suspend fun searchVideos(query: String, sp: String?) = emptyList<SearchResult>()
            override suspend fun resolveAudioStream(videoId: String, preferredMaxBitrateKbps: Int): ResolvedAudioStream =
                throw UnsupportedOperationException("not used")
            override suspend fun getRelatedVideos(videoId: String) = emptyList<SearchResult>()
            override suspend fun getMixQueue(videoId: String) = emptyList<SearchResult>()
            override suspend fun getMixQueueWithContinuation(videoId: String): MixPage = MixPage(
                items = listOf(
                    searchResult("one",   durationSec = 180),
                    searchResult("two",   durationSec = 200),
                    searchResult("three", durationSec = 210),
                    searchResult("four",  durationSec = 220),
                    searchResult("five",  durationSec = 230),
                ),
                nextToken = "tok1",
            )
            override suspend fun getMixContinuation(token: String): MixPage {
                failCount++
                throw RuntimeException("simulated failure #$failCount")
            }
        }
        val controller = makeController(youtubeService = service)

        controller.playNow(trackOne)
        runCurrent()
        assertEquals(5, controller.playerState.value.queue.size)

        // Advance to index 3: queue.size-2 = 3, so this is the first near-tail position.
        // prefetch fires and fails (failCount=1).
        controller.skipNext(); runCurrent() // idx→1 (not near tail: 1 < 3)
        controller.skipNext(); runCurrent() // idx→2 (not near tail: 2 < 3)
        controller.skipNext(); runCurrent() // idx→3 (near tail: 3 >= 3 → prefetch fires, fails)
        assertEquals(1, failCount)

        // Queue must NOT have grown (fetch failed), size stays at 5.
        assertEquals(5, controller.playerState.value.queue.size)
        assertEquals(3, controller.playerState.value.currentQueueIndex)

        // Advance to index 4 (tail). maybePrefetchMixContinuation fires again because
        // mixContinuationJob is null (cleared after the failed attempt) and the token
        // is still held (failure path keeps the token).
        controller.skipNext(); runCurrent() // idx→4, second prefetch attempt (failCount=2)
        assertEquals(2, failCount)

        // Queue still has not grown — all failures.
        assertEquals(5, controller.playerState.value.queue.size)
        assertEquals(4, controller.playerState.value.currentQueueIndex)
    }

    // Continuation returns no nextToken → token cleared → tryAutoplayAdvance fires at tail.
    @Test
    fun `continuation with no nextToken clears token and falls back to autoplay related`() =
        runTest(dispatcher) {
            val service = PaginatingYoutubeService(
                seedVideoId = "one",
                initialPage = MixPage(
                    items = listOf(
                        searchResult("one", durationSec = 180),
                        searchResult("two", durationSec = 200),
                    ),
                    nextToken = "tok1",
                ),
                continuationPages = mapOf(
                    // Terminal page: one extra entry, no further token.
                    "tok1" to MixPage(
                        items = listOf(searchResult("three", durationSec = 210)),
                        nextToken = null,
                    ),
                ),
            )
            val enqueuingAutoplay = EnqueuingAutoplayController(trackToEnqueue = trackThree)
            val transport = FakePlaybackTransport()
            val controller = makeController(
                transport = transport,
                autoplayController = enqueuingAutoplay,
                youtubeService = service,
            )
            enqueuingAutoplay.controller = controller

            controller.playNow(trackOne)
            runCurrent()
            // Initial queue: [one, two]; token "tok1" held.
            assertEquals(2, controller.playerState.value.queue.size)

            // Skip to near-tail (idx 1) — prefetch fires for tok1, returning [three, null].
            controller.skipNext()
            runCurrent()
            // Continuation appended "three"; token cleared (nextToken=null on page).
            assertEquals(3, controller.playerState.value.queue.size)
            assertEquals("three", controller.playerState.value.queue[2].track.videoId)

            // Now advance to idx 2 — then skip at the tail.
            controller.skipNext()
            runCurrent()
            assertEquals(2, controller.playerState.value.currentQueueIndex)

            // At the true tail with no token — tryExtendMixAtTail returns false immediately,
            // tryAutoplayAdvance runs. Autoplay appended trackThree (different from "three"
            // in queue — this is the EnqueuingAutoplayController's candidate).
            controller.skipNext()
            runCurrent()

            val state = controller.playerState.value
            // Autoplay fired: queue grew past 3.
            assertTrue(state.queue.size > 3)
        }

    // playNow while inFlight → cancels fetch, clears token, fresh Mix starts cleanly.
    @Test
    fun `playNow while continuation in flight cancels fetch and starts fresh Mix`() =
        runTest(dispatcher) {
            // Continuation will suspend until we release it.
            val continuationDeferred = CompletableDeferred<MixPage>()
            val service = object : YoutubeService {
                override suspend fun searchVideos(query: String, sp: String?) = emptyList<SearchResult>()
                override suspend fun resolveAudioStream(videoId: String, preferredMaxBitrateKbps: Int): ResolvedAudioStream =
                    throw UnsupportedOperationException("not used")
                override suspend fun getRelatedVideos(videoId: String) = emptyList<SearchResult>()
                override suspend fun getMixQueue(videoId: String) = emptyList<SearchResult>()
                override suspend fun getMixQueueWithContinuation(videoId: String): MixPage = MixPage(
                    items = listOf(
                        searchResult("one", durationSec = 180),
                        searchResult("two", durationSec = 200),
                        searchResult("three", durationSec = 210),
                    ),
                    nextToken = "tok1",
                )
                override suspend fun getMixContinuation(token: String): MixPage =
                    continuationDeferred.await()
            }
            val controller = makeController(youtubeService = service)

            controller.playNow(trackOne)
            runCurrent()
            assertEquals(3, controller.playerState.value.queue.size)

            // Advance to near-tail to kick off the prefetch.
            controller.skipNext() // idx→1
            controller.skipNext() // idx→2, prefetch fires and suspends on continuationDeferred
            runCurrent()

            // Now play a new track — must cancel the in-flight continuation and clear the token.
            controller.playNow(trackTwo)
            runCurrent()

            // State must reflect a fresh single-item queue for trackTwo.
            val state = controller.playerState.value
            assertEquals(trackTwo, state.currentTrack)

            // Release the old continuation deferred — must NOT append to the new queue.
            continuationDeferred.complete(
                MixPage(
                    items = listOf(searchResult("four", durationSec = 220)),
                    nextToken = null,
                ),
            )
            runCurrent()

            // Queue must still be just [trackTwo] (the initial 1-item queue) — the stale
            // continuation page was discarded because cancelMixContinuation() ran before
            // the deferred resolved, and the new Mix (if any) has a different seed guard.
            // Note: tryLoadMixQueue for trackTwo may have extended the queue; what matters
            // is that the stale "four" entry did not sneak in.
            val finalVideoIds = controller.playerState.value.queue.map { it.track.videoId }
            assertTrue(
                "four" !in finalVideoIds,
                "Stale continuation entry 'four' must not appear in the new queue: $finalVideoIds",
            )
        }

    // Dedup: continuation items with videoIds already in queue are filtered out.
    @Test
    fun `continuation deduplicates entries already present in queue`() = runTest(dispatcher) {
        // Initial page has "one","two","three". Continuation token "tok1" returns "two"
        // (already in queue) + "four" (novel). Only "four" should be appended.
        val service = PaginatingYoutubeService(
            seedVideoId = "one",
            initialPage = MixPage(
                items = listOf(
                    searchResult("one", durationSec = 180),
                    searchResult("two", durationSec = 200),
                    searchResult("three", durationSec = 210),
                ),
                nextToken = "tok1",
            ),
            continuationPages = mapOf(
                "tok1" to MixPage(
                    items = listOf(
                        searchResult("two", durationSec = 200),  // already in queue
                        searchResult("four", durationSec = 220), // novel
                    ),
                    nextToken = null,
                ),
            ),
        )
        val controller = makeController(youtubeService = service)

        controller.playNow(trackOne)
        runCurrent()
        assertEquals(3, controller.playerState.value.queue.size)

        // Advance to near-tail to trigger the prefetch.
        controller.skipNext() // idx→1
        controller.skipNext() // idx→2, prefetch fires
        runCurrent()

        val state = controller.playerState.value
        // "two" is a dup — must be filtered; "four" is novel — must be appended.
        val videoIds = state.queue.map { it.track.videoId }
        assertEquals(4, state.queue.size, "Expected 4 entries (one, two, three, four): $videoIds")
        assertEquals(listOf("one", "two", "three", "four"), videoIds)
    }

    // ── YT-0300 round-5b: setQueueAndPlay and resume entry points ─────────────────────────

    // setQueueAndPlay with a single track + autoplay ON → eager Mix seeds while track plays.
    @Test
    fun `setQueueAndPlay single track with autoplay ON seeds Mix eagerly`() = runTest(dispatcher) {
        val service = MixLoadingYoutubeService(
            seedVideoId = trackOne.videoId,
            mixResult = listOf(
                searchResult(trackOne.videoId, durationSec = 180), // seed (dropped)
                searchResult("two", durationSec = 200),
                searchResult("three", durationSec = 210),
            ),
        )
        val enqueuingAutoplay = EnqueuingAutoplayController(trackToEnqueue = trackTwo)
        val controller = makeController(autoplayController = enqueuingAutoplay, youtubeService = service)

        controller.setQueueAndPlay(listOf(trackOne), startIndex = 0)
        advanceUntilIdle()

        val state = controller.playerState.value
        assertEquals(trackOne, state.currentTrack)
        val videoIds = state.queue.map { it.track.videoId }
        assertTrue(state.queue.size > 1, "Mix must seed via setQueueAndPlay path: $videoIds")
        assertTrue("two" in videoIds && "three" in videoIds, "Mix entries must be present: $videoIds")
    }

    // addToQueue on empty controller + resume + autoplay ON → eager Mix seeds.
    @Test
    fun `addToQueue then resume with autoplay ON seeds Mix eagerly`() = runTest(dispatcher) {
        val service = MixLoadingYoutubeService(
            seedVideoId = trackOne.videoId,
            mixResult = listOf(
                searchResult(trackOne.videoId, durationSec = 180),
                searchResult("two", durationSec = 200),
                searchResult("three", durationSec = 210),
            ),
        )
        val enqueuingAutoplay = EnqueuingAutoplayController(trackToEnqueue = trackTwo)
        val controller = makeController(autoplayController = enqueuingAutoplay, youtubeService = service)

        controller.addToQueue(trackOne)          // stages as PAUSED, engineLoaded=false
        controller.resume()                       // bootstraps engine via playQueueItem
        advanceUntilIdle()

        val state = controller.playerState.value
        assertEquals(trackOne, state.currentTrack)
        val videoIds = state.queue.map { it.track.videoId }
        assertTrue(state.queue.size > 1, "Mix must seed via resume bootstrap path: $videoIds")
        assertTrue("two" in videoIds, "Mix entries must be present: $videoIds")
    }

    // setQueueAndPlay with 3 tracks starting at index 0 → eager seeder MUST NOT fire
    // (index 0 is not the tail; tail is index 2).
    @Test
    fun `setQueueAndPlay multi-track at index 0 does not fire eager seed`() = runTest(dispatcher) {
        var fetchCount = 0
        val service = object : NoOpYoutubeService() {
            override suspend fun getMixQueueWithContinuation(videoId: String): MixPage {
                fetchCount++
                return MixPage(
                    items = listOf(searchResult(videoId, durationSec = 180), searchResult("extra", durationSec = 200)),
                    nextToken = null,
                )
            }
        }
        val enqueuingAutoplay = EnqueuingAutoplayController(trackToEnqueue = trackTwo)
        val controller = makeController(autoplayController = enqueuingAutoplay, youtubeService = service)

        controller.setQueueAndPlay(listOf(trackOne, trackTwo, trackThree), startIndex = 0)
        advanceUntilIdle()

        assertEquals(0, fetchCount, "Eager seeder must NOT fire at index 0 when tail is index 2")
        assertEquals(3, controller.playerState.value.queue.size)
    }

    // Failure-then-retry: first seed fails, second visit to tail re-fetches (mixSeedVideoId reset).
    @Test
    fun `failed eager seed resets guard so second visit retries`() = runTest(dispatcher) {
        var fetchCount = 0
        val service = object : NoOpYoutubeService() {
            override suspend fun getMixQueueWithContinuation(videoId: String): MixPage {
                fetchCount++
                if (fetchCount == 1) throw RuntimeException("network error")
                return MixPage(
                    items = listOf(
                        searchResult(videoId, durationSec = 180),
                        searchResult("two", durationSec = 200),
                    ),
                    nextToken = null,
                )
            }
        }
        val enqueuingAutoplay = EnqueuingAutoplayController(trackToEnqueue = trackTwo)
        val controller = makeController(autoplayController = enqueuingAutoplay, youtubeService = service)

        // First visit: fails, queue stays at 1, guard reset.
        controller.setQueueAndPlay(listOf(trackOne), startIndex = 0)
        advanceUntilIdle()
        assertEquals(1, controller.playerState.value.queue.size, "Failure must not expand queue")
        assertEquals(1, fetchCount)

        // Second visit (simulate by invoking maybeEagerSeedMix indirectly via setQueueAndPlay again).
        controller.setQueueAndPlay(listOf(trackOne), startIndex = 0)
        advanceUntilIdle()
        assertEquals(2, fetchCount, "Guard must have been reset; second visit must re-fetch")
        assertTrue(controller.playerState.value.queue.size > 1, "Second fetch must succeed and extend queue")
    }

    // ── YT-0300: eager Mix-seed when last track starts ──────────────────────────────────────

    // 2-track non-Mix queue, navigate to last (index 1) via skip-next, autoplay ON →
    // as soon as the last track starts, Mix seeds while it plays.
    @Test
    fun `skip-next to last position on non-Mix queue with autoplay ON seeds Mix while track plays`() =
        runTest(dispatcher) {
            val service = MixLoadingYoutubeService(
                seedVideoId = trackTwo.videoId,
                mixResult = listOf(
                    searchResult(trackTwo.videoId, durationSec = 180), // seed (dropped)
                    searchResult("three", durationSec = 200),
                    searchResult("four", durationSec = 210),
                ),
            )
            val enqueuingAutoplay = EnqueuingAutoplayController(trackToEnqueue = trackThree)
            val controller = makeController(autoplayController = enqueuingAutoplay, youtubeService = service)

            // Build a 2-track non-Mix queue: [trackOne, trackTwo].
            controller.playNow(trackOne)
            runCurrent()
            controller.addToQueue(trackTwo)
            runCurrent()
            assertEquals(2, controller.playerState.value.queue.size)

            // Navigate to trackTwo (the last position) — eager seeder fires.
            controller.skipNext()
            advanceUntilIdle()

            val state = controller.playerState.value
            assertEquals(trackTwo, state.currentTrack)   // still playing trackTwo
            val videoIds = state.queue.map { it.track.videoId }
            assertTrue(
                state.queue.size > 2,
                "Mix must have seeded while trackTwo plays: $videoIds",
            )
            assertTrue("three" in videoIds && "four" in videoIds, "Mix entries must be present: $videoIds")
        }

    // Autoplay OFF → skip-next to last position does NOT trigger eager seeding.
    @Test
    fun `skip-next to last position on non-Mix queue with autoplay OFF does not seed Mix`() =
        runTest(dispatcher) {
            val service = MixLoadingYoutubeService(
                seedVideoId = trackTwo.videoId,
                mixResult = listOf(
                    searchResult(trackTwo.videoId, durationSec = 180),
                    searchResult("three", durationSec = 200),
                ),
            )
            // NoOpAutoplayController.isAutoplayEnabled() returns false by default
            val controller = makeController(youtubeService = service)

            controller.playNow(trackOne)
            runCurrent()
            controller.addToQueue(trackTwo)
            runCurrent()
            assertEquals(2, controller.playerState.value.queue.size)

            controller.skipNext()
            advanceUntilIdle()

            assertEquals(2, controller.playerState.value.queue.size, "No Mix when autoplay OFF")
        }

    // Re-entering last position (skip-prev + skip-next) does NOT re-fetch.
    @Test
    fun `re-entering last position does not re-fetch Mix`() = runTest(dispatcher) {
        var fetchCount = 0
        val service = object : NoOpYoutubeService() {
            override suspend fun getMixQueueWithContinuation(videoId: String): MixPage {
                if (videoId == trackTwo.videoId) {
                    fetchCount++
                    return MixPage(
                        items = listOf(
                            searchResult(trackTwo.videoId, durationSec = 180),
                            searchResult("three", durationSec = 200),
                        ),
                        nextToken = null,
                    )
                }
                return MixPage.empty()
            }
        }
        val enqueuingAutoplay = EnqueuingAutoplayController(trackToEnqueue = trackThree)
        val controller = makeController(autoplayController = enqueuingAutoplay, youtubeService = service)

        // 2-track queue [trackOne, trackTwo]; navigate to trackTwo (last).
        controller.playNow(trackOne)
        runCurrent()
        controller.addToQueue(trackTwo)
        runCurrent()

        controller.skipNext()   // navigate to trackTwo — eager seeder fires (first fetch)
        advanceUntilIdle()
        val fetchCountAfterFirst = fetchCount

        // Navigate back to trackOne then forward again to trackTwo.
        controller.skipPrevious() // back to trackOne (sub-threshold seek → seekTo or prev)
        advanceUntilIdle()

        // Now skip-next to trackTwo again — the single-flight guard must prevent re-fetch.
        // Note: after the Mix seed, the queue grew, so skipNext moves to the Mix entry (index 2),
        // NOT back to trackTwo (index 1). We test that the fetch count for trackTwo did not increase.
        // The seed fetch must not have run again for trackTwo.
        assertEquals(fetchCountAfterFirst, fetchCount, "Re-entering last position must not re-fetch")
    }

    // Mix-initial fetch fails → silent, queue stays at original size, no crash.
    @Test
    fun `eager Mix seed failure is silent and queue stays at original length`() = runTest(dispatcher) {
        val service = object : NoOpYoutubeService() {
            override suspend fun getMixQueueWithContinuation(videoId: String): MixPage {
                if (videoId == trackTwo.videoId) throw RuntimeException("network error")
                return MixPage.empty()
            }
        }
        val enqueuingAutoplay = EnqueuingAutoplayController(trackToEnqueue = trackThree)
        val controller = makeController(autoplayController = enqueuingAutoplay, youtubeService = service)

        controller.playNow(trackOne)
        runCurrent()
        controller.addToQueue(trackTwo)
        runCurrent()
        val queueSizeBefore = controller.playerState.value.queue.size  // 2

        controller.skipNext()   // navigate to trackTwo — seeder fires, throws, is silent
        advanceUntilIdle()

        assertEquals(queueSizeBefore, controller.playerState.value.queue.size,
            "Failure must not crash or expand queue")
        assertEquals(trackTwo, controller.playerState.value.currentTrack)
    }

    // After eager seeding, EOF advances into the pre-seeded Mix entry without a LOADING flash.
    @Test
    fun `after eager seeding EOF advances into Mix entry directly`() = runTest(dispatcher) {
        val transport = FakePlaybackTransport()
        val service = MixLoadingYoutubeService(
            seedVideoId = trackTwo.videoId,
            mixResult = listOf(
                searchResult(trackTwo.videoId, durationSec = 180),
                searchResult("three", durationSec = 200),
            ),
        )
        val enqueuingAutoplay = EnqueuingAutoplayController(trackToEnqueue = trackThree)
        val controller = makeController(
            transport = transport,
            autoplayController = enqueuingAutoplay,
            youtubeService = service,
        )

        // Build [trackOne, trackTwo]; navigate to trackTwo (last).
        controller.playNow(trackOne)
        runCurrent()
        controller.addToQueue(trackTwo)
        runCurrent()
        controller.skipNext()
        advanceUntilIdle()
        // Queue should now be [trackOne, trackTwo, "three"] from the eager seeder.
        assertTrue(controller.playerState.value.queue.size > 2, "Eager seeder must have populated the queue")

        val trackTwoIndex = controller.playerState.value.currentQueueIndex

        // Simulate EOF on trackTwo — should advance to "three" directly.
        transport.listener?.onTrackEnded()
        runCurrent()

        val state = controller.playerState.value
        assertEquals(trackTwoIndex + 1, state.currentQueueIndex, "Must advance to Mix entry after trackTwo")
    }

    // ── YT-0309: LOADING → PAUSED on transport failure / timeout ─────────────────────────

    // YT-0309: a transport failure (IOException / network error) must clear LOADING
    // to PAUSED or ERROR with engineLoaded=false so the Play button becomes tappable again.
    @Test
    fun `transport failure on playNow clears LOADING to PAUSED with engineLoaded false`() =
        runTest(dispatcher) {
            val failingTransport = object : PlaybackTransport {
                override fun setListener(listener: PlaybackTransportListener?) = Unit
                override suspend fun playTrack(request: PlaybackRequest): PlaybackResult =
                    PlaybackResult.Failure("simulated network failure")
                override suspend fun pause() = Unit
                override suspend fun resume() = Unit
                override suspend fun seekTo(positionMs: Long) = Unit
                override suspend fun stopAndClearCurrent() = Unit
                override suspend fun setShuffleMode(enabled: Boolean) = Unit
                override suspend fun setRepeatMode(mode: Int) = Unit
                override suspend fun setPlaybackSpeed(speed: Float) = Unit
            }
            val controller = makeController(failingTransport)

            controller.playNow(trackOne)
            runCurrent()

            val state = controller.playerState.value
            // After failure the status must not stay LOADING — either ERROR or PAUSED is valid.
            assertTrue(
                state.playbackStatus == PlaybackStatus.ERROR ||
                    state.playbackStatus == PlaybackStatus.PAUSED,
                "After transport failure status must not stay LOADING: ${state.playbackStatus}",
            )
            assertEquals(false, state.engineLoaded, "engineLoaded must be false so Play retries via playQueueItem")
        }

    // YT-0309: stream-resolve timeout (withTimeoutOrNull returning null) must clear LOADING to PAUSED.
    @Test
    fun `stream resolve timeout clears LOADING to PAUSED`() = runTest(dispatcher) {
        val hangingTransport = object : PlaybackTransport {
            override fun setListener(listener: PlaybackTransportListener?) = Unit
            override suspend fun playTrack(request: PlaybackRequest): PlaybackResult {
                // Suspend indefinitely — the controller must time out via withTimeoutOrNull.
                kotlinx.coroutines.awaitCancellation()
            }
            override suspend fun pause() = Unit
            override suspend fun resume() = Unit
            override suspend fun seekTo(positionMs: Long) = Unit
            override suspend fun stopAndClearCurrent() = Unit
            override suspend fun setShuffleMode(enabled: Boolean) = Unit
            override suspend fun setRepeatMode(mode: Int) = Unit
            override suspend fun setPlaybackSpeed(speed: Float) = Unit
        }
        val controller = makeController(hangingTransport)

        controller.playNow(trackOne)
        // Advance past the STREAM_RESOLVE_TIMEOUT_MS threshold.
        advanceTimeBy(DefaultPlayerController.STREAM_RESOLVE_TIMEOUT_MS + 1_000L)
        runCurrent()

        val state = controller.playerState.value
        assertEquals(false, state.engineLoaded)
        assertTrue(
            state.playbackStatus == PlaybackStatus.PAUSED || state.playbackStatus == PlaybackStatus.IDLE,
            "Timeout must clear LOADING: ${state.playbackStatus}",
        )
    }

    // YT-0309: after a Failure on the first resolve attempt the controller lands in ERROR
    // with engineLoaded=false; a subsequent resume() retries via playQueueItem and, when
    // the transport now succeeds, clears to PLAYING with engineLoaded=true.
    @Test
    fun `resume after transport failure retries resolve and clears to PLAYING`() = runTest(dispatcher) {
        var callCount = 0
        val retryTransport = object : PlaybackTransport {
            override fun setListener(listener: PlaybackTransportListener?) = Unit
            override suspend fun playTrack(request: PlaybackRequest): PlaybackResult {
                callCount++
                return if (callCount == 1) PlaybackResult.Failure("no network") else PlaybackResult.Success
            }
            override suspend fun pause() = Unit
            override suspend fun resume() = Unit
            override suspend fun seekTo(positionMs: Long) = Unit
            override suspend fun stopAndClearCurrent() = Unit
            override suspend fun setShuffleMode(enabled: Boolean) = Unit
            override suspend fun setRepeatMode(mode: Int) = Unit
            override suspend fun setPlaybackSpeed(speed: Float) = Unit
        }
        val controller = makeController(retryTransport)

        // First attempt: Failure → PAUSED (round-6: was ERROR, now PAUSED per AC#2), engineLoaded=false.
        controller.playNow(trackOne)
        runCurrent()
        val errorState = controller.playerState.value
        assertEquals(PlaybackStatus.PAUSED, errorState.playbackStatus)
        assertEquals(false, errorState.engineLoaded)
        assertEquals(1, callCount)

        // Recovery: resume() re-enters the engineLoaded=false → playQueueItem path.
        // Transport now returns Success → PLAYING, engineLoaded=true.
        controller.resume()
        runCurrent()
        val recoveredState = controller.playerState.value
        assertEquals(PlaybackStatus.PLAYING, recoveredState.playbackStatus)
        assertEquals(true, recoveredState.engineLoaded)
        assertEquals(2, callCount)
    }

    // YT-0309 round-10: handleEngineUnloaded reads positionMs directly at IDLE time and
    // handleEngineUnloaded commits it to restoredPositionMs. Models READY→IDLE without BUFFERING.
    @Test
    fun `candidate captured on isPlaying false from PLAYING and committed by handleEngineUnloaded`() =
        runTest(dispatcher) {
            val transport = FakePlaybackTransport()
            val controller = makeController(transport)

            controller.playNow(trackOne)
            runCurrent()
            // Simulate progress: tick the progress loop past 30s.
            controller.seekTo(30_000L)
            advanceTimeBy(DefaultPlayerController.SEEK_DEBOUNCE_MS + 10L)
            runCurrent()

            // Network drains: isPlaying flips to false while state is still PLAYING.
            // Note: NO onBufferingStateChanged(true) fires — this is the READY→IDLE path.
            transport.listener?.onIsPlayingChanged(false)
            runCurrent()

            // Engine drops to IDLE. handleEngineUnloaded must commit candidate.
            transport.listener?.onEngineUnloaded()
            runCurrent()
            assertEquals(false, controller.playerState.value.engineLoaded)

            // resume() must carry startPos=30_000.
            transport.events.clear()
            controller.resume()
            runCurrent()
            assertTrue(
                transport.events.any { it.contains("startPos=30000") },
                "resume() must use position from handleEngineUnloaded positionMs read. Events: ${transport.events}",
            )
        }

    // YT-0309 round-10: EOF fires STATE_ENDED (not IDLE) so handleEngineUnloaded never runs;
    @Test
    fun `handleTrackEnded clears candidate so it is not committed to next track`() =
        runTest(dispatcher) {
            val transport = FakePlaybackTransport()
            val controller = makeController(transport)
            controller.addToQueue(trackTwo)

            controller.playNow(trackOne)
            runCurrent()
            controller.seekTo(30_000L)
            advanceTimeBy(DefaultPlayerController.SEEK_DEBOUNCE_MS + 10L)
            runCurrent()

            // isPlaying=false fires (end of track) — candidate set.
            transport.listener?.onIsPlayingChanged(false)
            runCurrent()

            // Natural EOF fires — handleTrackEnded must clear the candidate.
            transport.listener?.onTrackEnded()
            runCurrent()

            // No STATE_IDLE follows (track ended cleanly), so handleEngineUnloaded never fires.
            // Verify by starting a second track and asserting it starts at 0.
            transport.events.clear()
            controller.resume()
            runCurrent()
            assertTrue(
                transport.events.none { it.contains("startPos=") },
                "After EOF candidate must be cleared — next track must start at 0. Events: ${transport.events}",
            )
        }

    // YT-0309 round-9: user-pause does NOT set candidate (state already PAUSED at callback).
    @Test
    fun `user pause then engine unload restores position on resume`() = runTest(dispatcher) {
        val transport = FakePlaybackTransport()
        val controller = makeController(transport)

        controller.playNow(trackOne)
        runCurrent()
        controller.seekTo(30_000L)
        advanceTimeBy(DefaultPlayerController.SEEK_DEBOUNCE_MS + 10L)
        runCurrent()

        // User-initiated pause: controller sets state to PAUSED synchronously before
        // the isPlaying=false callback arrives from the engine.
        controller.pause()
        runCurrent()
        transport.listener?.onIsPlayingChanged(false)
        runCurrent()

        // Engine drops to IDLE while paused (e.g. buffer exhausted). handleEngineUnloaded
        // reads positionMs=30000 directly — Media3 calls onPlaybackStateChanged(IDLE) before
        // onIsPlayingChanged, so positionMs is still correct. This is the same mechanism used
        // for the airplane-mode drain scenario (round-10 fix). Result: resume starts at 30s.
        transport.listener?.onEngineUnloaded()
        runCurrent()

        transport.events.clear()
        controller.resume()
        runCurrent()
        assertTrue(
            transport.events.any { it.contains("startPos=30000") },
            "Engine unload after pause must preserve position for resume. Events: ${transport.events}",
        )
    }

    // YT-0309: playNow clears restoredPositionMs so a fresh play starts at 0.
    @Test
    fun `playNow clears restoredPositionMs so fresh play starts at 0`() = runTest(dispatcher) {
        val transport = FakePlaybackTransport()
        val controller = makeController(transport)

        controller.playNow(trackOne)
        runCurrent()
        controller.seekTo(30_000L)
        advanceTimeBy(DefaultPlayerController.SEEK_DEBOUNCE_MS + 10L)
        runCurrent()
        // Set candidate via isPlaying=false while PLAYING.
        transport.listener?.onIsPlayingChanged(false)
        runCurrent()

        // User taps a new track — must clear the candidate.
        transport.events.clear()
        controller.playNow(trackTwo)
        runCurrent()

        assertTrue(
            transport.events.none { it.contains("startPos=") },
            "After playNow restoredPositionMs must be 0. Events: ${transport.events}",
        )
    }

    // YT-0309 round-7: on transport Failure, restoredPositionMs stays intact for next resume().
    @Test
    fun `Failure branch preserves restoredPositionMs for next resume attempt`() = runTest(dispatcher) {
        var callCount = 0
        val playTrackEvents = mutableListOf<String>()
        val retryTransport = object : PlaybackTransport {
            private var listener: PlaybackTransportListener? = null
            override fun setListener(l: PlaybackTransportListener?) { listener = l }
            override suspend fun playTrack(request: PlaybackRequest): PlaybackResult {
                callCount++
                val label = if (request.startPositionMs > 0L) "playTrack(startPos=${request.startPositionMs})" else "playTrack"
                playTrackEvents += label
                return if (callCount == 1) PlaybackResult.Failure("no network") else PlaybackResult.Success
            }
            override suspend fun pause() = Unit
            override suspend fun resume() = Unit
            override suspend fun seekTo(positionMs: Long) = Unit
            override suspend fun stopAndClearCurrent() = Unit
            override suspend fun setShuffleMode(enabled: Boolean) = Unit
            override suspend fun setRepeatMode(mode: Int) = Unit
            override suspend fun setPlaybackSpeed(speed: Float) = Unit
        }
        val controller = makeController(retryTransport)
        val snapshot = makeSnapshot(
            currentVideoId = trackOne.videoId,
            queueJson = encodeQueue(trackOne),
            queueIndex = 0,
            positionMs = 45_000L,
        )
        controller.restoreFromSnapshot(snapshot)

        controller.resume()
        runCurrent()
        assertEquals(PlaybackStatus.PAUSED, controller.playerState.value.playbackStatus)
        assertEquals(1, callCount)

        controller.resume()
        runCurrent()
        assertEquals(2, callCount)
        assertTrue(
            playTrackEvents.any { it.contains("startPos=45000") },
            "Second attempt must still carry startPos=45000. Events: $playTrackEvents",
        )
        assertEquals(PlaybackStatus.PLAYING, controller.playerState.value.playbackStatus)
    }

    // YT-0309 round-7: on timeout, restoredPositionMs stays intact for next resume().
    @Test
    fun `timeout branch preserves restoredPositionMs for next resume attempt`() = runTest(dispatcher) {
        var callCount = 0
        val playTrackEvents = mutableListOf<String>()
        val hangThenSucceed = object : PlaybackTransport {
            private var listener: PlaybackTransportListener? = null
            override fun setListener(l: PlaybackTransportListener?) { listener = l }
            override suspend fun playTrack(request: PlaybackRequest): PlaybackResult {
                callCount++
                val label = if (request.startPositionMs > 0L) "playTrack(startPos=${request.startPositionMs})" else "playTrack"
                playTrackEvents += label
                return if (callCount == 1) kotlinx.coroutines.awaitCancellation() else PlaybackResult.Success
            }
            override suspend fun pause() = Unit
            override suspend fun resume() = Unit
            override suspend fun seekTo(positionMs: Long) = Unit
            override suspend fun stopAndClearCurrent() = Unit
            override suspend fun setShuffleMode(enabled: Boolean) = Unit
            override suspend fun setRepeatMode(mode: Int) = Unit
            override suspend fun setPlaybackSpeed(speed: Float) = Unit
        }
        val controller = makeController(hangThenSucceed)
        val snapshot = makeSnapshot(
            currentVideoId = trackOne.videoId,
            queueJson = encodeQueue(trackOne),
            queueIndex = 0,
            positionMs = 45_000L,
        )
        controller.restoreFromSnapshot(snapshot)

        controller.resume()
        advanceTimeBy(DefaultPlayerController.STREAM_RESOLVE_TIMEOUT_MS + 1_000L)
        runCurrent()
        assertEquals(PlaybackStatus.PAUSED, controller.playerState.value.playbackStatus)
        assertEquals(1, callCount)

        controller.resume()
        runCurrent()
        assertEquals(2, callCount)
        assertTrue(
            playTrackEvents.any { it.contains("startPos=45000") },
            "After timeout restoredPositionMs must still be 45000. Events: $playTrackEvents",
        )
        assertEquals(PlaybackStatus.PLAYING, controller.playerState.value.playbackStatus)
    }

    // YT-0309 round-7: Success drains restoredPositionMs; next track starts at 0.
    @Test
    fun `Success branch drains restoredPositionMs so subsequent track starts at 0`() = runTest(dispatcher) {
        val transport = FakePlaybackTransport()
        val controller = makeController(transport)
        val snapshot = makeSnapshot(
            currentVideoId = trackOne.videoId,
            queueJson = encodeQueue(trackOne, trackTwo),
            queueIndex = 0,
            positionMs = 45_000L,
        )
        controller.restoreFromSnapshot(snapshot)

        controller.resume()
        runCurrent()
        assertEquals(PlaybackStatus.PLAYING, controller.playerState.value.playbackStatus)
        transport.events.clear()

        controller.skipNext()
        runCurrent()
        assertTrue(
            transport.events.none { it.contains("startPos=") },
            "After successful resume, next track must start at 0. Events: ${transport.events}",
        )
    }

    // YT-0309 round-10: handleEngineUnloaded reads positionMs directly and carries it
    // into the next playQueueItem as restoredPositionMs.
    @Test
    fun `onEngineUnloaded with non-zero candidate preserves position for next playQueueItem`() = runTest(dispatcher) {
        val transport = FakePlaybackTransport()
        val controller = makeController(transport)

        controller.playNow(trackOne)
        runCurrent()
        controller.seekTo(45_000L)
        advanceTimeBy(DefaultPlayerController.SEEK_DEBOUNCE_MS + 10L)
        runCurrent()
        assertEquals(45_000L, controller.playerState.value.positionMs)
        transport.events.clear()

        // isPlaying=false while PLAYING sets the candidate; then IDLE commits it.
        transport.listener?.onIsPlayingChanged(false)
        runCurrent()
        transport.listener?.onEngineUnloaded()
        runCurrent()

        controller.resume()
        runCurrent()

        assertTrue(
            transport.events.any { it.contains("startPos=45000") },
            "PlaybackRequest must carry startPositionMs=45000 after engine-unload recovery. Events: ${transport.events}",
        )
    }

    // YT-0309 round-6: onEngineUnloaded with position==0 does NOT set restoredPositionMs
    // (no-op preserves the "fresh play starts at 0" contract).
    @Test
    fun `onEngineUnloaded at position 0 does not set restoredPositionMs`() = runTest(dispatcher) {
        val transport = FakePlaybackTransport()
        val controller = makeController(transport)

        controller.playNow(trackOne)
        runCurrent()
        assertEquals(0L, controller.playerState.value.positionMs)
        transport.events.clear()

        transport.listener?.onEngineUnloaded()
        runCurrent()

        controller.resume()
        runCurrent()

        // No startPos token in playTrack event — engine starts fresh at 0.
        assertTrue(
            transport.events.none { it.contains("startPos=") },
            "playTrack must NOT carry startPositionMs when position was 0 at unload. Events: ${transport.events}",
        )
    }

    // YT-0309 round-5: engine drops to STATE_IDLE after playing (airplane mid-stream).
    // onEngineUnloaded must clear engineLoaded and clear stuck BUFFERING to PAUSED.
    @Test
    fun `onEngineUnloaded clears engineLoaded and stuck BUFFERING to PAUSED`() = runTest(dispatcher) {
        val transport = FakePlaybackTransport()
        val controller = makeController(transport)

        controller.playNow(trackOne)
        runCurrent()
        assertEquals(true, controller.playerState.value.engineLoaded)

        // Engine starts buffering during normal playback.
        transport.listener?.onBufferingStateChanged(true)
        runCurrent()
        assertEquals(PlaybackStatus.BUFFERING, controller.playerState.value.playbackStatus)

        // Engine gives up — drops to STATE_IDLE (airplane drained buffer).
        transport.listener?.onEngineUnloaded()
        runCurrent()

        val state = controller.playerState.value
        assertEquals(false, state.engineLoaded, "engineLoaded must clear so next resume() re-enters playQueueItem")
        assertEquals(PlaybackStatus.PAUSED, state.playbackStatus, "Stuck BUFFERING must clear to PAUSED")
        assertEquals(false, state.isPlaying)
    }

    // YT-0309 round-5: after engine-unloaded, resume() must enter playQueueItem (not bare resume).
    @Test
    fun `resume after onEngineUnloaded routes through playQueueItem path`() = runTest(dispatcher) {
        val transport = FakePlaybackTransport()
        val controller = makeController(transport)

        controller.playNow(trackOne)
        runCurrent()
        transport.events.clear()

        // Engine dies.
        transport.listener?.onEngineUnloaded()
        runCurrent()
        assertEquals(false, controller.playerState.value.engineLoaded)

        controller.resume()
        runCurrent()

        // Must call playTrack (engine re-bootstrap), NOT bare resume().
        assertTrue(transport.events.contains("playTrack"), "Expected playTrack in transport events: ${transport.events}")
        assertEquals(0, transport.resumeCalls, "Bare transport.resume() must NOT be called after engine unload")
    }

    // YT-0309 round-5: resume() after engine-IDLE + hung transport → 15s timeout fires → PAUSED.
    // This is the full-path proof the round-5 smoke was asking for.
    @Test
    fun `resume after engine unload with hung transport times out to PAUSED`() = runTest(dispatcher) {
        var firstCallSeen = false
        val hangingAfterFirst = object : PlaybackTransport {
            private var listener: PlaybackTransportListener? = null
            override fun setListener(l: PlaybackTransportListener?) { listener = l }
            override suspend fun playTrack(request: PlaybackRequest): PlaybackResult {
                if (!firstCallSeen) {
                    firstCallSeen = true
                    return PlaybackResult.Success
                }
                kotlinx.coroutines.awaitCancellation()
            }
            override suspend fun pause() = Unit
            override suspend fun resume() = Unit
            override suspend fun seekTo(positionMs: Long) = Unit
            override suspend fun stopAndClearCurrent() = Unit
            override suspend fun setShuffleMode(enabled: Boolean) = Unit
            override suspend fun setRepeatMode(mode: Int) = Unit
            override suspend fun setPlaybackSpeed(speed: Float) = Unit
            fun fireEngineUnloaded() { listener?.onEngineUnloaded() }
        }
        val controller = makeController(hangingAfterFirst)

        controller.playNow(trackOne)
        runCurrent()
        assertEquals(true, controller.playerState.value.engineLoaded)

        // Engine drops to IDLE (airplane).
        hangingAfterFirst.fireEngineUnloaded()
        runCurrent()
        assertEquals(false, controller.playerState.value.engineLoaded)

        // resume() triggers re-bootstrap via playQueueItem. Transport hangs on second call.
        controller.resume()
        advanceTimeBy(DefaultPlayerController.STREAM_RESOLVE_TIMEOUT_MS + 1_000L)
        runCurrent()

        val finalState = controller.playerState.value
        assertEquals(false, finalState.engineLoaded)
        assertTrue(
            finalState.playbackStatus == PlaybackStatus.PAUSED || finalState.playbackStatus == PlaybackStatus.IDLE,
            "Hung resolve after engine-unload must clear LOADING: ${finalState.playbackStatus}",
        )
    }

    // YT-0307: jumping to a queue item plays it and sets currentQueueIndex without clearing the queue.
    @Test
    fun `jumpToQueueItem plays target and preserves queue`() = runTest(dispatcher) {
        val transport = FakePlaybackTransport()
        val controller = makeController(transport)

        controller.playNow(trackOne)
        controller.addToQueue(trackTwo)
        controller.addToQueue(trackThree)
        runCurrent()
        // Initial state: [trackOne(0), trackTwo(1), trackThree(2)], currentIndex=0.
        assertEquals(3, controller.playerState.value.queue.size)
        assertEquals(0, controller.playerState.value.currentQueueIndex)
        transport.events.clear()

        controller.jumpToQueueItem(2)
        runCurrent()

        val state = controller.playerState.value
        assertEquals(trackThree, state.currentTrack)
        assertEquals(2, state.currentQueueIndex)
        assertEquals(3, state.queue.size, "Queue must be preserved across jump")
        assertTrue(transport.events.contains("playTrack"), "Transport must play the target track")
    }

    // YT-0307: jumping to the current item is a no-op.
    @Test
    fun `jumpToQueueItem on current item is a no-op`() = runTest(dispatcher) {
        val transport = FakePlaybackTransport()
        val controller = makeController(transport)

        controller.playNow(trackOne)
        controller.addToQueue(trackTwo)
        runCurrent()
        transport.events.clear()

        controller.jumpToQueueItem(0) // already current
        runCurrent()

        assertEquals(0, controller.playerState.value.currentQueueIndex)
        assertEquals(
            0,
            transport.events.count { it == "playTrack" || it.startsWith("playTrack") },
            "No transport call on current-item jump",
        )
    }

    // YT-0307: out-of-bounds index is a no-op.
    @Test
    fun `jumpToQueueItem out of bounds is a no-op`() = runTest(dispatcher) {
        val transport = FakePlaybackTransport()
        val controller = makeController(transport)

        controller.playNow(trackOne)
        runCurrent()
        transport.events.clear()

        controller.jumpToQueueItem(99)
        runCurrent()

        assertEquals(0, controller.playerState.value.currentQueueIndex)
        assertEquals(trackOne, controller.playerState.value.currentTrack)
    }

    // ─── YT-0311 skip-back threshold tests ───────────────────────────────────────

    // Test 1: position > threshold → seekTo(0); currentQueueIndex unchanged; no playTrack.
    @Test
    fun `skipPrevious above threshold restarts current track via seekTo and does not change index`() =
        runTest(dispatcher) {
            val transport = FakePlaybackTransport()
            val controller = makeController(transport)

            // Two-track queue: idx=1 at 5000 ms (well above 3000 ms threshold).
            controller.playNow(trackOne)
            runCurrent()
            controller.addToQueue(trackTwo)
            controller.skipNext()
            runCurrent()
            // Advance synthetic progress to 5000 ms.
            advanceTimeBy(5_000L)
            runCurrent()
            assertEquals(1, controller.playerState.value.currentQueueIndex)
            assertEquals(5_000L, controller.playerState.value.positionMs)
            transport.events.clear()

            controller.skipPrevious()
            advanceTimeBy(DefaultPlayerController.SEEK_DEBOUNCE_MS + 5L)
            runCurrent()

            // Must rewind current track (idx=1 stays), not walk back to idx=0.
            assertEquals(1, controller.playerState.value.currentQueueIndex)
            assertEquals(trackTwo, controller.playerState.value.currentTrack)
            assertEquals(0L, controller.playerState.value.positionMs)
            assertEquals(listOf(0L), transport.seekCalls)
            assertTrue(transport.events.none { it.startsWith("playTrack") })
        }

    // Test 2: position ≤ threshold + idx > 0 → walk back to idx - 1.
    @Test
    fun `skipPrevious below threshold with idx greater than zero walks to previous track`() =
        runTest(dispatcher) {
            val transport = FakePlaybackTransport()
            val controller = makeController(transport)

            // Three-track queue: play to idx=2, then do NOT advance time (positionMs stays at 0).
            controller.playNow(trackOne)
            runCurrent()
            controller.addToQueue(trackTwo)
            controller.addToQueue(trackThree)
            controller.skipNext()
            runCurrent()
            controller.skipNext()
            runCurrent()
            // Force positionMs to 2000 ms (sub-threshold) directly via seekTo.
            controller.seekTo(2_000L)
            advanceTimeBy(DefaultPlayerController.SEEK_DEBOUNCE_MS + 5L)
            runCurrent()
            assertEquals(2, controller.playerState.value.currentQueueIndex)
            assertEquals(2_000L, controller.playerState.value.positionMs)
            transport.events.clear()

            controller.skipPrevious()
            runCurrent()

            // Must walk back to idx=1 (trackTwo).
            assertEquals(1, controller.playerState.value.currentQueueIndex)
            assertEquals(trackTwo, controller.playerState.value.currentTrack)
            assertTrue(transport.events.any { it.startsWith("playTrack") })
            // seekTo(0) must NOT have been called as part of the skip-back decision.
            assertEquals(emptyList<Long>(), transport.seekCalls)
        }

    // Test 3: position ≤ threshold + idx == 0 → restart from 0 (no previous track, restart is better than no-op).
    @Test
    fun `skipPrevious below threshold at idx zero restarts current track`() =
        runTest(dispatcher) {
            val transport = FakePlaybackTransport()
            val controller = makeController(transport)

            controller.playNow(trackOne)
            runCurrent()
            controller.seekTo(2_000L)
            advanceTimeBy(DefaultPlayerController.SEEK_DEBOUNCE_MS + 5L)
            runCurrent()
            assertEquals(0, controller.playerState.value.currentQueueIndex)
            assertEquals(2_000L, controller.playerState.value.positionMs)
            transport.events.clear()

            controller.skipPrevious()
            advanceTimeBy(DefaultPlayerController.SEEK_DEBOUNCE_MS + 5L)
            runCurrent()

            // Restarts current track: seekTo(0), index unchanged, no playTrack.
            assertEquals(0, controller.playerState.value.currentQueueIndex)
            assertEquals(0L, controller.playerState.value.positionMs)
            assertTrue(transport.seekCalls.contains(0L))
            assertTrue(transport.events.none { it.startsWith("playTrack") })
        }

    // Test 4: position == 3000 ms (exact boundary) → walk back (strict >, not >=).
    @Test
    fun `skipPrevious at exactly threshold boundary walks back not restarts`() =
        runTest(dispatcher) {
            val transport = FakePlaybackTransport()
            val controller = makeController(transport)

            // Two-track queue at idx=1, positionMs = 3000 ms exactly (boundary).
            controller.playNow(trackOne)
            runCurrent()
            controller.addToQueue(trackTwo)
            controller.skipNext()
            runCurrent()
            controller.seekTo(3_000L)
            advanceTimeBy(DefaultPlayerController.SEEK_DEBOUNCE_MS + 5L)
            runCurrent()
            assertEquals(1, controller.playerState.value.currentQueueIndex)
            assertEquals(3_000L, controller.playerState.value.positionMs)
            transport.events.clear()

            controller.skipPrevious()
            runCurrent()

            // 3000 ms == threshold, not > threshold → walk back to idx=0.
            assertEquals(0, controller.playerState.value.currentQueueIndex)
            assertEquals(trackOne, controller.playerState.value.currentTrack)
            assertTrue(transport.events.any { it.startsWith("playTrack") })
            // seekTo(0) must NOT have been called as the restart-rewind path.
            assertEquals(emptyList<Long>(), transport.seekCalls)
        }

    // Test 5: engineLoaded = false (LOADING) + position > threshold → walk back (not restart).
    @Test
    fun `skipPrevious in LOADING state ignores threshold and walks back`() =
        runTest(dispatcher) {
            // Use a transport that never completes playTrack so engineLoaded stays false.
            val transport = object : PlaybackTransport {
                val events = mutableListOf<String>()
                override fun setListener(l: PlaybackTransportListener?) = Unit
                override suspend fun playTrack(r: PlaybackRequest): PlaybackResult {
                    events += "playTrack"
                    // Suspend indefinitely — engineLoaded never flips to true.
                    kotlinx.coroutines.awaitCancellation()
                }
                override suspend fun pause() { events += "pause" }
                override suspend fun resume() { events += "resume" }
                override suspend fun seekTo(positionMs: Long) { events += "seekTo($positionMs)" }
                override suspend fun stopAndClearCurrent() { events += "stopAndClear" }
                override suspend fun setShuffleMode(enabled: Boolean) = Unit
                override suspend fun setRepeatMode(mode: Int) = Unit
                override suspend fun setPlaybackSpeed(speed: Float) = Unit
            }
            runTest(dispatcher) {
                val controller = makeController(transport)

                // Start a two-track queue; playQueueItem suspends so engineLoaded stays false.
                launch {
                    controller.setQueueAndPlay(listOf(trackOne, trackTwo), startIndex = 1)
                }
                runCurrent()
                // engineLoaded is false because playTrack never returned.
                assertEquals(false, controller.playerState.value.engineLoaded)
                assertEquals(1, controller.playerState.value.currentQueueIndex)
                // Manually push positionMs to 10_000 ms (well above threshold).
                // We cannot use seekTo here because the LOADING guard inside seekTo
                // clamps to durationMs. Instead assert the engineLoaded guard in skipPrevious.
                transport.events.clear()

                // Skip previous while engineLoaded = false and position would be above threshold.
                // The controller's positionMs is 0 right now (fresh LOADING state) but the
                // engineLoaded guard must short-circuit before the positionMs check anyway.
                controller.skipPrevious()
                runCurrent()

                // Must walk back to idx=0 regardless of position, because engine is not loaded.
                assertEquals(0, controller.playerState.value.currentQueueIndex)
                assertEquals(trackOne, controller.playerState.value.currentTrack)
                // No seekTo on the transport (no restart-rewind path taken).
                assertTrue(transport.events.none { it.startsWith("seekTo") })
            }
        }

    companion object {
        private val trackOne = Track("one", "Track One", "Channel A", 180, "")
        private val trackTwo = Track("two", "Track Two", "Channel B", 240, "")
        private val trackThree = Track("three", "Track Three", "Channel C", 200, "")

        private fun searchResult(videoId: String, durationSec: Int = 180) = SearchResult(
            videoId = videoId,
            title = "Title $videoId",
            channel = "Channel $videoId",
            durationSec = durationSec,
            thumbnailUrl = "https://img/$videoId",
        )
    }
}
