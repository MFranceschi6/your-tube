package com.yourtube.core.network

import com.yourtube.core.common.error.ExtractorError
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

// region PlayerResolution

/**
 * Result of resolving a videoId to a playable audio URL.
 *
 * `audioUrl` is pre-signed (the InnerTube `/player` response on the
 * `ANDROID_VR` client returns ready-to-play `googlevideo.com` URLs without
 * needing a JavaScript signature solver). [kind] lets the playback layer
 * decide how to route into ExoPlayer (HLS for livestream manifests;
 * `ProgressiveMediaSource` for the rest).
 */
internal data class PlayerResolution(
    val audioUrl: String,
    val kind: AudioFormat,
    val bitrateKbps: Int,
    val codec: String?,
    val container: String?,
) {
    /** True when this resolution is sourced from `streamingData.hlsManifestUrl`. */
    val isLivestream: Boolean get() = kind == AudioFormat.Livestream

    enum class AudioFormat {
        /** `streamingData.adaptiveFormats` audio-only (mp4a preferred, opus/webm fallback). */
        AudioOnly,

        /** `streamingData.formats` progressive audio+video. */
        Muxed,

        /** `streamingData.hlsManifestUrl`. ExoPlayer routes via `HlsMediaSource.Factory`. */
        Livestream,
    }
}

// endregion

// region PlayerExtracting

/**
 * Resolves a YouTube videoId to a playable audio stream URL.
 *
 * Mirrors the iOS `PlayerExtracting` protocol introduced in YT-0162. Replaces
 * the NewPipeExtractor `StreamInfo.getInfo()` site that ran a Rhino-based JS
 * signature solver. The `ANDROID_VR` client returns pre-signed URLs so the
 * solver isn't needed — eliminating the YT-0071 failure class structurally
 * rather than via retries.
 */
internal interface PlayerExtracting {
    /**
     * Resolve [videoId] against the InnerTube `/player` endpoint.
     *
     * @throws ExtractorError mapped from `playabilityStatus.status` or
     *   transport failures. Cancellation propagates as `CancellationException`.
     */
    suspend fun resolve(videoId: String, preferredMaxBitrateKbps: Int): PlayerResolution
}

// endregion

// region LivePlayerExtractor

/**
 * Production conformer to [PlayerExtracting].
 *
 * POSTs to `https://www.youtube.com/youtubei/v1/player` with the InnerTube
 * `ANDROID_VR` client. Constants are sourced from yt-dlp upstream
 * `yt_dlp/extractor/youtube/_base.py` (verified 2026-05-07 against upstream
 * master). Pin reason: yt-dlp comment "Using a clientVersion>1.65 may return
 * SABR streams only" (Server-side Ads Based Routing — protected, not directly
 * fetchable). When YouTube rotates the values, update this single block — the
 * rest of the file (decoding, selection, error mapping) is invariant.
 *
 * **Divergence from iOS**: ExoPlayer/Media3 1.4.1 plays opus/webm natively,
 * so audio-only selection prefers `audio/mp4 (mp4a)` for parity with iOS
 * first but falls back to `audio/webm (opus)` when no mp4a candidate fits.
 * AVPlayer cannot, hence the iOS implementation skips opus entirely.
 */
