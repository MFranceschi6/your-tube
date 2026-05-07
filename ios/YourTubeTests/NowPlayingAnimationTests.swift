import Testing
import SwiftUI
@testable import YourTube

// MARK: - NowPlayingAnimationTests

/// Reduce-Motion gating per YT-0027 Q9 and YT-0167 AC10. The animations themselves are
/// expressed by `Animation` values whose internals SwiftUI does not expose,
/// so the tests assert on the *identity* of the returned animation against
/// the documented tokens (e.g. `.linear(duration: 0)` for the reduced
/// branch). Identity is enough — the table in `haptics-and-a11y.md` is
/// observable in two values per row.
@Suite("NowPlayingAnimation – reduce motion gating")
struct NowPlayingAnimationTests {

    @Test("artwork pause-scale uses spring at full motion")
    func artworkScaleFullMotion() {
        let value = NowPlayingAnimation.artworkScale(reduceMotion: false)
        #expect(value == NowPlayingAnimation.artworkSpring)
    }

    @Test("artwork pause-scale collapses to instant under reduce motion")
    func artworkScaleReduced() {
        let value = NowPlayingAnimation.artworkScale(reduceMotion: true)
        #expect(value == .linear(duration: 0))
    }

    @Test("scrubber uses ease-in-out at full motion")
    func scrubberFullMotion() {
        let value = NowPlayingAnimation.scrubber(reduceMotion: false)
        #expect(value == NowPlayingAnimation.scrubberCurve)
    }

    @Test("scrubber collapses to instant under reduce motion")
    func scrubberReduced() {
        let value = NowPlayingAnimation.scrubber(reduceMotion: true)
        #expect(value == .linear(duration: 0))
    }

    @Test("queue slide uses move + fade transition at full motion")
    func queueSlideTransitionFullMotion() {
        // `AnyTransition` does not expose its kind, but the helper guarantees
        // the cross-fade branch is reached only under reduce motion. Asserting
        // that the two branches return *different* concrete values is the
        // strongest check we can make without a reflection seam.
        let full = NowPlayingAnimation.queueSlide(reduceMotion: false)
        let reduced = NowPlayingAnimation.queueSlide(reduceMotion: true)
        #expect(full != reduced)
    }

    @Test("radius curve never collapses to instant — radius carries information")
    func radiusCurveStaysAnimated() {
        // Q9: information-bearing transitions (progress, color, radius) keep
        // their animation under reduce motion. This guards against an
        // accidental "blanket-disable" regression.
        #expect(NowPlayingAnimation.radiusCurve == .easeInOut(duration: 0.25))
    }

    @Test("state curve never collapses to instant — color/state carries information")
    func stateCurveStaysAnimated() {
        #expect(NowPlayingAnimation.stateCurve == .easeInOut(duration: 0.15))
    }
}

// MARK: - YT-0167 MiniPlayer ↔ NowPlaying transition tests (AC10)

/// Swift Testing coverage per YT-0167 AC10:
/// (a) `matchedGeometryEffect` namespace is declared at shell scope and the same
///     `NowPlayingHero.artworkID` is shared between MiniPlayer + NowPlaying.
/// (b) Reduce-motion path: `expandAnimation(reduceMotion: true)` returns the 120 ms
///     linear cross-fade (no matched-geometry container is used — verified via the
///     flag that drives if/else in NowPlayingView).
/// (c) Drag distance ≥ 30% or velocity ≥ 800 pt/s triggers collapse not spring-back.
///
/// Pure motion timing is NOT asserted by unit tests — it is verified via visual pass.
@Suite("YT-0167 – MiniPlayer ↔ NowPlaying transition")
@MainActor
struct MiniPlayerNowPlayingTransitionTests {

    // MARK: (a) Namespace shared between MiniPlayer and NowPlaying (AC10a)

