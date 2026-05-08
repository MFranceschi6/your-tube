import Foundation
import os

// MARK: - PlaybackPerfTracer

/// Lightweight, ad-hoc instrumentation that measures the latency from the
/// moment the user taps "play" to the moment the first audio frame leaves
/// `AVPlayer`. Intended as a parity yardstick against the equivalent Android
/// `YT_PERF` instrumentation so the two platforms can be compared like-for-
/// like.
///
/// Usage:
///
/// 1. The TAP origin (a SwiftUI action / view-model method that takes a
///    user-initiated playback intent) calls ``markTap(videoId:source:)``.
///    That call resets the baseline.
/// 2. Every downstream signal (`EXTRACT_START`, `EXTRACT_DONE`, `PREPARE`,
///    `STATUS_READY_TO_PLAY`, `STATE_BUFFERING`, `STATE_READY`,
///    `FIRST_AUDIO`, `FAIL`) calls ``mark(_:videoId:context:)`` with the
///    same `videoId`. The tracer prints the elapsed milliseconds since the
///    last TAP through `os.Logger`.
///
/// All output goes to the unified logging system at `.notice` level (visible
/// by default in Console.app and `log stream`) under
/// `subsystem = <bundle-id>` and `category = "YT_PERF"`. To stream lines while
/// the app runs in the simulator:
///
/// ```
/// log stream --level info --predicate 'subsystem == "com.matteofranceschi.yourtube" AND category == "YT_PERF"'
/// ```
///
/// Thread-safety: callers are typically on the main actor (the SwiftUI
/// playback path is). However, the tracer itself uses an internal serial
/// `DispatchQueue` so calls from concurrent `Task`s — or the `os.Logger`
/// callback inside `addPeriodicTimeObserver` — never race on
/// ``tapStartTime``. `@unchecked Sendable` because the queue provides the
/// actual synchronisation; the singleton holds no user-visible state.
///
/// Security: per `.claude/rules/security.md` the tracer NEVER logs full
/// stream URLs (`googlevideo.com` carries short-lived signed tokens) or any
/// user-identifiable data — `EXTRACT_DONE` callers are expected to pass
/// `host=...` and `itag=...` only.
final class PlaybackPerfTracer: @unchecked Sendable {

    // MARK: - Shared

    /// Process-wide tracer. Singleton because the TAP origin (`AppShellViewModel`)
    /// and the audio-engine signals (`AVPlayerAudioEngine`) live in unrelated
    /// dependency graphs; threading a tracer through DI just to wire ad-hoc
    /// instrumentation is more churn than the value warrants.
    static let shared = PlaybackPerfTracer()

    // MARK: - Logger

    /// `subsystem` falls back to a stable string when the bundle id is missing
    /// (e.g. inside `xctest` host where `Bundle.main.bundleIdentifier` can be
    /// `nil`) so the predicate `subsystem == "com.matteofranceschi.yourtube"`
    /// matches both production and instrument-only runs.
    private let logger: Logger

    // MARK: - State

    /// Most recent TAP timestamp. Reset on every ``markTap(videoId:source:)``.
    /// Reads/writes go through ``queue`` so concurrent callers can never
    /// observe a torn value.
    private var tapStartTime: ContinuousClock.Instant?

    /// Serial queue protecting ``tapStartTime``. A queue (rather than an
    /// actor) keeps the tracer callable from any isolation domain — the
    /// `addPeriodicTimeObserver` callback ships from `AVFoundation` on a
    /// background queue and we don't want to gate every mark on a hop to
    /// `@MainActor`.
    private let queue = DispatchQueue(label: "com.matteofranceschi.yourtube.perftracer")

    // MARK: - Init

    /// Designated initialiser. Internal (not `private`) so unit tests can
    /// stand up an isolated tracer per test without touching the singleton.
    init() {
        let subsystem = Bundle.main.bundleIdentifier ?? "com.matteofranceschi.yourtube"
        self.logger = Logger(subsystem: subsystem, category: "YT_PERF")
    }

