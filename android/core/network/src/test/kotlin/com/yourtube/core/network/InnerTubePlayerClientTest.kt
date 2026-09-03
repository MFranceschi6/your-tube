package com.yourtube.core.network

import com.yourtube.core.common.error.ExtractorError
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * Mirrors `ios/YourTubeTests/InnerTubePlayerClientTests.swift`. Uses an OkHttp
 * `Interceptor`-fake transport for parity with `InnerTubeSearchClientTest`.
 * Fixtures live under `core/network/src/test/resources/innertube-player-*.json`.
 */
class InnerTubePlayerClientTest {

    // region Audio-only selection (pure-function tests against the picker)

    @Test
    fun `picks the highest-bitrate audio-only mp4a within ceiling`() {
        val formats = listOf(
            adaptiveFormat(itag = 139, mime = "audio/mp4; codecs=\"mp4a.40.5\"", avgBps = 48_000),
            adaptiveFormat(itag = 140, mime = "audio/mp4; codecs=\"mp4a.40.2\"", avgBps = 130_000),
            adaptiveFormat(itag = 141, mime = "audio/mp4; codecs=\"mp4a.40.2\"", avgBps = 256_000),
        )
        val resolution = LivePlayerExtractor.pickAudioOnly(formats, preferredMaxBitrateKbps = 128)
        assertNotNull(resolution)
        assertTrue(resolution.audioUrl.endsWith("/itag/139"), "Got: ${resolution.audioUrl}")
    }

    @Test
    fun `with two candidates under ceiling picks the higher-bitrate one`() {
        // Both fit under 128 kbps: 64 kbps and 96 kbps. Picker must return 96.
        // Locks `maxByOrNull` against a future swap to `minByOrNull`.
        val formats = listOf(
            adaptiveFormat(itag = 139, mime = "audio/mp4; codecs=\"mp4a.40.5\"", avgBps = 64_000),
            adaptiveFormat(itag = 140, mime = "audio/mp4; codecs=\"mp4a.40.2\"", avgBps = 96_000),
        )
        val resolution = LivePlayerExtractor.pickAudioOnly(formats, preferredMaxBitrateKbps = 128)
        assertNotNull(resolution)
        assertTrue(resolution.audioUrl.endsWith("/itag/140"), "Got: ${resolution.audioUrl}")
    }

    @Test
    fun `falls back to lowest mp4a when no candidate respects the ceiling`() {
        val formats = listOf(
            adaptiveFormat(itag = 140, mime = "audio/mp4; codecs=\"mp4a.40.2\"", avgBps = 130_000),
            adaptiveFormat(itag = 141, mime = "audio/mp4; codecs=\"mp4a.40.2\"", avgBps = 256_000),
        )
        // Ceiling 48 kbps — both above. Lowest among above-ceiling is 140.
        val resolution = LivePlayerExtractor.pickAudioOnly(formats, preferredMaxBitrateKbps = 48)
        assertNotNull(resolution)
        assertTrue(resolution.audioUrl.endsWith("/itag/140"), "Got: ${resolution.audioUrl}")
    }

    @Test
    fun `prefers mp4a when both mp4a and opus are present`() {
        val formats = listOf(
            adaptiveFormat(itag = 251, mime = "audio/webm; codecs=\"opus\"", avgBps = 160_000),
            adaptiveFormat(itag = 140, mime = "audio/mp4; codecs=\"mp4a.40.2\"", avgBps = 130_000),
        )
        val resolution = LivePlayerExtractor.pickAudioOnly(formats, preferredMaxBitrateKbps = 192)
        assertNotNull(resolution)
        assertTrue(
            resolution.audioUrl.endsWith("/itag/140"),
            "mp4a should win over opus when both fit. Got: ${resolution.audioUrl}",
        )
    }

    @Test
    fun `falls back to opus webm when no mp4a is present (Android divergence)`() {
        val formats = listOf(
            adaptiveFormat(itag = 251, mime = "audio/webm; codecs=\"opus\"", avgBps = 160_000),
        )
        val resolution = LivePlayerExtractor.pickAudioOnly(formats, preferredMaxBitrateKbps = 192)
        assertNotNull(resolution)
        assertTrue(resolution.audioUrl.endsWith("/itag/251"), "Got: ${resolution.audioUrl}")
        assertEquals("webm", resolution.container)
    }

