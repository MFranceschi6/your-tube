import SwiftUI

// MARK: - NowPlayingUpNextPreview

/// Inline "Up Next" preview shown on the Now Playing screen per YT-0027 Q6.
/// Renders the next 3 queued tracks plus a "See all" affordance that pushes
/// the full ``QueueView`` via `NavigationStack`.
///
/// **No collapsible panel.** A panel can't host edit mode + swipe-delete
/// cleanly for a long queue; the pushed destination handles those for free.
struct NowPlayingUpNextPreview: View {

    /// Tracks scheduled to play after the current item, in order. Caller
    /// trims to "next 3" responsibility-side; the view renders whatever it
    /// is handed.
    let upcoming: [Track]
    let onSeeAll: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            header
            ForEach(Array(upcoming.prefix(3).enumerated()), id: \.element.videoId) { _, track in
                row(for: track)
            }
        }
        .padding(.horizontal, Tokens.Spacing.lg)
    }

    // MARK: Header

    private var header: some View {
        HStack {
            Text("Up Next")
                .font(.caption.weight(.semibold))
                .foregroundStyle(Color.white.opacity(0.55))
                .textCase(.uppercase)
                .tracking(1.2)
            Spacer()
            Button(action: onSeeAll) {
                Text("See all")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(Color.accentColor)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("See all queued tracks")
        }
    }

    // MARK: Row

    private func row(for track: Track) -> some View {
        HStack(spacing: 12) {
            RoundedRectangle(cornerRadius: 8, style: Tokens.cornerStyle)
                .fill(Theme.accent.opacity(0.18))
                .frame(width: 44, height: 44)
                .overlay(
                    Image(systemName: "music.note")
                        .font(.system(size: 16, weight: .medium))
                        .foregroundStyle(Theme.accent)
                )
            VStack(alignment: .leading, spacing: 2) {
                Text(track.title)
                    .font(.system(size: 15, weight: .medium))
                    .foregroundStyle(Color.white)
                    .lineLimit(1)
                Text(track.channel)
                    .font(.system(size: 13))
                    .foregroundStyle(Color.white.opacity(0.55))
                    .lineLimit(1)
            }
            Spacer()
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel("Up next: \(track.title) by \(track.channel)")
    }
}
