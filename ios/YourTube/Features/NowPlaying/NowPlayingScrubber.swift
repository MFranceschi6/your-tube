import SwiftUI

// MARK: - NowPlayingScrubber

/// Custom `DragGesture`-driven scrubber that lives on a `GeometryReader`,
/// per YT-0027 Q4. **Do not replace with a native `Slider`.** The Q4 spec
/// requires:
///
/// - Track height: `4pt` rest → `8pt` while dragging, `.easeInOut(0.18)`.
/// - Thumb: `12pt` rest → `18pt` while dragging.
/// - Time labels: `.monospacedDigit()`, secondary at rest, primary while
///   dragging, `12pt` → `14pt`.
/// - Haptic ticks every 10 seconds of scrub distance via `.selection`.
/// - `seek(to:)` fires only in `.onEnded` — drag is *seek preview*.
/// - Artwork shrinks to `0.92` while dragging (driven from the parent via
///   `isDragging` binding).
/// - Reduce Motion (Q9) collapses the easing curves to instant.
///
/// VoiceOver gets an `.accessibilityAdjustableAction` that scrubs in 5%
/// steps so users can scrub via swipe-up/down on the rotor.
struct NowPlayingScrubber: View {
    let elapsed: TimeInterval
    let duration: TimeInterval
    @Binding var isDragging: Bool
    let onSeek: (TimeInterval) -> Void

    @State private var dragValue: TimeInterval = 0

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    /// Time displayed during a drag is a "preview"; falls back to the model
    /// elapsed time when the user is not dragging so progress observation
    /// keeps advancing.
    private var displayed: TimeInterval { isDragging ? dragValue : elapsed }

    /// Normalised 0–1 progress of the visible thumb. Returns 0 when the
    /// duration is unknown to avoid `NaN` width math.
    private var progress: CGFloat {
        guard duration > 0 else { return 0 }
        return CGFloat(min(max(displayed / duration, 0), 1))
    }

    /// Tick counter used by `.sensoryFeedback(.selection)`. Q4 Q8 require a
    /// tick every 10 seconds of scrub distance — keying on `Int(value/10)`
    /// gives one fire per crossing without time-based machinery.
    private var tickBucket: Int { Int(dragValue / 10) }

    var body: some View {
        VStack(spacing: 8) {
            track
            timeLabels
        }
        // Q8 — drag tick every 10s of scrub distance.
        .sensoryFeedback(.selection, trigger: tickBucket)
        // Q8 — release commits the seek with a medium impact.
        .sensoryFeedback(.impact(weight: .medium), trigger: isDragging) { old, new in
            old == true && new == false
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Playback position")
        .accessibilityValue("\(timeString(elapsed)) of \(timeString(duration))")
        .accessibilityHint("Swipe up or down to scrub")
        .accessibilityAdjustableAction { direction in
            guard duration > 0 else { return }
            let step = duration * 0.05
            let target: TimeInterval
            switch direction {
            case .increment:
                target = min(duration, elapsed + step)
            case .decrement:
                target = max(0, elapsed - step)
            @unknown default:
                return
            }
            onSeek(target)
        }
    }

    // MARK: Subviews

    private var track: some View {
        GeometryReader { geo in
            ZStack(alignment: .leading) {
                Capsule()
                    .fill(Color.white.opacity(0.18))
                Capsule()
                    .fill(Color.white)
                    .frame(width: max(0, geo.size.width * progress))
                Circle()
                    .fill(Color.white)
                    .frame(
                        width: isDragging ? 18 : 12,
                        height: isDragging ? 18 : 12
                    )
                    .shadow(color: .black.opacity(0.35), radius: 1, x: 0, y: 1)
                    .offset(x: geo.size.width * progress - (isDragging ? 9 : 6))
            }
            .frame(height: isDragging ? 8 : 4)
            .frame(maxHeight: .infinity, alignment: .center)
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { value in
                        if !isDragging {
                            isDragging = true
                            dragValue = elapsed
                        }
                        let proportion = max(0, min(1, value.location.x / max(geo.size.width, 1)))
                        dragValue = duration * Double(proportion)
                    }
                    .onEnded { _ in
                        // Q4: `seek(to:)` fires only in `.onEnded` so drag-spam
                        // can't produce seek-spam.
                        let final = max(0, min(duration, dragValue))
                        onSeek(final)
                        isDragging = false
                    }
            )
            .animation(NowPlayingAnimation.scrubber(reduceMotion: reduceMotion), value: isDragging)
        }
        // Generous vertical hit area while keeping the visible track thin.
        .frame(height: 24)
    }

    private var timeLabels: some View {
        HStack {
            Text(timeString(displayed))
            Spacer()
            Text("-" + timeString(max(0, duration - displayed)))
        }
        .monospacedDigit()
        .font(.system(size: isDragging ? 14 : 12, weight: .medium))
        .foregroundStyle(isDragging ? Color.white : Color.white.opacity(0.55))
        .dynamicTypeSize(...DynamicTypeSize.xxLarge)
        .animation(NowPlayingAnimation.scrubber(reduceMotion: reduceMotion), value: isDragging)
    }

    // MARK: Helpers

    private func timeString(_ seconds: TimeInterval) -> String {
        let total = Int(seconds.isFinite ? max(0, seconds) : 0)
        let hours = total / 3600
        let minutes = (total % 3600) / 60
        let secs = total % 60
        if hours > 0 {
            return String(format: "%d:%02d:%02d", hours, minutes, secs)
        }
        return String(format: "%d:%02d", minutes, secs)
    }
}
