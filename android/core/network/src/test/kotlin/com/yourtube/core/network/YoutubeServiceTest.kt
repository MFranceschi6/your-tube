package com.yourtube.core.network

import com.yourtube.core.common.error.ExtractorError
import com.yourtube.core.common.model.SearchResult
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@OptIn(ExperimentalCoroutinesApi::class)
class YoutubeServiceTest {

    private val dispatcher = StandardTestDispatcher()

    @Test
    fun `search returns fixture-backed video results and caches repeated lookups`() = runTest(dispatcher) {
        val client = FixtureYoutubeExtractorClient()
        val service = NewPipeYoutubeService(
            extractorClient = client,
            dispatcher = dispatcher,
            searchCacheSize = 2,
        )

        val first = service.searchVideos("lofi")
        val second = service.searchVideos("lofi")

        assertEquals(1, client.searchInvocations["lofi"])
        assertEquals(first, second)
        assertEquals(
            listOf(
                SearchResult(
                    videoId = "alpha123",
                    title = "Late Night Coding Mix",
                    channel = "Open Waves",
                    durationSec = 3723,
                    thumbnailUrl = "https://img.youtube.com/vi/alpha123/mqdefault.jpg",
                ),
                SearchResult(
                    videoId = "beta456",
                    title = "Focus Session",
                    channel = "Desk Radio",
                    durationSec = 0,
                    thumbnailUrl = "https://img.youtube.com/vi/beta456/mqdefault.jpg",
                ),
            ),
            first,
        )
    }

    @Test
    fun `search cache stays bounded and evicts the oldest query`() = runTest(dispatcher) {
        val client = FixtureYoutubeExtractorClient()
        val service = NewPipeYoutubeService(
            extractorClient = client,
            dispatcher = dispatcher,
            searchCacheSize = 2,
        )

        service.searchVideos("first")
        service.searchVideos("second")
        service.searchVideos("third")
        service.searchVideos("first")

        assertEquals(2, client.searchInvocations["first"])
        assertEquals(1, client.searchInvocations["second"])
        assertEquals(1, client.searchInvocations["third"])
    }

    @Test
    fun `stream resolution picks the highest audio stream at or below preferred quality`() = runTest(dispatcher) {
        val service = NewPipeYoutubeService(
            extractorClient = FixtureYoutubeExtractorClient(),
            dispatcher = dispatcher,
        )

        val stream = service.resolveAudioStream(
            videoId = "alpha123",
            preferredMaxBitrateKbps = 160,
        )

        assertEquals(
            ResolvedAudioStream(
                videoId = "alpha123",
                streamUrl = "https://cdn.example.com/alpha-128.m4a",
                bitrateKbps = 128,
                codec = "mp4a.40.2",
                container = "m4a",
            ),
            stream,
        )
    }

    @Test
    fun `stream resolution falls back to the smallest stream above preferred quality`() = runTest(dispatcher) {
        val service = NewPipeYoutubeService(
            extractorClient = FixtureYoutubeExtractorClient(),
            dispatcher = dispatcher,
        )

        val stream = service.resolveAudioStream(
            videoId = "gamma789",
            preferredMaxBitrateKbps = 96,
        )

        assertEquals(160, stream.bitrateKbps)
        assertEquals("https://cdn.example.com/gamma-160.webm", stream.streamUrl)
    }

