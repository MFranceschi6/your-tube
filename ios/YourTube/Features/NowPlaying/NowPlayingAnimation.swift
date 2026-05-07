import SwiftUI

// MARK: - NowPlayingAnimation

/// Centralised animation tokens for the YT-0027 Now Playing screen and the
/// YT-0167 MiniPlayer ↔ NowPlaying motion spec.
///
/// Per `design-system/handoff/YT-0027/haptics-and-a11y.md` and
/// `design-system/handoff/YT-0074/motion-spec.md` Reduce-Motion contracts,
/// several animations must "degrade to instant or cross-fade" when
/// `accessibilityReduceMotion` is on while progress, color, and
/// drag-to-dismiss continue to animate normally.
///
/// Each helper accepts the current Reduce-Motion flag so the gating logic
/// is unit-testable in isolation. Returning `nil` collapses the animation
/// (used by callers that want `.animation(curve, value:)` to be a no-op).
enum NowPlayingAnimation {

    // MARK: Tokens (YT-0027)

    /// Spring used by artwork pause-scale on resume/pause. Locked by Q2 of
    /// the handoff (`response: 0.45`, `dampingFraction: 0.78`).
    static let artworkSpring: Animation = .spring(response: 0.45, dampingFraction: 0.78)

    /// Curve for the scrubber track expand and thumb grow on drag.
    /// Q4 spec: `.easeInOut(0.18)` at full motion, instant when reduce-motion is on.
    static let scrubberCurve: Animation = .easeInOut(duration: 0.18)

    /// Curve for the artwork radius transition (information change, kept
    /// even under Reduce Motion per Q9).
    static let radiusCurve: Animation = .easeInOut(duration: 0.25)

    /// Curve for color/state changes (icon active/inactive). Stays under
    /// Reduce Motion per Q9.
    static let stateCurve: Animation = .easeInOut(duration: 0.15)

    // MARK: YT-0167 MiniPlayer ↔ NowPlaying transition tokens

    /// Expand artwork morph curve: `cubic-bezier(0.2, 0.0, 0.0, 1.0)` over 320 ms.
    /// `motion.easing.standard` — Material "emphasized-decelerate".
    /// Source of truth: `design-system/handoff/YT-0074/motion-spec.md` §1.
    static let expandCurve: Animation = .timingCurve(0.2, 0.0, 0.0, 1.0, duration: 0.32)

    /// Collapse artwork morph curve: `cubic-bezier(0.3, 0.0, 0.8, 0.15)` over 260 ms.
    /// `motion.easing.exit` — accelerate easing for artwork-only on collapse.
    /// Source of truth: `design-system/handoff/YT-0074/motion-spec.md` §1.
    static let collapseCurve: Animation = .timingCurve(0.3, 0.0, 0.8, 0.15, duration: 0.26)

    /// Reduce-motion cross-fade: 120 ms linear, both directions.
    /// Source of truth: `design-system/handoff/YT-0074/motion-spec.md` §4.
    static let reduceMotionCrossFade: Animation = .linear(duration: 0.12)

    /// Spring-back on drag release when below threshold (< 30% height, < 800 pt/s).
    /// 200 ms, `.spring(response: 0.2, dampingFraction: 0.85)`.
    static let dragSpringBack: Animation = .spring(response: 0.2, dampingFraction: 0.85)

    /// Transport controls delayed entry transition (expand direction).
    /// Fades in at 200–320 ms delay with an 8 pt upward translate.
    /// Source of truth: `design-system/handoff/YT-0074/motion-spec.md` §2.
    static let transportEntryTransition: AnyTransition = .opacity
        .animation(.easeOut(duration: 0.12).delay(0.20))
        .combined(with: .offset(y: 8))

    /// Transport controls exit transition (collapse direction, 0–120 ms).
    static let transportExitTransition: AnyTransition = .opacity
        .animation(.easeIn(duration: 0.12))

    /// Scrim / blur settle duration. Blur layer fades in 0–200 ms on expand;
    /// replaced by solid after settle at 320 ms.
    static let blurFadeDuration: Double = 0.20

    /// Collapse threshold: drag must be ≥ 30% of screen height to auto-collapse.
    static let collapseDistanceRatio: CGFloat = 0.30

    /// Collapse threshold: predicted velocity ≥ 800 pt/s downward auto-collapses.
    static let collapseVelocityThreshold: CGFloat = 800

    /// Cold-open duration (spec §6): fade + 4 pt upward translate when no shared element.
    /// Uses `motion.easing.standard` over 240 ms — same easing as scrim cross-fade.
    static let coldOpenDuration: Double = 0.24

    /// Cold-open upward translate offset in points (spec §6).
    static let coldOpenTranslateOffset: CGFloat = 4

    // MARK: Reduce-motion gates (YT-0027)

    /// Animation for artwork pause-scale. Becomes an instant change when
    /// `reduceMotion` is on.
    static func artworkScale(reduceMotion: Bool) -> Animation {
        reduceMotion ? .linear(duration: 0) : artworkSpring
    }

    /// Animation for the scrubber thumb grow / track expand on drag.
    /// Becomes an instant change when `reduceMotion` is on.
    static func scrubber(reduceMotion: Bool) -> Animation {
        reduceMotion ? .linear(duration: 0) : scrubberCurve
    }

    /// Animation for the Up Next preview appearance / queue slide. Cross-fade
    /// when Reduce Motion is on.
    static func queueSlide(reduceMotion: Bool) -> Animation {
        reduceMotion ? .easeInOut(duration: 0.2) : .spring(response: 0.4, dampingFraction: 0.85)
    }

    /// Transition variant for the queue / Up Next view. Cross-fade under
    /// Reduce Motion, slide from bottom otherwise.
    static func queueTransition(reduceMotion: Bool) -> AnyTransition {
        reduceMotion
            ? .opacity
            : .move(edge: .bottom).combined(with: .opacity)
    }

    // MARK: YT-0167 helpers

    /// Linearly interpolates from `a` to `b` by `t` (0…1).
    /// Used for corner-radius morph: `lerp(4, 12, progress)`.
    static func lerp(_ a: CGFloat, _ b: CGFloat, _ t: CGFloat) -> CGFloat {
        a + (b - a) * max(0, min(1, t))
    }

    /// The expand animation to apply against `isExpanded` on the matched-geometry
    /// container. Returns the reduce-motion cross-fade when `reduceMotion` is on.
    static func expandAnimation(reduceMotion: Bool) -> Animation {
        reduceMotion ? reduceMotionCrossFade : expandCurve
    }

    /// The collapse animation to apply when dismissing. Returns the reduce-motion
    /// cross-fade when `reduceMotion` is on.
    static func collapseAnimation(reduceMotion: Bool) -> Animation {
        reduceMotion ? reduceMotionCrossFade : collapseCurve
    }
}
