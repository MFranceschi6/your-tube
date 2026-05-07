import SwiftUI

// MARK: - NowPlayingTransportRow

/// Free-floating five-icon transport row per YT-0027 Q5. **No capsule**,
/// **no glass**, **no equal-size buttons** — the play-pause button is the
/// centre of gravity through size + weight contrast (72pt filled white
/// circle), flanked by 26pt skip glyphs and 20pt shuffle/repeat glyphs.
///
/// Hit areas are decoupled from glyph size via `.contentShape(Rectangle())`
/// so the 20pt shuffle/repeat glyphs still get a 44pt tap target and the
/// 26pt skip glyphs get 56pt.
struct NowPlayingTransportRow: View {

    let isPlaying: Bool
    /// YT-0055: drives the loading affordance inside the centre play button.
    /// While `true`, the play/pause glyph is replaced with a circular
    /// `ProgressView`. The button still fires `onTogglePlayPause`, which
    /// the coordinator handles via the existing `.loading` branch (cancels
    /// the in-flight resolve, parks state in `.paused`).
    var isLoading: Bool = false
    let shuffleEnabled: Bool
    let repeatMode: RepeatMode
    let canSkipPrevious: Bool
    let canSkipNext: Bool

    let onShuffle: () -> Void
    let onSkipPrevious: () -> Void
    let onTogglePlayPause: () -> Void
    let onSkipNext: () -> Void
    let onRepeat: () -> Void

    /// Increment-only counters drive the skip haptics so .sensoryFeedback
    /// fires once per tap regardless of repeated taps in the same direction.
    @State private var skipFwdCount: Int = 0
    @State private var skipBackCount: Int = 0

    var body: some View {
        row
            .modifier(TransportHaptics(
                isPlaying: isPlaying,
                shuffleEnabled: shuffleEnabled,
                repeatMode: repeatMode,
                skipFwdCount: skipFwdCount,
                skipBackCount: skipBackCount
            ))
    }

    private var row: some View {
        HStack(spacing: 0) {
            shuffleButton
            Spacer(minLength: 0)
            skipBackwardButton
            Spacer(minLength: 0)
            playPauseButton
            Spacer(minLength: 0)
            skipForwardButton
            Spacer(minLength: 0)
            repeatButton
        }
        .padding(.horizontal, 8)
    }

    // MARK: Shuffle / Repeat (20pt glyphs, 44pt hit)