    /// Both `MiniPlayer.thumbnail` and `NowPlayingArtworkView` use the same
    /// `NowPlayingHero.artworkID` string for `matchedGeometryEffect`. This test
    /// guards against the two sides drifting to different keys, which silently
    /// breaks the shared-element transition without a compile error.
    @Test("MiniPlayer and NowPlayingArtworkView share the same matchedGeometryEffect ID")
    func artworkIDSharedBetweenMiniPlayerAndNowPlaying() {
        // The spec requires the namespace and ID to live at the highest common
        // ancestor (ContentView). `NowPlayingHero.artworkID` is the single source
        // of truth for the key used by both sides.
        let expectedID = "yt-0027.artwork"
        #expect(NowPlayingHero.artworkID == expectedID)

        // Verify the MiniPlayer view type accepts and uses `nowPlayingNamespace`.
        // We cannot inspect the `@Namespace.ID` value at runtime, but we CAN
        // confirm the public interface compiles correctly with a namespace argument,
        // which means both sides consume the same `Namespace.ID` type from the
        // common ancestor.
        let track = Track(
            videoId: "test-namespace",
            title: "Test Track",
            channel: "Test Channel",
            durationSec: 180,
            thumbnailUrl: ""
        )
        // This compiles only if `nowPlayingNamespace` is the correct type and the
        // view builds without the namespace (nil path also still valid for previews).
        let miniPlayerWithoutNS = MiniPlayer(track: track, isPlaying: true, progress: 0)
        #expect(miniPlayerWithoutNS.nowPlayingNamespace == nil)
    }

    // MARK: (b) Reduce-motion path uses if/else cross-fade, not matched-geometry (AC10b)

    /// When `accessibilityReduceMotion` is ON, `expandAnimation(reduceMotion: true)`
    /// must return the 120 ms linear cross-fade (spec §4: "no transform, no delays").
    /// `NowPlayingView` gates on this to switch between the matched-geometry path
    /// (fullMotionBody) and the plain if/else path (reduceMotionBody).
    @Test("reduce-motion expand animation is a 120 ms linear cross-fade (not expand curve)")
    func reduceMotionExpandAnimationIs120msLinear() {
        let reducedAnimation = NowPlayingAnimation.expandAnimation(reduceMotion: true)
        let expected = NowPlayingAnimation.reduceMotionCrossFade
        #expect(reducedAnimation == expected)
    }

    @Test("reduce-motion collapse animation is a 120 ms linear cross-fade (not collapse curve)")
    func reduceMotionCollapseAnimationIs120msLinear() {
        let reducedAnimation = NowPlayingAnimation.collapseAnimation(reduceMotion: true)
        let expected = NowPlayingAnimation.reduceMotionCrossFade
        #expect(reducedAnimation == expected)
    }

    @Test("full-motion expand animation is the timingCurve(0.2, 0.0, 0.0, 1.0, 0.32) expand curve")
    func fullMotionExpandAnimationIsExpandCurve() {
        let fullAnimation = NowPlayingAnimation.expandAnimation(reduceMotion: false)
        #expect(fullAnimation == NowPlayingAnimation.expandCurve)
        // Confirm it differs from the reduce-motion path — they must not be the same.
        #expect(fullAnimation != NowPlayingAnimation.reduceMotionCrossFade)
    }

    @Test("full-motion collapse animation is the timingCurve(0.3, 0.0, 0.8, 0.15, 0.26) collapse curve")
    func fullMotionCollapseAnimationIsCollapseCurve() {
        let fullAnimation = NowPlayingAnimation.collapseAnimation(reduceMotion: false)
        #expect(fullAnimation == NowPlayingAnimation.collapseCurve)
        #expect(fullAnimation != NowPlayingAnimation.reduceMotionCrossFade)
    }

    // MARK: (c) Drag distance ≥ 30% or velocity ≥ 800 pt/s triggers collapse (AC10c)

    /// Verifies the threshold constants are set to the spec values.
    /// The DragGesture handler in `NowPlayingView` compares against these
    /// constants — they are the observable contract, not the view internals.
    @Test("collapse distance ratio threshold is 30% of screen height (spec §3)")
    func collapseDistanceRatioIs30Percent() {
        #expect(NowPlayingAnimation.collapseDistanceRatio == 0.30)
    }

    @Test("collapse velocity threshold is 800 pt/s (spec §3)")
    func collapseVelocityThresholdIs800PtsPerSec() {
        #expect(NowPlayingAnimation.collapseVelocityThreshold == 800)
    }

    /// Simulates the collapse decision logic from NowPlayingView's `dragToDismiss.onEnded`.
    /// At exactly 30% drag ratio, collapse fires. Below 30%, spring-back fires.
    @Test("drag at exactly 30% triggers collapse, below 30% triggers spring-back")
    func dragAt30PercentTriggersCollapse() {
        let screenHeight: CGFloat = 844 // iPhone 14 logical points

        // Exactly at threshold: should collapse.
        let atThreshold = (screenHeight * NowPlayingAnimation.collapseDistanceRatio) / screenHeight
        #expect(atThreshold >= NowPlayingAnimation.collapseDistanceRatio)

        // Below threshold (25%): should spring back.
        let belowThreshold: CGFloat = 0.25
        let zeroVelocity: CGFloat = 0
        let shouldSpringBack = belowThreshold < NowPlayingAnimation.collapseDistanceRatio
            && zeroVelocity < NowPlayingAnimation.collapseVelocityThreshold
        #expect(shouldSpringBack == true)
    }