    @Test
    fun `rejects formats with both bitrate and averageBitrate null`() {
        val formats = listOf(
            adaptiveFormat(itag = 140, mime = "audio/mp4; codecs=\"mp4a.40.2\"", avgBps = 130_000),
            // Both bitrate AND averageBitrate null.
            InnerTubePlayerResponse.AdaptiveFormat(
                itag = 999,
                url = "https://example.invalid/itag/999",
                mimeType = "audio/mp4; codecs=\"mp4a.40.2\"",
                bitrate = null,
                averageBitrate = null,
            ),
        )
        // At ceiling 48 kbps the 130k overshoots and lowest-fallback runs.
        // The unsized 999 must NOT win: it's filtered out up-front.
        val resolution = LivePlayerExtractor.pickAudioOnly(formats, preferredMaxBitrateKbps = 48)
        assertNotNull(resolution)
        assertTrue(resolution.audioUrl.endsWith("/itag/140"), "Got: ${resolution.audioUrl}")
    }

    @Test
    fun `returns null when no audio-only candidates`() {
        val formats = listOf(
            adaptiveFormat(itag = 22, mime = "video/mp4; codecs=\"avc1.64001F, mp4a.40.2\"", avgBps = 1_500_000),
        )
        assertTrue(LivePlayerExtractor.pickAudioOnly(formats, preferredMaxBitrateKbps = 192) == null)
    }

    // endregion

    // region Muxed selection

    @Test
    fun `picks the highest-bitrate progressive mp4`() {
        val formats = listOf(
            adaptiveFormat(itag = 18, mime = "video/mp4; codecs=\"avc1.42001E, mp4a.40.2\"", avgBps = 300_000),
            adaptiveFormat(itag = 22, mime = "video/mp4; codecs=\"avc1.64001F, mp4a.40.2\"", avgBps = 1_500_000),
        )
        val resolution = LivePlayerExtractor.pickMuxed(formats)
        assertNotNull(resolution)
        assertTrue(resolution.audioUrl.endsWith("/itag/22"))
        assertEquals(PlayerResolution.AudioFormat.Muxed, resolution.kind)
    }

    @Test
    fun `skips non-mp4 progressive entries`() {
        val formats = listOf(
            adaptiveFormat(itag = 43, mime = "video/webm; codecs=\"vp8, vorbis\"", avgBps = 500_000),
        )
        assertTrue(LivePlayerExtractor.pickMuxed(formats) == null)
    }

    // endregion

    // region End-to-end resolve via OkHttp interceptor + JSON fixtures

    @Test
    fun `audio-only happy path returns mp4a stream`() = runTest {
        val client = cannedClient(code = 200, body = loadFixture("innertube-player-audio-only-ok.json"))
        val extractor = LivePlayerExtractor(client, visitorDataProvider = { null })

        val resolution = extractor.resolve("abc", preferredMaxBitrateKbps = 192)

        assertEquals(PlayerResolution.AudioFormat.AudioOnly, resolution.kind)
        assertTrue(resolution.audioUrl.contains("itag=140"))
        assertEquals("m4a", resolution.container)
        assertEquals("mp4a.40.2", resolution.codec)
    }

    @Test
    fun `mp4a wins over opus when both present in adaptive formats`() = runTest {
        val client = cannedClient(code = 200, body = loadFixture("innertube-player-audio-only-mp4a-vs-opus.json"))
        val extractor = LivePlayerExtractor(client, visitorDataProvider = { null })

        val resolution = extractor.resolve("abc", preferredMaxBitrateKbps = 192)

        assertTrue(
            resolution.audioUrl.contains("itag=140"),
            "Expected mp4a (itag=140) to win, got: ${resolution.audioUrl}",
        )
    }

    @Test
    fun `opus webm is selected when no mp4a is present (Android-only fallback)`() = runTest {
        val client = cannedClient(code = 200, body = loadFixture("innertube-player-audio-only-opus-fallback.json"))
        val extractor = LivePlayerExtractor(client, visitorDataProvider = { null })

        val resolution = extractor.resolve("abc", preferredMaxBitrateKbps = 192)

        assertTrue(resolution.audioUrl.contains("itag=251"))
        assertEquals("webm", resolution.container)
    }