internal class LivePlayerExtractor(
    private val okHttpClient: OkHttpClient,
    visitorDataProvider: (suspend () -> String?)? = null,
) : PlayerExtracting {

    // Default lazy provider holds a per-extractor visitor cache; tests inject
    // their own to short-circuit transport.
    private val visitorDataProvider: suspend () -> String? = visitorDataProvider
        ?: VisitorIdCache(okHttpClient)::fetch

    override suspend fun resolve(
        videoId: String,
        preferredMaxBitrateKbps: Int,
    ): PlayerResolution {
        val response = fetchPlayerResponse(videoId)

        // Surface playability gates first so a user-safe error displaces any
        // partial streamingData payload that might otherwise be misinterpreted.
        validatePlayability(response.playabilityStatus, videoId)

        val streamingData = response.streamingData
            ?: throw ExtractorError.VideoUnavailable(videoId)

        // 1. Audio-only adaptive (mp4a preferred, opus/webm fallback) — happy path.
        pickAudioOnly(streamingData.adaptiveFormats.orEmpty(), preferredMaxBitrateKbps)
            ?.let { return it }

        // 2. Progressive (muxed audio+video) fallback — rare on modern uploads.
        pickMuxed(streamingData.formats.orEmpty())?.let { return it }

        // 3. HLS livestream manifest. ExoPlayer routes via HlsMediaSource.Factory.
        val manifest = streamingData.hlsManifestUrl
        if (!manifest.isNullOrBlank()) {
            return PlayerResolution(
                audioUrl = manifest,
                kind = PlayerResolution.AudioFormat.Livestream,
                bitrateKbps = 0,
                codec = null,
                container = "hls",
            )
        }

        throw ExtractorError.VideoUnavailable(videoId)
    }

    private suspend fun fetchPlayerResponse(videoId: String): InnerTubePlayerResponse {
        // Fetch the visitor data first — empirically required to avoid the
        // ANDROID_VR "Sign in to confirm you're not a bot" gate (verified
        // against `n61ULEU7CO0` on 2026-05-06 during YT-0162 work).
        val visitor = try {
            visitorDataProvider()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            null
        }

        val body = buildRequestBody(videoId = videoId, visitorData = visitor)
        val builder = Request.Builder()
            .url(PLAYER_URL)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .header("Content-Type", "application/json")
            .header("X-YouTube-Client-Name", CLIENT_NAME_NUMERIC)
            .header("X-YouTube-Client-Version", CLIENT_VERSION)
            .header("Origin", "https://www.youtube.com")
            .header("User-Agent", USER_AGENT)
        if (visitor != null) {
            builder.header("X-Goog-Visitor-Id", visitor)
        }
        val request = builder.build()

        val rawBody = try {
            okHttpClient.newCall(request).execute().use { response ->
                if (response.code == HTTP_TOO_MANY_REQUESTS) {
                    throw ExtractorError.BotChallenge
                }
                response.body?.string()
                    ?: throw ExtractorError.NetworkError(
                        IllegalStateException("Empty body from /player for videoId=$videoId"),
                    )
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (extractor: ExtractorError) {
            throw extractor
        } catch (throwable: Throwable) {
            throw ExtractorError.NetworkError(throwable)
        }

        return try {
            JSON.decodeFromString(InnerTubePlayerResponse.serializer(), rawBody)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            // Malformed body — most likely a captcha / consent page returned
            // where we expected JSON. Surface as a generic network failure so
            // the caller can offer Retry rather than crashing the decoder.
            throw ExtractorError.NetworkError(throwable)
        }
    }

    private fun buildRequestBody(videoId: String, visitorData: String?): String {
        // `racyCheckOk` + `contentCheckOk` mirror the flags yt-dlp injects for
        // unauthenticated music extraction. `visitorData` is the bot-check
        // ticket; without it ANDROID_VR returns "Sign in to confirm you're
        // not a bot" for most public music videos.
        //
        // Build via `kotlinx.serialization.buildJsonObject` rather than string
        // interpolation so escaping is delegated to the serializer (rules out
        // the entire jsonEscape control-char bug class — YT-0163 review nit B3).
        val payload = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", CLIENT_NAME)
                    put("clientVersion", CLIENT_VERSION)
                    put("deviceMake", "Oculus")
                    put("deviceModel", "Quest 3")
                    put("androidSdkVersion", ANDROID_SDK_VERSION)
                    put("osName", "Android")
                    put("osVersion", OS_VERSION)
                    put("hl", "en")
                    put("gl", "US")
                    if (visitorData != null) {
                        put("visitorData", visitorData)
                    }
                }
            }
            put("videoId", videoId)
            put("racyCheckOk", true)
            put("contentCheckOk", true)
            putJsonObject("playbackContext") {
                putJsonObject("contentPlaybackContext") {
                    put("html5Preference", "HTML5_PREF_WANTS")
                }
            }
        }
        return JSON.encodeToString(JsonObject.serializer(), payload)
    }

    internal companion object {
        // yt-dlp pin (verified 2026-05-07 against upstream master).
        // Source: yt_dlp/extractor/youtube/_base.py + _android_vr.py.
        // Pin reason: yt-dlp comment "Using a clientVersion>1.65 may return
        // SABR streams only".
        const val CLIENT_NAME = "ANDROID_VR"
        const val CLIENT_VERSION = "1.65.10"
        const val CLIENT_NAME_NUMERIC = "28" // INNERTUBE_CONTEXT_CLIENT_NAME
        const val ANDROID_SDK_VERSION = 32
        const val OS_VERSION = "12L"
        const val USER_AGENT =
            "com.google.android.apps.youtube.vr.oculus/1.65.10 " +
                "(Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"

        // Same public InnerTube key already used by `InnerTubeSearchClient`.
        private const val PLAYER_URL =
            "https://www.youtube.com/youtubei/v1/player" +
                "?key=AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8&prettyPrint=false"
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private const val HTTP_TOO_MANY_REQUESTS = 429

        private val JSON: Json = Json { ignoreUnknownKeys = true }

        // region Stream selection

        /**
         * Picks the best audio-only stream at or below the requested bitrate
         * ceiling (in kbps). Prefers `audio/mp4 (mp4a)` for parity with iOS;
         * falls back to `audio/webm (opus)` when no mp4a candidate fits the
         * ceiling. Within a tier, picks the highest bitrate; if no candidate
         * respects the ceiling within that tier, picks the lowest available
         * within the tier so the user still gets audio on a constrained
         * network. Mirrors `selectPreferredStream()` in `NewPipeYoutubeService`.
         *
         * Rejects formats with both `bitrate` and `averageBitrate` null — they
         * would otherwise coalesce to `Int.MAX_VALUE` in the lowest-bitrate
         * fallback and win over real candidates (YT-0162 reviewer pass nit #1).
         */
        internal fun pickAudioOnly(
            formats: List<InnerTubePlayerResponse.AdaptiveFormat>,
            preferredMaxBitrateKbps: Int,
        ): PlayerResolution? {
            val sized = formats.filter { it.hasKnownBitrate }
            // Mp4a preferred; opus/webm is a fallback when no mp4a fits.
            val mp4a = sized.filter { it.isAudioOnlyMp4a }
            mp4a.pickBestWithinCeiling(preferredMaxBitrateKbps)
                ?.toResolution(PlayerResolution.AudioFormat.AudioOnly)
                ?.let { return it }

            val opus = sized.filter { it.isAudioOnlyOpus }
            opus.pickBestWithinCeiling(preferredMaxBitrateKbps)
                ?.toResolution(PlayerResolution.AudioFormat.AudioOnly)
                ?.let { return it }

            return null
        }

        /**
         * Picks the highest-bitrate progressive (muxed audio+video) mp4 stream.
         * Used when the server returned no audio-only adaptive formats — rare
         * on modern uploads but occasionally seen on older or partially
         * restricted videos.
         */
        internal fun pickMuxed(
            formats: List<InnerTubePlayerResponse.AdaptiveFormat>,
        ): PlayerResolution? {
            val muxed = formats.filter { it.isMuxedMp4 && it.hasKnownBitrate }
            val best = muxed.maxByOrNull { it.bitrateValueOrZero() } ?: return null
            return best.toResolution(PlayerResolution.AudioFormat.Muxed)
        }

        private fun List<InnerTubePlayerResponse.AdaptiveFormat>.pickBestWithinCeiling(
            preferredMaxBitrateKbps: Int,
        ): InnerTubePlayerResponse.AdaptiveFormat? {
            if (isEmpty()) return null
            val ceilingBps = preferredMaxBitrateKbps * 1_000
            val withinCeiling = filter { it.bitrateValueOrMax() <= ceilingBps }
            // Above-ceiling fallback intentionally picks the LOWEST-bitrate
            // candidate in tier rather than the closest-to-ceiling one. Simplest
            // viable shape; matches iOS YT-0162. Revisit only if a real-world
            // tier has many entries clustered far above the ceiling and the user
            // would benefit from "closest-to-ceiling" instead. (YT-0163 review
            // nit B5.)
            return withinCeiling.maxByOrNull { it.bitrateValueOrZero() }
                ?: minByOrNull { it.bitrateValueOrMax() }
        }

        private fun InnerTubePlayerResponse.AdaptiveFormat.bitrateValueOrZero(): Int =
            averageBitrate ?: bitrate ?: 0

        private fun InnerTubePlayerResponse.AdaptiveFormat.bitrateValueOrMax(): Int =
            averageBitrate ?: bitrate ?: Int.MAX_VALUE

        private fun InnerTubePlayerResponse.AdaptiveFormat.toResolution(
            kind: PlayerResolution.AudioFormat,
        ): PlayerResolution? {
            val resolved = url?.takeIf { it.isNotBlank() } ?: return null
            val mime = mimeType?.lowercase().orEmpty()
            val codec = mime.extractCodec()
            val container = when {
                mime.startsWith("audio/mp4") -> "m4a"
                mime.startsWith("audio/webm") -> "webm"
                mime.startsWith("video/mp4") -> "mp4"
                else -> null
            }
            return PlayerResolution(
                audioUrl = resolved,
                kind = kind,
                bitrateKbps = bitrateValueOrZero() / 1_000,
                codec = codec,
                container = container,
            )
        }

        private fun String.extractCodec(): String? {
            val codecsMatch = Regex("""codecs="([^"]+)"""").find(this) ?: return null
            return codecsMatch.groupValues.getOrNull(1)
                ?.split(',')
                ?.firstOrNull()
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        }

        // endregion

        // region Playability gating

        /**
         * Maps `playabilityStatus.status` to [ExtractorError]. Any non-`OK`
         * status is user-facing and unrecoverable from the user's
         * perspective — pick a different track. Specific subreasons we expect:
         *
         * - `LOGIN_REQUIRED` — age-gated content. ANDROID_VR doesn't carry
         *   account credentials so this is a hard wall for music videos
         *   behind the age gate.
         * - `UNPLAYABLE` — geo-blocked, members-only, etc.
         * - `LIVE_STREAM_OFFLINE` — past livestream that hasn't been archived.
         * - `ERROR` — generic content removal / takedown.
         */
        internal fun validatePlayability(
            status: InnerTubePlayerResponse.PlayabilityStatus?,
            videoId: String,
        ) {
            val raw = status?.status ?: return
            when (raw) {
                "OK" -> return
                "LOGIN_REQUIRED",
                "AGE_VERIFICATION_REQUIRED",
                "CONTENT_CHECK_REQUIRED",
                "UNPLAYABLE",
                "ERROR",
                "LIVE_STREAM_OFFLINE",
                -> throw ExtractorError.VideoUnavailable(videoId)
                else -> throw ExtractorError.NetworkError(
                    IllegalStateException("Unexpected playabilityStatus.status=$raw"),
                )
            }
        }

        // endregion
    }
}

