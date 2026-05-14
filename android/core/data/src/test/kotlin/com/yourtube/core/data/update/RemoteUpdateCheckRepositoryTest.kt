package com.yourtube.core.data.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

/**
 * YT-0251 — unit tests for [RemoteUpdateCheckRepository].
 *
 * Uses MockWebServer to avoid real network calls and to exercise both the happy path
 * and failure modes without mocking OkHttp internals.
 *
 * AC covered:
 * - installed < latest → UpdateAvailable
 * - installed == latest → UpToDate
 * - installed > latest → UpToDate
 * - min == installed boundary → UpToDate (strict `>`)
 * - minimumSupportedVersionCode > installed → UpdateRequired
 * - UpdateRequired precedence: min > installed even when versionCode > installed
 * - malformed JSON → CheckFailed (no crash)
 * - empty JSON object (missing required fields) → CheckFailed
 * - versionCode as string → CheckFailed (type mismatch)
 * - HTTP error → CheckFailed
 * - network error (server closed) → CheckFailed
 * - successful check after error → UpToDate (error cleared)
 */
class RemoteUpdateCheckRepositoryTest {

    private val server = MockWebServer()
    private val client = OkHttpClient()
    private val testDispatcher = StandardTestDispatcher()

    private fun repo(): RemoteUpdateCheckRepository {
        server.start()
        return RemoteUpdateCheckRepository(
            okHttpClient = client,
            ioDispatcher = testDispatcher,
            feedUrl = server.url("/android/update.json").toString(),
        )
    }

    private fun validJson(
        versionCode: Long = 10,
        versionName: String = "1.1.0",
        minimumSupportedVersionCode: Long = 1,
        apkUrl: String = "https://example.com/app.apk",
        notes: String = "Release notes",
    ) = """
        {
          "versionName": "$versionName",
          "versionCode": $versionCode,
          "publishedAt": "2026-05-09T12:00:00Z",
          "notes": "$notes",
          "apkUrl": "$apkUrl",
          "minimumSupportedVersionCode": $minimumSupportedVersionCode
        }
    """.trimIndent()

    @Test
    fun `installed versionCode less than latest returns UpdateAvailable`() = runTest(testDispatcher) {
        val r = repo()
        server.enqueue(MockResponse().setBody(validJson(versionCode = 10)))
        val result = r.checkForUpdates(installedVersionCode = 5)
        assertEquals(UpdateStatus.UpdateAvailable::class, result::class)
        server.shutdown()
    }

    @Test
    fun `installed versionCode equal to latest returns UpToDate`() = runTest(testDispatcher) {
        val r = repo()
        server.enqueue(MockResponse().setBody(validJson(versionCode = 5)))
        assertEquals(UpdateStatus.UpToDate, r.checkForUpdates(installedVersionCode = 5))
        server.shutdown()
    }

    @Test
    fun `installed versionCode greater than latest returns UpToDate`() = runTest(testDispatcher) {
        val r = repo()
        server.enqueue(MockResponse().setBody(validJson(versionCode = 3)))
        assertEquals(UpdateStatus.UpToDate, r.checkForUpdates(installedVersionCode = 5))
        server.shutdown()
    }

    @Test
    fun `minimumSupportedVersionCode equal to installed returns UpToDate`() = runTest(testDispatcher) {
        val r = repo()
        server.enqueue(MockResponse().setBody(validJson(versionCode = 5, minimumSupportedVersionCode = 5)))
        // Boundary: strict `>` so equal min is NOT blocking.
        assertEquals(UpdateStatus.UpToDate, r.checkForUpdates(installedVersionCode = 5))
        server.shutdown()
    }

    @Test
    fun `minimumSupportedVersionCode above installed returns UpdateRequired`() = runTest(testDispatcher) {
        val r = repo()
        server.enqueue(
            MockResponse().setBody(
                validJson(versionCode = 10, minimumSupportedVersionCode = 6),
            ),
        )
        assertEquals(UpdateStatus.UpdateRequired::class, r.checkForUpdates(installedVersionCode = 5)::class)
        server.shutdown()
    }

    @Test
    fun `UpdateRequired takes precedence when both min and versionCode exceed installed`() = runTest(testDispatcher) {
        val r = repo()
        // min=6 > installed=5, versionCode=10 > installed=5 → UpdateRequired wins.
        server.enqueue(
            MockResponse().setBody(
                validJson(versionCode = 10, minimumSupportedVersionCode = 6),
            ),
        )
        assertEquals(UpdateStatus.UpdateRequired::class, r.checkForUpdates(installedVersionCode = 5)::class)
        server.shutdown()
    }

