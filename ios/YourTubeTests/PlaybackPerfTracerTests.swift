import Testing
import Foundation
@testable import YourTube

// MARK: - PlaybackPerfTracerTests

/// Behavioural tests for the ad-hoc perf tracer. The os.Logger output is
/// intentionally NOT asserted — Apple does not provide a public API to
/// drain unified-log lines from inside an XCTest host. Instead we assert
/// the tracer's observable state (baseline presence, elapsed math) so the
/// public contract that the wiring relies on is locked in.
@Suite("PlaybackPerfTracer")
struct PlaybackPerfTracerTests {

    /// `markTap` flips the baseline from "not set" to "set". A subsequent
    /// `markTap` re-sets it (the elapsed reading after the second tap is
    /// strictly less than after the first, modulo clock granularity).
    @Test
    func markTapResetsBaseline() async throws {
        let tracer = PlaybackPerfTracer()
        #expect(tracer.hasBaseline == false)

        tracer.markTap(videoId: "abc123", source: "trackTap")
        #expect(tracer.hasBaseline == true)

        // Burn a small but observable amount of wall clock. 5 ms is well
        // within `ContinuousClock`'s resolution (sub-microsecond on Apple
        // Silicon) but well below xctest's per-test wall-clock cost.
        try await Task.sleep(for: .milliseconds(5))
        let firstReading = tracer.elapsedMilliseconds(at: ContinuousClock.now)
        let unwrappedFirst = try #require(firstReading)
        #expect(unwrappedFirst >= 0)

        // Re-baseline. The next reading is taken immediately, so it must be
        // strictly less than `firstReading` (which had the 5 ms sleep
        // accumulated).
        tracer.markTap(videoId: "abc123", source: "skipNext")
        let secondReading = try #require(tracer.elapsedMilliseconds(at: ContinuousClock.now))
        #expect(secondReading < unwrappedFirst)
    }

    /// `mark` produces non-negative elapsed milliseconds. Guards against the
    /// monotonicity invariant ever silently regressing — the tracer floors
    /// negative deltas to 0, but if a future change broke the floor we want
    /// a failing test rather than nonsense lines in `log stream`.
    @Test
    func markProducesNonNegativeElapsed() async throws {
        let tracer = PlaybackPerfTracer()
        tracer.markTap(videoId: "video1", source: "trackTap")

        // Hop forward in time by sleeping in small steps and reading the
        // elapsed value after each step. Every reading must be >= 0 and
        // monotonically non-decreasing.
        var lastReading = 0
        for _ in 0..<5 {
            try await Task.sleep(for: .milliseconds(2))
            let reading = try #require(tracer.elapsedMilliseconds(at: ContinuousClock.now))
            #expect(reading >= 0)
            #expect(reading >= lastReading)
            lastReading = reading
        }
    }

    /// `elapsedMilliseconds(at:)` returns nil when no TAP has been recorded.
    /// The production path renders this as `t=-` so an orphaned event is
    /// still readable in the log stream.
    @Test
    func elapsedMillisecondsReturnsNilWithoutTap() {
        let tracer = PlaybackPerfTracer()
        #expect(tracer.elapsedMilliseconds(at: ContinuousClock.now) == nil)

        tracer.markTap(videoId: "v", source: "trackTap")
        #expect(tracer.elapsedMilliseconds(at: ContinuousClock.now) != nil)

        tracer.resetForTesting()
        #expect(tracer.elapsedMilliseconds(at: ContinuousClock.now) == nil)
    }

    /// Concurrent calls from two `Task`s do not crash and observe a
    /// consistent baseline. The serial-queue protection inside the tracer is
    /// the contract under test — the periodic time observer fires on a
    /// background queue while `markTap` happens on the main actor in
    /// production, so the tracer MUST tolerate concurrent reads/writes.
    @Test
    func concurrentTaskAccessIsSafe() async throws {
        let tracer = PlaybackPerfTracer()
        tracer.markTap(videoId: "v", source: "trackTap")

        await withTaskGroup(of: Void.self) { group in
            // Writer task: re-baseline 50 times.
            group.addTask {
                for i in 0..<50 {
                    tracer.markTap(videoId: "v\(i)", source: "trackTap")
                }
            }
            // Reader task: read elapsed 50 times. We don't assert the value —
            // the assertion is the absence of a crash / data race (TSan
            // would catch any unguarded shared access).
            group.addTask {
                for _ in 0..<50 {
                    _ = tracer.elapsedMilliseconds(at: ContinuousClock.now)
                    _ = tracer.hasBaseline
                }
            }
            // Mark task: emit `mark` 50 times. Must not crash even when the
            // baseline is being mutated underneath.
            group.addTask {
                for i in 0..<50 {
                    tracer.mark("STATE_READY", videoId: "v", context: "i=\(i)")
                }
            }
        }

        // After the storm settles, the tracer is still usable.
        #expect(tracer.hasBaseline == true)
        let final = tracer.elapsedMilliseconds(at: ContinuousClock.now)
        #expect(final != nil)
        #expect((final ?? -1) >= 0)
    }

    /// The shared singleton exists and emits `mark` calls without crashing.
    /// The production path uses `PlaybackPerfTracer.shared` exclusively, so
    /// a test that simply drives the shared instance through the public API
    /// catches any bundle-id / Logger-construction regressions.
    @Test
    func sharedSingletonEmitsMarksWithoutCrash() {
        let shared = PlaybackPerfTracer.shared
        shared.resetForTesting()
        shared.markTap(videoId: "shared", source: "trackTap")
        shared.mark("EXTRACT_START", videoId: "shared")
        shared.mark("EXTRACT_DONE", videoId: "shared", context: "host=example.invalid itag=140")
        shared.mark("PREPARE", videoId: "shared")
        shared.mark("STATUS_READY_TO_PLAY", videoId: "shared")
        shared.mark("STATE_BUFFERING", videoId: "shared", context: "reason=AVPlayerWaitingToMinimizeStallsReason")
        shared.mark("STATE_READY", videoId: "shared")
        shared.mark("FIRST_AUDIO", videoId: "shared", context: "currentTime=0.100")
        // Reset so subsequent tests run from a clean baseline.
        shared.resetForTesting()
        #expect(shared.hasBaseline == false)
    }
}