    @Test
    fun `extractor failures map to shared extractor errors`() = runTest(dispatcher) {
        // After YT-0163 the extractor surfaces ExtractorError directly (HTTP 429
        // is mapped to ExtractorError.BotChallenge inside LivePlayerExtractor;
        // playabilityStatus.UNPLAYABLE etc. is mapped to VideoUnavailable; any
        // IOException bubbles up as NetworkError). Tests cover those paths via
        // the throwing fake.
        val botService = NewPipeYoutubeService(
            extractorClient = ThrowingYoutubeExtractorClient(ExtractorError.BotChallenge),
            dispatcher = dispatcher,
        )
        val unavailableService = NewPipeYoutubeService(
            extractorClient = ThrowingYoutubeExtractorClient(ExtractorError.VideoUnavailable("video-42")),
            dispatcher = dispatcher,
        )
        val networkService = NewPipeYoutubeService(
            extractorClient = ThrowingYoutubeExtractorClient(IOException("offline")),
            dispatcher = dispatcher,
        )

        assertFailsWith<ExtractorError.BotChallenge> {
            botService.searchVideos("anything")
        }
        val unavailable = assertFailsWith<ExtractorError.VideoUnavailable> {
            unavailableService.resolveAudioStream("video-42")
        }
        assertEquals("video-42", unavailable.videoId)
        assertFailsWith<ExtractorError.NetworkError> {
            networkService.searchVideos("anything")
        }
    }

    @Test
    fun `unknown exception maps to ExtractorError Unknown`() = runTest(dispatcher) {
        val service = NewPipeYoutubeService(
            extractorClient = ThrowingYoutubeExtractorClient(RuntimeException("unexpected")),
            dispatcher = dispatcher,
        )
        val error = assertFailsWith<ExtractorError.Unknown> {
            service.searchVideos("anything")
        }
        assertEquals("unexpected", error.cause.message)
    }

    @Test
    fun `exception with bot-challenge message maps to BotChallenge`() = runTest(dispatcher) {
        val service = NewPipeYoutubeService(
            extractorClient = ThrowingYoutubeExtractorClient(RuntimeException("please confirm not a bot")),
            dispatcher = dispatcher,
        )
        assertFailsWith<ExtractorError.BotChallenge> {
            service.searchVideos("anything")
        }
    }

    @Test
    fun `blank query returns empty list without calling the extractor`() = runTest(dispatcher) {
        val client = FixtureYoutubeExtractorClient()
        val service = NewPipeYoutubeService(extractorClient = client, dispatcher = dispatcher)

        val result = service.searchVideos("   ")

        assertEquals(emptyList(), result)
        assertEquals(true, client.searchInvocations.isEmpty())
    }

