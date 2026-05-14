package com.yourtube.core.network

import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Manual integration diagnostics — hit live YouTube InnerTube endpoints to
 * verify API health and parser correctness.
 *
 * Run when autoplay stops working (remove @Ignore from individual tests):
 *   cd android && ./gradlew :core:network:testDebugUnitTest \
 *     --tests "com.yourtube.core.network.InnerTubeDiagnosticsTest" \
 *     --info 2>&1 | grep -A 50 "==="
 *
 * All tests are @Ignored by default so CI never makes network calls.
 * Remove @Ignore only from the test(s) you want to run.
 *
 * Diagnostic strategy:
 *  - [nextEndpointRawHttp] — prints HTTP status + body excerpt + key presence
 *    for the /next endpoint. First place to look when autoplay returns empty.
 *  - [nextEndpointParsedResults] — runs RelatedVideoClient and prints candidates.
 *  - [playerEndpointStreamResolution] — runs LivePlayerExtractor, prints URL.
 *  - [visitorIdFetch] — verifies VisitorIdCache can fetch the bot-check token.
 */
class InnerTubeDiagnosticsTest {

    // Rick Astley "Never Gonna Give You Up" — stable public music video since 2009.
    private val testVideoId = "dQw4w9WgXcQ"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    // ── /next endpoint ────────────────────────────────────────────────────────

    /**
     * Raw HTTP diagnostic for /next.
     *
     * Prints: HTTP status, clientVersion used, whether the body contains the
     * structural keys we parse, and the first 1 000 chars of the response body.
     *
     * If HTTP != 200  → WEB client rejected.
     * If no "twoColumnWatchNextResults" → JSON shape changed root structure.
     * If no "compactVideoRenderer" → /next returned 0 related videos.
     * If body looks like HTML → consent/bot page intercepted the JSON endpoint.
     */
    @Ignore
    @Test
    fun nextEndpointRawHttp() {
        val requestBody =
            """{"context":{"client":{"clientName":"WEB","clientVersion":"$INNERTUBE_CLIENT_VERSION","hl":"en"}},"videoId":"$testVideoId"}"""

        val request = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/next")
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .header("Content-Type", "application/json")
            .header("User-Agent", YOUTUBE_DESKTOP_USER_AGENT)
            .header("X-YouTube-Client-Name", "1")
            .header("X-YouTube-Client-Version", INNERTUBE_CLIENT_VERSION)
            .build()

        val (code, body) = httpClient.newCall(request).execute().use { resp ->
            resp.code to (resp.body?.string() ?: "<empty body>")
        }

        println("=== /next raw HTTP ===")
        println("clientVersion      : $INNERTUBE_CLIENT_VERSION")
        println("HTTP status        : $code")
        println("isJson             : ${body.trimStart().startsWith("{")}")
        println("twoColumnWatch…    : ${body.contains("twoColumnWatchNextResults")}")
        println("secondaryResultsR. : ${body.contains("secondaryResultsRenderer")}")
        println("compactVideoRend.  : ${body.contains("compactVideoRenderer")}")
        println("bot/consent page   : ${body.contains("consentBump") || body.contains("CONSENT")}")
        println("Body (first 1000) :\n${body.take(1000)}")

        assertTrue(code == 200, "HTTP $code — WEB client may be blocked or version stale")
        assertTrue(body.trimStart().startsWith("{"), "Response is not JSON — likely a consent/bot intercept page")
        assertTrue(
            body.contains("twoColumnWatchNextResults"),
            "Missing 'twoColumnWatchNextResults' — root structure may have changed",
        )
        assertTrue(
            body.contains("compactVideoRenderer"),
            "No compactVideoRenderer — /next returned 0 related videos for videoId=$testVideoId",
        )
    }

    /**
     * Runs the actual [RelatedVideoClient] against the live API and prints candidates.
     *
     * Failure means autoplay has no candidates, so the next track never enqueues.
     * Check [nextEndpointRawHttp] first to distinguish a network/auth failure from
     * a parse regression.
     */
    @Ignore
    @Test
    fun nextEndpointParsedResults() {
        val client = RelatedVideoClient(httpClient)
        val results = client.getRelatedVideos(testVideoId)

        println("=== RelatedVideoClient results ===")
        println("Count: ${results.size}")
        results.take(5).forEachIndexed { i, r ->
            println("  [$i] id=${r.videoId}  dur=${r.durationSec}s  title=${r.title.take(60)}")
        }
        if (results.isEmpty()) {
            println("!! EMPTY — autoplay will never fire. clientVersion=$INNERTUBE_CLIENT_VERSION")
        }

        assertTrue(
            results.isNotEmpty(),
            "getRelatedVideos() returned empty — autoplay has no candidates. " +
                "clientVersion ($INNERTUBE_CLIENT_VERSION) may be stale.",
        )
    }

    // ── /player endpoint ──────────────────────────────────────────────────────

    /**
     * Runs the actual [LivePlayerExtractor] against the live API.
     *
     * Prints the audioUrl prefix, format kind, bitrate, codec.
     * A blank audioUrl or an exception means stream resolution is broken
     * (separate from the /next issue).
     */
    @Ignore
    @Test
    fun playerEndpointStreamResolution() = runBlocking {
        val extractor = LivePlayerExtractor(httpClient)

        val resolution = extractor.resolve(testVideoId, 160)

        println("=== LivePlayerExtractor resolution ===")
        println("clientName    : ${LivePlayerExtractor.CLIENT_NAME} ${LivePlayerExtractor.CLIENT_VERSION}")
        println("audioUrl      : ${resolution.audioUrl.take(80)}...")
        println("kind          : ${resolution.kind}")
        println("bitrateKbps   : ${resolution.bitrateKbps}")
        println("codec         : ${resolution.codec}")
        println("container     : ${resolution.container}")
        println("isLivestream  : ${resolution.isLivestream}")

        assertTrue(resolution.audioUrl.isNotBlank(), "audioUrl is blank — stream resolution failed")
    }

    /**
     * Verifies [VisitorIdCache] can fetch a visitor token from YouTube.
     *
     * Missing visitor token causes ANDROID_VR client to hit the "Sign in to
     * confirm you're not a bot" gate, which maps to LOGIN_REQUIRED and kills
     * stream resolution for all tracks — not just autoplay candidates.
     */
    @Ignore
    @Test
    fun visitorIdFetch() = runBlocking {
        val cache = VisitorIdCache(httpClient)
        val visitorData = cache.fetch()

        println("=== VisitorIdCache ===")
        println("visitorData  : ${visitorData?.take(40) ?: "<null — bot-check gate active>"}")
        println("present      : ${visitorData != null}")

        assertTrue(visitorData != null, "VisitorIdCache returned null — bot-check gate may block player")
    }
}
