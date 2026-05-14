import Foundation
import SwiftUI

// MARK: - PlayerCoordinator

/// Owns the playback queue and the high-level `PlayerState` surfaced to the
/// SwiftUI shell. Sits between the screens (`SearchScreen`, `MiniPlayer`,
/// `NowPlayingView`) and the lower-level `AudioEngineProtocol`.
///
/// Responsibilities:
/// - Queue ops: play-now, append, next/previous, remove, reorder.
/// - State machine: idle → loading → playing/paused/buffering/error.
/// - Stream resolution: reads the persisted `audioQuality` preference and
///   forwards it to ``YouTubeServiceProtocol/resolveStreamURL(videoId:quality:)``
///   before handing the resolved track to the audio engine.
/// - Progress: derives `currentTime` / `duration` from the engine without
///   pushing layout-bound bindings. Views read these properties directly.
///
/// Architecture notes:
/// - `@Observable` + `@MainActor`. The coordinator never touches `AVPlayer`
///   directly — that is the engine's job.
/// - All long-running work lives on the coordinator (not in view bodies) so a
///   fake `AudioEngineProtocol` and `YouTubeServiceProtocol` can drive the
///   full state machine in tests.
@Observable
@MainActor
final class PlayerCoordinator {

    // MARK: - Observed state

    /// High-level transport state. Starts at `.idle`.
    private(set) var state: PlayerState = .idle

    /// Tracks queued for playback. The currently-playing track is `queue[currentIndex]`.
    private(set) var queue: [Track] = []

    /// Index of the active track in `queue`, or `nil` when nothing is loaded.
    private(set) var currentIndex: Int?

    // MARK: - YT-0298 Mix continuation state

    /// Continuation token captured from the most recent Mix initial-fetch or
    /// continuation page. `nil` means either no Mix is active or the server
    /// returned no further pages (terminal). Survives skip-next / skip-prev /
    /// reorder; cleared only on a fresh `playNow(_:)` call.
    ///
    /// All reads and writes are on `@MainActor` so a plain `var` is safe —
    /// no atomics or locks needed.
    ///
    /// `internal` (not `private`) so `@testable import YourTube` tests can
    /// assert on it directly via `sut.mixContinuationToken`.
    var mixContinuationToken: String?

    /// The in-flight continuation Task, if one is currently running. Single-flight:
    /// `maybePrefetchMixContinuation` is a no-op when this is non-nil. Cleared on
    /// completion or cancellation so subsequent near-tail hits can retry.
    ///
    /// `internal` for the same testability reason as `mixContinuationToken`.
    var mixContinuationTask: Task<Void, Never>?

    // MARK: - Shuffle & Repeat (YT-0027 Q10)

    /// Shuffle indicator. v1 mirrors the state to `MPRemoteCommandCenter.changeShuffleModeCommand`
    /// and feeds the Now Playing UI; queue reordering on shuffle is staged
    /// for v1.1. Toggling does not currently rearrange `queue` to keep the
    /// state machine deterministic in unit tests.
    private(set) var shuffleEnabled: Bool = false

    /// Three-state repeat. Off / All / One per `RepeatMode`. v1 mirrors the
    /// state to the Now Playing UI and `changeRepeatModeCommand`; queue-end
    /// repeat behaviour ships with the queue follow-up.
    private(set) var repeatMode: RepeatMode = .off

    // MARK: - Derived state (read by UI)

    /// The currently-loaded track, if any.
    var currentTrack: Track? {
        guard let i = currentIndex, queue.indices.contains(i) else { return nil }
        return queue[i]
    }

    /// Convenience: `true` while playback is in flight.
    ///
    /// Treats `.loading` as "intends to play" so `MiniPlayer` and the now-playing
    /// view show a pause affordance from the moment the user taps play, instead
    /// of flickering to a play icon while the stream URL resolves. Maps to false
    /// for `.idle`, `.paused`, `.buffering`, and `.error` — buffering is
    /// considered a stalled-playing state for the engine but not for the
    /// "intending" UI bool (a buffer stall should not show the play icon either,
    /// but engine-level recovery is owned by the engine, so we keep it false
    /// here until YT-0027 wires the buffer-stall UI).
    var isPlaying: Bool {
        switch state {
        case .playing, .loading: return true
        case .idle, .paused, .buffering, .error: return false
        }
    }