    @Test
    fun `resolveAudioStream rejects non-positive preferredMaxBitrateKbps`() = runTest(dispatcher) {
        val service = NewPipeYoutubeService(
            extractorClient = FixtureYoutubeExtractorClient(),
            dispatcher = dispatcher,
        )
        assertFailsWith<IllegalArgumentException> {
            service.resolveAudioStream("alpha123", preferredMaxBitrateKbps = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            service.resolveAudioStream("alpha123", preferredMaxBitrateKbps = -1)
        }
    }

    @Test
    fun `CancellationException from extractor propagates as CancellationException not NetworkError`() = runTest(dispatcher) {
        // YT-0163 review B7: the catch site at NewPipeYoutubeService.kt:36-37
        // re-throws CancellationException ahead of the generic Throwable
        // mapping. Lock this so a cancelled coroutine never gets surfaced as
        // ExtractorError.NetworkError or .Unknown.
        val service = NewPipeYoutubeService(
            extractorClient = ThrowingYoutubeExtractorClient(
                kotlin.coroutines.cancellation.CancellationException("cancelled by parent"),
            ),
            dispatcher = dispatcher,
        )

        // Manually catch — kotlin.test.assertFailsWith would also re-throw the
        // CancellationException to the enclosing test scope and fail the test.
        var searchCaught: Throwable? = null
        try {
            service.searchVideos("anything")
        } catch (other: Throwable) {
            searchCaught = other
        }
        kotlin.test.assertTrue(
            searchCaught is kotlin.coroutines.cancellation.CancellationException,
            "Expected CancellationException from searchVideos, got $searchCaught (${searchCaught?.javaClass?.name})",
        )
        kotlin.test.assertTrue(
            searchCaught !is ExtractorError,
            "CancellationException must NOT be re-mapped to ExtractorError, got $searchCaught",
        )

        var resolveCaught: Throwable? = null
        try {
            service.resolveAudioStream("anything")
        } catch (other: Throwable) {
            resolveCaught = other
        }
        kotlin.test.assertTrue(
            resolveCaught is kotlin.coroutines.cancellation.CancellationException,
            "Expected CancellationException from resolveAudioStream, got $resolveCaught (${resolveCaught?.javaClass?.name})",
        )
        kotlin.test.assertTrue(
            resolveCaught !is ExtractorError,
            "CancellationException must NOT be re-mapped to ExtractorError, got $resolveCaught",
        )
    }

    @Test
    fun `youtubeUrlToVideoId extracts id from watch URL and falls back to last path segment`() {
        assertEquals("abc123", "https://www.youtube.com/watch?v=abc123".youtubeUrlToVideoId())
        assertEquals("abc123", "https://www.youtube.com/watch?v=abc123&list=PL1".youtubeUrlToVideoId())
        assertEquals("abc123", "https://www.youtube.com/shorts/abc123".youtubeUrlToVideoId())
    }

    @Test
    fun `youtubeUrlToVideoId returns empty string for malformed url with trailing slash`() {
        // Negative case for parity with iOS contract: no v= query, no usable last path
        // segment, so the helper degrades to an empty id rather than throwing.
        assertEquals("", "https://www.youtube.com/".youtubeUrlToVideoId())
    }

    private class ThrowingYoutubeExtractorClient(
        private val throwable: Throwable,
    ) : YoutubeExtractorClient {
        override fun searchVideos(query: String): List<SearchResult> = throw throwable

        override suspend fun getAudioStreams(
            videoId: String,
            preferredMaxBitrateKbps: Int,
        ): List<ExtractedAudioStream> = throw throwable
    }

    private class FixtureYoutubeExtractorClient : YoutubeExtractorClient {
        val searchInvocations = linkedMapOf<String, Int>()

        override fun searchVideos(query: String): List<SearchResult> {
            searchInvocations[query] = (searchInvocations[query] ?: 0) + 1
            return loadSearchFixture()
        }

        override suspend fun getAudioStreams(
            videoId: String,
            preferredMaxBitrateKbps: Int,
        ): List<ExtractedAudioStream> = loadStreamFixture().getValue(videoId)

        private fun loadSearchFixture(): List<SearchResult> {
            val fixture = loadFixture("youtube-search-fixture.json")
            return Json.parseToJsonElement(fixture).jsonArray.map { item ->
                val json = item.jsonObject
                SearchResult(
                    videoId = json.getValue("videoId").jsonPrimitive.content,
                    title = json.getValue("title").jsonPrimitive.content,
                    channel = json.getValue("channel").jsonPrimitive.content,
                    durationSec = json.getValue("durationSec").jsonPrimitive.content.toInt(),
                    thumbnailUrl = json.getValue("thumbnailUrl").jsonPrimitive.content,
                )
            }
        }

        private fun loadStreamFixture(): Map<String, List<ExtractedAudioStream>> {
            val fixture = loadFixture("youtube-stream-fixture.json")
            val root = Json.parseToJsonElement(fixture).jsonObject
            return root.mapValues { (_, streams) ->
                streams.jsonArray.map { stream ->
                    val json = stream.jsonObject
                    ExtractedAudioStream(
                        url = json.getValue("url").jsonPrimitive.content,
                        bitrateKbps = json.getValue("bitrateKbps").jsonPrimitive.content.toInt(),
                        codec = json["codec"]?.jsonPrimitive?.content,
                        container = json["container"]?.jsonPrimitive?.content,
                    )
                }
            }
        }

        private fun loadFixture(name: String): String =
            checkNotNull(javaClass.classLoader?.getResource(name)) { "Missing fixture: $name" }
                .readText()
    }
}
