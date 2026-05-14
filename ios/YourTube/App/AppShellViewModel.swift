import SwiftUI
import SwiftData

// MARK: - AppShellViewModel

/// Owns shell-level UI state (NowPlaying sheet visibility) and forwards
/// playback to the injected ``PlayerCoordinator``. The coordinator owns queue,
/// player state, progress, and stream resolution — see YT-0024.
///
/// Read-only accessors (`currentTrack`, `isPlaying`, `progress`) proxy the
/// coordinator so existing screens (`MiniPlayer`, `NowPlayingView`) keep the
/// same shape.
@Observable
@MainActor
final class AppShellViewModel {

    // MARK: - Sub-state

    let player: PlayerCoordinator

    // MARK: - Shell UI state

    var isNowPlayingOpen: Bool = false

    // MARK: YT-0167 — MiniPlayer ↔ NowPlaying transition state

    /// Normalized progress of the MiniPlayer → NowPlaying transition (0 = MiniPlayer, 1 = NowPlaying).
    /// Driven by `DragGesture` 1:1 during a drag; animated via expand/collapse curves on release.
    /// Every per-frame value (scrim opacity, transport opacity, tab bar offset, corner radius)
    /// derives from this single source. No parallel `withAnimation` blocks.
    var nowPlayingProgress: CGFloat = 0

    /// `true` while any expand or collapse animation is in flight.
    /// Callers apply `.allowsHitTesting(!shell.isTransitioning)` on the transition surface.
    var isTransitioning: Bool = false

    /// `true` while expand is in flight — used to debounce rapid double-tap on MiniPlayer (AC6).
    var isExpandInFlight: Bool = false

    /// `true` once the MiniPlayer thumbnail has appeared on screen at least once.
    /// When `false` at the moment `openNowPlaying()` fires, `NowPlayingView` treats this
    /// as a cold-open (no shared element) and falls back to a plain fade + 4 pt upward
    /// translate over 240 ms per spec §6. Set via `MiniPlayer.onAppear`.
    var isMiniPlayerSourceRendered: Bool = false

    // MARK: - Init

    init(player: PlayerCoordinator) {
        self.player = player
    }

    /// Convenience initialiser for production composition. Builds a coordinator
    /// wired to the live audio engine and YouTube service.
    convenience init() {
        self.init(player: PlayerModule.makeCoordinator())
    }

    // MARK: - Proxied playback state

    var currentTrack: Track? { player.currentTrack }
    var isPlaying: Bool { player.isPlaying }
    var progress: Double { player.progress }
    var hasMiniPlayer: Bool { currentTrack != nil }

    // MARK: YT-0055 — perceived-latency loading affordance

    /// `true` while the coordinator is resolving the stream URL or pre-rolling
    /// the audio engine. Drives the loading affordance in `MiniPlayer` and
    /// `NowPlayingView` so the user gets immediate feedback after a track tap
    /// (the underlying ~3.5s YouTubeKit resolve is unchanged — only perception).
    var isLoading: Bool {
        if case .loading = player.state { return true }
        return false
    }

    // MARK: YT-0070 — surfaced error state

    /// The user-safe error message when the coordinator is in
    /// ``PlayerState/error(message:)``, otherwise `nil`. Drives the
    /// dismissable error banner shown above the MiniPlayer and inside the
    /// expanded NowPlayingView. Single source of truth — both surfaces
    /// derive their visibility from this property; no parallel error bool.
    var errorMessage: String? {
        if case let .error(message) = player.state { return message }
        return nil
    }

    /// `true` when the coordinator is in an error state. Convenience for
    /// SwiftUI views that only need a Bool.
    var hasError: Bool { errorMessage != nil }