    /// Whether a previous-track operation will succeed.
    var hasPrevious: Bool {
        guard let i = currentIndex else { return false }
        return i > 0
    }

    /// Whether a next-track operation will succeed.
    var hasNext: Bool {
        guard let i = currentIndex else { return false }
        return i < queue.count - 1
    }

    /// Engine playback time (seconds).
    var currentTime: TimeInterval { audioEngine.currentTime }

    /// Engine track duration (seconds). 0 when unknown.
    var duration: TimeInterval { audioEngine.duration }

    /// Normalised 0–1 progress used by `MiniPlayer` and the now-playing scrubber.
    /// Returns 0 when duration is unknown to avoid layout instability from `NaN`.
    var progress: Double {
        guard duration > 0 else { return 0 }
        return min(max(currentTime / duration, 0), 1)
    }

    // MARK: - Dependencies

    private let audioEngine: any AudioEngineProtocol
    private let youtubeService: any YouTubeServiceProtocol
    /// Reads the persisted audio-quality preference. Defaults to UserDefaults.standard
    /// so production reads `SettingsKeys.audioQuality` and tests can substitute an
    /// in-memory provider.
    private let qualityProvider: @MainActor () -> AudioQuality

    /// Optional history recorder invoked once a track has been handed to the
    /// audio engine. Production wiring writes a ``HistoryEntryEntity`` via
    /// ``HistoryStore``; tests use a no-op default. Kept as a closure so the
    /// coordinator does not import SwiftData and stays fully unit-testable.
    ///
    /// Mutable so the shell can attach a `ModelContext`-backed recorder after
    /// the coordinator has been constructed (the SwiftData environment is only
    /// available inside the view tree).
    private var historyRecorder: @MainActor (Track) -> Void

    /// Most recent stream-resolution task; cancelled when a new `playNow(_:)`
    /// supersedes it so old loads cannot overwrite newer state.
    private var resolveTask: Task<Void, Never>?

    // MARK: YT-0053 — next-track prefetch

    /// One-slot in-memory cache of the next queued track's resolved stream
    /// URL. Populated as a side-effect of the current track reaching
    /// stable `.playing` and a queue successor being available; consumed
    /// on `next()` (manual or auto-advance) before issuing a fresh
    /// resolve. In-memory only — never persisted (signed YouTube URLs
    /// carry short-lived tokens per `.claude/rules/security.md`).
    ///
    /// Multi-slot prefetch + LRU is a v1.1 follow-up; v1 is one-slot.
    private var prefetchCache: NextTrackResolveCache.Entry?

    /// In-flight prefetch task. Cancelled on queue mutation, on a new
    /// `playNow(_:)` (different track), or when the user skips before the
    /// prefetch lands. The task itself is fire-and-forget on completion —
    /// any error (network / signature / cancellation) leaves the cache
    /// empty so `next()` falls through to a live resolve.
    private var prefetchTask: Task<Void, Never>?

    /// Test seam for the prefetch freshness window. Override in tests to
    /// drive expiry without sleeping. Production keeps 30 minutes per
    /// the YT-0053 spec.
    private let prefetchFreshness: TimeInterval

    /// Test seam for "now" in the freshness check. Defaults to live wall
    /// clock; tests substitute a fixed clock to drive expiry deterministically.
    private let prefetchClock: @Sendable () -> Date

    // MARK: YT-0298 — Mix continuation constants

    /// Number of unplayed queue slots remaining at which a continuation prefetch
    /// fires. Matches the Android reference (PREFETCH_THRESHOLD = 2 per YT-0297 /
    /// `docs/mix-queue.md §Anti-bot`).
    private static let mixPrefetchThreshold = 2

    /// Minimum track duration in seconds for Mix eligibility. Tracks shorter than
    /// this are Shorts/previews and are filtered out. Same rule as initial Mix fetch.
    private static let minMixDurationSec = 60

    /// Maximum time (seconds) `tryExtendMixAtTail` idles for an in-flight
    /// continuation fetch before falling through to autoplay-related.
    /// Matches Android's `MIX_TAIL_WAIT_MS = 2_000L` for cross-platform parity.
    private static let mixTailWaitTimeout: TimeInterval = 2.0