// endregion

// region InnerTube response Codable (kotlinx.serialization)

/**
 * Minimal projection of the InnerTube `/player` response shape we care about.
 * Other fields (`videoDetails`, `microformat`, `playerConfig`, etc.) are
 * intentionally absent — extending this struct is a follow-up if a feature
 * needs them. Mirrors the iOS `InnerTubePlayerResponse` Codable shape from
 * YT-0162.
 */
@Serializable
internal data class InnerTubePlayerResponse(
    val playabilityStatus: PlayabilityStatus? = null,
    val streamingData: StreamingData? = null,
) {

    @Serializable
    data class PlayabilityStatus(
        val status: String? = null,
        val reason: String? = null,
    )

    @Serializable
    data class StreamingData(
        val formats: List<AdaptiveFormat>? = null,
        val adaptiveFormats: List<AdaptiveFormat>? = null,
        val hlsManifestUrl: String? = null,
    )

    /**
     * Both `formats` (progressive) and `adaptiveFormats` (DASH) entries share
     * the same JSON shape. We treat them via one struct and discriminate at
     * pick-time using the [mimeType].
     */
    @Serializable
    data class AdaptiveFormat(
        val itag: Int? = null,
        val url: String? = null,
        val mimeType: String? = null,
        val bitrate: Int? = null,
        val averageBitrate: Int? = null,
        @SerialName("audioQuality") val audioQuality: String? = null,
    ) {

        /** True for `audio/mp4` adaptive formats with `mp4a` codec (parity with iOS). */
        val isAudioOnlyMp4a: Boolean
            get() {
                val mime = mimeType?.lowercase() ?: return false
                return mime.startsWith("audio/mp4") && mime.contains("mp4a")
            }

        /**
         * True for `audio/webm` adaptive formats with `opus` codec.
         *
         * **Divergence from iOS**: AVPlayer cannot decode webm/opus outside
         * HLS, so the iOS `InnerTubePlayerClient` skips this branch entirely.
         * ExoPlayer/Media3 1.4.1 has native opus extractor support, so we use
         * webm/opus as a fallback when no mp4a candidate fits the bitrate
         * ceiling. See `docs/android-extraction.md`.
         */
        val isAudioOnlyOpus: Boolean
            get() {
                val mime = mimeType?.lowercase() ?: return false
                return mime.startsWith("audio/webm") && mime.contains("opus")
            }

        /**
         * True for progressive `video/mp4` formats with both audio AND video
         * codecs (avc1 + mp4a). Used as muxed fallback when no audio-only
         * format is available (rare on modern uploads). The substring match
         * is deliberately narrower than NewPipe's parsed-codec match — relax
         * the predicate explicitly when a concrete progressive stream proves
         * decodable rather than removing the substring.
         */
        val isMuxedMp4: Boolean
            get() {
                val mime = mimeType?.lowercase() ?: return false
                return mime.startsWith("video/mp4") &&
                    mime.contains("avc1") &&
                    mime.contains("mp4a")
            }

        /** True when at least one of [bitrate] / [averageBitrate] is non-null. */
        val hasKnownBitrate: Boolean
            get() = bitrate != null || averageBitrate != null
    }
}