    @Test
    fun `bandwidth ceiling under medium picks 96k from a 64k-96k pair`() = runTest {
        val client = cannedClient(code = 200, body = loadFixture("innertube-player-audio-only-ceiling.json"))
        val extractor = LivePlayerExtractor(client, visitorDataProvider = { null })

        // medium ceiling = 128 kbps. Both 64k and 96k fit; 96k must win.
        val resolution = extractor.resolve("abc", preferredMaxBitrateKbps = 128)

        assertTrue(
            resolution.audioUrl.contains("itag=140"),
            "Expected highest-under-ceiling (96 kbps, itag=140), got: ${resolution.audioUrl}",
        )
    }

    @Test
    fun `format with both bitrates null does not win`() = runTest {
        val client = cannedClient(code = 200, body = loadFixture("innertube-player-audio-only-bitrates-null.json"))
        val extractor = LivePlayerExtractor(client, visitorDataProvider = { null })

        // ceiling 48 kbps — 130k overshoots; lowest-fallback picks 140 (only sized candidate).
        val resolution = extractor.resolve("abc", preferredMaxBitrateKbps = 48)

        assertTrue(
            resolution.audioUrl.contains("itag=140"),
            "Unsized format must be rejected. Got: ${resolution.audioUrl}",
        )
    }

    @Test
    fun `livestream returns hls manifest with isLivestream true`() = runTest {
        val client = cannedClient(code = 200, body = loadFixture("innertube-player-livestream.json"))
        val extractor = LivePlayerExtractor(client, visitorDataProvider = { null })

        val resolution = extractor.resolve("live", preferredMaxBitrateKbps = 128)

        assertEquals(PlayerResolution.AudioFormat.Livestream, resolution.kind)
        assertTrue(resolution.isLivestream)
        assertEquals("https://manifest.googlevideo.com/hls/manifest.m3u8", resolution.audioUrl)
        assertEquals("hls", resolution.container)
    }

    @Test
    fun `muxed fallback when only progressive formats are present`() = runTest {
        val client = cannedClient(code = 200, body = loadFixture("innertube-player-muxed-only.json"))
        val extractor = LivePlayerExtractor(client, visitorDataProvider = { null })

        val resolution = extractor.resolve("old", preferredMaxBitrateKbps = 128)

        assertEquals(PlayerResolution.AudioFormat.Muxed, resolution.kind)
        assertEquals("https://example.invalid/muxed.mp4", resolution.audioUrl)
    }

    @Test
    fun `AGE_VERIFICATION_REQUIRED maps to VideoUnavailable`() = runTest {
        // YT-0163 review B6: validatePlayability handles AGE_VERIFICATION_REQUIRED
        // and CONTENT_CHECK_REQUIRED via the same branch as LOGIN_REQUIRED. One
        // end-to-end fixture is sufficient to lock that branch — both statuses
        // share the same mapping path inside validatePlayability.
        val client = cannedClient(code = 200, body = loadFixture("innertube-player-age-verification-required.json"))
        val extractor = LivePlayerExtractor(client, visitorDataProvider = { null })

        val error = assertFailsWith<ExtractorError.VideoUnavailable> {
            extractor.resolve("agegated", preferredMaxBitrateKbps = 128)
        }
        assertEquals("agegated", error.videoId)
    }

    @Test
    fun `LOGIN_REQUIRED maps to VideoUnavailable`() = runTest {
        val client = cannedClient(code = 200, body = loadFixture("innertube-player-login-required.json"))
        val extractor = LivePlayerExtractor(client, visitorDataProvider = { null })

        val error = assertFailsWith<ExtractorError.VideoUnavailable> {
            extractor.resolve("agegated", preferredMaxBitrateKbps = 128)
        }
        assertEquals("agegated", error.videoId)
    }