    // MARK: - Init

    init(
        audioEngine: any AudioEngineProtocol,
        youtubeService: any YouTubeServiceProtocol,
        qualityProvider: @escaping @MainActor () -> AudioQuality = PlayerCoordinator.defaultQualityProvider,
        historyRecorder: @escaping @MainActor (Track) -> Void = { _ in },
        prefetchFreshness: TimeInterval = 30 * 60,
        prefetchClock: @escaping @Sendable () -> Date = { Date() }
    ) {
        self.audioEngine = audioEngine
        self.youtubeService = youtubeService
        self.qualityProvider = qualityProvider
        self.historyRecorder = historyRecorder
        self.prefetchFreshness = prefetchFreshness
        self.prefetchClock = prefetchClock
    }

    /// Default reader for `AudioQuality` from UserDefaults. Honours the
    /// `.auto → .high` policy documented on
    /// ``AudioQualityPreference/toServiceQuality()``.
    static let defaultQualityProvider: @MainActor () -> AudioQuality = {
        let raw = UserDefaults.standard.string(forKey: SettingsKeys.audioQuality)
            ?? AudioQualityPreference.auto.rawValue
        return (AudioQualityPreference(rawValue: raw) ?? .auto).toServiceQuality()
    }

    /// Replace the history recorder. Called by the app shell once the SwiftData
    /// `ModelContext` is available so the live coordinator can persist recently
    /// played entries. Idempotent.
    func setHistoryRecorder(_ recorder: @escaping @MainActor (Track) -> Void) {
        self.historyRecorder = recorder
    }

    // MARK: - Queue mutations

    /// Replaces any in-flight playback with `track` and starts loading it.
    /// `track` is appended to the queue (or selected if already present at the
    /// tail) so subsequent next/previous behave predictably.
    ///
    /// YT-0298: cancels any in-flight Mix continuation and clears the token so
    /// the new track starts a fresh Mix walk. After the seed track is queued, a
    /// fire-and-forget Mix initial-fetch runs asynchronously.
    func playNow(_ track: Track) {
        // YT-0298 — fresh tap starts a fresh Mix. Cancel before any state
        // mutation so a racing continuation page cannot append to the new queue.
        cancelMixContinuation()

        if let existing = queue.firstIndex(where: { $0.videoId == track.videoId }) {
            currentIndex = existing
        } else {
            queue.append(track)
            currentIndex = queue.count - 1
        }
        beginLoadingCurrent()

        // YT-0298 — fire-and-forget initial Mix fetch. The seed track is already
        // at queue[currentIndex]; this call appends the Mix tail asynchronously
        // without disturbing playback. Mirrors YT-0295 / Android YT-0294.
        let seedVideoId = track.videoId
        Task { @MainActor [weak self] in
            await self?.tryLoadMixQueue(seedVideoId: seedVideoId)
        }
    }

    /// Appends `track` to the end of the queue. If nothing is currently loaded,
    /// the appended track becomes the active one and starts loading.
    func append(_ track: Track) {
        // YT-0053: appending mid-queue (anywhere except as the very-next
        // slot) doesn't change the prefetched successor — but if the queue
        // had only the current track and this append makes `track` the new
        // successor, the prefetch slot is stale. Re-prefetch via the
        // current track once the queue change settles. Keeping it simple:
        // any append invalidates the prefetch and re-runs the schedule.
        let prevHadSuccessor = (currentIndex.map { $0 + 1 < queue.count }) ?? false
        queue.append(track)
        if currentIndex == nil {
            currentIndex = queue.count - 1
            beginLoadingCurrent()
        } else if !prevHadSuccessor, let current = currentTrack {
            // The append created a new successor that didn't exist before.
            // Schedule a prefetch only if the current track is already
            // playing — otherwise `beginLoadingCurrent`'s post-resolve
            // hook will pick it up.
            if case .playing = state {
                schedulePrefetchForSuccessor(of: current)
            }
        }
    }

