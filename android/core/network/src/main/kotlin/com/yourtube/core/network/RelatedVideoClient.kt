package com.yourtube.core.network

import com.yourtube.core.common.model.SearchResult
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * YT-0296 — paged Mix queue result. [items] is the ordered list of tracks parsed from
 * the current page (initial fetch or continuation page). [nextToken] is the continuation
 * token to feed into [RelatedVideoClient.getMixContinuation] for the next page; `null`
 * means the server did not return a continuation and the consumer should stop paginating.
 *
 * See `docs/mix-queue.md#continuation--pagination` for the full pagination contract.
 */
internal data class MixQueueResult(
    val items: List<SearchResult>,
    val nextToken: String?,
) {
    companion object {
        fun empty(): MixQueueResult = MixQueueResult(items = emptyList(), nextToken = null)
    }
}

/**
 * YT-0089 — fetches related videos for a given [videoId] via the InnerTube
 * `/next` endpoint (the same endpoint YouTube's web client uses to populate the
 * "Up next" / secondary-results ribbon on the watch page).
 *
 * The response shape mirrors `secondaryResults.secondaryResultsRenderer.results[]`
 * items of type `compactVideoRenderer` — same fields as a search `videoRenderer`
 * so [SearchResult] maps cleanly. Livestreams (`durationSec == 0`) and items
 * with an empty `videoId` are included in the raw response and filtered
 * downstream by [DefaultAutoplayController.eligibilityFilter].
 *
 * This client is `internal` to `core:network`; the autoplay controller depends
 * on [YoutubeService] being extended or on a separate DI-visible abstraction.
 * We expose it via the [YoutubeService] extension [YoutubeService.getRelatedVideos]
 * so the controller stays decoupled from the InnerTube HTTP layer.
 */
