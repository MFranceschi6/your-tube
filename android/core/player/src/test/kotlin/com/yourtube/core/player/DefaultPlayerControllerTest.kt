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
    private val perfTracer = PlaybackPerfTracer(NoOpLogger)

    private fun makeController(transport: PlaybackTransport = FakePlaybackTransport()) =
        DefaultPlayerController(
            playbackTransport = transport,
            dispatcher = dispatcher,
            audioQualityPreferences = audioQualityPreferences,
            playlistRepository = playlistRepository,
            perfTracer = perfTracer,
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

    // YT-0239 (round-3 spec amendment, 2026-05-08T23:30): at idx=0 with
    // `positionMs <= RESTART_THRESHOLD_MS`, `skipPrevious()` MUST fall through to
    // `seekTo(0L)` of the current track instead of returning a silent no-op via
    // `playQueueItemAt(-1)`. The same call powers the lock-screen / notification prev
    // button (single source of truth in `DefaultPlayerController.skipPrevious()`), so
    // this assertion protects both the in-app NowPlaying skip-prev and the system
    // shell prev button at the head of the queue.
    //
    // We use the same seek-debounce timing as the existing YT-0193 seek tests: positions
    // are written into PlayerState synchronously, then a single `transport.seekTo(...)`
    // is dispatched after `SEEK_DEBOUNCE_MS` elapses. We assert `transport.events` ends
    // with `seekTo(0)` rather than checking the FIRST entry because the path also issues
    // the bookkeeping `stopAndClearCurrent` + `playTrack` from the initial `playNow`.
    @Test
    fun `skipPrevious at idx zero with sub threshold position rewinds via seekTo zero`() =
        runTest(dispatcher) {
            val transport = FakePlaybackTransport()
            val controller = makeController(transport)

            // queue=[A], currentQueueIndex=0. We seek the current track to 500 ms — well
            // below RESTART_THRESHOLD_MS (3_000 ms) — so the idx=0 sub-threshold branch
            // is the one under test.
            controller.playNow(trackOne)
            runCurrent()
            controller.seekTo(500L)
            advanceTimeBy(DefaultPlayerController.SEEK_DEBOUNCE_MS + 5L)
            runCurrent()
            // Sanity: at this point the position is 500 ms and the queue has a single
            // entry. The previous index would be -1 — the NEW idx=0 branch must rewind
            // instead of no-op.
            assertEquals(500L, controller.playerState.value.positionMs)
            assertEquals(0, controller.playerState.value.currentQueueIndex)
            transport.events.clear()

            controller.skipPrevious()
            // Drain the seek-debounce window so the trailing `transport.seekTo(...)`
            // dispatch fires. Same timing as the YT-0193 seek tests above.
            advanceTimeBy(DefaultPlayerController.SEEK_DEBOUNCE_MS + 5L)
            runCurrent()

            // PlayerState reflects the rewind synchronously.
            assertEquals(0L, controller.playerState.value.positionMs)
            // Engine-side: a SINGLE `seekTo(0)` lands on the transport. No
            // `stopAndClearCurrent` + `playTrack` re-bootstrap, no pause/resume bracket.
            assertEquals("seekTo(0)", transport.events.last())
            assertEquals(listOf(0L), transport.seekCalls)
            assertEquals(0, transport.pauseCalls)
            assertEquals(0, transport.resumeCalls)
            // Current entry is unchanged — we are still on trackOne at idx=0.
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
        assertEquals(PlaybackStatus.ERROR, state.playbackStatus)
        assertEquals(false, state.isPlaying)
        assertEquals(trackOne, state.currentTrack)
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
            assertEquals(
                listOf("stopAndClearCurrent", "playTrack"),
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
            assertEquals(
                listOf("stopAndClearCurrent", "playTrack"),
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
            events += "playTrack"
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

    private object NoOpLogger : Logger {
        override fun debug(tag: String, message: String) = Unit
        override fun info(tag: String, message: String) = Unit
        override fun warn(tag: String, message: String, throwable: Throwable?) = Unit
        override fun error(tag: String, message: String, throwable: Throwable?) = Unit
    }

    companion object {
        private val trackOne = Track("one", "Track One", "Channel A", 180, "")
        private val trackTwo = Track("two", "Track Two", "Channel B", 240, "")
        private val trackThree = Track("three", "Track Three", "Channel C", 200, "")
    }
}