    @Test
    fun `UNPLAYABLE maps to VideoUnavailable`() = runTest {
        val client = cannedClient(code = 200, body = loadFixture("innertube-player-unplayable.json"))
        val extractor = LivePlayerExtractor(client, visitorDataProvider = { null })

        assertFailsWith<ExtractorError.VideoUnavailable> {
            extractor.resolve("blocked", preferredMaxBitrateKbps = 128)
        }
    }

    @Test
    fun `ERROR maps to VideoUnavailable`() = runTest {
        val client = cannedClient(code = 200, body = loadFixture("innertube-player-error.json"))
        val extractor = LivePlayerExtractor(client, visitorDataProvider = { null })

        assertFailsWith<ExtractorError.VideoUnavailable> {
            extractor.resolve("removed", preferredMaxBitrateKbps = 128)
        }
    }

    @Test
    fun `LIVE_STREAM_OFFLINE maps to VideoUnavailable`() = runTest {
        val client = cannedClient(code = 200, body = loadFixture("innertube-player-live-stream-offline.json"))
        val extractor = LivePlayerExtractor(client, visitorDataProvider = { null })

        assertFailsWith<ExtractorError.VideoUnavailable> {
            extractor.resolve("ended", preferredMaxBitrateKbps = 128)
        }
    }

    @Test
    fun `unknown status defensively maps to NetworkError`() = runTest {
        val client = cannedClient(code = 200, body = loadFixture("innertube-player-unknown-status.json"))
        val extractor = LivePlayerExtractor(client, visitorDataProvider = { null })

        assertFailsWith<ExtractorError.NetworkError> {
            extractor.resolve("future", preferredMaxBitrateKbps = 128)
        }
    }

    @Test
    fun `empty streamingData maps to VideoUnavailable`() = runTest {
        val client = cannedClient(code = 200, body = loadFixture("innertube-player-empty-streaming-data.json"))
        val extractor = LivePlayerExtractor(client, visitorDataProvider = { null })

        assertFailsWith<ExtractorError.VideoUnavailable> {
            extractor.resolve("empty", preferredMaxBitrateKbps = 128)
        }
    }

    @Test
    fun `HTTP 429 maps to BotChallenge`() = runTest {
        val client = cannedClient(code = 429, body = "rate limited")
        val extractor = LivePlayerExtractor(client, visitorDataProvider = { null })

        assertFailsWith<ExtractorError.BotChallenge> {
            extractor.resolve("any", preferredMaxBitrateKbps = 128)
        }
    }

    @Test
    fun `transport throw maps to NetworkError without crashing`() = runTest {
        val client = throwingClient(IOException("offline"))
        val extractor = LivePlayerExtractor(client, visitorDataProvider = { null })

        assertFailsWith<ExtractorError.NetworkError> {
            extractor.resolve("offline", preferredMaxBitrateKbps = 128)
        }
    }

    @Test
    fun `non-JSON body maps to NetworkError without crashing`() = runTest {
        // Captcha / consent page returned with 200 OK where we expected JSON.
        val html = "<html><body>You are being redirected to consent</body></html>"
        val client = cannedClient(code = 200, body = html)
        val extractor = LivePlayerExtractor(client, visitorDataProvider = { null })

        assertFailsWith<ExtractorError.NetworkError> {
            extractor.resolve("consent", preferredMaxBitrateKbps = 128)
        }
    }

    @Test
    fun `visitor data provider failure does not crash resolve`() = runTest {
        val client = cannedClient(code = 200, body = loadFixture("innertube-player-audio-only-ok.json"))
        val extractor = LivePlayerExtractor(client, visitorDataProvider = {
            throw IOException("visitor endpoint offline")
        })

        // Visitor failure is swallowed — the resolve still proceeds.
        val resolution = extractor.resolve("abc", preferredMaxBitrateKbps = 192)
        assertEquals(PlayerResolution.AudioFormat.AudioOnly, resolution.kind)
    }

    // endregion

    // region Body verification — confirms VISIONOS client constants are sent