    /// Advances to the next queued track.
    ///
    /// At queue-end (AC7 / YT-0298): if a Mix continuation is active, spawns
    /// `tryExtendMixAtTail()` to await the in-flight fetch (bounded timeout)
    /// before falling through to the autoplay-related path (YT-0292).
    func next() {
        guard let i = currentIndex else { return }
        guard hasNext else {
            if isMixContinuationActive {
                Task { @MainActor [weak self] in
                    await self?.tryExtendMixAtTail()
                }
            }
            return
        }
        currentIndex = i + 1
        beginLoadingCurrent()
        // YT-0298 — check whether a continuation prefetch should fire now.
        maybePrefetchMixContinuation()
    }

    /// Called at queue-end when `isMixContinuationActive` is true (AC7 / YT-0298).
    ///
    /// Fires a continuation fetch if none is in flight, then idles for up to
    /// `mixTailWaitTimeout` seconds for the fetch to land. If the queue has
    /// grown after the wait, calls `next()` to advance. Otherwise returns and
    /// lets the caller fall through to autoplay-related (YT-0292).
    func tryExtendMixAtTail() async {
        guard isMixContinuationActive else { return }
        maybePrefetchMixContinuation()
        let deadline = Date(timeIntervalSinceNow: Self.mixTailWaitTimeout)
        while mixContinuationTask != nil && Date() < deadline {
            await Task.yield()
        }
        if hasNext { next() }
    }

    /// Returns to the previous queued track. No-op at the head of the queue.
    func previous() {
        guard hasPrevious, let i = currentIndex else { return }
        // YT-0053: `previous()` re-positions the cursor backwards, so the
        // current prefetch slot (which was filled for the OLD successor)
        // is no longer relevant. `beginLoadingCurrent` will re-schedule
        // prefetch for the new successor once the previous track lands.
        invalidatePrefetch()
        currentIndex = i - 1
        beginLoadingCurrent()
    }

    /// YT-0308 — jump to an arbitrary slot in the existing queue. Behaves as if
    /// the user pressed skip-next (or skip-prev) repeatedly until `index` became
    /// current: `queue` contents and order are preserved, `currentIndex` moves
    /// to `index`, and the track at `index` begins loading via the same path
    /// `next()` / `previous()` use.
    ///
    /// Differences from ``playNow(_:)`` (the new-queue path):
    /// - Does NOT clear `mixContinuationToken` / `mixContinuationTask` — the
    ///   in-progress Mix walk is preserved.
    /// - Does NOT rebuild the queue or trigger a fresh initial-Mix fetch.
    ///
    /// Guards:
    /// - Out-of-bounds `index` → no-op.
    /// - `index == currentIndex` → no-op (do not restart, do not re-resolve).
    ///
    /// Mirrors Android `DefaultPlayerController.jumpToQueueItem(index)` (YT-0307).
    func jumpToQueueItem(at index: Int) {
        guard queue.indices.contains(index) else { return }
        guard index != currentIndex else { return }
        // YT-0053: jumping re-positions the cursor anywhere in the queue, so
        // the cached successor (filled for the OLD position) is no longer
        // relevant. `beginLoadingCurrent` will re-schedule prefetch for the
        // new successor once the tapped track lands. Mirrors `previous()`.
        invalidatePrefetch()
        currentIndex = index
        beginLoadingCurrent()
        // YT-0298 — same trigger as `next()`: a tap-jump can land within
        // `mixPrefetchThreshold` of the tail. The continuation token (if any)
        // is intentionally preserved across the jump.
        maybePrefetchMixContinuation()
    }

    /// Removes the track at `index`. If the active track is removed, playback
    /// continues with whatever now occupies that index (or stops if the queue
    /// becomes empty / `index` was the tail).
    func remove(at index: Int) {
        guard queue.indices.contains(index) else { return }
        let wasCurrent = (index == currentIndex)
        // YT-0053: removing any item adjacent to the cursor can invalidate
        // the cached successor (if the removed item *was* the successor,
        // or shifted it). Cheapest correct behaviour: drop the cache and
        // re-schedule when state settles.
        invalidatePrefetch()
        queue.remove(at: index)

        guard let current = currentIndex else { return }

        if queue.isEmpty {
            currentIndex = nil
            resolveTask?.cancel()
            audioEngine.stop()
            state = .idle
            return
        }

        if wasCurrent {
            // Keep the same index pointing at the next track; clamp to last.
            let clamped = min(index, queue.count - 1)
            currentIndex = clamped
            beginLoadingCurrent()
        } else if index < current {
            // Items before the active index shift the cursor left.
            currentIndex = current - 1
            // The successor may have changed identity if the removed item
            // came from before-or-at the old successor index. Re-prefetch
            // via the current track if it's already playing.
            if case .playing = state, let track = currentTrack {
                schedulePrefetchForSuccessor(of: track)
            }
        } else if case .playing = state, let track = currentTrack {
            // Removed from after-cursor — the successor identity may have
            // shifted. Re-prefetch.
            schedulePrefetchForSuccessor(of: track)
        }
    }

