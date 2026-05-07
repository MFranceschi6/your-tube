import SwiftUI

// MARK: - ShimmerModifier

/// Hand-rolled shimmer modifier matching the state-catalog spec (YT-0073):
/// - Background fill: `Color(.tertiarySystemBackground)` (maps to `--skeleton-bg`).
/// - Highlight: a 30%-wide diagonal `LinearGradient` sweeping left → right.
/// - Cycle: 1400 ms, linear, infinite.
/// - Stagger: callers pass `index` to offset each row by `index × 80 ms`.
/// - Paused when Reduce Motion is enabled — static fill remains for shape cue.
///
/// Apply via `.shimmer(index:)` on any `View`.
struct ShimmerModifier: ViewModifier {
    /// Row index used for stagger offset (index × 80 ms).
    var index: Int = 0

    @State private var phase: CGFloat = 0
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func body(content: Content) -> some View {
        content
            .overlay(
                GeometryReader { geo in
                    if !reduceMotion {
                        shimmerGradient(width: geo.size.width)
                    }
                }
            )
            .onAppear {
                guard !reduceMotion else { return }
                let delay = Double(index) * 0.08
                withAnimation(
                    .linear(duration: 1.4)
                    .repeatForever(autoreverses: false)
                    .delay(delay)
                ) {
                    phase = 1
                }
            }
    }

    private func shimmerGradient(width: CGFloat) -> some View {
        let shimmerWidth = width * 0.30
        let offset = (phase * (width + shimmerWidth)) - shimmerWidth

        return LinearGradient(
            colors: [
                Color.clear,
                Color.white.opacity(0.06),
                Color.clear
            ],
            startPoint: .leading,
            endPoint: .trailing
        )
        .frame(width: shimmerWidth)
        .offset(x: offset)
        .blendMode(.screen)
    }
}

extension View {
    /// Applies the catalog-compliant shimmer animation.
    /// - Parameter index: Row position for stagger offset (each row delayed by index × 80 ms).
    func shimmer(index: Int = 0) -> some View {
        modifier(ShimmerModifier(index: index))
    }
}

// MARK: - SkeletonBlock

/// Single rounded-rectangle skeleton block with shimmer.
/// Used as a building block for `SkeletonRow` and `SkeletonPlaylistRow`.
struct SkeletonBlock: View {
    var cornerRadius: CGFloat = Tokens.Radius.sm
    var index: Int = 0

    var body: some View {
        RoundedRectangle(cornerRadius: cornerRadius, style: Tokens.cornerStyle)
            .fill(Color(.tertiarySystemBackground))
            .shimmer(index: index)
    }
}

// MARK: - SkeletonRow

/// Skeleton placeholder for a track row (C1, C9, C12 loading states).
///
/// Template (from `loading.md`):
/// - 56 pt thumbnail block on the leading side.
/// - Two stacked text lines: line 1 ~60% width 14 pt height,
///   line 2 ~35% width 11 pt height, 6 pt gap.
/// - 16 pt padding all sides.
///
/// Invisible to VoiceOver (accessibilityHidden). The list container carries
/// `accessibilityValue("Loading")` per AC10.
struct SkeletonRow: View {
    var index: Int = 0

    var body: some View {
        HStack(spacing: Tokens.Spacing.md) {
            SkeletonBlock(cornerRadius: Tokens.Radius.sm, index: index)
                .frame(width: Tokens.Thumbnail.row, height: Tokens.Thumbnail.row)

            VStack(alignment: .leading, spacing: 6) {
                // Line 1: ~60% width, 14 pt height.
                // GeometryReader reads the available column width (not UIScreen.main
                // which is deprecated on iOS 17+) so multi-column / Split-View layouts
                // scale correctly.
                GeometryReader { geo in
                    SkeletonBlock(cornerRadius: 4, index: index)
                        .frame(width: geo.size.width * 0.60, height: 14)
                }
                .frame(height: 14)

                // Line 2: ~35% width, 11 pt height
                SkeletonBlock(cornerRadius: 4, index: index)
                    .frame(height: 11)
                    .frame(maxWidth: 140, alignment: .leading)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .padding(.horizontal, Tokens.Spacing.md)
        .padding(.vertical, Tokens.Spacing.sm + 2)
        .accessibilityHidden(true)
    }
}

// MARK: - SkeletonPlaylistRow

/// Skeleton placeholder for a playlist row (C6 loading state).
///
/// Template (from `loading.md`):
/// - 40 pt 4-up cover block (solid, `--radius-sm`).
/// - One text line ~50% width 14 pt, one ~25% width 11 pt, 6 pt gap.
struct SkeletonPlaylistRow: View {
    var index: Int = 0

    var body: some View {
        HStack(spacing: Tokens.Spacing.md) {
            // 4-up cover block: solid skeleton (no inner grid in skeleton form)
            SkeletonBlock(cornerRadius: Tokens.Radius.sm, index: index)
                .frame(width: 40, height: 40)

            VStack(alignment: .leading, spacing: 6) {
                // Line 1: ~50% width, 14 pt height
                SkeletonBlock(cornerRadius: 4, index: index)
                    .frame(height: 14)
                    .frame(maxWidth: 150, alignment: .leading)
                    .frame(maxWidth: .infinity, alignment: .leading)

                // Line 2: ~25% width, 11 pt height
                SkeletonBlock(cornerRadius: 4, index: index)
                    .frame(height: 11)
                    .frame(maxWidth: 80, alignment: .leading)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .padding(.horizontal, Tokens.Spacing.md)
        .padding(.vertical, Tokens.Spacing.sm + 2)
        .accessibilityHidden(true)
    }
}

// MARK: - Preview

#Preview("SkeletonRow × 6") {
    VStack(spacing: 0) {
        ForEach(0..<6, id: \.self) { i in
            SkeletonRow(index: i)
            if i < 5 {
                Divider()
                    .padding(.leading, Tokens.Thumbnail.row + Tokens.Spacing.md * 2)
            }
        }
    }
    .background(Theme.background)
}

#Preview("SkeletonPlaylistRow × 4") {
    VStack(spacing: 0) {
        ForEach(0..<4, id: \.self) { i in
            SkeletonPlaylistRow(index: i)
        }
    }
    .background(Theme.background)
}
