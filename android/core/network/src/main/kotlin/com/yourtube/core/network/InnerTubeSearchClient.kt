package com.yourtube.core.network

import android.util.Log
import com.yourtube.core.common.model.SearchResult
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
 * Searches YouTube via the InnerTube API (the same JSON API YouTube's own web client uses).
 * This bypasses NewPipe's HTML scraping, which breaks whenever YouTube updates their page structure.
 */
internal class InnerTubeSearchClient(private val okHttpClient: OkHttpClient) {

    private val json = Json { ignoreUnknownKeys = true }

    fun search(query: String, sp: String? = null): List<SearchResult> {
        val body = """
            {"context":{"client":{"clientName":"WEB","clientVersion":"$INNERTUBE_CLIENT_VERSION","hl":"en"}},"query":"${query.replace("\"", "\\\"")}"}
        """.trimIndent()

        val url = if (sp != null) "$SEARCH_URL?sp=$sp" else SEARCH_URL
        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .header("Content-Type", "application/json")
            .header("User-Agent", YOUTUBE_DESKTOP_USER_AGENT)
            .header("X-YouTube-Client-Name", "1")
            .header("X-YouTube-Client-Version", INNERTUBE_CLIENT_VERSION)
            .build()

        val responseBody = okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "InnerTube search HTTP ${response.code}")
                return emptyList()
            }
            response.body?.string() ?: return emptyList()
        }

        return try {
            parseResults(json.parseToJsonElement(responseBody).jsonObject)
        } catch (e: Exception) {
            Log.e(TAG, "InnerTube parse error: ${e.message}", e)
            emptyList()
        }
    }

    private fun parseResults(root: JsonObject): List<SearchResult> {
        // Navigate: contents → twoColumnSearchResultsRenderer → primaryContents
        //   → sectionListRenderer → contents[] → itemSectionRenderer → contents[]
        //   → videoRenderer
        val sections = root
            .getObj("contents")
            ?.getObj("twoColumnSearchResultsRenderer")
            ?.getObj("primaryContents")
            ?.getObj("sectionListRenderer")
            ?.get("contents")?.jsonArray
            ?: return emptyList()

        return sections
            .mapNotNull { it.jsonObject.getObj("itemSectionRenderer")?.get("contents")?.jsonArray }
            .flatMap { it.toList() }
            .mapNotNull { it.jsonObject.getObj("videoRenderer") }
            .mapNotNull { it.toSearchResult() }
    }

    private fun JsonObject.toSearchResult(): SearchResult? {
        val videoId = get("videoId")?.jsonPrimitive?.content.orEmpty()
        if (videoId.isBlank()) return null

        val title = getObj("title")?.get("runs")?.jsonArray
            ?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content.orEmpty()

        val channel = getObj("ownerText")?.get("runs")?.jsonArray
            ?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content.orEmpty()

        // "4:32" → 272 seconds; missing = 0
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

    private fun JsonObject.getObj(key: String): JsonObject? =
        get(key)?.jsonObject

    private fun String.parseDuration(): Int {
        val parts = split(":").mapNotNull { it.toIntOrNull() }
        return when (parts.size) {
            2 -> parts[0] * 60 + parts[1]
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            else -> 0
        }
    }

    private companion object {
        private const val TAG = "YT-InnerTube"
        private const val SEARCH_URL = "https://www.youtube.com/youtubei/v1/search"
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