    /// Moves the track at `from` to `to`. Invalid indices are ignored.
    /// Active-track tracking is preserved.
    func move(from: Int, to: Int) {
        guard
            queue.indices.contains(from),
            to >= 0,
            to <= queue.count
        else { return }
        // YT-0053: any reorder potentially changes the successor identity.
        // Drop the prefetch and re-schedule below if state allows.
        invalidatePrefetch()

        let track = queue.remove(at: from)
        let insertion = (from < to) ? (to - 1) : to
        let bounded = max(0, min(insertion, queue.count))
        queue.insert(track, at: bounded)

        // Preserve the active track by following its identity.
        if let i = currentIndex {
            if i == from {
                currentIndex = bounded
            } else {
                let lower = min(from, bounded)
                let upper = max(from, bounded)
                if i >= lower && i <= upper {
                    currentIndex = (from < bounded) ? i - 1 : i + 1
                }
            }
        }
        // YT-0053: re-prefetch for the new successor if currently playing.
        if case .playing = state, let current = currentTrack {
            schedulePrefetchForSuccessor(of: current)
        }
    }

    // MARK: - Transport

    func togglePlayPause() {
        switch state {
        case .playing, .loading:
            // .loading is treated as "user intends to play" by `isPlaying`,
            // so the pause affordance must reflect that intent: pause the
            // engine and stop the in-flight resolve from auto-resuming.
            resolveTask?.cancel()
            audioEngine.pause()
            state = .paused
        case .paused:
            audioEngine.resume()
            state = .playing
        case .idle, .buffering, .error:
            // No-op: nothing to toggle yet. The next state-changing op
            // (playNow / next / previous) will move us out of this state.
            break
        }
    }

    func seek(to time: TimeInterval) {
        audioEngine.seek(to: time)
    }

    /// Forces the coordinator into an error state with a user-safe message.
    /// Exposed for view-level error reporting and for tests.
    func reportError(_ message: String) {
        state = .error(message: message)
    }

    // MARK: - Error dismissal & retry (YT-0070)

    /// Dismisses an active `.error` state. Returns to `.paused` when a track
    /// is still loaded (so the user can hit play to retry transport without
    /// re-resolving), or `.idle` when nothing is loaded. No-op for
    /// non-error states. Used by the MiniPlayer / NowPlaying error banner's
    /// dismiss affordance.
    func dismissError() {
        guard case .error = state else { return }
        if currentTrack != nil {
            state = .paused
        } else {
            state = .idle
        }
    }

    /// Retries the most recently attempted track by re-running the resolve
    /// flow via `playNow(_:)`. No-op when no track is currently selected
    /// (e.g. the user cleared the queue while the error banner was visible).
    /// Used by the MiniPlayer / NowPlaying error banner's retry affordance.
    func retryLastAttempt() {
        guard let track = currentTrack else { return }
        playNow(track)
    }

    // MARK: - YT-0192 Active-playback helper

    /// Returns `true` only when `videoId` matches the current track AND playback
    /// is actively in flight (`.playing` or `.loading`). Use this instead of a
    /// bare id-equality check when deciding whether to animate EQ bars, so that
    /// paused or stopped rows stay visually inert.
    ///
    /// - Parameter videoId: The video identifier of the candidate row.
    /// - Returns: `true` when the row's track is the currently-playing (or
    ///   loading) one; `false` in all other cases including `currentTrack == nil`.
    func isActivelyPlaying(videoId: String) -> Bool {
        guard isPlaying else { return false }
        return currentTrack?.videoId == videoId
    }

    // MARK: - Shuffle & Repeat actions (YT-0027 Q10)

