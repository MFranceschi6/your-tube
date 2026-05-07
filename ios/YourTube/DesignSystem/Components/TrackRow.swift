import SwiftUI

// MARK: - TrackRow

/// A single track in any list — search results, queue, history.
/// Stateless: callers own `isPlaying` and `onTap`/`onMoreTap`.
struct TrackRow: View {
    let track: Track
    var isPlaying: Bool = false
    var onTap: () -> Void = {}
    var onMoreTap: () -> Void = {}

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: Tokens.Spacing.md) {
                thumbnail
                metadata
                moreButton
            }
            .padding(.horizontal, Tokens.Spacing.md)
            .padding(.vertical, Tokens.Spacing.sm + 2)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(accessibilityDescription)
        .accessibilityHint("Double-tap to play")
        .accessibilityAddTraits(isPlaying ? .startsMediaSession : [])
    }

    // MARK: Subviews

    private var thumbnail: some View {
        ZStack {
            RoundedRectangle(cornerRadius: Tokens.Radius.sm, style: Tokens.cornerStyle)
                .fill(isPlaying ? Theme.accent.opacity(0.15) : Theme.surfaceVariant)
                .frame(width: Tokens.Thumbnail.row, height: Tokens.Thumbnail.row)

            if isPlaying {
                EqBarsView()
                    .foregroundStyle(Theme.accent)
            } else {
                Image(systemName: "play.fill")
                    .imageScale(.large)
                    .foregroundStyle(Theme.onBackgroundTertiary)
            }
        }
        .frame(width: Tokens.Thumbnail.row, height: Tokens.Thumbnail.row)
    }

    private var metadata: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(track.title)
                .font(Theme.bodyLarge)
                .fontWeight(isPlaying ? .semibold : .regular)
                .foregroundStyle(isPlaying ? Theme.accent : Theme.onBackground)
                .lineLimit(2)
                .multilineTextAlignment(.leading)

            HStack(spacing: Tokens.Spacing.xs) {
                Text(track.channel)
                    .font(Theme.bodyMedium)
                    .foregroundStyle(Theme.onBackgroundSecondary)
                    .lineLimit(1)

                if track.durationSec > 0 {
                    Text("·")
                        .foregroundStyle(Theme.onBackgroundTertiary)
                    Text(track.formattedDuration)
                        .font(Theme.labelSmall)
                        .foregroundStyle(Theme.onBackgroundSecondary)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var moreButton: some View {
        Button(action: onMoreTap) {
            Image(systemName: "ellipsis")
                .imageScale(.medium)
                .fontWeight(.medium)
                .foregroundStyle(Theme.onBackgroundTertiary)
                .frame(width: Tokens.HitTarget.minimum, height: Tokens.HitTarget.minimum)
        }
        .buttonStyle(.plain)
        .accessibilityLabel("More options for \(track.title)")
    }

    // MARK: Accessibility

    private var accessibilityDescription: String {
        var parts = [track.title, "by \(track.channel)"]
        if track.durationSec > 0 { parts.append(track.formattedDuration) }
        if isPlaying { parts.append("Now playing") }
        return parts.joined(separator: ", ")
    }
}

// MARK: - EqBarsView

/// Animated equalizer bars shown on the currently-playing track thumbnail.
private struct EqBarsView: View {
    @State private var animate = false

    var body: some View {
        HStack(alignment: .bottom, spacing: 2) {
            bar(delay: 0.0)
            bar(delay: 0.2)
            bar(delay: 0.4)
        }
        .frame(width: 18, height: 14)
        .onAppear { animate = true }
        .onDisappear { animate = false }
    }

    private func bar(delay: Double) -> some View {
        RoundedRectangle(cornerRadius: 1)
            .frame(width: 3, height: animate ? 14 : 4)
            .animation(
                .easeInOut(duration: 0.6).repeatForever().delay(delay),
                value: animate
            )
    }
}

// MARK: - Track formatting helper

private extension Track {
    var formattedDuration: String {
        guard durationSec > 0 else { return "Live" }
        let hours = durationSec / 3600
        let minutes = (durationSec % 3600) / 60
        let seconds = durationSec % 60
        if hours > 0 {
            return String(format: "%d:%02d:%02d", hours, minutes, seconds)
        }
        return String(format: "%d:%02d", minutes, seconds)
    }
}

// MARK: - Preview

#Preview("TrackRow states") {
    let sample = Track(
        videoId: "abc",
        title: "lofi hip hop radio – beats to relax/study to",
        channel: "Lofi Girl",
        durationSec: 0,
        thumbnailUrl: ""
    )
    let song = Track(
        videoId: "def",
        title: "Bohemian Rhapsody",
        channel: "Queen",
        durationSec: 355,
        thumbnailUrl: ""
    )

    List {
        TrackRow(track: sample, isPlaying: false)
        TrackRow(track: song, isPlaying: true)
        TrackRow(track: song, isPlaying: false)
    }
    .listStyle(.plain)
}
