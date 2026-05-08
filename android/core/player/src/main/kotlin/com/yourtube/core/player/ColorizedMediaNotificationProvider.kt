package com.yourtube.core.player

import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import androidx.annotation.ColorInt
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.FutureCallback
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors

/**
 * YT-0076 — wraps Media3's [androidx.media3.session.DefaultMediaNotificationProvider] and post-processes the resulting
 * [MediaNotification] to apply a palette-extracted dominant color via
 * [NotificationCompat.Builder.setColorized] + [NotificationCompat.Builder.setColor].
 *
 * Why a delegating wrapper rather than a subclass: in Media3 1.4.1 both
 * `DefaultMediaNotificationProvider.createNotification(...)` and `handleCustomCommand(...)` are
 * declared `final`. The protected hook points (`getMediaButtons`, `addNotificationActions`,
 * `getNotificationContentTitle`, `getNotificationContentText`) only let us influence inputs and
 * text — we cannot mutate the assembled `Notification` or its `NotificationCompat.Builder`.
 *
 * The wrapper instead keeps the default's full layout work intact and rebuilds the notification
 * around it via [NotificationCompat.Builder.recoverBuilder], applying:
 *  - `setColorized(true)` so the system honors `setColor` on the media style notification
 *  - `setColor(...)` with a cached or freshly extracted palette swatch
 *
 * Bitmap loading and palette extraction are non-trivial and run asynchronously off the calling
 * thread. The [MediaNotification.Provider.Callback.onNotificationChanged] hook lets us re-issue
 * the colorized notification once the bitmap is available, mirroring how the default provider
 * already re-issues the notification when its async bitmap load completes.
 *
 * `MediaNotification.Provider` is `@UnstableApi` in Media3 1.4.x. Opt-in is contained at this
 * file's annotated members; callers (i.e. [PlaybackService]) do not need their own opt-in.
 */