    /// Toggles shuffle state. State only — queue reordering is intentionally
    /// out of scope for v1 (see ``shuffleEnabled``). Mirrors to the engine
    /// so `MPRemoteCommandCenter` reflects the new value.
    func toggleShuffle() {
        shuffleEnabled.toggle()
        audioEngine.setShuffleMode(shuffleEnabled)
    }

    /// Cycles through ``RepeatMode`` `off → all → one → off`. Mirrors to the
    /// engine.
    func cycleRepeat() {
        repeatMode = repeatMode.next
        audioEngine.setRepeatMode(repeatMode)
    }

    /// Sets the shuffle state directly. Used by `MPRemoteCommandCenter`
    /// `changeShuffleModeCommand` so the lock-screen toggle stays in sync.
    /// Does not re-mirror to the engine — the engine is the source of the
    /// remote-initiated event.
    func setShuffleEnabled(_ enabled: Bool) {
        shuffleEnabled = enabled
    }

    /// Sets the repeat mode directly. Used by `MPRemoteCommandCenter`
    /// `changeRepeatModeCommand`.
    func setRepeatMode(_ mode: RepeatMode) {
        repeatMode = mode
    }

    // MARK: - Private

    /// Begins resolving and loading the track at `currentIndex`. Cancels any
    /// in-flight resolution so the most recent user action wins.
    private func beginLoadingCurrent() {
        guard let track = currentTrack else { return }
        resolveTask?.cancel()
        // YT-0053: cancel any prefetch task in flight — its target may no
        // longer be the new successor (the user could have skipped past
        // it). The cache itself is consulted below before issuing a fresh
        // resolve; entries that don't match the new track are simply
        // ignored (and the slot is dropped).
        prefetchTask?.cancel()
        prefetchTask = nil
        state = .loading

        // YT-0046 Bug B: reset the engine's observable state to the new track's
        // metadata immediately. Stream resolution can take several hundred ms
        // (long videos especially), and without this call `engine.duration`
        // would still reflect the *previous* track until `play(track:url:)`
        // runs — so MiniPlayer and Now Playing would briefly show e.g. a 6h
        // duration against a 4-minute song. `prepare` does not touch the
        // AVPlayer item; the real load still happens after resolution.
        audioEngine.prepare(track: track)

        let quality = qualityProvider()
        let service = youtubeService
        let engine = audioEngine

        // YT-0053: cache hit — skip the live resolve when a prefetched URL
        // for THIS track is in the slot and still fresh. Saves the ~3.5s
        // YouTubeKit roundtrip on auto-advance / manual `next()`. Cancel
        // any unrelated in-flight prefetch (queue mutation case) and
        // start prefetching the next-after-this so the slot stays warm.
        if let cached = consumePrefetchedStream(for: track) {
            engine.play(track: track, url: cached.url, resourceLoader: cached.proxyLoader)
            state = .playing
            historyRecorder(track)
            schedulePrefetchForSuccessor(of: track)
            return
        }

        resolveTask = Task { @MainActor [weak self] in
            do {
                let resolved = try await service.resolveStreamURL(
                    videoId: track.videoId,
                    quality: quality
                )
                if Task.isCancelled { return }
                guard let self else { return }
                // Hand the resolved stream URL to the engine so AVPlayer can
                // build an `AVPlayerItem` and actually start playing. Without
                // the URL the engine only mutates transport state (YT-0044).
                // The optional `proxyLoader` is non-nil when YT-0157 wrapped
                // a long fragmented-mp4 audio URL with the HLS proxy — the
                // engine binds and retains it for the new item's lifetime.
                engine.play(track: track, url: resolved.url, resourceLoader: resolved.proxyLoader)
                self.state = .playing
                // Record the play event after the engine accepts the track so
                // history reflects actual playback intent (and not failed
                // resolutions or cancelled superseded loads).
                self.historyRecorder(track)
                // YT-0053: now that the current track has actually begun
                // audible playback, kick off a prefetch for the next-in-
                // queue track so a manual `next()` or auto-advance lands
                // ~0s of resolve latency.
                self.schedulePrefetchForSuccessor(of: track)
            } catch is CancellationError {
                // Superseded by a newer op — leave state alone.
                return
            } catch let error as YouTubeServiceError {
                if Task.isCancelled { return }
                self?.state = .error(message: error.errorDescription ?? "Playback failed.")
            } catch {
                if Task.isCancelled { return }
                self?.state = .error(message: "Playback failed.")
            }
        }
    }

