package com.yourtube.core.network

import com.yourtube.core.common.error.ExtractorError
import com.yourtube.core.common.model.SearchResult
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * `YoutubeService` backed by direct InnerTube calls (search via
 * [InnerTubeSearchClient], stream resolution via [PlayerExtracting]). Retains
 * the historical `NewPipeYoutubeService` name to keep DI-binding callers
 * stable; the implementation no longer carries any NewPipe symbols after
 * YT-0163.
 */
class NewPipeYoutubeService internal constructor(
    private val extractorClient: YoutubeExtractorClient,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    searchCacheSize: Int = DEFAULT_SEARCH_CACHE_SIZE,
) : YoutubeService {

    private val searchCache = BoundedMemoryCache<String, List<SearchResult>>(maxSize = searchCacheSize)

    override suspend fun searchVideos(query: String): List<SearchResult> = withContext(dispatcher) {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isEmpty()) {
            return@withContext emptyList()
        }

        searchCache.get(normalizedQuery)?.let { return@withContext it }

        try {
            extractorClient.searchVideos(normalizedQuery).also { searchCache.put(normalizedQuery, it) }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            throw throwable.toExtractorError()
        }
    }

    override suspend fun resolveAudioStream(
        videoId: String,
        preferredMaxBitrateKbps: Int,
    ): ResolvedAudioStream = withContext(dispatcher) {
        val normalizedVideoId = videoId.trim()
        require(normalizedVideoId.isNotEmpty()) { "videoId must not be blank" }
        require(preferredMaxBitrateKbps > 0) { "preferredMaxBitrateKbps must be positive, got $preferredMaxBitrateKbps" }

        try {
            val selectedStream = extractorClient
                .getAudioStreams(normalizedVideoId, preferredMaxBitrateKbps)
                .selectPreferredStream(preferredMaxBitrateKbps)

            ResolvedAudioStream(
                videoId = normalizedVideoId,
                streamUrl = selectedStream.url,
                bitrateKbps = selectedStream.bitrateKbps,
                codec = selectedStream.codec,
                container = selectedStream.container,
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            throw throwable.toExtractorError(videoId = normalizedVideoId)
        }
    }

    private fun Throwable.toExtractorError(videoId: String? = null): ExtractorError =
        when (this) {
            is ExtractorError -> this
            is IOException -> ExtractorError.NetworkError(this)
            else -> {
                if (message?.contains("not a bot", ignoreCase = true) == true) {
                    ExtractorError.BotChallenge
                } else {
                    ExtractorError.Unknown(this)
                }
            }
        }

    private fun List<ExtractedAudioStream>.selectPreferredStream(
        preferredMaxBitrateKbps: Int,
    ): ExtractedAudioStream {
        require(isNotEmpty()) { "No audio streams available for the requested video." }

        val sorted = sortedBy { it.bitrateRank }
        val preferred = sorted.lastOrNull { it.bitrateKbps in 1..preferredMaxBitrateKbps }
        return preferred ?: sorted.first()
    }

    private val ExtractedAudioStream.bitrateRank: Int
        get() = if (bitrateKbps > 0) bitrateKbps else Int.MAX_VALUE

    private companion object {
        const val DEFAULT_SEARCH_CACHE_SIZE = 24
    }
}
