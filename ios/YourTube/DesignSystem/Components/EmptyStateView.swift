import SwiftUI
import UIKit

// MARK: - ButtonAction

/// Typed action descriptor for buttons on empty/error state views.
struct ButtonAction {
    let title: String
    let action: () -> Void
}

// MARK: - EmptyStateView

/// Canonical empty state for every list-driven screen (YT-0165 / YT-0073 catalog).
///
/// Uses `ContentUnavailableView` as the underlying container per the iOS mapping
/// in `design-system/handoff/state-catalog/README.md`. Symbol is 56 pt, colored
/// `.secondary` (never red). Wraps in a `ScrollView` so Dynamic Type at
/// `.accessibility3` does not clip.
///
/// Accessibility contract (AC10):
/// - The combined element reads: "{title}. {message}. {actionTitle} button."
/// - Symbol is `.accessibilityHidden(true)` (decorative).
/// - Container uses `.accessibilityElement(children: .combine)`.
struct EmptyStateView: View {
    let systemImage: String
    let title: String
    let message: String
    /// Primary action (optional). Rendered as `.borderedProminent` + `.accentColor`.
    var primaryAction: ButtonAction? = nil

    // MARK: Legacy convenience init (backwards compat with callers using positional params)

    init(
        systemImage: String,
        title: String,
        message: String,
        actionTitle: String? = nil,
        action: (() -> Void)? = nil
    ) {
        self.systemImage = systemImage
        self.title = title
        self.message = message
        if let actionTitle, let action {
            self.primaryAction = ButtonAction(title: actionTitle, action: action)
        }
    }

    init(
        systemImage: String,
        title: String,
        message: String,
        primaryAction: ButtonAction?
    ) {
        self.systemImage = systemImage
        self.title = title
        self.message = message
        self.primaryAction = primaryAction
    }

    var body: some View {
        ScrollView {
            ContentUnavailableView {
                // Symbol: 56 pt, .secondary color, accessibilityHidden.
                Label {
                    Text(title)
                } icon: {
                    Image(systemName: systemImage)
                        .font(.system(size: 56))
                        .foregroundStyle(Color.secondary)
                        .accessibilityHidden(true)
                }
            } description: {
                Text(message)
            } actions: {
                if let primaryAction {
                    Button(primaryAction.title, action: primaryAction.action)
                        .buttonStyle(.borderedProminent)
                        .tint(.accentColor)
                        .accessibilityLabel(primaryAction.title)
                }
            }
            .accessibilityElement(children: .combine)
            .accessibilityLabel(combinedLabel)
        }
    }

    private var combinedLabel: String {
        if let primaryAction {
            return "\(title). \(message). \(primaryAction.title) button."
        }
        return "\(title). \(message)."
    }
}

// MARK: - Previews (AC14)

#Preview("C2 — Search idle", traits: .sizeThatFitsLayout) {
    EmptyStateView(
        systemImage: "magnifyingglass",
        title: "Search YourTube",
        message: "Find tracks, channels, and topics from your subscriptions."
    )
    .preferredColorScheme(.dark)
}

#Preview("C3 — No results", traits: .sizeThatFitsLayout) {
    EmptyStateView(
        systemImage: "magnifyingglass",
        title: "No results for \"lofi xyz\"",
        message: "Check your spelling or try a different search.",
        actionTitle: "Clear search",
        action: {}
    )
    .preferredColorScheme(.dark)
    .dynamicTypeSize(.accessibility3)
}

#Preview("C7 — No playlists", traits: .sizeThatFitsLayout) {
    EmptyStateView(
        systemImage: "music.note.list",
        title: "No playlists yet",
        message: "Create one to organize tracks for offline listening.",
        actionTitle: "Create playlist",
        action: {}
    )
    .preferredColorScheme(.dark)
    .dynamicTypeSize(.accessibility3)
}

#Preview("C10 — Empty playlist", traits: .sizeThatFitsLayout) {
    EmptyStateView(
        systemImage: "music.note",
        title: "This playlist is empty",
        message: "Add tracks from search or your history.",
        actionTitle: "Find tracks",
        action: {}
    )
    .preferredColorScheme(.dark)
}

#Preview("C13 — Nothing played", traits: .sizeThatFitsLayout) {
    EmptyStateView(
        systemImage: "clock.arrow.circlepath",
        title: "Nothing played yet",
        message: "Tracks you play will show up here.",
        actionTitle: "Browse search",
        action: {}
    )
    .preferredColorScheme(.dark)
}