// endregion

// region VisitorIdCache

/**
 * Lazily fetches a `visitorData` token from `/youtubei/v1/visitor_id` and
 * caches it for the lifetime of the cache instance (typically tied to a
 * `LivePlayerExtractor`, which in turn is a Hilt singleton).
 *
 * Cooperative once-only via a shared [CompletableDeferred]: the first caller
 * through the [Mutex] becomes the "owner" and runs the actual transport; any
 * concurrent caller arriving while the owner is in-flight reads the same
 * `CompletableDeferred` and `await()`s its result. The owner runs in the
 * caller's own coroutine context — there is **no** internally-managed
 * `CoroutineScope`, so there is nothing to leak across instances (YT-0163
 * rework round 2, gating item #1). Failure (network error, malformed response)
 * is silently swallowed — the player request proceeds without `visitorData`,
 * which produces a clear `LOGIN_REQUIRED` from the server that the existing
 * error mapping surfaces to the user.
 *
 * `CancellationException` is propagated so a calling coroutine can cancel a
 * superseded resolve cleanly.
 */
internal class VisitorIdCache(
    private val okHttpClient: OkHttpClient,
) {

    private val mutex = Mutex()

    @Volatile
    private var cached: String? = null

    @Volatile
    private var inFlight: CompletableDeferred<String?>? = null

    /**
     * Returns the cached visitor token, or fetches one cooperatively.
     *
     * Concurrent first-callers share the same in-flight `CompletableDeferred`
     * so the transport sees exactly ONE hit even with N awaiters. Subsequent
     * callers after the first success read the cached value with no transport
     * hit. The owner runs the fetch on the caller's own coroutine — no
     * extractor-owned `CoroutineScope` exists.
     */
    suspend fun fetch(): String? {
        cached?.let { return it }

        // Race for the mutex. Whoever wins and finds no `inFlight` becomes the
        // "owner" — they run the transport. Others read the existing deferred
        // and `await()`. Only the owner mutates `cached` / `inFlight`.
        val (deferred, isOwner) = mutex.withLock {
            cached?.let { return it }
            val existing = inFlight
            if (existing != null) {
                existing to false
            } else {
                val fresh = CompletableDeferred<String?>()
                inFlight = fresh
                fresh to true
            }
        }

        return if (isOwner) {
            runOwnerFetch(deferred)
        } else {
            try {
                deferred.await()
            } catch (cancellation: CancellationException) {
                // Awaiter's coroutine was cancelled. Propagate — caller's
                // structured concurrency expects this. The owner is unaffected.
                throw cancellation
            } catch (_: Throwable) {
                null
            }
        }
    }

    private suspend fun runOwnerFetch(deferred: CompletableDeferred<String?>): String? {
        try {
            val value = performFetch()
            mutex.withLock {
                if (value != null) cached = value
                if (inFlight === deferred) inFlight = null
            }
            deferred.complete(value)
            return value
        } catch (cancellation: CancellationException) {
            // Owner cancelled mid-fetch: clear in-flight slot, propagate cancel
            // to any awaiters, and re-throw so the caller's structured
            // concurrency unwinds. Mirror of YT-0162 review nit #3.
            mutex.withLock { if (inFlight === deferred) inFlight = null }
            deferred.completeExceptionally(cancellation)
            throw cancellation
        } catch (_: Throwable) {
            // Non-cancellation failure is swallowed (cache returns null, player
            // proceeds without visitor data). Awaiters get null too.
            mutex.withLock { if (inFlight === deferred) inFlight = null }
            deferred.complete(null)
            return null
        }
    }

    private fun performFetch(): String? {
        val request = Request.Builder()
            .url(VISITOR_URL)
            .post(VISITOR_BODY.toRequestBody("application/json".toMediaType()))
            .header("Content-Type", "application/json")
            .header("User-Agent", YOUTUBE_DESKTOP_USER_AGENT)
            .build()
        val raw = try {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                response.body?.string() ?: return null
            }
        } catch (_: Throwable) {
            return null
        }
        return try {
            val json = Json { ignoreUnknownKeys = true }
            val root = json.parseToJsonElement(raw) as? JsonObject ?: return null
            val responseContext = root["responseContext"] as? JsonObject ?: return null
            val visitor = (responseContext["visitorData"] as? JsonPrimitive)?.content
            visitor?.takeIf { it.isNotEmpty() }
        } catch (_: Throwable) {
            null
        }
    }

    private companion object {
        private const val VISITOR_URL =
            "https://www.youtube.com/youtubei/v1/visitor_id" +
                "?key=AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8&prettyPrint=false"
        private val VISITOR_BODY = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", "WEB")
                    put("clientVersion", INNERTUBE_CLIENT_VERSION)
                    put("hl", "en")
                    put("gl", "US")
                }
            }
        }.toString()
    }
}

// endregion
