import Testing
import Foundation
@testable import YourTube

// MARK: - AppShellLoadingAffordanceTests
//
// YT-0055 — assert the shell-level surface that drives the immediate loading
// affordance and the per-tap haptic trigger:
//
// 1. `AppShellViewModel.isLoading` mirrors `PlayerCoordinator.state == .loading`
//    synchronously after `play(_:)` and flips back to `false` once the resolve
//    completes. This is the hook every UI consumer (`MiniPlayer`,
//    `NowPlayingView`) reads to render the loading variant.
// 2. `AppShellViewModel.trackTapHapticTrigger` is monotonically incremented
//    on every user-initiated `play(_:)` so SwiftUI's
//    `.sensoryFeedback(_:trigger:)` modifier — attached once at shell scope
//    in `ContentView` — fires exactly one light haptic per track tap without
//    allocating a fresh `UIImpactFeedbackGenerator` per call site.
//
// Snapshot infra is intentionally absent (the project has no snapshot
// dependency); a state-driven view-model assertion is the contracted path
// per the YT-0055 task note ("If snapshot infra doesn't exist, a state-
// driven view-model assertion is acceptable").

@Suite("AppShellViewModel – loading affordance + tap haptic (YT-0055)")
@MainActor
struct AppShellLoadingAffordanceTests {

    private let track = Track(
        videoId: "yt0055",
        title: "Some Track",
        channel: "Some Channel",
        durationSec: 180,
        thumbnailUrl: ""
    )

    /// Build a shell wired to the in-memory fakes so the coordinator's
    /// state machine is fully observable from this test target.
    private func makeShell() -> (shell: AppShellViewModel, engine: FakeAudioEngine, service: RecordingYouTubeService) {
        let engine = FakeAudioEngine()
        let service = RecordingYouTubeService()
        let coordinator = PlayerCoordinator(
            audioEngine: engine,
            youtubeService: service,
            qualityProvider: { .medium }
        )
        return (AppShellViewModel(player: coordinator), engine, service)
    }

    /// Yield enough times for the coordinator's `resolveTask` to advance from
    /// `.loading` to `.playing` (or `.error`). Mirrors the helper in
    /// `PlayerCoordinatorTests`.
    private func flushPlayerTasks() async {
        for _ in 0..<5 { await Task.yield() }
    }

    // MARK: isLoading proxy

    @Test("isLoading is false at rest")
    func isLoadingFalseAtRest() {
        let (shell, _, _) = makeShell()
        #expect(shell.isLoading == false)
    }

    @Test("isLoading flips to true synchronously on play(_:)")
    func isLoadingTrueImmediatelyAfterPlay() {
        let (shell, _, _) = makeShell()
        shell.play(track)
        // The loading affordance must be visible "within one frame" — the
        // shell exposes the coordinator's `.loading` state synchronously
        // before any `await` runs.
        #expect(shell.isLoading == true)
    }

    @Test("isLoading returns to false after the engine reaches .playing")
    func isLoadingFalseAfterResolve() async {
        let (shell, _, _) = makeShell()
        shell.play(track)
        #expect(shell.isLoading == true)
        await flushPlayerTasks()
        #expect(shell.isLoading == false)
        #expect(shell.isPlaying == true)
    }

    @Test("isLoading flips to false on resolve failure (error state)")
    func isLoadingFalseAfterResolveError() async {
        let (shell, _, service) = makeShell()
        service.streamResult = .failure(.noStreamFound)
        shell.play(track)
        #expect(shell.isLoading == true)
        await flushPlayerTasks()
        #expect(shell.isLoading == false)
    }

    // MARK: trackTapHapticTrigger

    @Test("trackTapHapticTrigger starts at zero")
    func tapTriggerStartsAtZero() {
        let (shell, _, _) = makeShell()
        #expect(shell.trackTapHapticTrigger == 0)
    }

    @Test("trackTapHapticTrigger increments on every play(_:) call")
    func tapTriggerIncrementsPerPlay() {
        let (shell, _, _) = makeShell()
        let before = shell.trackTapHapticTrigger
        shell.play(track)
        #expect(shell.trackTapHapticTrigger == before + 1)
        shell.play(track)
        #expect(shell.trackTapHapticTrigger == before + 2)
    }

    @Test("trackTapHapticTrigger does NOT increment on auto-advance via skipNext()")
    func tapTriggerOnlyForUserInitiatedPlay() async {
        let (shell, _, _) = makeShell()
        shell.play(track)
        await flushPlayerTasks()
        let before = shell.trackTapHapticTrigger
        shell.appendToQueue(Track(
            videoId: "yt0055-2",
            title: "Next",
            channel: "Some Channel",
            durationSec: 200,
            thumbnailUrl: ""
        ))
        shell.skipNext()
        // Auto-advance is engine-driven and must not produce a tap haptic.
        #expect(shell.trackTapHapticTrigger == before)
    }

    // MARK: tap-while-loading semantics (YT-0055 reviewer follow-up, AC#7)