@OptIn(UnstableApi::class)
internal class ColorizedMediaNotificationProvider(
    private val context: Context,
    private val delegate: MediaNotification.Provider,
    private val cache: PaletteColorCache,
    private val logger: Logger,
) : MediaNotification.Provider {

    /**
     * Tracks the last bitmap-load future so a stale callback for a previous track cannot
     * overwrite the colorized notification with the wrong color. Guarded by `this`.
     */
    private var pendingFuture: ListenableFuture<Bitmap>? = null

    /**
     * YT-0076 review change-request (2026-05-08): the latest `videoId` for which
     * [createNotification] has been invoked. The async colorize callback captures the `base`
     * snapshot in its closure; if Media3 issues a fresh `createNotification` cycle for a new
     * track before the in-flight bitmap completes, the captured `base` is stale (it represents
     * the previous track's layout). Re-issuing it through `onNotificationChanged` would
     * overwrite the active frame on the lock-screen card with stale artwork. We therefore
     * gate the callback on `latestVideoId == capturedVideoId` so only the current track's
     * colorize cycle can re-issue. Volatile because reads happen on whatever executor the
     * `ListenableFuture` directExecutor lands on; writes are on the player application thread
     * during `createNotification`.
     */
    @Volatile
    private var latestVideoId: String? = null

    override fun createNotification(
        mediaSession: MediaSession,
        customLayout: ImmutableList<CommandButton>,
        actionFactory: MediaNotification.ActionFactory,
        onNotificationChangedCallback: MediaNotification.Provider.Callback,
    ): MediaNotification {
        // Always let the default provider build the layout, transport actions, large icon, etc.
        val base = delegate.createNotification(
            mediaSession,
            customLayout,
            actionFactory,
            onNotificationChangedCallback,
        )

        val metadata: MediaMetadata? = runCatching {
            mediaSession.player.takeIf {
                it.isCommandAvailable(androidx.media3.common.Player.COMMAND_GET_METADATA)
            }?.mediaMetadata
        }.getOrNull()
        val videoId: String = metadata?.extras
            ?.getString(PlaybackSessionCommand.EXTRAS_KEY_VIDEO_ID)
            ?.takeIf { it.isNotBlank() }
            ?: return base

        // Mark this as the active videoId before kicking off any async work. The async
        // colorize callback gates on this to detect that a newer `createNotification` cycle
        // (possibly for a different track) has superseded its captured `base` snapshot.
        latestVideoId = videoId

        // Fast path: cached color, recolor synchronously. Also clear any stale pending future
        // — if a previous track's bitmap load is still in flight, its callback would carry a
        // `base` from that previous cycle and overwrite this colorized frame.
        cache.get(videoId)?.let { cachedColor ->
            synchronized(this) {
                pendingFuture?.cancel(/* mayInterruptIfRunning = */ false)
                pendingFuture = null
            }
            return base.recolored(cachedColor)
        }

        // Cold path: kick off async bitmap load + palette extraction. Re-issue the
        // notification through `onNotificationChangedCallback` once the color is ready. We
        // return the un-colorized base notification immediately so the system surface
        // never blocks on Palette work.
        val bitmapFuture: ListenableFuture<Bitmap>? = runCatching {
            mediaSession.bitmapLoader.loadBitmapFromMetadata(metadata)
        }.getOrNull()
        if (bitmapFuture != null) {
            scheduleColorize(videoId, base, bitmapFuture, onNotificationChangedCallback)
        }

        return base
    }

    /**
     * Test-only seam used to simulate a fresh `createNotification` cycle bumping the active
     * videoId between [scheduleColorize] and the bitmap-future completion. Production code
     * sets [latestVideoId] inside [createNotification] only.
     */
    internal fun setLatestVideoIdForTest(videoId: String?) {
        latestVideoId = videoId
    }

    /**
     * YT-0076 review change-request (2026-05-08): extracted so the gating logic for stale
     * colorize callbacks can be unit-tested without instantiating a real [MediaSession]. The
     * callback registered here:
     *  1. Tracks the latest in-flight future via [pendingFuture] so a previous track's
     *     bitmap load cannot reach `onNotificationChanged` once a newer cycle has started
     *     (`pendingFuture !== bitmapFuture` early return).
     *  2. Gates on [latestVideoId] so even if [pendingFuture] reference equality holds (e.g.
     *     a synchronous cache-hit cycle did not allocate a new future), the captured `base`
     *     snapshot is dropped when the active videoId has moved on. This is the regression
     *     fix for "lockscreen artwork stale after in-app skip".
     */
    internal fun scheduleColorize(
        videoId: String,
        base: MediaNotification,
        bitmapFuture: ListenableFuture<Bitmap>,
        onNotificationChangedCallback: MediaNotification.Provider.Callback,
    ) {
        synchronized(this) {
            // Cancel any in-flight load for a previous track — the result would be stale.
            pendingFuture?.cancel(/* mayInterruptIfRunning = */ false)
            pendingFuture = bitmapFuture
        }
        Futures.addCallback(
            bitmapFuture,
            object : FutureCallback<Bitmap> {
                override fun onSuccess(result: Bitmap?) {
                    synchronized(this@ColorizedMediaNotificationProvider) {
                        if (pendingFuture !== bitmapFuture) return
                        pendingFuture = null
                    }
                    if (result == null) return
                    // Stale-snapshot guard: if a fresh createNotification cycle has occurred
                    // for a different videoId since we captured `base`, the captured layout
                    // is no longer what the system surface is showing. Dropping this
                    // re-issue prevents the previous track's artwork from clobbering the
                    // current track's frame on the lock-screen.
                    if (latestVideoId != videoId) return
                    val color = cache.getOrExtract(videoId, result) ?: return
                    val updated = base.recolored(color)
                    onNotificationChangedCallback.onNotificationChanged(updated)
                }

                override fun onFailure(t: Throwable) {
                    synchronized(this@ColorizedMediaNotificationProvider) {
                        if (pendingFuture === bitmapFuture) pendingFuture = null
                    }
                    // Do not log the bitmap URI — it is derived from a videoId and is PII.
                    logger.warn(TAG, "palette bitmap load failed", t)
                }
            },
            MoreExecutors.directExecutor(),
        )
    }

    override fun handleCustomCommand(
        session: MediaSession,
        action: String,
        extras: Bundle,
    ): Boolean = delegate.handleCustomCommand(session, action, extras)

    /**
     * Pre-warm the cache for [track] by loading its artwork via Media3's [BitmapLoader] and
     * extracting the palette swatch ahead of the metadata change. Best-effort: failures are
     * swallowed — the cold path inside [createNotification] will re-try if the user actually
     * advances to this track.
     *
     * [bitmapLoader] is intentionally taken as a parameter rather than read from a session
     * because [PlaybackService] holds the session and can pass it in; this also keeps the
     * provider unit-testable without a `MediaSession`.
     */
    fun prewarm(track: com.yourtube.core.common.model.Track, bitmapLoader: androidx.media3.common.util.BitmapLoader) {
        val videoId = track.videoId.trim()
        if (videoId.isEmpty()) return
        if (cache.get(videoId) != null) return

        val metadata = PlaybackSessionCommand.toMediaMetadata(track)
        val future = runCatching { bitmapLoader.loadBitmapFromMetadata(metadata) }.getOrNull()
            ?: return
        Futures.addCallback(
            future,
            object : FutureCallback<Bitmap> {
                override fun onSuccess(result: Bitmap?) {
                    if (result == null) return
                    cache.getOrExtract(videoId, result)
                }

                override fun onFailure(t: Throwable) {
                    // Swallow; pre-warm is opportunistic.
                }
            },
            MoreExecutors.directExecutor(),
        )
    }

    private fun MediaNotification.recolored(@ColorInt color: Int): MediaNotification {
        val recovered = NotificationCompat.Builder(context, this.notification)
        recovered.setColorized(true)
        recovered.setColor(color)
        return MediaNotification(notificationId, recovered.build())
    }

    companion object {
        private const val TAG = "YT-NotifProvider"
    }
}
