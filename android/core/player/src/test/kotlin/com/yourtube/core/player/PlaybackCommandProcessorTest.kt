package com.yourtube.core.player

import com.yourtube.core.common.model.Track
import com.yourtube.core.network.ResolvedAudioStream
import com.yourtube.core.network.YoutubeService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull

class PlaybackCommandProcessorTest {

    @Test
    fun `resolved stream becomes queued prepared playback and starts playing`() = runTest {
        val track = Track(
            videoId = "alpha123",
            title = "Late Night Coding Mix",
            channel = "Open Waves",
            durationSec = 3723,
            thumbnailUrl = "https://img.youtube.com/vi/alpha123/mqdefault.jpg",
        )
        val youtubeService = FakeYoutubeService(
            resolvedAudioStream = ResolvedAudioStream(
                videoId = "alpha123",
                streamUrl = "https://cdn.example.com/audio-alpha.m4a",
                bitrateKbps = 128,
                codec = "mp4a.40.2",
                container = "m4a",
            ),
        )
        val playbackEngine = RecordingPlaybackEngine()
        val processor = PlaybackCommandProcessor(
            youtubeService = youtubeService,
            preparedPlaybackFactory = PreparedPlaybackFactory(),
            logger = NoOpLogger,
            perfTracer = PlaybackPerfTracer(NoOpLogger),
        )

        processor.playTrack(PlaybackRequest(track = track), playbackEngine)

        assertEquals("alpha123" to YoutubeService.DEFAULT_PREFERRED_MAX_BITRATE_KBPS, youtubeService.lastResolveRequest)
        assertEquals(
            PreparedPlayback(
                track = track,
                streamUrl = "https://cdn.example.com/audio-alpha.m4a",
                bitrateKbps = 128,
                codec = "mp4a.40.2",
                container = "m4a",
            ),
            playbackEngine.queuedPlayback,
        )
        assertEquals(listOf("queue", "prepare", "play"), playbackEngine.events)
    }

    @Test
    fun `smoke test accepts local fixture media urls`() = runTest {
        val request = PlaybackRequest(
            track = Track(
                videoId = "fixture-001",
                title = "Fixture Tone",
                channel = "Local Test",
                durationSec = 5,
                thumbnailUrl = "",
            ),
            preferredMaxBitrateKbps = 64,
        )
        val youtubeService = FakeYoutubeService(
            resolvedAudioStream = ResolvedAudioStream(
                videoId = "fixture-001",
                streamUrl = "file:///android_asset/audio/fixture-tone.mp3",
                bitrateKbps = 64,
                codec = "mp3",
                container = "mp3",
            ),
        )
        val playbackEngine = RecordingPlaybackEngine()
        val processor = PlaybackCommandProcessor(
            youtubeService = youtubeService,
            preparedPlaybackFactory = PreparedPlaybackFactory(),
            logger = NoOpLogger,
            perfTracer = PlaybackPerfTracer(NoOpLogger),
        )

        processor.playTrack(request, playbackEngine)

        assertEquals("file:///android_asset/audio/fixture-tone.mp3", playbackEngine.queuedPlayback?.streamUrl)
        assertEquals(listOf("queue", "prepare", "play"), playbackEngine.events)
    }

    // YT-0309 — service-side integration: when resolveAudioStream hangs and the caller wraps
    // with withTimeoutOrNull(SERVICE_RESOLVE_TIMEOUT_MS), the engine must NOT receive any
    // queue/prepare/play calls. This pins the PlaybackService.handlePlayTrack contract without
    // spinning up a real Android service.
    @Test
    fun `hung resolveAudioStream times out and engine receives no calls`() = runTest {
        val hangingService = object : YoutubeService {
            override suspend fun searchVideos(query: String, sp: String?) = emptyList<com.yourtube.core.common.model.SearchResult>()
            override suspend fun resolveAudioStream(videoId: String, preferredMaxBitrateKbps: Int): ResolvedAudioStream =
                awaitCancellation()  // suspends forever — simulates hung airplane-mode call
            override suspend fun getRelatedVideos(videoId: String) = emptyList<com.yourtube.core.common.model.SearchResult>()
            override suspend fun getMixQueue(videoId: String) = emptyList<com.yourtube.core.common.model.SearchResult>()
            override suspend fun getMixQueueWithContinuation(videoId: String) = com.yourtube.core.network.MixPage.empty()
            override suspend fun getMixContinuation(token: String) = com.yourtube.core.network.MixPage.empty()
        }
        val engine = RecordingPlaybackEngine()
        val processor = PlaybackCommandProcessor(
            youtubeService = hangingService,
            preparedPlaybackFactory = PreparedPlaybackFactory(),
            logger = NoOpLogger,
            perfTracer = PlaybackPerfTracer(NoOpLogger),
        )
        val timeoutMs = 15_000L

        var timedOut = false
        launch {
            val result = withTimeoutOrNull(timeoutMs) {
                runCatching { processor.playTrack(PlaybackRequest(track = stubTrack), engine) }.isSuccess
            }
            timedOut = (result == null)
        }

        advanceTimeBy(timeoutMs + 1L)
        runCurrent()

        assertTrue(timedOut, "withTimeoutOrNull must fire after $timeoutMs ms")
        assertTrue(engine.events.isEmpty(), "Engine must NOT receive queue/prepare/play on timeout: ${engine.events}")
    }

    private val stubTrack = Track(
        videoId = "stub",
        title = "Stub Track",
        channel = "Stub",
        durationSec = 180,
        thumbnailUrl = "",
    )

    private class FakeYoutubeService(
        private val resolvedAudioStream: ResolvedAudioStream,
    ) : YoutubeService {
        var lastResolveRequest: Pair<String, Int>? = null

        override suspend fun searchVideos(query: String, sp: String?) = emptyList<com.yourtube.core.common.model.SearchResult>()

        override suspend fun resolveAudioStream(
            videoId: String,
            preferredMaxBitrateKbps: Int,
        ): ResolvedAudioStream {
            lastResolveRequest = videoId to preferredMaxBitrateKbps
            return resolvedAudioStream
        }

        // YT-0089 — not exercised by this test; return empty list.
        override suspend fun getRelatedVideos(videoId: String): List<com.yourtube.core.common.model.SearchResult> = emptyList()
        // YT-0293 — not exercised by this test; return empty list.
        override suspend fun getMixQueue(videoId: String): List<com.yourtube.core.common.model.SearchResult> = emptyList()
        // YT-0297 — not exercised by this test; return empty page.
        override suspend fun getMixQueueWithContinuation(videoId: String): com.yourtube.core.network.MixPage =
            com.yourtube.core.network.MixPage.empty()
        override suspend fun getMixContinuation(token: String): com.yourtube.core.network.MixPage =
            com.yourtube.core.network.MixPage.empty()
    }

    private object NoOpLogger : Logger {
        override fun debug(tag: String, message: String) = Unit
        override fun info(tag: String, message: String) = Unit
        override fun warn(tag: String, message: String, throwable: Throwable?) = Unit
        override fun error(tag: String, message: String, throwable: Throwable?) = Unit
    }

    private class RecordingPlaybackEngine : PlaybackEngine {
        val events = mutableListOf<String>()
        var queuedPlayback: PreparedPlayback? = null

        override fun queue(preparedPlayback: PreparedPlayback) {
            events += "queue"
            queuedPlayback = preparedPlayback
        }

        override fun prepare() {
            events += "prepare"
        }

        override fun play() {
            events += "play"
        }
    }
}