    /// The MiniPlayer / NowPlayingTransportRow play-pause control stays
    /// tappable while `isLoading == true` so the user can cancel a stuck
    /// resolve. The reviewer's blocker on the original YT-0055 review was
    /// that the visible spinner contradicted the announced "Pause" label;
    /// the production fix is to override the `accessibilityLabel` to
    /// "Loading, double-tap to cancel" while loading. This test locks the
    /// behaviour the label promises: `togglePlayPause()` during `.loading`
    /// cancels the in-flight resolve and parks state at `.paused` (the
    /// existing `.loading` branch in `PlayerCoordinator.togglePlayPause`).
    @Test("togglePlayPause() during .loading cancels the resolve and lands at .paused")
    func toggleDuringLoadingCancelsResolveAndPauses() async {
        let engine = FakeAudioEngine()
        // Suspend resolution forever so the coordinator stays in `.loading`
        // long enough to assert the synchronous toggle behaviour.
        let service = LoadingNeverResolvingYouTubeService()
        let coordinator = PlayerCoordinator(
            audioEngine: engine,
            youtubeService: service,
            qualityProvider: { .medium }
        )
        let shell = AppShellViewModel(player: coordinator)

        shell.play(track)
        // Synchronous post-`play(_:)` window: resolve task is suspended,
        // state is `.loading`, the loading affordance is visible, the
        // engine has not yet been handed an item to play.
        #expect(shell.isLoading == true)
        #expect(shell.isPlaying == true) // .loading is "intends to play"
        #expect(engine.playCallCount == 0)

        shell.togglePlayPause()

        // Coordinator's `.loading` branch cancels the in-flight resolve and
        // pauses the engine. Assert against the published surface the UI
        // binds to (shell + engine), not the private `resolveTask`.
        #expect(coordinator.state == .paused)
        #expect(shell.isLoading == false)
        #expect(shell.isPlaying == false)
        #expect(engine.pauseCallCount == 1)
        // Resolution was cancelled — `play(track:url:)` must not have fired.
        #expect(engine.playCallCount == 0)

        // Yield to ensure no late `.playing` transition slips in from a
        // resumed task; the resolve task must stay cancelled.
        await flushPlayerTasks()
        #expect(coordinator.state == .paused)
    }
}

// MARK: - LoadingNeverResolvingYouTubeService

/// Test-local stream service that suspends `resolveStreamURL` forever so the
/// coordinator stays in `.loading` while the test asserts the synchronous
/// window. Matches the pattern used by `NeverResolvingYouTubeService` in
/// `PlayerCoordinatorTests` but lives here so the loading-affordance suite
/// is self-contained and doesn't reach into another test file's private
/// type.
private final class LoadingNeverResolvingYouTubeService: YouTubeServiceProtocol, @unchecked Sendable {
    func search(query: String, maxResults: Int) async throws -> [SearchResult] { [] }
    func resolveStreamURL(videoId: String, quality: AudioQuality) async throws -> ResolvedStream {
        try await Task.sleep(nanoseconds: .max)
        throw CancellationError()
    }
    // YT-0298 Mix stubs.
    func getMixQueueWithContinuation(videoId: String) async -> MixQueueResult { .empty }
    func getMixContinuation(token: String) async -> MixQueueResult { .empty }
}

// MARK: - MiniPlayer loading variant
//
// The `MiniPlayer` is value-type SwiftUI; a "renders loading variant" test
// against the rendered view tree requires snapshot infra that this project
// intentionally does not pull in. Instead, we exercise the contract from
// the perspective of the value type: the `isLoading` property is plumbed
// through and accepts both states without compile- or runtime-time error.
// This guards against accidental reordering of the parameter list and
// keeps the public surface honest.

@Suite("MiniPlayer – loading parameter (YT-0055)")
@MainActor
struct MiniPlayerLoadingParameterTests {

    private let sampleTrack = Track(
        videoId: "mp-load",
        title: "Loading Title",
        channel: "Channel",
        durationSec: 120,
        thumbnailUrl: ""
    )

    @Test("MiniPlayer accepts isLoading=true and exposes the value on the value type")
    func acceptsLoadingTrue() {
        let view = MiniPlayer(track: sampleTrack, isPlaying: true, progress: 0.0, isLoading: true)
        #expect(view.isLoading == true)
        #expect(view.isPlaying == true)
        #expect(view.track.videoId == "mp-load")
    }

    @Test("MiniPlayer accepts isLoading=false (resolved variant)")
    func acceptsLoadingFalse() {
        let view = MiniPlayer(track: sampleTrack, isPlaying: true, progress: 0.5, isLoading: false)
        #expect(view.isLoading == false)
    }

    @Test("MiniPlayer isLoading defaults to false for source-compatibility with pre-YT-0055 callers")
    func defaultsToFalse() {
        let view = MiniPlayer(track: sampleTrack, isPlaying: false, progress: 0)
        #expect(view.isLoading == false)
    }
}
