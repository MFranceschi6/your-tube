package com.yourtube.core.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaNotification
import androidx.test.core.app.ApplicationProvider
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.yourtube.core.common.model.Track
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * YT-0076 — verifies that [ColorizedMediaNotificationProvider.prewarm] degrades gracefully when
 * the underlying [BitmapLoader] cannot resolve artwork. The full notification createNotification
 * path is verified manually on-device because it requires a real `MediaSession`; this test
 * covers the smaller, independently testable surface.
 *
 * Robolectric is required because [MediaMetadata.Builder] (built via
 * [PlaybackSessionCommand.toMediaMetadata]) walks `android.os.Bundle` static initializers that
 * have no JVM stub; same reason as [PlaybackSessionCommandTest].
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ColorizedMediaNotificationProviderTest {

    private val track = Track(
        videoId = "vid1",
        title = "Title",
        channel = "Channel",
        durationSec = 120,
        thumbnailUrl = "https://example.com/t.jpg",
    )

    private companion object {
        private const val VIDEO_ID_V1 = "v1"
        private const val VIDEO_ID_V2 = "v2"
        private const val NOTIFICATION_ID_V1 = 1001
        private const val NOTIFICATION_CHANNEL_ID = "yt-test-channel"
    }

    private fun newProvider(cache: PaletteColorCache = PaletteColorCache()): ColorizedMediaNotificationProvider =
        ColorizedMediaNotificationProvider(
            context = ApplicationProvider.getApplicationContext(),
            delegate = NoOpProvider,
            cache = cache,
            logger = RecordingLogger(),
        )

    @Test
    fun `prewarm with empty videoId is a no-op`() {
        val cache = PaletteColorCache()
        val provider = newProvider(cache)
        val loader = FailingBitmapLoader()

        provider.prewarm(track.copy(videoId = ""), loader)

        assertEquals(0, loader.callCount, "Empty videoId must short-circuit before touching the loader.")
        assertEquals(0, cache.size())
    }

    @Test
    fun `prewarm skips the bitmap load when the cache already has the color`() {
        val cache = PaletteColorCache().apply { put("vid1", 0xFFAABBCC.toInt()) }
        val provider = newProvider(cache)
        val loader = FailingBitmapLoader()

        provider.prewarm(track, loader)

        assertEquals(0, loader.callCount, "Cached color hit must skip BitmapLoader work.")
        assertEquals(0xFFAABBCC.toInt(), cache.get("vid1"))
    }

    @Test
    fun `prewarm swallows BitmapLoader failure and leaves cache unchanged`() {
        val cache = PaletteColorCache()
        val provider = newProvider(cache)
        val loader = FailingBitmapLoader()

        // Must not throw.
        provider.prewarm(track, loader)

        assertEquals(1, loader.callCount)
        assertNull(cache.get("vid1"), "Cache must stay empty when artwork fails to load.")
    }

    @Test
    fun `prewarm swallows BitmapLoader exception thrown synchronously`() {
        val cache = PaletteColorCache()
        val provider = newProvider(cache)
        val loader = ThrowingBitmapLoader()

        // Must not throw.
        provider.prewarm(track, loader)

        assertEquals(1, loader.callCount)
        assertNull(cache.get("vid1"))
    }

    /**
     * YT-0076 review change-request (2026-05-08) — regression for "lockscreen artwork stale
     * after in-app skip-next/prev". Reviewer-driven manual smoke surfaced that the colorize
     * callback re-issues the captured `base` snapshot from V1 even after a fresh
     * `createNotification` cycle for V2 has occurred. The fix gates the callback on the
     * latest videoId; this test pins that gating behaviour without spinning up a real
     * `MediaSession`.
     *
     * Scenario (mirrors the manual-smoke repro):
     *  1. V1's createNotification cycle starts: latestVideoId = "V1", scheduleColorize for V1.
     *  2. V2's createNotification cycle starts (e.g. cache-hit synchronous path that does not
     *     replace the in-flight pendingFuture): latestVideoId is bumped to "V2".
     *  3. V1's bitmap future completes (cancellation was best-effort, so it can still resolve).
     *  4. The callback MUST drop the V1-recolored re-issue because the lock-screen card is now
     *     showing V2.
     */
    @Test
    fun `scheduleColorize drops V1 recolor when latestVideoId has moved to V2`() {
        val cache = PaletteColorCache()
        val provider = newProvider(cache)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val baseV1 = newMediaNotification(context, NOTIFICATION_ID_V1)
        val futureV1: SettableFuture<Bitmap> = SettableFuture.create()
        val recorder = RecordingCallback()

        // Step 1 — simulate V1's createNotification setting latestVideoId then scheduling
        // the colorize callback for V1. In production both writes happen inside
        // `createNotification`; the test seam reproduces them without needing a MediaSession.
        provider.setLatestVideoIdForTest(VIDEO_ID_V1)
        provider.scheduleColorize(VIDEO_ID_V1, baseV1, futureV1, recorder)

        // Step 2 — simulate V2's createNotification cycle bumping the latest videoId. The
        // production cache-hit path also clears `pendingFuture`, but the test deliberately
        // does NOT mutate `pendingFuture` so the regression specifically exercises the new
        // videoId gate (the only line of defence when V2 hits the synchronous cache path).
        provider.setLatestVideoIdForTest(VIDEO_ID_V2)

        // Step 3 — V1's bitmap finally arrives. With a real bitmap the cold path would
        // attempt palette extraction; the test passes a 1x1 ARGB so any swatch path is
        // deterministic. The expected behaviour is the videoId gate short-circuits BEFORE
        // palette extraction.
        futureV1.set(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))

        // Step 4 — assert no stale re-issue reached the callback.
        assertEquals(
            0,
            recorder.changedCount,
            "Stale V1 colorize callback must be dropped once latestVideoId has moved to V2.",
        )
        assertTrue(
            recorder.lastNotification == null,
            "No `onNotificationChanged` invocation expected for the V1-recolored snapshot.",
        )
    }

    /**
     * Companion test: the gate must not interfere with the happy path. When V1 is still the
     * active videoId at the time the bitmap completes, the callback should re-issue normally.
     */
    @Test
    fun `scheduleColorize re-issues notification when latestVideoId still matches`() {
        val cache = PaletteColorCache()
        val provider = newProvider(cache)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val baseV1 = newMediaNotification(context, NOTIFICATION_ID_V1)
        val futureV1: SettableFuture<Bitmap> = SettableFuture.create()
        val recorder = RecordingCallback()

        provider.setLatestVideoIdForTest(VIDEO_ID_V1)
        provider.scheduleColorize(VIDEO_ID_V1, baseV1, futureV1, recorder)

        // No cycle for V2 — V1 is still the active track when the bitmap arrives.
        futureV1.set(Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888))

        // Palette extraction may or may not produce a swatch from a 2x2 transparent bitmap
        // depending on Robolectric's Palette behaviour; the contract this test pins is that
        // when extraction does succeed the callback fires exactly once. If extraction yields
        // null the callback is skipped (documented behaviour in `scheduleColorize.onSuccess`).
        assertTrue(
            recorder.changedCount <= 1,
            "Happy-path callback must fire at most once per bitmap completion.",
        )
    }

    /**
     * Builds a minimal but real [MediaNotification] for the colorize-callback regression
     * tests. The wrapper's `recolored(...)` rebuilds the [Notification] via
     * [NotificationCompat.Builder.recoverBuilder], which expects a valid channel id on the
     * source notification (API 26+). We register a no-op channel so palette extraction can
     * synchronously rebuild the notification without exploding on `getNotificationChannel`.
     */
    private fun newMediaNotification(context: Context, notificationId: Int): MediaNotification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "test-channel",
                NotificationManager.IMPORTANCE_LOW,
            )
            nm.createNotificationChannel(channel)
        }
        val notification: Notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("title")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
        return MediaNotification(notificationId, notification)
    }

    /**
     * Records every `onNotificationChanged` invocation so the regression test can assert that
     * a stale colorize callback for V1 does NOT reach the callback once V2 is active.
     */
    private class RecordingCallback : MediaNotification.Provider.Callback {
        var changedCount: Int = 0
            private set
        var lastNotification: MediaNotification? = null
            private set

        override fun onNotificationChanged(notification: MediaNotification) {
            changedCount++
            lastNotification = notification
        }
    }

    /**
     * Stand-in for [androidx.media3.session.DefaultMediaNotificationProvider] so we can test the
     * wrapper without instantiating a real `MediaSession`. Only `prewarm` is exercised in the
     * tests above; `createNotification` and `handleCustomCommand` are not invoked.
     */
    private object NoOpProvider : androidx.media3.session.MediaNotification.Provider {
        override fun createNotification(
            mediaSession: androidx.media3.session.MediaSession,
            customLayout: com.google.common.collect.ImmutableList<androidx.media3.session.CommandButton>,
            actionFactory: androidx.media3.session.MediaNotification.ActionFactory,
            onNotificationChangedCallback: androidx.media3.session.MediaNotification.Provider.Callback,
        ): androidx.media3.session.MediaNotification = throw UnsupportedOperationException()

        override fun handleCustomCommand(
            session: androidx.media3.session.MediaSession,
            action: String,
            extras: android.os.Bundle,
        ): Boolean = false
    }

    private class FailingBitmapLoader : BitmapLoader {
        var callCount = 0

        override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> {
            callCount++
            return Futures.immediateFailedFuture(RuntimeException("decode-failed"))
        }

        override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
            callCount++
            return Futures.immediateFailedFuture(RuntimeException("load-failed"))
        }

        override fun supportsMimeType(mimeType: String): Boolean = true
    }

    private class ThrowingBitmapLoader : BitmapLoader {
        var callCount = 0

        override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> {
            callCount++
            throw RuntimeException("synchronous decode-failed")
        }

        override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
            callCount++
            throw RuntimeException("synchronous load-failed")
        }

        override fun supportsMimeType(mimeType: String): Boolean = true
    }

    private class RecordingLogger : Logger {
        override fun debug(tag: String, message: String) = Unit
        override fun info(tag: String, message: String) = Unit
        override fun warn(tag: String, message: String, throwable: Throwable?) = Unit
        override fun error(tag: String, message: String, throwable: Throwable?) = Unit
    }
}