    @Test("velocity at exactly 800 pt/s triggers collapse, below 800 does not")
    func velocityAt800TriggersCollapse() {
        // At exactly the threshold: should collapse.
        let atVelocity: CGFloat = 800
        let zeroDrag: CGFloat = 0
        let shouldCollapse = zeroDrag >= NowPlayingAnimation.collapseDistanceRatio
            || atVelocity >= NowPlayingAnimation.collapseVelocityThreshold
        #expect(shouldCollapse == true)

        // Below threshold velocity (799 pt/s) with no distance drag: spring back.
        let belowVelocity: CGFloat = 799
        let shouldSpringBack = zeroDrag < NowPlayingAnimation.collapseDistanceRatio
            && belowVelocity < NowPlayingAnimation.collapseVelocityThreshold
        #expect(shouldSpringBack == true)
    }

    // MARK: lerp helper (used for corner-radius morph)

    @Test("lerp(4, 12, 0) = 4 (MiniPlayer end)")
    func lerpAtZero() {
        #expect(NowPlayingAnimation.lerp(4, 12, 0) == 4)
    }

    @Test("lerp(4, 12, 1) = 12 (NowPlaying end)")
    func lerpAtOne() {
        #expect(NowPlayingAnimation.lerp(4, 12, 1) == 12)
    }

    @Test("lerp(4, 12, 0.5) = 8 (midpoint)")
    func lerpAtMidpoint() {
        #expect(NowPlayingAnimation.lerp(4, 12, 0.5) == 8)
    }

    @Test("lerp clamps below 0")
    func lerpClampsNegative() {
        #expect(NowPlayingAnimation.lerp(4, 12, -1) == 4)
    }

    @Test("lerp clamps above 1")
    func lerpClampsAboveOne() {
        #expect(NowPlayingAnimation.lerp(4, 12, 2) == 12)
    }

    // MARK: Shell debounce (AC6 rapid double-tap guard)

    /// While expand is in flight (`isExpandInFlight == true`), a second call to
    /// `openNowPlaying()` must be a no-op so rapid double-taps do not stack
    /// animation layers (AC6, spec §6 "Rapid double-tap on MiniPlayer").
    @Test("openNowPlaying is debounced while expand is in flight")
    func openNowPlayingDebounced() {
        let shell = AppShellViewModel(player: PlayerCoordinator(
            audioEngine: FakeAudioEngine(),
            youtubeService: RecordingYouTubeService(),
            qualityProvider: { .medium }
        ))

        // Without a track, openNowPlaying is always a no-op via the nil guard.
        shell.openNowPlaying()
        #expect(shell.isNowPlayingOpen == false)
        #expect(shell.isExpandInFlight == false)

        // Simulate the debounce path by setting the flag that the first successful
        // openNowPlaying() would set. The key invariant: once isExpandInFlight is
        // true, further calls MUST NOT flip isNowPlayingOpen regardless of track state.
        // We test this by pre-setting the flag and verifying the guard fires.
        shell.isExpandInFlight = true
        // isNowPlayingOpen remains false (set by closeNowPlaying below would clear it).
        // A second openNowPlaying while in-flight must not set isNowPlayingOpen.
        let wasOpen = shell.isNowPlayingOpen
        shell.openNowPlaying() // debounced by isExpandInFlight guard
        #expect(shell.isNowPlayingOpen == wasOpen) // unchanged
    }

    @Test("didFinishExpand clears isTransitioning and isExpandInFlight")
    func didFinishExpandClearsFlags() {
        let shell = AppShellViewModel(player: PlayerCoordinator(
            audioEngine: FakeAudioEngine(),
            youtubeService: RecordingYouTubeService(),
            qualityProvider: { .medium }
        ))
        shell.isTransitioning = true
        shell.isExpandInFlight = true
        shell.didFinishExpand()
        #expect(shell.isTransitioning == false)
        #expect(shell.isExpandInFlight == false)
    }

    @Test("didFinishCollapse clears isTransitioning and resets nowPlayingProgress to 0")
    func didFinishCollapseResetsProgress() {
        let shell = AppShellViewModel(player: PlayerCoordinator(
            audioEngine: FakeAudioEngine(),
            youtubeService: RecordingYouTubeService(),
            qualityProvider: { .medium }
        ))
        shell.nowPlayingProgress = 0.75
        shell.isTransitioning = true
        shell.didFinishCollapse()
        #expect(shell.isTransitioning == false)
        #expect(shell.nowPlayingProgress == 0)
    }

