package com.yourtube.core.player

import java.net.URI
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lightweight timing instrumentation for first-note playback latency
 * (tap-to-audio).
 *
 * Records a single tap-origin timestamp and emits subsequent events with the
 * elapsed milliseconds since the most recent tap. Cross-platform comparison
 * with iOS uses these markers as the source of truth for "what's slow on
 * Android"; all logs share the [TAG] tag and `info` level so they survive
 * release-build filtering.
 *
 * Format:
 * `<event> t=<elapsed_ms>ms videoId=<id> <optional context>`
 *
 * The tracer is process-singleton: one user-initiated playback is in flight
 * at a time, and a fresh [markTap] resets the baseline for the next attempt.
 * Concurrent [mark] calls from different threads are safe because the
 * baseline is held in an [AtomicLong].
 *
 * Stream URLs are NEVER logged in full — only the host (e.g.
 * `googlevideo.com`) surfaces, because URLs resolved from InnerTube embed
 * short-lived access tokens.
 */
@Singleton
open class PlaybackPerfTracer @Inject constructor(
    private val logger: Logger,
) {
    /**
     * `nanoTime` baseline of the most recent TAP. `0L` means "no TAP yet" and
     * causes [mark] to log `t=0ms` rather than a negative or huge value.
     */
    private val tapStartNanos = AtomicLong(0L)

    /**
     * Record a user-initiated playback intent. Should be called BEFORE the
     * coroutine that performs the play work is launched, so the baseline
     * captures genuine UI latency including dispatcher delay.
     *
     * [source] distinguishes call sites: `trackTap`, `playPlaylist`,
     * `shufflePlaylist`, `playPlaylistAt`, `skipNext`, `skipPrevious`,
     * `miniPlayerPlayPause`, `nowPlayingPlayPause`, etc.
     */
    open fun markTap(videoId: String, source: String) {
        tapStartNanos.set(System.nanoTime())
        logger.info(TAG, "TAP t=0ms videoId=$videoId source=$source")
    }

    /**
     * Emit a non-tap event with elapsed-since-tap. Safe to call from any
     * thread. [context] is appended verbatim with a leading space when
     * non-empty.
     */
    open fun mark(event: String, videoId: String, context: String = "") {
        val elapsed = elapsedMs()
        val suffix = if (context.isEmpty()) "" else " $context"
        logger.info(TAG, "$event t=${elapsed}ms videoId=$videoId$suffix")
    }

    /**
     * Emit a failure marker. Logs the throwable's class name and message;
     * stack traces are intentionally omitted to keep the perf channel
     * scannable. The next [markTap] resets the clock for the retry.
     */
    open fun markFail(videoId: String, throwable: Throwable, context: String = "") {
        val elapsed = elapsedMs()
        val errorClass = throwable::class.java.simpleName
        val message = throwable.message ?: ""
        val suffix = if (context.isEmpty()) "" else " $context"
        logger.info(
            TAG,
            "FAIL t=${elapsed}ms videoId=$videoId error=$errorClass msg=\"$message\"$suffix",
        )
    }

    private fun elapsedMs(): Long {
        val baseline = tapStartNanos.get()
        if (baseline == 0L) return 0L
        return (System.nanoTime() - baseline) / 1_000_000L
    }

    companion object {
        const val TAG = "YT_PERF"

        /**
         * Returns the host portion of [streamUrl] for logging, never the full
         * URL (which contains access tokens). Falls back to a fixed sentinel
         * if the URL cannot be parsed.
         */
        fun safeHostOf(streamUrl: String): String = try {
            URI(streamUrl).host ?: "unknown"
        } catch (_: Throwable) {
            "unparseable"
        }
    }
}