    // MARK: YT-0053 — prefetch helpers

    /// If the prefetch slot holds a fresh entry for `track`, returns it and
    /// clears the slot. Stale entries (older than `prefetchFreshness`) are
    /// dropped without being returned — `next()` will fall through to a
    /// live resolve. Different-videoId entries are dropped silently.
    private func consumePrefetchedStream(for track: Track) -> ResolvedStream? {
        guard let entry = prefetchCache, entry.videoId == track.videoId else {
            return nil
        }
        let age = prefetchClock().timeIntervalSince(entry.timestamp)
        prefetchCache = nil
        guard age <= prefetchFreshness else { return nil }
        return entry.stream
    }

    /// Cancels any in-flight prefetch and starts a new one for the queue
    /// position immediately after `current`. No-op when the queue has no
    /// successor. Prefetch failures are silent (any
    /// `YouTubeServiceError`, `CancellationError`, or network error
    /// leaves the cache empty so `next()` falls through to a live
    /// resolve — no UI surface for prefetch failure).
    private func schedulePrefetchForSuccessor(of current: Track) {
        prefetchTask?.cancel()
        prefetchTask = nil
        guard let i = currentIndex,
              queue.indices.contains(i),
              queue[i].videoId == current.videoId,
              i + 1 < queue.count else {
            return
        }
        let successor = queue[i + 1]
        // Skip if the slot already holds a fresh entry for the same track.
        if let cached = prefetchCache,
           cached.videoId == successor.videoId,
           prefetchClock().timeIntervalSince(cached.timestamp) <= prefetchFreshness {
            return
        }
        let quality = qualityProvider()
        let service = youtubeService
        let clock = prefetchClock
        prefetchTask = Task { @MainActor [weak self] in
            do {
                let resolved = try await service.resolveStreamURL(
                    videoId: successor.videoId,
                    quality: quality
                )
                if Task.isCancelled { return }
                guard let self else { return }
                self.prefetchCache = NextTrackResolveCache.Entry(
                    videoId: successor.videoId,
                    stream: resolved,
                    timestamp: clock()
                )
            } catch {
                // Silent — `next()` falls through to live resolve. Don't
                // surface to UI per AC#5.
                return
            }
        }
    }

    /// Cancels any in-flight prefetch and clears the slot. Called whenever
    /// the queue mutates in a way that could invalidate the cached
    /// successor (`append` may be the new successor; `remove` may shift
    /// the cursor; `move` may reorder; `previous` re-positions the
    /// cursor).
    private func invalidatePrefetch() {
        prefetchTask?.cancel()
        prefetchTask = nil
        prefetchCache = nil
    }

    // MARK: YT-0298 — Mix continuation helpers

    /// `true` when a Mix continuation token is held OR a continuation fetch is in
    /// flight. Used by the autoplay-related gate at the queue tail:
    /// autoplay-related fires only when this returns `false`.
    var isMixContinuationActive: Bool {
        mixContinuationToken != nil || mixContinuationTask != nil
    }

    /// Triggers a single Mix continuation prefetch when all of the following are
    /// true at the moment of the call:
    ///   1. A continuation token is held.
    ///   2. No fetch is already in flight (`mixContinuationTask == nil`).
    ///   3. The current queue index is within `mixPrefetchThreshold` slots of the tail.
    ///
    /// On success: appends filtered + deduped items to the queue tail and updates
    /// `mixContinuationToken`. On failure: keeps the token so a transient network
    /// blip does not strand the Mix. When the server returns `nil` nextToken:
    /// clears the token so the autoplay-related fallback takes over.
    private func maybePrefetchMixContinuation() {
        guard let token = mixContinuationToken else { return }
        guard mixContinuationTask == nil else { return }
        guard let i = currentIndex else { return }
        guard queue.count - i <= Self.mixPrefetchThreshold else { return }

        mixContinuationTask = Task { @MainActor [weak self] in
            await self?.fetchMixContinuation(token: token)
        }
    }

