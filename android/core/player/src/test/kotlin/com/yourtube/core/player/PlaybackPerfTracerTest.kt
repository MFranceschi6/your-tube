package com.yourtube.core.player

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Locks the timing-instrumentation contract added for first-note latency
 * measurement. We don't assert on the actual `YT_PERF` log lines as a
 * stable contract — the format is intentionally loose because it exists for
 * adb logcat scanning. Instead we capture the messages emitted to a
 * recording logger and check the structural invariants: tag, level,
 * baseline-reset, elapsed-ms shape, and thread safety under contention.
 */
class PlaybackPerfTracerTest {

    private class RecordingLogger : Logger {
        data class Entry(val level: String, val tag: String, val message: String)

        private val store = mutableListOf<Entry>()

        @get:Synchronized
        val entries: List<Entry> get() = store.toList()

        @Synchronized
        override fun debug(tag: String, message: String) {
            store += Entry("debug", tag, message)
        }

        @Synchronized
        override fun info(tag: String, message: String) {
            store += Entry("info", tag, message)
        }

        @Synchronized
        override fun warn(tag: String, message: String, throwable: Throwable?) {
            store += Entry("warn", tag, message)
        }

        @Synchronized
        override fun error(tag: String, message: String, throwable: Throwable?) {
            store += Entry("error", tag, message)
        }
    }

    @Test
    fun `markTap emits a TAP line at info level on the YT_PERF tag with t equals 0ms`() {
        val logger = RecordingLogger()
        val tracer = PlaybackPerfTracer(logger)

        tracer.markTap(videoId = "abc123", source = "trackTap")

        val entry = logger.entries.single()
        assertEquals("info", entry.level)
        assertEquals(PlaybackPerfTracer.TAG, entry.tag)
        assertTrue(
            entry.message.startsWith("TAP t=0ms videoId=abc123 source=trackTap"),
            "Expected TAP line, got: ${entry.message}",
        )
    }

    @Test
    fun `mark with no prior tap reports zero elapsed`() {
        val logger = RecordingLogger()
        val tracer = PlaybackPerfTracer(logger)

        tracer.mark("EXTRACT_START", videoId = "abc123")

        val entry = logger.entries.single()
        assertTrue(
            entry.message.startsWith("EXTRACT_START t=0ms videoId=abc123"),
            "Expected zero-elapsed line, got: ${entry.message}",
        )
    }

    @Test
    fun `mark after markTap reports a non-negative elapsed_ms`() {
        val logger = RecordingLogger()
        val tracer = PlaybackPerfTracer(logger)

        tracer.markTap(videoId = "abc123", source = "trackTap")
        tracer.mark("PREPARE", videoId = "abc123")

        // Two log lines: TAP and PREPARE.
        assertEquals(2, logger.entries.size)
        val prepare = logger.entries.last()
        val elapsed = parseElapsedMs(prepare.message)
        assertTrue(elapsed >= 0L, "elapsed_ms must be non-negative, was $elapsed")
        // Sanity ceiling — anything above 5_000ms in a unit test is a bug.
        assertTrue(elapsed < 5_000L, "elapsed_ms unexpectedly large: $elapsed")
    }

    @Test
    fun `markTap resets the baseline so a new attempt starts from a fresh clock`() {
        val logger = RecordingLogger()
        val tracer = PlaybackPerfTracer(logger)

        tracer.markTap(videoId = "first", source = "trackTap")
        // Pretend some work happened.
        Thread.sleep(20)
        tracer.mark("PREPARE", videoId = "first")
        val firstElapsed = parseElapsedMs(logger.entries.last().message)
        assertTrue(firstElapsed >= 10L, "expected ~20ms elapsed, got $firstElapsed")

        // New TAP — baseline resets, the next mark should be small again.
        tracer.markTap(videoId = "second", source = "trackTap")
        tracer.mark("PREPARE", videoId = "second")
        val secondElapsed = parseElapsedMs(logger.entries.last().message)
        assertTrue(
            secondElapsed < firstElapsed,
            "Expected baseline reset: secondElapsed=$secondElapsed firstElapsed=$firstElapsed",
        )
    }

    @Test
    fun `markFail logs error class and message at info level`() {
        val logger = RecordingLogger()
        val tracer = PlaybackPerfTracer(logger)
        tracer.markTap(videoId = "abc", source = "trackTap")

        tracer.markFail(videoId = "abc", throwable = IllegalStateException("kaboom"))

        val fail = logger.entries.last()
        assertEquals("info", fail.level)
        assertTrue(fail.message.startsWith("FAIL t="), "got: ${fail.message}")
        assertTrue(fail.message.contains("error=IllegalStateException"), "got: ${fail.message}")
        assertTrue(fail.message.contains("msg=\"kaboom\""), "got: ${fail.message}")
    }

    @Test
    fun `concurrent mark calls do not corrupt the baseline or drop entries`() {
        val logger = RecordingLogger()
        val tracer = PlaybackPerfTracer(logger)
        tracer.markTap(videoId = "shared", source = "trackTap")

        val executor = Executors.newFixedThreadPool(2)
        val ready = CountDownLatch(2)
        val go = CountDownLatch(1)
        val done = CountDownLatch(2)

        repeat(2) {
            executor.submit {
                ready.countDown()
                go.await()
                repeat(50) { tracer.mark("EVENT_$it", videoId = "shared") }
                done.countDown()
            }
        }

        ready.await()
        go.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS), "concurrent marks did not complete")
        executor.shutdownNow()

        // 1 TAP + 50 marks * 2 threads.
        assertEquals(1 + 100, logger.entries.size)
        // Every mark line must report a non-negative elapsed_ms.
        for (entry in logger.entries.drop(1)) {
            assertTrue(parseElapsedMs(entry.message) >= 0L, "negative elapsed in: ${entry.message}")
        }
    }

    @Test
    fun `safeHostOf strips path and query keeping only the host`() {
        val host = PlaybackPerfTracer.safeHostOf(
            "https://rr3---sn-example.googlevideo.com/videoplayback?expire=123&token=secret",
        )
        assertEquals("rr3---sn-example.googlevideo.com", host)
    }

    @Test
    fun `safeHostOf returns a sentinel for unparseable input rather than the raw URL`() {
        val host = PlaybackPerfTracer.safeHostOf(" not a url ")
        // Either "unparseable" (URI ctor throws) or "unknown" (URI parses but
        // host is null). Both are valid — the contract is "not the raw URL".
        assertTrue(host == "unparseable" || host == "unknown", "got $host")
    }

    private fun parseElapsedMs(message: String): Long {
        // Format: "<event> t=<n>ms videoId=..."
        val tIndex = message.indexOf("t=")
        check(tIndex >= 0) { "no t= in message: $message" }
        val msIndex = message.indexOf("ms", startIndex = tIndex)
        check(msIndex > tIndex) { "no ms suffix in message: $message" }
        return message.substring(tIndex + 2, msIndex).toLong()
    }
}
