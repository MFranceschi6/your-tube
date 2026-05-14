package com.yourtube.core.player

import com.yourtube.core.common.model.PlayerState
import com.yourtube.core.common.model.Track
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * YT-0239 — locks the `(hasPrev, hasNext) -> List<CommandButton>` rule used by
 * `PlaybackService.observeQueueBoundaryForCustomLayout` to publish the lock-screen /
 * notification custom layout.
 *
 * The Flow path that drives `MediaSession.setCustomLayout(...)` depends on a live
 * `MediaSession` and the service scope, neither of which is unit-testable in isolation.
 * The layout decision itself is a pure function of `QueueBoundary`, so we exercise the
 * extracted [buildCustomLayoutButtons] helper across the four boundary states. Passing
 * here keeps the no-flicker contract documented in `PlaybackService` (single emission =
 * single `setCustomLayout` call with the combined button list).
 *
 * Robolectric matches the existing [PlaybackSessionCommandTest] runner — `CommandButton`
 * construction touches `android.os.Bundle` static initializers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class PlaybackServiceLayoutTest {

    @Test
    fun `idx in middle of queue publishes both prev and next buttons`() {
        // queue=[A,B,C], currentQueueIndex=1 — both boundaries available; emission must
        // contain both buttons in a single list so the lock-screen layout never flickers
        // through a partial state.
        val buttons = buildCustomLayoutButtons(QueueBoundary(hasPrev = true, hasNext = true))

        assertEquals(2, buttons.size, "Mid-queue index must publish prev + next together.")
        // Stable order: prev then next so the visual placement does not jump when only one
        // boundary changes between emissions.
        assertEquals(
            PlaybackSessionCommand.SKIP_TO_PREV_QUEUE_ACTION,
            buttons[0].sessionCommand?.customAction,
        )
        assertEquals(
            PlaybackSessionCommand.SKIP_TO_NEXT_QUEUE_ACTION,
            buttons[1].sessionCommand?.customAction,
        )
    }

    @Test
    fun `idx zero publishes both prev and next buttons`() {
        // YT-0239 (round-3 spec amendment, 2026-05-08T23:30) — at idx=0 the prev button MUST
        // remain visible; tapping it falls through to a `seekTo(0L)` rewind of the current
        // track via `playerController.skipPrevious()` (single source of truth). Visibility is
        // gated on `currentTrack != null` rather than `currentQueueIndex > 0`, so the
        // boundary feeding this helper has `hasPrev = true` at idx=0. Order [prev, next] is
        // preserved so the lock-screen button placement does not shift between emissions.
        val buttons = buildCustomLayoutButtons(QueueBoundary(hasPrev = true, hasNext = true))

        assertEquals(2, buttons.size, "idx=0 must publish prev + next together.")
        assertEquals(
            PlaybackSessionCommand.SKIP_TO_PREV_QUEUE_ACTION,
            buttons[0].sessionCommand?.customAction,
        )
        assertEquals(
            PlaybackSessionCommand.SKIP_TO_NEXT_QUEUE_ACTION,
            buttons[1].sessionCommand?.customAction,
        )
    }

    @Test
    fun `idx last in queue of three publishes only the prev button`() {
        // queue=[A,B,C], currentQueueIndex=2 — no next neighbour, so the next custom button
        // must not appear; the prev custom button must still publish so lock-screen prev
        // can cross the queue boundary backward.
        val buttons = buildCustomLayoutButtons(QueueBoundary(hasPrev = true, hasNext = false))

        assertEquals(1, buttons.size)
        assertEquals(
            PlaybackSessionCommand.SKIP_TO_PREV_QUEUE_ACTION,
            buttons[0].sessionCommand?.customAction,
        )
    }

    @Test
    fun `single item queue publishes empty layout`() {
        // queue=[A], currentQueueIndex=0 — neither boundary applies; the empty list signals
        // the provider to fall back to its default play/pause-only layout.
        val buttons = buildCustomLayoutButtons(QueueBoundary(hasPrev = false, hasNext = false))

        assertTrue(buttons.isEmpty(), "No-neighbour state must publish an empty layout.")
    }

    /**
     * YT-0076 review change-request (2026-05-08) — `currentTrackPrewarmFlow` must emit exactly
     * once per distinct `currentTrack.videoId`. The bug surfaced because position / playback-
     * status updates flowing through `playerState` were not producing notification frames
     * with prewarmed artwork on cold-launch; the new observer triggers a prewarm on every
     * track change, but must NOT re-prewarm on every position tick.
     *
     * Flow contract: A, A, B, B, A → emits A, B, A. Null entries (no track) are dropped.
     */
    @Test
    fun `currentTrackPrewarmFlow emits once per distinct videoId`() = runTest {
        val trackA = trackWithId("A")
        val trackB = trackWithId("B")
        val source = flowOf(
            PlayerState(currentTrack = null), // dropped — nothing to prewarm yet
            PlayerState(currentTrack = trackA),
            PlayerState(currentTrack = trackA, positionMs = 1_000L), // dedup — same videoId
            PlayerState(currentTrack = trackA, positionMs = 5_000L), // dedup
            PlayerState(currentTrack = trackB),
            PlayerState(currentTrack = trackB, positionMs = 2_000L), // dedup
            PlayerState(currentTrack = null), // dropped
            PlayerState(currentTrack = trackA), // re-emit — distinct from previous trackB
        )

        val emissions = currentTrackPrewarmFlow(source).toList().map { it.videoId }

        assertEquals(
            listOf("A", "B", "A"),
            emissions,
            "currentTrackPrewarmFlow must emit one Track per distinct videoId, with nulls dropped.",
        )
    }

    @Test
    fun `currentTrackPrewarmFlow emits nothing when current track stays null`() = runTest {
        val source = flowOf(
            PlayerState(currentTrack = null),
            PlayerState(currentTrack = null, positionMs = 100L),
        )

        val emissions = currentTrackPrewarmFlow(source).toList()

        assertTrue(emissions.isEmpty(), "Null current track must not trigger a prewarm.")
    }

    private fun trackWithId(videoId: String): Track = Track(
        videoId = videoId,
        title = "title-$videoId",
        channel = "channel",
        durationSec = 120,
        thumbnailUrl = "https://example.com/$videoId.jpg",
    )

    // ---------------------------------------------------------------------------------------
    // YT-0285 — `shouldRestoreOnBind` is the predicate that guards the on-bind restore path.
    //
    // The production gap: `PlaybackService.onCreate` schedules `restoreFromSnapshot` in a
    // `serviceScope.launch {}` — asynchronous. The Activity may bind and compose before
    // the coroutine runs. At bind time, if `playerController.playerState.value.currentTrack`
    // is still null, the MiniPlayer gate evaluates to false and the MiniPlayer never renders
    // on the relaunch screen.
    //
    // Fix: `onBind()` calls `shouldRestoreOnBind(currentTrack)`. When true, it launches a
    // second restore coroutine on `serviceScope` so the controller is populated before or
    // shortly after the first composition. The predicate is pure so it is testable here.
    //
    // These tests are pure (no Robolectric, no MediaSession). They run under Robolectric
    // only because the surrounding class already declares the runner.
    // ---------------------------------------------------------------------------------------

    @Test
    fun `shouldRestoreOnBind returns true when controller has no current track`() {
        // The controller just started, snapshot not yet applied → currentTrack is null.
        assertTrue(
            shouldRestoreOnBind(currentTrack = null),
            "shouldRestoreOnBind must return true when currentTrack is null so the on-bind " +
                "path triggers snapshot restore and populates the MiniPlayer.",
        )
    }

    @Test
    fun `shouldRestoreOnBind returns false when controller already has a current track`() {
        // The async onCreate restore already ran (or the user started playback) — no re-restore needed.
        val liveTrack = Track(
            videoId = "abc123",
            title = "Live Track",
            channel = "Channel",
            durationSec = 180,
            thumbnailUrl = "",
        )
        assertFalse(
            shouldRestoreOnBind(currentTrack = liveTrack),
            "shouldRestoreOnBind must return false when currentTrack is already populated — " +
                "we must not clobber live state with a stale snapshot.",
        )
    }

    // ---------------------------------------------------------------------------------------
    // YT-0241 — `decideOnTaskRemoved` covers the toggle-driven branch of `onTaskRemoved`.
    //
    // Toggle ON (`stopOnTaskRemoved = true`): the user has opted into a single-gesture kill,
    // so the action must always be `StopPlayerAndService` regardless of `playWhenReady` or
    // `mediaItemCount`. `PlaybackService.onTaskRemoved` translates this into
    // `mediaSession.player.stop()` followed by `stopSelf()` so the foreground notification
    // is dismissed and audio is silenced in one step.
    //
    // Toggle OFF (`stopOnTaskRemoved = false`): the existing YT-0076 AC#5 contract is
    // preserved — `stopSelf()` only when there is no active playback (idle, paused, or
    // empty queue), otherwise the foreground notification persists Spotify-style.
    //
    // Tests are pure (no Robolectric, no MediaSession). They run on the Robolectric runner
    // only because the surrounding class already declares it; the assertions here would also
    // pass on a plain JUnit runner.
    // ---------------------------------------------------------------------------------------

    @Test
    fun `decideOnTaskRemoved with toggle ON stops player and service while playing`() {
        // Toggle ON, currently playing audible audio. Must stop both regardless.
        val action = decideOnTaskRemoved(
            stopOnTaskRemoved = true,
            playWhenReady = true,
            mediaItemCount = 1,
        )
        assertEquals(OnTaskRemovedAction.StopPlayerAndService, action)
    }

    @Test
    fun `decideOnTaskRemoved with toggle ON stops player and service while paused`() {
        // Toggle ON, paused but with a track loaded. Spec: stop both regardless of
        // playWhenReady — the user wants the app gone, not just paused.
        val action = decideOnTaskRemoved(
            stopOnTaskRemoved = true,
            playWhenReady = false,
            mediaItemCount = 1,
        )
        assertEquals(OnTaskRemovedAction.StopPlayerAndService, action)
    }

    @Test
    fun `decideOnTaskRemoved with toggle ON stops player and service when queue empty`() {
        // Toggle ON, empty queue. Still stop both — the toggle decision is dominant.
        val action = decideOnTaskRemoved(
            stopOnTaskRemoved = true,
            playWhenReady = false,
            mediaItemCount = 0,
        )
        assertEquals(OnTaskRemovedAction.StopPlayerAndService, action)
    }

    @Test
    fun `decideOnTaskRemoved with toggle OFF preserves YT-0076 stopSelf when paused`() {
        // Toggle OFF, paused. YT-0076 AC#5: stopSelf so the service is not kept alive
        // unnecessarily. Player is NOT stopped — the notification is already cleared by
        // the foreground-state exit.
        val action = decideOnTaskRemoved(
            stopOnTaskRemoved = false,
            playWhenReady = false,
            mediaItemCount = 1,
        )
        assertEquals(OnTaskRemovedAction.StopServiceOnly, action)
    }

    @Test
    fun `decideOnTaskRemoved with toggle OFF preserves YT-0076 stopSelf when queue empty`() {
        val action = decideOnTaskRemoved(
            stopOnTaskRemoved = false,
            playWhenReady = true,
            mediaItemCount = 0,
        )
        assertEquals(OnTaskRemovedAction.StopServiceOnly, action)
    }

    @Test
    fun `decideOnTaskRemoved with toggle OFF preserves YT-0076 persistence while playing`() {
        // Toggle OFF, audible playback. YT-0076 AC#5: notification persists, service stays
        // alive — None action means `onTaskRemoved` falls through without calling stopSelf.
        val action = decideOnTaskRemoved(
            stopOnTaskRemoved = false,
            playWhenReady = true,
            mediaItemCount = 1,
        )
        assertEquals(OnTaskRemovedAction.None, action)
    }

    // ── YT-0291 L1: buildQueueBoundary — skip-next at queue end respects autoplay state ────

    @Test
    fun `buildQueueBoundary at queue end with autoplay ON sets hasNext true`() {
        // Queue end: queue.size (0 or ≤ index+1) → hasNext from queue is false.
        // With autoplay ON and a current track, the autoplay-extension rule kicks in.
        val state = PlayerState(
            currentTrack = trackWithId("A"),
            currentQueueIndex = -1, // default "no queue" state
            // queue = emptyList() by default — 0 > (-1+1)=0 → false from queue
        )
        val boundary = buildQueueBoundary(state, autoplayEnabled = true)

        assertTrue(
            boundary.hasNext,
            "skip-next must be available at queue end when autoplay is ON",
        )
        val buttons = buildCustomLayoutButtons(boundary)
        assertTrue(
            buttons.any { it.sessionCommand?.customAction == PlaybackSessionCommand.SKIP_TO_NEXT_QUEUE_ACTION },
            "skip-next CommandButton must be emitted when autoplay ON at queue end",
        )
    }

    @Test
    fun `buildQueueBoundary at queue end with autoplay OFF sets hasNext false`() {
        val state = PlayerState(
            currentTrack = trackWithId("A"),
            currentQueueIndex = -1,
        )
        val boundary = buildQueueBoundary(state, autoplayEnabled = false)

        assertFalse(
            boundary.hasNext,
            "skip-next must be absent at queue end when autoplay is OFF",
        )
    }

    @Test
    fun `buildQueueBoundary mid-queue always sets hasNext true regardless of autoplay`() {
        // Even with autoplay OFF, a next queue item means hasNext is true.
        val stateWithNextItem = PlayerState(
            currentTrack = trackWithId("A"),
            queue = listOf(
                com.yourtube.core.common.model.QueueItem(trackWithId("A"), "q0"),
                com.yourtube.core.common.model.QueueItem(trackWithId("B"), "q1"),
            ),
            currentQueueIndex = 0,
        )
        val boundary = buildQueueBoundary(stateWithNextItem, autoplayEnabled = false)

        assertTrue(boundary.hasNext, "skip-next must be available mid-queue even with autoplay OFF")
    }
}