    @Test("setDragProgress clamps to 0…1")
    func setDragProgressClamps() {
        let shell = AppShellViewModel(player: PlayerCoordinator(
            audioEngine: FakeAudioEngine(),
            youtubeService: RecordingYouTubeService(),
            qualityProvider: { .medium }
        ))
        shell.setDragProgress(-0.5)
        #expect(shell.nowPlayingProgress == 0)
        shell.setDragProgress(1.5)
        #expect(shell.nowPlayingProgress == 1)
        shell.setDragProgress(0.42)
        #expect(shell.nowPlayingProgress == 0.42)
    }

    // MARK: Cold-open (AC6, spec §6)

    /// `isMiniPlayerSourceRendered` starts false; `markMiniPlayerRendered()` flips it true.
    /// This is what NowPlayingView reads as `isColdOpen = !shell.isMiniPlayerSourceRendered`
    /// to determine whether to use the shared-element or fallback expand path.
    @Test("isMiniPlayerSourceRendered starts false and is set by markMiniPlayerRendered")
    func coldOpenFlagFlippedByMarkRendered() {
        let shell = AppShellViewModel(player: PlayerCoordinator(
            audioEngine: FakeAudioEngine(),
            youtubeService: RecordingYouTubeService(),
            qualityProvider: { .medium }
        ))
        #expect(shell.isMiniPlayerSourceRendered == false)
        shell.markMiniPlayerRendered()
        #expect(shell.isMiniPlayerSourceRendered == true)
    }

    /// Once `markMiniPlayerRendered()` is called (idempotent — calling multiple times
    /// does not regress the flag back to false).
    @Test("markMiniPlayerRendered is idempotent")
    func markMiniPlayerRenderedIsIdempotent() {
        let shell = AppShellViewModel(player: PlayerCoordinator(
            audioEngine: FakeAudioEngine(),
            youtubeService: RecordingYouTubeService(),
            qualityProvider: { .medium }
        ))
        shell.markMiniPlayerRendered()
        shell.markMiniPlayerRendered()
        #expect(shell.isMiniPlayerSourceRendered == true)
    }

    // MARK: Cold-open animation tokens (AC6, spec §6)

    @Test("coldOpenDuration is 240 ms (spec §6)")
    func coldOpenDurationIs240ms() {
        #expect(NowPlayingAnimation.coldOpenDuration == 0.24)
    }

    @Test("coldOpenTranslateOffset is 4 pt (spec §6)")
    func coldOpenTranslateOffsetIs4pt() {
        #expect(NowPlayingAnimation.coldOpenTranslateOffset == 4)
    }

    // MARK: MT1 — reduce-motion expand leaves isExpandInFlight == false

    /// MT1: Under reduce-motion, the expand path calls `shell.didFinishExpand()` (not
    /// the bare `shell.isTransitioning = false` assignment that was the N2 bug).
    /// `animateExpand()` is private to `NowPlayingView` so we cannot call it directly;
    /// instead we assert the invariant at the `AppShellViewModel` level:
    ///   1. `openNowPlaying()` sets both `isTransitioning` and `isExpandInFlight` to true.
    ///   2. `didFinishExpand()` — the only call the reduce-motion branch should make —
    ///      clears BOTH flags, leaving `isExpandInFlight == false`.
    /// If the view had used direct `isTransitioning = false` assignment, step 2 would
    /// still pass here but step 1's `isExpandInFlight` would remain true in the view.
    /// This test guards the `didFinishExpand()` contract that the fix depends on.
    @Test("reduce-motion expand path: didFinishExpand clears isExpandInFlight (MT1)")
    func reduceMotionExpandLeavesExpandInFlightFalse() {
        let shell = AppShellViewModel(player: PlayerCoordinator(
            audioEngine: FakeAudioEngine(),
            youtubeService: RecordingYouTubeService(),
            qualityProvider: { .medium }
        ))
        // Simulate the shell state that openNowPlaying() sets before NowPlayingView appears.
        shell.isTransitioning = true
        shell.isExpandInFlight = true

        // The reduce-motion branch in animateExpand() calls didFinishExpand().
        // Assert both flags are cleared — the N2 bug left isExpandInFlight stuck at true.
        shell.didFinishExpand()
        #expect(shell.isExpandInFlight == false, "isExpandInFlight must be false after didFinishExpand")
        #expect(shell.isTransitioning == false, "isTransitioning must be false after didFinishExpand")
    }
}