    // MARK: - API

    /// Records the user-initiated "play" intent and resets the baseline that
    /// every subsequent ``mark(_:videoId:context:)`` measures from. `source`
    /// is a short tag identifying which call site fired (`trackTap`,
    /// `playPlaylist`, `skipNext`, `miniPlayerPlayPause`, ...). It is
    /// included in the log line so traces from different surfaces can be
    /// disambiguated when reading `log stream`.
    func markTap(videoId: String, source: String) {
        let now = ContinuousClock.now
        queue.sync { tapStartTime = now }
        // TAP itself is at t=0 by construction. Logging the line keeps the
        // trace self-contained — a reader can identify the source of the
        // baseline without correlating against other lines.
        logger.notice("TAP t=0ms videoId=\(videoId, privacy: .public) source=\(source, privacy: .public)")
    }

    /// Records a downstream timing event. `event` is the event name
    /// (`EXTRACT_START`, `PREPARE`, `STATE_READY`, ...). `context` is a free-
    /// form key=value blob (e.g. `host=rr5---sn-2gb7sn7r.googlevideo.com itag=140`).
    /// The tracer prints the elapsed milliseconds since the last
    /// ``markTap(videoId:source:)``; if no tap has been recorded yet, the
    /// elapsed value is rendered as `-` and the line still ships so the
    /// reader can spot orphaned events.
    func mark(_ event: String, videoId: String, context: String = "") {
        let now = ContinuousClock.now
        let baseline: ContinuousClock.Instant? = queue.sync { tapStartTime }

        let elapsedFragment: String
        if let baseline {
            let elapsedMs = Self.elapsedMilliseconds(from: baseline, to: now)
            elapsedFragment = "\(elapsedMs)ms"
        } else {
            elapsedFragment = "-"
        }

        let trailingContext = context.isEmpty ? "" : " \(context)"
        logger.notice("\(event, privacy: .public) t=\(elapsedFragment, privacy: .public) videoId=\(videoId, privacy: .public)\(trailingContext, privacy: .public)")
    }

    // MARK: - Test affordances

    /// Exposes the elapsed milliseconds since the last ``markTap`` for a
    /// given clock instant. Used by unit tests to assert the tracer never
    /// returns negative values across `Task`s without parsing log output.
    func elapsedMilliseconds(at instant: ContinuousClock.Instant) -> Int? {
        let baseline: ContinuousClock.Instant? = queue.sync { tapStartTime }
        guard let baseline else { return nil }
        return Self.elapsedMilliseconds(from: baseline, to: instant)
    }

    /// Returns whether a baseline has been set since process start (or the
    /// last test reset). Intended for assertion only.
    var hasBaseline: Bool {
        queue.sync { tapStartTime != nil }
    }

    /// Test-only baseline reset so each `@Test` starts from a known state.
    /// Production callers re-baseline through ``markTap(videoId:source:)``.
    func resetForTesting() {
        queue.sync { tapStartTime = nil }
    }

    // MARK: - Private

    /// Pure conversion of `from → to` to milliseconds. Floors negative deltas
    /// to 0 — `ContinuousClock` is monotonic so a negative delta is
    /// theoretically impossible, but defending the invariant is cheap and
    /// keeps the printed line readable if a future bug ever lands a tick
    /// before its baseline.
    private static func elapsedMilliseconds(from start: ContinuousClock.Instant, to end: ContinuousClock.Instant) -> Int {
        let duration = start.duration(to: end)
        // `Duration.components` is `(seconds, attoseconds)`. Convert to
        // milliseconds in integer arithmetic to avoid floating-point churn
        // in the hot path.
        let comps = duration.components
        let ms = comps.seconds * 1_000 + Int64(comps.attoseconds / 1_000_000_000_000_000)
        return Int(max(0, ms))
    }
}
