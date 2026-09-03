package com.yourtube.core.network

import com.yourtube.core.common.error.ExtractorError
import com.yourtube.core.common.model.SearchResult
import okhttp3.OkHttpClient

/**
 * Search-and-resolve YouTube data via direct InnerTube `/search` and `/player`
 * calls. Replaces the YT-0042-era `NewPipeYoutubeExtractorClient` that ran
 * NewPipe's Rhino-based JS signature solver. The `VISIONOS` client returns
 * pre-signed URLs so the solver isn't needed.
 *
 * [getAudioStreams] is a thin `suspend` adapter over [PlayerExtracting] that
 * maps a single [PlayerResolution] into the existing
 * `List<ExtractedAudioStream>` call-site contract. The method is `suspend` so
 * cancellation propagates through the call chain (YT-0163 rework round 2,
 * gating item #2 — replaces the previous `runBlocking` wrapper, which both
 * blocked an IO worker and broke structured-cancellation propagation).
 * Bot-challenge surfacing (HTTP 429 → [ExtractorError.BotChallenge]) happens
 * inside [LivePlayerExtractor], which inspects the `/player` response code
 * directly — no NewPipe symbols in the mapping path.
 */
internal interface YoutubeExtractorClient {
    fun searchVideos(query: String, sp: String? = null): List<SearchResult>

    suspend fun getAudioStreams(
        videoId: String,
        preferredMaxBitrateKbps: Int,
    ): List<ExtractedAudioStream>
}

internal data class ExtractedAudioStream(
    val url: String,
    val bitrateKbps: Int,
    val codec: String?,
    val container: String?,
)

internal class InnerTubeYoutubeExtractorClient(
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder().build(),
    private val playerClient: PlayerExtracting = LivePlayerExtractor(okHttpClient),
) : YoutubeExtractorClient {

    private val innerTubeSearch = InnerTubeSearchClient(okHttpClient)

    // Search uses InnerTube directly — same posture as the search migration in
    // YT-0042. After YT-0163, stream resolution does too.
    override fun searchVideos(query: String, sp: String?): List<SearchResult> =
        innerTubeSearch.search(query, sp)

    override suspend fun getAudioStreams(
        videoId: String,
        preferredMaxBitrateKbps: Int,
    ): List<ExtractedAudioStream> {
        // The `YoutubeExtractorClient` contract is `List<ExtractedAudioStream>`,
        // but InnerTube `/player` already does the picking for us. Surface a
        // single best-pick wrapped in a list so `selectPreferredStream()`
        // upstream is a no-op for InnerTube but still works if a future
        // fanout / fallback chain emits more than one candidate.
        val resolution = playerClient.resolve(videoId, preferredMaxBitrateKbps)
        return listOf(
            ExtractedAudioStream(
                url = resolution.audioUrl,
                bitrateKbps = resolution.bitrateKbps,
                codec = resolution.codec,
                container = resolution.container,
            ),
        )
    }
}

// Extracted for testability. Handles watch URLs and falls back to the last path segment.
internal fun String.youtubeUrlToVideoId(): String =
    substringAfter("v=", missingDelimiterValue = this)
        .substringBefore('&')
        .substringAfterLast('/')
