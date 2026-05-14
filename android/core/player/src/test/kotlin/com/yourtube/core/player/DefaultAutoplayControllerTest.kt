package com.yourtube.core.player

import com.yourtube.core.common.model.PlayerState
import com.yourtube.core.common.model.SearchResult
import com.yourtube.core.common.model.Track
import com.yourtube.core.data.preferences.AutoplayPreferences
import com.yourtube.core.database.entity.PlayerSnapshotEntity
import com.yourtube.core.network.ResolvedAudioStream
import com.yourtube.core.network.YoutubeService
import dagger.Lazy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest

/**
 * YT-0089 — unit tests for [DefaultAutoplayController].
 *
 * All tests use hand-rolled fakes; no mocking libraries are used per project conventions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DefaultAutoplayControllerTest {

    // ── Fakes ─────────────────────────────────────────────────────────────────────────────

    private class FakeAutoplayPreferences(initial: Boolean = true) : AutoplayPreferences {
        val state = MutableStateFlow(initial)
        override val autoplayEnabled: Flow<Boolean> = state
        override suspend fun setAutoplayEnabled(enabled: Boolean) { state.value = enabled }
    }

    private class FakeYoutubeService(
        private val related: List<SearchResult> = emptyList(),
        private val shouldThrow: Boolean = false,
    ) : YoutubeService {
        var lastRelatedVideoIdQueried: String? = null

        override suspend fun searchVideos(query: String, sp: String?): List<SearchResult> = emptyList()

        override suspend fun resolveAudioStream(
            videoId: String,
            preferredMaxBitrateKbps: Int,
        ): ResolvedAudioStream = throw UnsupportedOperationException("not used in autoplay tests")

        override suspend fun getRelatedVideos(videoId: String): List<SearchResult> {
            lastRelatedVideoIdQueried = videoId
            if (shouldThrow) throw RuntimeException("network error")
            return related
        }
        // YT-0293 — not exercised by autoplay tests; return empty list.
        override suspend fun getMixQueue(videoId: String): List<SearchResult> = emptyList()
        // YT-0297 — not exercised by autoplay tests; return empty page.
        override suspend fun getMixQueueWithContinuation(videoId: String): com.yourtube.core.network.MixPage =
            com.yourtube.core.network.MixPage.empty()
        override suspend fun getMixContinuation(token: String): com.yourtube.core.network.MixPage =
            com.yourtube.core.network.MixPage.empty()
    }

    private class FakePlayerController : PlayerController {
        val enqueuedTracks = mutableListOf<Track>()
        override val playerState: StateFlow<PlayerState> = MutableStateFlow(PlayerState())

        override suspend fun playNow(track: Track) {}
        override suspend fun setQueueAndPlay(tracks: List<Track>, startIndex: Int) {}
        override suspend fun addToQueue(track: Track) { enqueuedTracks.add(track) }
        override suspend fun playNext(track: Track) {}
        override suspend fun skipNext() {}
        override suspend fun skipPrevious() {}
        override suspend fun pause() {}
        override suspend fun resume() {}
        override suspend fun seekTo(positionMs: Long) {}
        override suspend fun removeQueueItem(queueId: String) {}
        override suspend fun moveQueueItem(fromIndex: Int, toIndex: Int) {}
        override suspend fun jumpToQueueItem(index: Int) {}
        override suspend fun setShuffleMode(enabled: Boolean) {}
        override suspend fun setRepeatMode(mode: Int) {}
        override suspend fun setPlaybackSpeed(speed: Float) {}
        override suspend fun restoreFromSnapshot(snapshot: PlayerSnapshotEntity) {}
        override suspend fun ensureRestored() {}
    }

    private fun makeCandidate(
        videoId: String,
        durationSec: Int = 180,
        title: String = "Track $videoId",
        channel: String = "Channel",
        thumbnailUrl: String = "",
    ) = SearchResult(videoId, title, channel, durationSec, thumbnailUrl)

    private fun makeController(
        related: List<SearchResult> = emptyList(),
        autoplayEnabled: Boolean = true,
        shouldThrow: Boolean = false,
    ): Triple<DefaultAutoplayController, FakeYoutubeService, FakePlayerController> {
        val service = FakeYoutubeService(related = related, shouldThrow = shouldThrow)
        val prefs = FakeAutoplayPreferences(initial = autoplayEnabled)
        val fakePlayerController = FakePlayerController()
        // YT-0100: DefaultAutoplayController now takes Lazy<PlayerController> to break the
        // Dagger DependencyCycle. Wrap the fake in a trivial Lazy implementation for tests.
        val controller = DefaultAutoplayController(
            youtubeService = service,
            autoplayPreferences = prefs,
            playerController = Lazy { fakePlayerController },
        )
        return Triple(controller, service, fakePlayerController)
    }

    // ── isEligible unit tests ────────────────────────────────────────────────────────────

    @Test
    fun `isEligible returns false for blank videoId`() {
        val (controller, _, _) = makeController()
        assertFalse(controller.isEligible("", durationSec = 180))
        assertFalse(controller.isEligible("   ", durationSec = 180))
    }

    @Test
    fun `isEligible returns false for durationSec less than 60`() {
        val (controller, _, _) = makeController()
        assertFalse(controller.isEligible("abc", durationSec = 59))
        assertFalse(controller.isEligible("abc", durationSec = 0))
        assertFalse(controller.isEligible("abc", durationSec = -1))
    }

    @Test
    fun `isEligible returns true for durationSec exactly 60`() {
        val (controller, _, _) = makeController()
        assertTrue(controller.isEligible("abc", durationSec = 60))
    }

    @Test
    fun `isEligible returns false when videoId is in history buffer`() {
        val (controller, _, _) = makeController()
        controller.onTrackStarted("abc")
        assertFalse(controller.isEligible("abc", durationSec = 180))
    }

    @Test
    fun `isEligible returns true when videoId is not in history buffer`() {
        val (controller, _, _) = makeController()
        controller.onTrackStarted("other")
        assertTrue(controller.isEligible("abc", durationSec = 180))
    }

    // ── History buffer tests ─────────────────────────────────────────────────────────────

    @Test
    fun `history buffer caps at HISTORY_CAPACITY and evicts oldest entry`() {
        val (controller, _, _) = makeController()

        // Fill the buffer to capacity with track_0..track_19 (20 entries).
        repeat(DefaultAutoplayController.HISTORY_CAPACITY) { i ->
            controller.onTrackStarted("track_$i")
        }

        // Buffer is at full capacity. track_0 IS in the buffer so it is NOT eligible.
        assertFalse(controller.isEligible("track_0", durationSec = 180))
        assertFalse(controller.isEligible("track_19", durationSec = 180))

        // Adding one more entry evicts the oldest (track_0).
        controller.onTrackStarted("track_overflow")

        // track_0 was evicted — it is now eligible again.
        assertTrue(controller.isEligible("track_0", durationSec = 180))
        // track_1..track_19 and track_overflow remain in the buffer.
        assertFalse(controller.isEligible("track_1", durationSec = 180))
        assertFalse(controller.isEligible("track_overflow", durationSec = 180))
    }

    @Test
    fun `onTrackStarted ignores blank videoIds`() {
        val (controller, _, _) = makeController()
        controller.onTrackStarted("")
        controller.onTrackStarted("   ")
        // Non-blank id is still eligible — blank entries were not recorded.
        assertTrue(controller.isEligible("abc", durationSec = 180))
    }

    // ── fetchAndEnqueue integration tests ────────────────────────────────────────────────

    @Test
    fun `fetchAndEnqueue returns false when autoplay is disabled`() = runTest {
        val (controller, service, playerController) = makeController(
            related = listOf(makeCandidate("vid1")),
            autoplayEnabled = false,
        )

        val result = controller.fetchAndEnqueue("finished")

        assertFalse(result)
        assertEquals(null, service.lastRelatedVideoIdQueried)
        assertEquals(emptyList(), playerController.enqueuedTracks)
    }

    @Test
    fun `fetchAndEnqueue returns false when related list is empty`() = runTest {
        val (controller, _, playerController) = makeController(
            related = emptyList(),
            autoplayEnabled = true,
        )

        val result = controller.fetchAndEnqueue("finished")

        assertFalse(result)
        assertEquals(emptyList(), playerController.enqueuedTracks)
    }

    @Test
    fun `fetchAndEnqueue returns false on network failure`() = runTest {
        val (controller, _, playerController) = makeController(
            shouldThrow = true,
            autoplayEnabled = true,
        )

        val result = controller.fetchAndEnqueue("finished")

        assertFalse(result)
        assertEquals(emptyList(), playerController.enqueuedTracks)
    }

    @Test
    fun `fetchAndEnqueue skips Short candidates (durationSec less than 60)`() = runTest {
        val (controller, _, playerController) = makeController(
            related = listOf(
                makeCandidate("short1", durationSec = 30),
                makeCandidate("short2", durationSec = 0),
                makeCandidate("eligible", durationSec = 120),
            ),
        )

        val result = controller.fetchAndEnqueue("finished")

        assertTrue(result)
        assertEquals(1, playerController.enqueuedTracks.size)
        assertEquals("eligible", playerController.enqueuedTracks.first().videoId)
    }

    @Test
    fun `fetchAndEnqueue skips candidates already in history buffer`() = runTest {
        val (controller, _, playerController) = makeController(
            related = listOf(
                makeCandidate("already_played"),
                makeCandidate("fresh_track"),
            ),
        )
        controller.onTrackStarted("already_played")

        val result = controller.fetchAndEnqueue("finished")

        assertTrue(result)
        assertEquals("fresh_track", playerController.enqueuedTracks.first().videoId)
    }

    @Test
    fun `fetchAndEnqueue returns false when no candidate passes all eligibility filters`() =
        runTest {
            val (controller, _, playerController) = makeController(
                related = listOf(
                    makeCandidate("vid1", durationSec = 30),
                    makeCandidate("vid2", durationSec = 59),
                ),
            )

            val result = controller.fetchAndEnqueue("finished")

            assertFalse(result)
            assertEquals(emptyList(), playerController.enqueuedTracks)
        }

    @Test
    fun `fetchAndEnqueue picks first eligible candidate in extractor order`() = runTest {
        val (controller, _, playerController) = makeController(
            related = listOf(
                makeCandidate("first", durationSec = 180),
                makeCandidate("second", durationSec = 180),
                makeCandidate("third", durationSec = 180),
            ),
        )

        val result = controller.fetchAndEnqueue("finished")

        assertTrue(result)
        assertEquals("first", playerController.enqueuedTracks.first().videoId)
    }

    @Test
    fun `fetchAndEnqueue enqueues exactly one candidate`() = runTest {
        val (controller, _, playerController) = makeController(
            related = listOf(
                makeCandidate("a", durationSec = 180),
                makeCandidate("b", durationSec = 180),
            ),
        )

        controller.fetchAndEnqueue("finished")

        assertEquals(1, playerController.enqueuedTracks.size)
    }

    @Test
    fun `fetchAndEnqueue maps SearchResult fields onto Track correctly`() = runTest {
        val candidate = makeCandidate(
            videoId = "vid1",
            durationSec = 240,
            title = "My Song",
            channel = "My Artist",
            thumbnailUrl = "https://example.com/thumb.jpg",
        )
        val (controller, _, playerController) = makeController(related = listOf(candidate))

        controller.fetchAndEnqueue("finished")

        val enqueued = playerController.enqueuedTracks.first()
        assertEquals("vid1", enqueued.videoId)
        assertEquals("My Song", enqueued.title)
        assertEquals("My Artist", enqueued.channel)
        assertEquals(240, enqueued.durationSec)
        assertEquals("https://example.com/thumb.jpg", enqueued.thumbnailUrl)
    }

    @Test
    fun `fetchAndEnqueue queries the service with the correct finishedVideoId`() = runTest {
        val (controller, service, _) = makeController(
            related = listOf(makeCandidate("vid1")),
        )

        controller.fetchAndEnqueue("the_finished_track")

        assertEquals("the_finished_track", service.lastRelatedVideoIdQueried)
    }

    // ── Language preference tests ────────────────────────────────────────────────────────

    @Test
    fun `titleMatchesLanguage returns false for blank title`() {
        val (controller, _, _) = makeController()
        assertFalse(controller.titleMatchesLanguage("", "ja"))
        assertFalse(controller.titleMatchesLanguage("   ", "ja"))
    }

    @Test
    fun `titleMatchesLanguage returns false for unknown language`() {
        val (controller, _, _) = makeController()
        // English titles with ASCII text — no special block match
        assertFalse(controller.titleMatchesLanguage("Hello World", "en"))
        assertFalse(controller.titleMatchesLanguage("Song Title", "fr"))
    }

    @Test
    fun `titleMatchesLanguage detects Japanese hiragana`() {
        val (controller, _, _) = makeController()
        assertTrue(controller.titleMatchesLanguage("こんにちは", "ja"))
    }

    @Test
    fun `titleMatchesLanguage detects Japanese katakana`() {
        val (controller, _, _) = makeController()
        assertTrue(controller.titleMatchesLanguage("アリガト", "ja"))
    }

    @Test
    fun `titleMatchesLanguage detects Korean hangul`() {
        val (controller, _, _) = makeController()
        assertTrue(controller.titleMatchesLanguage("안녕하세요", "ko"))
    }

    @Test
    fun `titleMatchesLanguage detects Arabic`() {
        val (controller, _, _) = makeController()
        assertTrue(controller.titleMatchesLanguage("مرحبا", "ar"))
    }
}