    @Test
    fun `malformed JSON returns CheckFailed`() = runTest(testDispatcher) {
        val r = repo()
        server.enqueue(MockResponse().setBody("not json at all {{{"))
        assertEquals(UpdateStatus.CheckFailed, r.checkForUpdates(installedVersionCode = 5))
        server.shutdown()
    }

    @Test
    fun `empty JSON object returns CheckFailed`() = runTest(testDispatcher) {
        val r = repo()
        server.enqueue(MockResponse().setBody("{}"))
        assertEquals(UpdateStatus.CheckFailed, r.checkForUpdates(installedVersionCode = 5))
        server.shutdown()
    }

    @Test
    fun `versionCode as non-numeric string returns CheckFailed`() = runTest(testDispatcher) {
        val r = repo()
        // versionCode is a non-parseable string — kotlinx.serialization will fail to coerce it.
        server.enqueue(
            MockResponse().setBody(
                """{"versionName":"1.0","versionCode":"not-a-number","publishedAt":"","notes":"","apkUrl":"https://x.com/a.apk","minimumSupportedVersionCode":1}""",
            ),
        )
        assertEquals(UpdateStatus.CheckFailed, r.checkForUpdates(installedVersionCode = 5))
        server.shutdown()
    }

    @Test
    fun `HTTP 404 returns CheckFailed`() = runTest(testDispatcher) {
        val r = repo()
        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals(UpdateStatus.CheckFailed, r.checkForUpdates(installedVersionCode = 5))
        server.shutdown()
    }

    @Test
    fun `HTTP 500 returns CheckFailed`() = runTest(testDispatcher) {
        val r = repo()
        server.enqueue(MockResponse().setResponseCode(500))
        assertEquals(UpdateStatus.CheckFailed, r.checkForUpdates(installedVersionCode = 5))
        server.shutdown()
    }

    @Test
    fun `network failure returns CheckFailed`() = runTest(testDispatcher) {
        val r = repo()
        server.shutdown()
        assertEquals(UpdateStatus.CheckFailed, r.checkForUpdates(installedVersionCode = 5))
    }

    @Test
    fun `successful check after error returns UpToDate`() = runTest(testDispatcher) {
        val r = repo()
        server.enqueue(MockResponse().setResponseCode(500))
        assertEquals(UpdateStatus.CheckFailed, r.checkForUpdates(installedVersionCode = 5))
        server.enqueue(MockResponse().setBody(validJson(versionCode = 5)))
        assertEquals(UpdateStatus.UpToDate, r.checkForUpdates(installedVersionCode = 5))
        server.shutdown()
    }

    @Test
    fun `UpdateAvailable carries correct versionName and apkUrl`() = runTest(testDispatcher) {
        val r = repo()
        server.enqueue(
            MockResponse().setBody(
                validJson(
                    versionCode = 20,
                    versionName = "2.0.0",
                    apkUrl = "https://cdn.example.com/yourtube-2.0.0.apk",
                    notes = "Major update.",
                ),
            ),
        )
        val result = r.checkForUpdates(installedVersionCode = 5)
        val available = result as UpdateStatus.UpdateAvailable
        assertEquals("2.0.0", available.latestVersionName)
        assertEquals("https://cdn.example.com/yourtube-2.0.0.apk", available.apkUrl)
        assertEquals("Major update.", available.notes)
        server.shutdown()
    }

    @Test
    fun `UpdateRequired carries correct versionName and apkUrl`() = runTest(testDispatcher) {
        val r = repo()
        server.enqueue(
            MockResponse().setBody(
                validJson(
                    versionCode = 20,
                    versionName = "3.0.0",
                    minimumSupportedVersionCode = 10,
                    apkUrl = "https://cdn.example.com/yourtube-3.0.0.apk",
                    notes = "Security critical update.",
                ),
            ),
        )
        val result = r.checkForUpdates(installedVersionCode = 5)
        val required = result as UpdateStatus.UpdateRequired
        assertEquals("3.0.0", required.latestVersionName)
        assertEquals("https://cdn.example.com/yourtube-3.0.0.apk", required.apkUrl)
        assertEquals("Security critical update.", required.notes)
        server.shutdown()
    }
}