    private var shuffleButton: some View {
        Button(action: onShuffle) {
            Image(systemName: "shuffle")
                .font(.system(size: 20, weight: .semibold))
                .foregroundStyle(shuffleEnabled ? Color.accentColor : Color.white.opacity(0.45))
                .frame(width: Tokens.HitTarget.minimum, height: Tokens.HitTarget.minimum)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Shuffle, \(shuffleEnabled ? "on" : "off")")
        .accessibilityHint("Double-tap to toggle")
    }

    private var repeatButton: some View {
        Button(action: onRepeat) {
            Image(systemName: repeatGlyph)
                .font(.system(size: 20, weight: .semibold))
                .foregroundStyle(repeatMode == .off ? Color.white.opacity(0.45) : Color.accentColor)
                .frame(width: Tokens.HitTarget.minimum, height: Tokens.HitTarget.minimum)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Repeat \(repeatMode.label)")
    }

    private var repeatGlyph: String {
        switch repeatMode {
        case .off, .all: return "repeat"
        case .one: return "repeat.1"
        }
    }

    // MARK: Skip (26pt glyphs, 56pt hit)

    private var skipBackwardButton: some View {
        Button {
            skipBackCount &+= 1
            onSkipPrevious()
        } label: {
            Image(systemName: "backward.fill")
                .font(.system(size: 26, weight: .semibold))
                .foregroundStyle(canSkipPrevious ? Color.white : Color.white.opacity(0.35))
                .frame(width: 56, height: 56)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!canSkipPrevious)
        .accessibilityLabel("Previous track or restart")
    }

    private var skipForwardButton: some View {
        Button {
            skipFwdCount &+= 1
            onSkipNext()
        } label: {
            Image(systemName: "forward.fill")
                .font(.system(size: 26, weight: .semibold))
                .foregroundStyle(canSkipNext ? Color.white : Color.white.opacity(0.35))
                .frame(width: 56, height: 56)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!canSkipNext)
        .accessibilityLabel("Next track")
    }

    // MARK: Play / Pause (72pt filled circle)

    private var playPauseButton: some View {
        Button(action: onTogglePlayPause) {
            ZStack {
                Circle()
                    .fill(Color.white)
                    .frame(width: 72, height: 72)
                if isLoading {
                    // YT-0055: ProgressView replaces the glyph during the
                    // resolve window. Decorative — `accessibilityHidden(true)`
                    // hides the spinner from VoiceOver; the button's
                    // accessibility label below reflects the loading state.
                    ProgressView()
                        .progressViewStyle(.circular)
                        .controlSize(.regular)
                        .tint(Color.black)
                        .accessibilityHidden(true)
                } else {
                    Image(systemName: isPlaying ? "pause.fill" : "play.fill")
                        .font(.system(size: 30, weight: .bold))
                        .foregroundStyle(Color.black)
                        .offset(x: isPlaying ? 0 : 2) // optical centring of play.fill
                }
            }
            .contentShape(Circle())
        }
        .buttonStyle(.plain)
        // YT-0055 reviewer follow-up (AC#7): while loading, override the
        // accessibility label so VoiceOver matches the visible spinner
        // instead of announcing "Pause". Tap is preserved because the
        // coordinator's `.loading` branch cancels the in-flight resolve
        // and parks state at `.paused` (YT-0049 semantics).
        .accessibilityLabel(isLoading ? "Loading, double-tap to cancel" : (isPlaying ? "Pause" : "Play"))
    }
}

// MARK: - TransportHaptics

/// `.sensoryFeedback` stack for the transport row. Implemented as a chain
/// of small `ViewModifier`s so the SwiftUI type-checker can resolve each
/// `sensoryFeedback` overload cheaply — a single body chaining all eight
/// modifiers exceeds the type-check budget on iOS 17 / Swift 5.10.
///
/// The mapping mirrors `design-system/handoff/YT-0027/haptics-and-a11y.md`
/// table — keep these in sync.
private struct TransportHaptics: ViewModifier {
    let isPlaying: Bool
    let shuffleEnabled: Bool
    let repeatMode: RepeatMode
    let skipFwdCount: Int
    let skipBackCount: Int

    func body(content: Content) -> some View {
        content
            .modifier(PlayPauseHaptics(isPlaying: isPlaying))
            .modifier(SkipHaptics(skipFwdCount: skipFwdCount, skipBackCount: skipBackCount))
            .modifier(ShuffleHaptics(shuffleEnabled: shuffleEnabled))
            .modifier(RepeatHaptics(repeatMode: repeatMode))
    }
}

private struct PlayPauseHaptics: ViewModifier {
    let isPlaying: Bool
    func body(content: Content) -> some View {
        content
            .sensoryFeedback(.impact(weight: .medium), trigger: isPlaying) { old, new in
                !old && new
            }
            .sensoryFeedback(.impact(weight: .light), trigger: isPlaying) { old, new in
                old && !new
            }
    }
}

private struct SkipHaptics: ViewModifier {
    let skipFwdCount: Int
    let skipBackCount: Int
    func body(content: Content) -> some View {
        content
            .sensoryFeedback(.impact(weight: .light, intensity: 0.7), trigger: skipFwdCount)
            .sensoryFeedback(.impact(weight: .light, intensity: 0.7), trigger: skipBackCount)
    }
}

private struct ShuffleHaptics: ViewModifier {
    let shuffleEnabled: Bool
    func body(content: Content) -> some View {
        content
            .sensoryFeedback(.success, trigger: shuffleEnabled) { old, new in !old && new }
            .sensoryFeedback(.selection, trigger: shuffleEnabled) { old, new in old && !new }
    }
}

private struct RepeatHaptics: ViewModifier {
    let repeatMode: RepeatMode

    /// Single Bool surrogate for the repeat enum so the compiler can resolve
    /// `.sensoryFeedback(_:trigger:)` against a `Hashable` `Bool` instead of
    /// the closure-form against an enum. Same semantics: `false` ↔ off,
    /// `true` ↔ any non-off mode.
    private var repeatActive: Bool { repeatMode != .off }

    func body(content: Content) -> some View {
        content
            .sensoryFeedback(.success, trigger: repeatActive) { old, new in !old && new }
            .sensoryFeedback(.selection, trigger: repeatActive) { old, new in old && !new }
    }
}
