package com.yourtube.core.network

import android.util.Log
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale

/**
 * Fetches live autocomplete suggestions from the YouTube suggest API.
 *
 * Contract (from docs/search-suggest.md):
 *   Endpoint: GET https://suggestqueries-clients6.youtube.com/complete/search
 *   Params: client=youtube, ds=yt, q=<query>, hl=<lang>, gl=<country>, xssi=t
 *
 * Response: XSSI-prefixed JSON array. After stripping the prefix the shape is
 *   `[query, [[suggestion, 0, [...]], ...], meta, meta]`.
 * Take `[1][i][0]` as suggestion text. Cap at [MAX_SUGGESTIONS].
 *
 * Returns an empty list on any error (network, parse, non-2xx) — callers must
 * never surface a crash from this path.
 */
class SuggestClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
) {
    // Not injected: Hilt has no unqualified CoroutineDispatcher binding.
    // IO is always the correct dispatcher for blocking OkHttp calls here.
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetchSuggestions(query: String): List<String> = withContext(dispatcher) {
        if (query.isBlank()) return@withContext emptyList()
        try {
            fetchSuggestionsBlocking(query)
        } catch (e: Exception) {
            Log.w(TAG, "Suggest fetch failed: ${e.message}")
            emptyList()
        }
    }

    private fun fetchSuggestionsBlocking(query: String): List<String> {
        val hl = Locale.getDefault().language.lowercase()
        val gl = Locale.getDefault().country.uppercase()

        val url = SUGGEST_BASE_URL.toHttpUrl().newBuilder()
            .addQueryParameter("client", "youtube")
            .addQueryParameter("ds", "yt")
            .addQueryParameter("q", query)
            .addQueryParameter("hl", hl)
            .addQueryParameter("gl", gl)
            .addQueryParameter("xssi", "t")
            .build()

        val request = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", YOUTUBE_DESKTOP_USER_AGENT)
            .build()

        val rawBody = okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "Suggest HTTP ${response.code}")
                return emptyList()
            }
            response.body?.string() ?: return emptyList()
        }

        return parseResponse(rawBody)
    }

    /**
     * Parses the XSSI-protected response. YouTube prepends `)]}'` (sometimes
     * followed by a newline) to prevent script injection; strip everything up
     * to and including the first `[`, then parse the JSON array.
     *
     * Internal visibility for testing.
     */
    internal fun parseResponse(rawBody: String): List<String> {
        val jsonStart = rawBody.indexOf('[')
        if (jsonStart < 0) return emptyList()
        val cleanJson = rawBody.substring(jsonStart)

        return try {
            val outer = json.parseToJsonElement(cleanJson).jsonArray
            if (outer.size < 2) return emptyList()

            outer[1].jsonArray
                .take(MAX_SUGGESTIONS)
                .mapNotNull { tuple ->
                    (tuple as? JsonArray)
                        ?.getOrNull(0)
                        ?.let { it as? JsonPrimitive }
                        ?.takeIf { it.isString }
                        ?.content
                        ?.takeIf { it.isNotBlank() }
                }
        } catch (e: Exception) {
            Log.w(TAG, "Suggest parse error: ${e.message}")
            emptyList()
        }
    }

    private companion object {
        private const val TAG = "YT-Suggest"
        private const val SUGGEST_BASE_URL =
            "https://suggestqueries-clients6.youtube.com/complete/search"
        private const val MAX_SUGGESTIONS = 8
    }
}
