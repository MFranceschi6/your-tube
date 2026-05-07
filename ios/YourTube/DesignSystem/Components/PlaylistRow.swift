import SwiftUI

// MARK: - PlaylistRow

/// A playlist entry in the Library list.
/// Shows the hybrid `PlaylistCover` alongside name and track count.
struct PlaylistRow: View {
    let playlist: PlaylistEntity

    var body: some View {
        HStack(spacing: 14) {
            PlaylistCover(playlist: playlist)
                .frame(width: 56, height: 56)
                .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))

            VStack(alignment: .leading, spacing: 2) {
                Text(playlist.name)
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(.primary)
                    .lineLimit(1)
                    .dynamicTypeSize(...DynamicTypeSize.accessibility3)

                let count = playlist.orderedTracks.count
                Text("^[\(count) track](inflect: true)")
                    .font(.system(size: 13))
                    .foregroundStyle(.secondary)
                    .monospacedDigit()
                    .dynamicTypeSize(...DynamicTypeSize.accessibility3)
            }

            Spacer(minLength: 0)
        }
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
        .accessibilityLabel({
            let count = playlist.orderedTracks.count
            let trackWord = count == 1 ? "1 track" : "\(count) tracks"
            return "\(playlist.name), \(trackWord)"
        }())
        .accessibilityHint("Opens playlist details")
    }
}

// MARK: - Preview

#Preview("PlaylistRow") {
    Text("Requires SwiftData container")
}
