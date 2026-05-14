package com.yourtube.core.data.update

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * YT-0251 — production [UpdateCheckRepository] backed by OkHttp + kotlinx.serialization.
 *
 * ## Metadata contract
 *
 * The hosted JSON at [feedUrl] must conform to:
 * ```json
 * {
 *   "versionName": "1.2.0",
 *   "versionCode": 12,
 *   "publishedAt": "2026-05-09T12:00:00Z",
 *   "notes": "Bug fixes and performance improvements.",
 *   "apkUrl": "https://example.github.io/yourtube/android/yourtube-1.2.0.apk",
 *   "minimumSupportedVersionCode": 5,
 *   "sha256": "abc123..."
 * }
 * ```
 *
 * Field semantics align with the iOS mirror (YT-0250):
 * - Shared names: `versionName`, `publishedAt`, `notes`, `minimumSupportedVersionCode`
 * - Android-only: `versionCode` (integer build number), `apkUrl`, `sha256` (optional checksum)
 *
 * ## Install handoff: browser handoff (MVP)
 *
 * The "Update" CTA opens [UpdateStatus.UpdateAvailable.apkUrl] via `Intent.ACTION_VIEW`.
 * The system browser handles the download and the user installs from the Downloads notification.
 *
 * **Why browser handoff and not in-app download:**
 * - Direct APK install requires `REQUEST_INSTALL_PACKAGES` permission + FileProvider URI
 *   plumbing on Android 7+. This is unnecessary permission surface for an MVP with a small
 *   install base.
 * - `REQUEST_INSTALL_PACKAGES` is intentionally absent from the manifest.
 * - If the user leaves the browser without installing, the prompt reappears on next launch
 *   (no persistent "dismissed" state is stored for MVP).
 *
 * ## Graceful degradation
 *
 * Any exception (network failure, timeout, HTTP 4xx/5xx, malformed JSON, missing required
 * fields) is caught and returned as [UpdateStatus.CheckFailed]. The Settings screen shows a
 * dismissible error note; playback and library flows continue unaffected.
 *
 * ## Thread safety
 *
 * [checkForUpdates] switches to [ioDispatcher] internally so callers may safely invoke it
 * from any coroutine context, including the Main thread. The call is bounded to [TIMEOUT].
 */
@Singleton
class RemoteUpdateCheckRepository @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val feedUrl: String = UpdateCheckRepository.DEFAULT_FEED_URL,
) : UpdateCheckRepository {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun checkForUpdates(installedVersionCode: Long): UpdateStatus =
        withContext(ioDispatcher) {
            try {
                val body = withTimeoutOrNull(TIMEOUT) { fetch(feedUrl) }
                    ?: return@withContext UpdateStatus.CheckFailed
                val metadata = json.decodeFromString(UpdateMetadata.serializer(), body)
                classify(installedVersionCode, metadata)
            } catch (e: Exception) {
                UpdateStatus.CheckFailed
            }
        }

    /** Synchronous OkHttp execute — must be called on a background thread ([ioDispatcher]). */
    private fun fetch(url: String): String {
        val request = Request.Builder().url(url).build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            return response.body?.string() ?: error("Empty body")
        }
    }

    private fun classify(installedVersionCode: Long, metadata: UpdateMetadata): UpdateStatus {
        // Blocking check takes priority over optional update.
        if (metadata.minimumSupportedVersionCode > installedVersionCode) {
            return UpdateStatus.UpdateRequired(
                latestVersionName = metadata.versionName,
                notes = metadata.notes,
                apkUrl = metadata.apkUrl,
            )
        }
        if (metadata.versionCode > installedVersionCode) {
            return UpdateStatus.UpdateAvailable(
                latestVersionName = metadata.versionName,
                notes = metadata.notes,
                apkUrl = metadata.apkUrl,
            )
        }
        return UpdateStatus.UpToDate
    }

    companion object {
        private val TIMEOUT = 10.seconds
    }

    /**
     * Internal deserialization model for the hosted update metadata JSON.
     * [ignoreUnknownKeys] ensures forward compatibility when new fields are added to the feed.
     */
    @Serializable
    internal data class UpdateMetadata(
        @SerialName("versionName") val versionName: String,
        @SerialName("versionCode") val versionCode: Long,
        @SerialName("publishedAt") val publishedAt: String,
        @SerialName("notes") val notes: String,
        @SerialName("apkUrl") val apkUrl: String,
        @SerialName("minimumSupportedVersionCode") val minimumSupportedVersionCode: Long,
        @SerialName("sha256") val sha256: String? = null,
    )
}