    /// Monotonically increasing counter incremented every time the user
    /// initiates playback by tapping a track row anywhere in the app
    /// (Search, Library/History, etc.). Used as a `Hashable` trigger for
    /// SwiftUI's `.sensoryFeedback(_:trigger:)` so we render a single light
    /// haptic per tap without allocating a new `UIImpactFeedbackGenerator`
    /// per call site. `.sensoryFeedback` already honours the system
    /// "System Haptics" toggle and silences feedback when haptics are
    /// disabled, satisfying the YT-0055 accessibility note.
    private(set) var trackTapHapticTrigger: Int = 0

    // MARK: YT-0298 Mix continuation gate

    /// `true` when the Mix continuation is still active (token held or fetch in
    /// flight). Autoplay-related should only fire at the queue tail when this is
    /// `false`. Exposed for the autoplay path (YT-0292) to gate against.
    var isMixContinuationActive: Bool { player.isMixContinuationActive }

    // MARK: YT-0027 Now Playing surface

    /// Read-only proxies for the Now Playing screen. Avoids dragging the
    /// coordinator into the view directly while keeping the screen reactive
    /// via `@Observable`.
    var queue: [Track] { player.queue }
    var currentIndex: Int? { player.currentIndex }
    var hasNext: Bool { player.hasNext }
    var hasPrevious: Bool { player.hasPrevious }
    var shuffleEnabled: Bool { player.shuffleEnabled }
    var repeatMode: RepeatMode { player.repeatMode }
    var currentTime: TimeInterval { player.currentTime }
    var duration: TimeInterval { player.duration }

    /// Current audio quality preference for stream resolution. Kept for
    /// backwards compatibility with anything previously reading the bridge;
    /// the coordinator already pipes this through ``YouTubeServiceProtocol``.
    var audioQuality: AudioQuality {
        PlayerCoordinator.defaultQualityProvider()
    }

    // MARK: - Actions

    func play(_ track: Track) {
        // YT-0055: bump the shared haptic trigger so the SwiftUI
        // `.sensoryFeedback` modifier fires a single light impact per
        // user-initiated track tap. The increment happens *before*
        // `playNow(_:)` so the haptic is perceptually simultaneous with
        // the loading affordance flipping on (coordinator state goes
        // synchronously to `.loading` inside `playNow`).
        trackTapHapticTrigger &+= 1
        // Ad-hoc perf instrumentation — measures tap → first audio. Reset
        // the baseline BEFORE `playNow(_:)` so the synchronous hop into
        // `.loading` and any downstream EXTRACT_START / PREPARE marks read
        // a non-stale tap timestamp. See `PlaybackPerfTracer`.
        PlaybackPerfTracer.shared.markTap(videoId: track.videoId, source: "trackTap")
        player.playNow(track)
    }

    /// Append `track` to the playback queue without disturbing the currently
    /// playing item. If nothing is loaded yet, the coordinator promotes the
    /// appended track to current and begins loading it.
    func appendToQueue(_ track: Track) {
        player.append(track)
    }

    func togglePlayPause() {
        // Ad-hoc perf instrumentation — only mark a TAP when this transition
        // actually starts NEW playback (paused → playing or resuming a track
        // from idle). A pause has no "first audio" downstream so re-baselining
        // would just produce noise in the trace.
        if let track = player.currentTrack, !player.isPlaying {
            PlaybackPerfTracer.shared.markTap(videoId: track.videoId, source: "miniPlayerPlayPause")
        }
        player.togglePlayPause()
    }

    func skipNext() {
        // Ad-hoc perf instrumentation — the tap origin for next-track. The
        // upcoming track is `queue[currentIndex + 1]` once the coordinator
        // advances, but we baseline against that videoId BEFORE the move so
        // EXTRACT_START / PREPARE can correlate against the same id.
        if player.hasNext, let i = player.currentIndex,
           player.queue.indices.contains(i + 1) {
            PlaybackPerfTracer.shared.markTap(videoId: player.queue[i + 1].videoId, source: "skipNext")
        }
        player.next()
    }