    /// Performs the continuation HTTP call, filters/dedupes results, appends
    /// novel items to the queue tail, and refreshes `mixContinuationToken`.
    ///
    /// On cancellation: propagates cleanly (Task cooperative cancellation). The
    /// token is NOT cleared on cancellation — the `cancelMixContinuation` call
    /// site in `playNow` already zeroes both token and task.
    ///
    /// On non-cancellation error: keeps the token so the next near-tail
    /// transition can retry. On `nextToken == nil` (terminal): clears the token.
    private func fetchMixContinuation(token: String) async {
        let service = youtubeService
        let page = await service.getMixContinuation(token: token)

        // Respect structured cancellation — if playNow fired while we were awaiting,
        // the Task was already cancelled; just clean up the handle.
        if Task.isCancelled {
            mixContinuationTask = nil
            return
        }

        // Filter Shorts (< 60 s) and zero-duration entries (livestreams).
        let filtered = page.items.filter { result in
            !result.videoId.isEmpty && result.durationSec >= Self.minMixDurationSec
        }

        // Dedup against entries already in the queue — long Mix walks recycle videoIds.
        if !filtered.isEmpty {
            let existing = Set(queue.map(\.videoId))
            let novel = filtered.filter { !existing.contains($0.videoId) }
            if !novel.isEmpty {
                let newTracks = novel.map { result in
                    Track(
                        videoId: result.videoId,
                        title: result.title,
                        channel: result.channel,
                        durationSec: result.durationSec,
                        thumbnailUrl: result.thumbnailUrl
                    )
                }
                queue.append(contentsOf: newTracks)
            }
        }

        // Refresh or clear the continuation token. nil / empty → terminal.
        mixContinuationToken = page.nextToken.flatMap { $0.isEmpty ? nil : $0 }
        mixContinuationTask = nil

        // If we just appended items, check whether another prefetch should fire
        // (handles the case where the user is already near the tail of the newly
        // extended queue).
        maybePrefetchMixContinuation()
    }

    /// Cancels any in-flight Mix continuation task and clears both the task handle
    /// and the held token. Called from `playNow(_:)` so a manual track-switch starts
    /// a fresh Mix walk.
    private func cancelMixContinuation() {
        mixContinuationTask?.cancel()
        mixContinuationTask = nil
        mixContinuationToken = nil
    }

    /// Fetches and appends the initial Mix page for `seedVideoId`. Runs
    /// fire-and-forget after `playNow(_:)`.
    ///
    /// Guards:
    ///   - Only extends when the coordinator is still on the same seed track
    ///     (guards against a racing `playNow` for a different track).
    ///   - Only extends when the queue still has exactly one item (the seed).
    ///   - Captures the first continuation token into `mixContinuationToken`.
    ///   - Silently returns on HTTP failure or empty result.
    private func tryLoadMixQueue(seedVideoId: String) async {
        let page = await youtubeService.getMixQueueWithContinuation(videoId: seedVideoId)

        if Task.isCancelled { return }

        // Skip seed (index 0) — it's already at queue position 0.
        let tail = page.items.dropFirst().filter { result in
            !result.videoId.isEmpty && result.durationSec >= Self.minMixDurationSec
        }

        // Guard: only extend when the controller is still on the same seed and
        // the queue has not been user-modified.
        guard currentTrack?.videoId == seedVideoId, queue.count == 1 else { return }

        guard !tail.isEmpty else {
            // Server returned no usable tail (but may still have a token — store it
            // for continuations even on an initially-empty tail).
            mixContinuationToken = page.nextToken.flatMap { $0.isEmpty ? nil : $0 }
            return
        }

        let newTracks = tail.map { result in
            Track(
                videoId: result.videoId,
                title: result.title,
                channel: result.channel,
                durationSec: result.durationSec,
                thumbnailUrl: result.thumbnailUrl
            )
        }
        queue.append(contentsOf: newTracks)

        // Retain the first continuation token for lazy pagination.
        mixContinuationToken = page.nextToken.flatMap { $0.isEmpty ? nil : $0 }
    }
}

// MARK: - NextTrackResolveCache

/// Namespace for the prefetch cache entry type. Kept here (rather than as a
/// nested type on `PlayerCoordinator`) so tests can construct fixtures
/// without crossing the coordinator's private boundary, and so the entry's
/// shape is documented in one place.
enum NextTrackResolveCache {
    struct Entry: Sendable {
        let videoId: String
        let stream: ResolvedStream
        let timestamp: Date
    }
}