    @Test
    fun `request body contains VISIONOS client constants`() = runTest {
        val capturedBodies = mutableListOf<String>()
        val client = cannedClient(
            code = 200,
            body = loadFixture("innertube-player-audio-only-ok.json"),
            onRequest = { request ->
                val buffer = okio.Buffer()
                request.body?.writeTo(buffer)
                capturedBodies += buffer.readUtf8()
            },
        )
        val extractor = LivePlayerExtractor(client, visitorDataProvider = { null })

        extractor.resolve("abc", preferredMaxBitrateKbps = 192)

        val body = capturedBodies.singleOrNull() ?: error("Expected exactly 1 request, got ${capturedBodies.size}")

        // YT-0163 review B4: parse the body as JSON and assert against
        // structure rather than literal whitespace. Lock against future
        // formatter / serialization-config swaps that break only the test.
        val root = Json.parseToJsonElement(body).jsonObject
        val clientObj = root.getValue("context").jsonObject.getValue("client").jsonObject
        assertEquals("VISIONOS", clientObj.getValue("clientName").jsonPrimitive.content)
        assertEquals("1.02", clientObj.getValue("clientVersion").jsonPrimitive.content)
        assertEquals("Apple", clientObj.getValue("deviceMake").jsonPrimitive.content)
        assertEquals("RealityDevice17,1", clientObj.getValue("deviceModel").jsonPrimitive.content)
        assertEquals("visionOS", clientObj.getValue("osName").jsonPrimitive.content)
        assertEquals("26.5.23O471", clientObj.getValue("osVersion").jsonPrimitive.content)
        // The former ANDROID_VR-only field must be gone.
        assertEquals(null, clientObj["androidSdkVersion"])
        assertEquals(true, root.getValue("racyCheckOk").jsonPrimitive.boolean)
        assertEquals(true, root.getValue("contentCheckOk").jsonPrimitive.boolean)
    }

    @Test
    fun `visitor data is injected into context client and request header when present`() = runTest {
        val capturedBodies = mutableListOf<String>()
        val capturedVisitorHeader = mutableListOf<String?>()
        val client = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    val request = chain.request()
                    val buf = okio.Buffer()
                    request.body?.writeTo(buf)
                    capturedBodies += buf.readUtf8()
                    capturedVisitorHeader += request.header("X-Goog-Visitor-Id")
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(loadFixture("innertube-player-audio-only-ok.json").toResponseBody("application/json".toMediaType()))
                        .build()
                },
            )
            .build()
        val extractor = LivePlayerExtractor(client, visitorDataProvider = { "VISITOR_TOKEN" })

        extractor.resolve("abc", preferredMaxBitrateKbps = 192)

        val body = capturedBodies.single()
        val clientObj = Json.parseToJsonElement(body).jsonObject
            .getValue("context").jsonObject
            .getValue("client").jsonObject
        assertEquals("VISITOR_TOKEN", clientObj.getValue("visitorData").jsonPrimitive.content)
        assertEquals("VISITOR_TOKEN", capturedVisitorHeader.single())
    }

    @Test
    fun `visitor provider is called exactly once per resolve regardless of result`() = runTest {
        val client = cannedClient(code = 200, body = loadFixture("innertube-player-audio-only-ok.json"))
        val callCount = AtomicInteger(0)
        val extractor = LivePlayerExtractor(client, visitorDataProvider = {
            callCount.incrementAndGet()
            null
        })

        extractor.resolve("abc", preferredMaxBitrateKbps = 192)

        assertEquals(1, callCount.get())
    }

    // endregion

    // region Helpers

    private fun adaptiveFormat(
        itag: Int,
        mime: String,
        avgBps: Int?,
    ): InnerTubePlayerResponse.AdaptiveFormat = InnerTubePlayerResponse.AdaptiveFormat(
        itag = itag,
        url = "https://example.invalid/itag/$itag",
        mimeType = mime,
        bitrate = avgBps,
        averageBitrate = avgBps,
    )

    private fun cannedClient(
        code: Int,
        body: String,
        onRequest: (okhttp3.Request) -> Unit = {},
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(
            Interceptor { chain ->
                val request = chain.request()
                onRequest(request)
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message(if (code in 200..299) "OK" else "ERR")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            },
        )
        .build()

    private fun throwingClient(throwable: Throwable): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(
            Interceptor { _ -> throw throwable },
        )
        .build()

    private fun loadFixture(name: String): String =
        checkNotNull(javaClass.classLoader?.getResource(name)) { "Missing fixture: $name" }
            .readText()

    // endregion
}