    func skipPrevious() {
        // Ad-hoc perf instrumentation — symmetric with `skipNext` above.
        if player.hasPrevious, let i = player.currentIndex,
           player.queue.indices.contains(i - 1) {
            PlaybackPerfTracer.shared.markTap(videoId: player.queue[i - 1].videoId, source: "skipPrevious")
        }
        player.previous()
    }

    /// Called from `MiniPlayer.onAppear` once the thumbnail has rendered.
    /// After this call, `openNowPlaying()` will use the full `matchedGeometryEffect`
    /// hero transition; before it, NowPlayingView uses the cold-open fallback
    /// (fade + 4 pt upward translate, spec §6).
    func markMiniPlayerRendered() {
        isMiniPlayerSourceRendered = true
    }

    func openNowPlaying() {
        guard currentTrack != nil, !isExpandInFlight else { return }
        isExpandInFlight = true
        isTransitioning = true
        isNowPlayingOpen = true
    }

    func closeNowPlaying() {
        isNowPlayingOpen = false
        isExpandInFlight = false
    }

    /// Called by NowPlayingView after the expand animation settles.
    /// Clears the transition guard and the in-flight debounce flag.
    func didFinishExpand() {
        isTransitioning = false
        isExpandInFlight = false
    }

    /// Called by NowPlayingView after the collapse animation settles.
    func didFinishCollapse() {
        isTransitioning = false
        nowPlayingProgress = 0
    }

    /// Called during drag-to-dismiss to update the shared progress (0…1).
    /// No animation — 1:1 finger follow per spec §3.
    func setDragProgress(_ progress: CGFloat) {
        nowPlayingProgress = max(0, min(1, progress))
    }

    func seek(to time: TimeInterval) {
        player.seek(to: time)
    }

    // MARK: YT-0070 — error banner actions

    /// Dismiss the active playback error. Returns the coordinator to
    /// `.paused` (when a track is loaded) or `.idle`. No-op outside an
    /// error state.
    func dismissError() {
        player.dismissError()
    }

    /// Re-attempt the most recently selected track. No-op when the queue
    /// is empty.
    func retryPlayback() {
        player.retryLastAttempt()
    }

    func toggleShuffle() {
        player.toggleShuffle()
    }

    func cycleRepeat() {
        player.cycleRepeat()
    }

    func remove(at index: Int) {
        player.remove(at: index)
    }

    func move(from: Int, to: Int) {
        player.move(from: from, to: to)
    }

    /// YT-0308 — tap-to-jump within the existing queue. Routes to
    /// ``PlayerCoordinator/jumpToQueueItem(at:)`` so Mix state and queue
    /// contents are preserved across the tap. Tapping the currently-playing
    /// entry is a silent no-op (handled by the coordinator).
    func jumpToQueueItem(at index: Int) {
        // YT-0055: bump the shared haptic trigger so the user feels the
        // same single light impact as a Search/Library track tap. The
        // coordinator's no-op guard runs after, so a tap on the current
        // entry still fires the haptic — same behaviour as Search would
        // exhibit on a repeat tap.
        if index != player.currentIndex,
           player.queue.indices.contains(index) {
            trackTapHapticTrigger &+= 1
            PlaybackPerfTracer.shared.markTap(
                videoId: player.queue[index].videoId,
                source: "queueJump"
            )
        }
        player.jumpToQueueItem(at: index)
    }

    // MARK: - History wiring

    /// Attach a SwiftData-backed history recorder to the player coordinator.
    /// Called from the shell once the `\.modelContext` environment is
    /// available. Each track that the engine accepts is appended to
    /// ``HistoryEntryEntity`` via ``HistoryStore``.
    func attachHistory(context: ModelContext) {
        let store = HistoryStore(context: context)
        player.setHistoryRecorder { track in
            // Per the iOS rules, history append is a critical write so we
            // forward it to the store (which calls `context.save()`). Failures
            // are swallowed here because a missed history row should never
            // break playback.
            try? store.append(track)
        }
    }
}