internal class RelatedVideoClient(private val okHttpClient: OkHttpClient) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * YT-0293 — returns the ordered Mix queue for [videoId] from the InnerTube `/next`
     * endpoint with `playlistId = "RD$videoId"`.
     *
     * The first entry is the seed track itself (tracks[0].videoId == videoId). Consumers
     * should skip index 0 (already playing) and append the tail to the playback queue.
     * See `docs/mix-queue.md` for full contract including field mapping and edge cases.
     *
     * Returns an empty list on any HTTP failure or parse error; callers must not throw.
     *
     * Note: this entry-point intentionally drops the first continuation token. Use
     * [getMixQueueWithContinuation] when callers need to extend the Mix beyond the
     * initial page (YT-0296 / YT-0297 / YT-0298).
     */
    fun getMixQueue(videoId: String): List<SearchResult> =
        getMixQueueWithContinuation(videoId).items

    /**
     * YT-0296 — same wire call as [getMixQueue] but also surfaces the first continuation
     * token (if any) so the consumer can lazy-load the next Mix page via
     * [getMixContinuation].
     *
     * Returns [MixQueueResult.empty] on HTTP failure or parse error. A non-empty list
     * with `nextToken == null` means the server did not return a continuation on the
     * initial page (rare; treat as terminal — fall back to autoplay-related at the tail).
     */
    fun getMixQueueWithContinuation(videoId: String): MixQueueResult {
        val body = buildMixRequestBody(videoId)
        val request = Request.Builder()
            .url(NEXT_URL)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .header("Content-Type", "application/json")
            .header("User-Agent", YOUTUBE_DESKTOP_USER_AGENT)
            .header("X-YouTube-Client-Name", "1")
            .header("X-YouTube-Client-Version", INNERTUBE_CLIENT_VERSION)
            .build()

        val rawBody = try {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return MixQueueResult.empty()
                response.body?.string() ?: return MixQueueResult.empty()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            return MixQueueResult.empty()
        }

        return try {
            parseMixQueue(json.parseToJsonElement(rawBody).jsonObject)
        } catch (_: Throwable) {
            MixQueueResult.empty()
        }
    }

    /**
     * YT-0296 — fetches the next Mix page given a continuation [token] obtained from a
     * previous [getMixQueueWithContinuation] or [getMixContinuation] call.
     *
     * The request body carries only `{ context, continuation }` — `videoId` and
     * `playlistId` are NOT re-sent; the token already encodes which Mix is being walked.
     *
     * The server eventually stops returning a `continuations[]` block; when that happens
     * [MixQueueResult.nextToken] is `null` and the consumer should stop paginating and
     * fall back to autoplay-related selection at the tail.
     *
     * Returns [MixQueueResult.empty] on HTTP failure, parse error, or if [token] is blank.
     */
    fun getMixContinuation(token: String): MixQueueResult {
        if (token.isBlank()) return MixQueueResult.empty()

        val body = buildContinuationRequestBody(token)
        val request = Request.Builder()
            .url(NEXT_URL)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .header("Content-Type", "application/json")
            .header("User-Agent", YOUTUBE_DESKTOP_USER_AGENT)
            .header("X-YouTube-Client-Name", "1")
            .header("X-YouTube-Client-Version", INNERTUBE_CLIENT_VERSION)
            .build()

        val rawBody = try {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return MixQueueResult.empty()
                response.body?.string() ?: return MixQueueResult.empty()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            return MixQueueResult.empty()
        }

        return try {
            parseMixContinuation(json.parseToJsonElement(rawBody).jsonObject)
        } catch (_: Throwable) {
            MixQueueResult.empty()
        }
    }

    private fun buildMixRequestBody(videoId: String): String =
        """{"context":{"client":{"clientName":"WEB","clientVersion":"$INNERTUBE_CLIENT_VERSION","hl":"en"}},"videoId":"$videoId","playlistId":"$MIX_PLAYLIST_PREFIX$videoId"}"""

    private fun buildContinuationRequestBody(token: String): String =
        """{"context":{"client":{"clientName":"WEB","clientVersion":"$INNERTUBE_CLIENT_VERSION","hl":"en"}},"continuation":"$token"}"""

    private fun parseMixQueue(root: JsonObject): MixQueueResult {
        val playlistPanel = root
            .getObj("contents")
            ?.getObj("twoColumnWatchNextResults")
            ?.getObj("playlist")
            ?.getObj("playlist")
            ?: return MixQueueResult.empty()

        val contents = playlistPanel.get("contents")?.jsonArray ?: return MixQueueResult.empty()
        val items = contents.mapNotNull { element ->
            element.jsonObject.getObj("playlistPanelVideoRenderer")?.toMixTrack()
        }
        val nextToken = extractContinuationToken(playlistPanel)
        return MixQueueResult(items = items, nextToken = nextToken)
    }

    private fun parseMixContinuation(root: JsonObject): MixQueueResult {
        // Continuation responses replace the `contents.twoColumnWatchNextResults.playlist`
        // wrapper with `continuationContents.playlistPanelContinuation`. The inner shape
        // (contents[] + continuations[]) is identical to the initial page's playlist node.
        val panel = root
            .getObj("continuationContents")
            ?.getObj("playlistPanelContinuation")
            ?: return MixQueueResult.empty()

        val contents = panel.get("contents")?.jsonArray ?: return MixQueueResult.empty()
        val items = contents.mapNotNull { element ->
            element.jsonObject.getObj("playlistPanelVideoRenderer")?.toMixTrack()
        }
        val nextToken = extractContinuationToken(panel)
        return MixQueueResult(items = items, nextToken = nextToken)
    }

    /**
     * Extract the continuation token from a `playlistPanel(Continuation|)` node.
     *
     * Production responses observed (Aug 2024 / WEB client 2.20240726.00.00) place the
     * token at `continuations[0].nextContinuationData.continuation` — the legacy
     * "nextContinuationData" envelope. Newer InnerTube surfaces (browse, search) have
     * migrated to `continuationItemRenderer.continuationEndpoint.continuationCommand.token`
     * but the Mix `playlistPanel` continues to ship the legacy shape.
     *
     * We try both paths so the parser stays forward-compatible if YouTube migrates the
     * Mix panel without notice (cross-referenced with NewPipeExtractor's
     * `YoutubeMixOrPlaylistExtractor`, which reads the legacy path, and yt-dlp's
     * `_extract_continuation`, which probes both).
     */
    private fun extractContinuationToken(panel: JsonObject): String? {
        val continuations = panel.get("continuations")?.jsonArray
        if (continuations != null) {
            for (entry in continuations) {
                val obj = entry.jsonObject
                // Legacy / Mix-current path
                val legacy = obj.getObj("nextContinuationData")
                    ?.get("continuation")?.jsonPrimitive?.content
                if (!legacy.isNullOrBlank()) return legacy
                // Modern path (defensive — not yet observed on Mix)
                val modern = obj.getObj("continuationEndpoint")
                    ?.getObj("continuationCommand")
                    ?.get("token")?.jsonPrimitive?.content
                if (!modern.isNullOrBlank()) return modern
            }
        }
        // Defensive: if the server ever wraps the Mix panel in a
        // `continuationItemRenderer` sibling of `contents[]` (search/browse style),
        // probe that too.
        val itemRendererToken = panel.get("contents")?.jsonArray
            ?.lastOrNull()?.jsonObject
            ?.getObj("continuationItemRenderer")
            ?.getObj("continuationEndpoint")
            ?.getObj("continuationCommand")
            ?.get("token")?.jsonPrimitive?.content
        return itemRendererToken?.takeIf { it.isNotBlank() }
    }

    private fun JsonObject.toMixTrack(): SearchResult? {
        val videoId = get("videoId")?.jsonPrimitive?.content.orEmpty()
        if (videoId.isBlank()) return null

        val title = getObj("title")
            ?.get("simpleText")?.jsonPrimitive?.content.orEmpty()

        val channel = getObj("longBylineText")
            ?.get("runs")?.jsonArray
            ?.firstOrNull()?.jsonObject
            ?.get("text")?.jsonPrimitive?.content.orEmpty()

        val durationSec = getObj("lengthText")
            ?.get("simpleText")?.jsonPrimitive?.content
            ?.parseDuration() ?: 0

        val thumbnailUrl = getObj("thumbnail")
            ?.get("thumbnails")?.jsonArray
            ?.lastOrNull()?.jsonObject
            ?.get("url")?.jsonPrimitive?.content.orEmpty()

        return SearchResult(
            videoId = videoId,
            title = title,
            channel = channel,
            durationSec = durationSec,
            thumbnailUrl = thumbnailUrl,
        )
    }

    /**
     * Returns the ordered list of related video candidates. An empty list is
     * returned on any parse / HTTP failure; callers must not throw on empty.
     */
    fun getRelatedVideos(videoId: String): List<SearchResult> {
        val body = buildRequestBody(videoId)
        val request = Request.Builder()
            .url(NEXT_URL)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .header("Content-Type", "application/json")
            .header("User-Agent", YOUTUBE_DESKTOP_USER_AGENT)
            .header("X-YouTube-Client-Name", "1")
            .header("X-YouTube-Client-Version", INNERTUBE_CLIENT_VERSION)
            .build()

        val rawBody = try {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                response.body?.string() ?: return emptyList()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            return emptyList()
        }

        return try {
            parseRelated(json.parseToJsonElement(rawBody).jsonObject)
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun buildRequestBody(videoId: String): String =
        """{"context":{"client":{"clientName":"WEB","clientVersion":"$INNERTUBE_CLIENT_VERSION","hl":"en"}},"videoId":"$videoId"}"""

    private fun parseRelated(root: JsonObject): List<SearchResult> {
        // YT-0284 — defensive dual-path parsing for the InnerTube /next response.
        //
        // The historical wire format exposed `secondaryResults.secondaryResults.results[]`
        // (a flat wrapper). A schema revision wraps the inner object under an additional
        // `secondaryResultsRenderer` key before the final `results[]` array. We try the
        // newer renderer path first; if it returns an empty array we fall back to the
        // legacy flat path so existing installs stay functional without a forced update.
        //
        // Both paths terminate in `results[] → compactVideoRenderer` so the downstream
        // `toRelatedResult()` mapper is unchanged.
        val twoCol = root
            .getObj("contents")
            ?.getObj("twoColumnWatchNextResults")
            ?: return emptyList()

        val secondaryResults = twoCol.getObj("secondaryResults") ?: return emptyList()

        // Path A (current): secondaryResults → secondaryResultsRenderer → results[]
        val resultsViaRenderer: JsonArray? = secondaryResults
            .getObj("secondaryResultsRenderer")
            ?.get("results")?.jsonArray

        // Path B (legacy): secondaryResults → secondaryResults → results[]
        val resultsViaFlat: JsonArray? = secondaryResults
            .getObj("secondaryResults")
            ?.get("results")?.jsonArray

        val results: JsonArray = resultsViaRenderer?.takeIf { it.isNotEmpty() }
            ?: resultsViaFlat
            ?: return emptyList()

        return results.mapNotNull { element ->
            val obj = element.jsonObject
            // YT-0XXX: InnerTube /next migrated from compactVideoRenderer to lockupViewModel.
            // Try lockupViewModel (current) first; fall back to compactVideoRenderer (legacy).
            obj.getObj("lockupViewModel")?.toLockupResult()
                ?: obj.getObj("compactVideoRenderer")?.toRelatedResult()
        }
    }

    /**
     * Parses the current `lockupViewModel` shape returned by InnerTube `/next`.
     *
     * Field mapping:
     *   videoId     → lockupViewModel.contentId
     *   title       → .metadata.lockupMetadataViewModel.title.content
     *   channel     → .metadata.lockupMetadataViewModel.metadata
     *                   .contentMetadataViewModel.metadataRows[0].metadataParts[0].text.content
     *   durationSec → parsed from the first duration-like badge text
     *                   (.contentImage.thumbnailViewModel.overlays[].thumbnailBottomOverlayViewModel
     *                    .badges[].thumbnailBadgeViewModel.text)
     *   thumbnailUrl → last source in .contentImage.thumbnailViewModel.image.sources
     *
     * Returns null for non-VIDEO content types (playlists, channels, Shorts shelf, etc.)
     * so the eligibility filter downstream stays in charge of business rules.
     */
    private fun JsonObject.toLockupResult(): SearchResult? {
        if (get("contentType")?.jsonPrimitive?.content != LOCKUP_CONTENT_TYPE_VIDEO) return null

        val videoId = get("contentId")?.jsonPrimitive?.content.orEmpty()
        if (videoId.isBlank()) return null

        val lockupMeta = getObj("metadata")?.getObj("lockupMetadataViewModel") ?: return null

        val title = lockupMeta.getObj("title")?.get("content")?.jsonPrimitive?.content.orEmpty()

        val channel = lockupMeta
            .getObj("metadata")
            ?.getObj("contentMetadataViewModel")
            ?.get("metadataRows")?.jsonArray
            ?.firstOrNull()?.jsonObject
            ?.get("metadataParts")?.jsonArray
            ?.firstOrNull()?.jsonObject
            ?.getObj("text")
            ?.get("content")?.jsonPrimitive?.content
            .orEmpty()

        // Duration is exposed as a human-readable badge text ("4:03", "1:02:15").
        // "LIVE" or missing badge → parseDuration returns 0 → eligibility filter drops it.
        val durationSec = getObj("contentImage")
            ?.getObj("thumbnailViewModel")
            ?.get("overlays")?.jsonArray
            ?.flatMap { overlay ->
                overlay.jsonObject
                    .getObj("thumbnailBottomOverlayViewModel")
                    ?.get("badges")?.jsonArray
                    ?.toList()
                    ?: emptyList()
            }
            ?.mapNotNull { badge ->
                badge.jsonObject
                    .getObj("thumbnailBadgeViewModel")
                    ?.get("text")?.jsonPrimitive?.content
            }
            ?.firstOrNull()
            ?.parseDuration()
            ?: 0

        val thumbnailUrl = getObj("contentImage")
            ?.getObj("thumbnailViewModel")
            ?.getObj("image")
            ?.get("sources")?.jsonArray
            ?.lastOrNull()?.jsonObject
            ?.get("url")?.jsonPrimitive?.content
            .orEmpty()

        return SearchResult(
            videoId = videoId,
            title = title,
            channel = channel,
            durationSec = durationSec,
            thumbnailUrl = thumbnailUrl,
        )
    }

    /**
     * Parses the legacy `compactVideoRenderer` shape. Kept as a fallback while
     * the InnerTube `/next` response migrates across regions/client versions.
     */
    private fun JsonObject.toRelatedResult(): SearchResult? {
        val videoId = get("videoId")?.jsonPrimitive?.content.orEmpty()
        if (videoId.isBlank()) return null

        val title = getObj("title")
            ?.get("simpleText")?.jsonPrimitive?.content
            ?: getObj("title")?.get("runs")?.jsonArray
                ?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content
            ?: ""

        val channel = getObj("shortBylineText")?.get("runs")?.jsonArray
            ?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content.orEmpty()

        val durationSec = getObj("lengthText")
            ?.get("simpleText")?.jsonPrimitive?.content
            ?.parseDuration() ?: 0

        val thumbnailUrl = getObj("thumbnail")
            ?.get("thumbnails")?.jsonArray
            ?.lastOrNull()?.jsonObject
            ?.get("url")?.jsonPrimitive?.content.orEmpty()

        return SearchResult(
            videoId = videoId,
            title = title,
            channel = channel,
            durationSec = durationSec,
            thumbnailUrl = thumbnailUrl,
        )
    }

    private fun JsonObject.getObj(key: String): JsonObject? = get(key)?.jsonObject

    private fun String.parseDuration(): Int {
        val parts = split(":").mapNotNull { it.toIntOrNull() }
        return when (parts.size) {
            2 -> parts[0] * 60 + parts[1]
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            else -> 0
        }
    }

    private companion object {
        // No API key — matches InnerTubeSearchClient pattern which works without one.
        // The hardcoded key was causing 4xx rejections on the /next endpoint.
        private const val NEXT_URL = "https://www.youtube.com/youtubei/v1/next"
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private const val LOCKUP_CONTENT_TYPE_VIDEO = "LOCKUP_CONTENT_TYPE_VIDEO"
        // YT-0293 — prefix for the YouTube radio/mix playlistId parameter.
        internal const val MIX_PLAYLIST_PREFIX = "RD"
    }
}
