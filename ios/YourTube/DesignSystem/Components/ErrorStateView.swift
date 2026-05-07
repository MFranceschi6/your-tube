import SwiftUI
import UIKit

// MARK: - ErrorStateView

/// Canonical error state for every list-driven screen (YT-0165 / YT-0073 catalog).
///
/// Uses `ContentUnavailableView` per the iOS mapping in the state-catalog README.
/// Symbol is 56 pt, colored `.secondary` (NOT red — see catalog README "Error icon
/// is NOT red"). Wraps in `ScrollView` so Dynamic Type `.accessibility3` does not clip.
///
/// Supports an optional secondary action used only by C5 (offline Search).
///
/// Accessibility contract (AC10):
/// - Combined element: "{title}. {message}. Try again button."
/// - Symbol is `.accessibilityHidden(true)`.
/// - On appear when replacing a loading state: caller must post
///   `UIAccessibility.post(.screenChanged, argument: nil)` — this view does NOT
///   auto-post because it cannot know whether it replaced loading or content.
///   See `SearchScreen` for correct usage.
struct ErrorStateView: View {
    let systemImage: String
    let title: String
    let message: String
    /// Primary retry action. Label from catalog: "Try again".
    var onRetry: (() -> Void)? = nil
    /// Secondary action — only C5 (offline Search) gets one.
    var secondaryAction: ButtonAction? = nil

    // MARK: Legacy convenience init (backwards compat)

    init(
        title: String,
        message: String,
        retryTitle: String = "Try again",
        onRetry: (() -> Void)? = nil
    ) {
        self.systemImage = "exclamationmark.triangle"
        self.title = title
        self.message = message
        self.onRetry = onRetry
        self.secondaryAction = nil
    }

    init(
        systemImage: String,
        title: String,
        message: String,
        onRetry: (() -> Void)? = nil,
        secondaryAction: ButtonAction? = nil
    ) {
        self.systemImage = systemImage
        self.title = title
        self.message = message
        self.onRetry = onRetry
        self.secondaryAction = secondaryAction
    }

    var body: some View {
        ScrollView {
            ContentUnavailableView {
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
                if let onRetry {
                    Button("Try again", action: onRetry)
                        .buttonStyle(.borderedProminent)
                        .tint(.accentColor)
                        .accessibilityLabel("Try again")
                }
                if let secondaryAction {
                    Button(secondaryAction.title, action: secondaryAction.action)
                        .buttonStyle(.bordered)
                        .accessibilityLabel(secondaryAction.title)
                }
            }
            .accessibilityElement(children: .combine)
            .accessibilityLabel(combinedLabel)
        }
    }

    private var combinedLabel: String {
        var parts = ["\(title).", "\(message)."]
        if onRetry != nil { parts.append("Try again button.") }
        if let secondaryAction { parts.append("\(secondaryAction.title) button.") }
        return parts.joined(separator: " ")
    }
}

// MARK: - Previews (AC14 — all 7 error variants)

#Preview("C4 — Search generic error", traits: .sizeThatFitsLayout) {
    ErrorStateView(
        systemImage: "exclamationmark.triangle",
        title: "Couldn't search",
        message: "Something went wrong on our end. Try again in a moment.",
        onRetry: {}
    )
    .preferredColorScheme(.dark)
}

#Preview("C5 — Search offline error", traits: .sizeThatFitsLayout) {
    ErrorStateView(
        systemImage: "wifi.slash",
        title: "You're offline",
        message: "Connect to the internet to search. Your saved playlists are still available in Library.",
        onRetry: {},
        secondaryAction: ButtonAction(title: "Go to Library", action: {})
    )
    .preferredColorScheme(.dark)
    .dynamicTypeSize(.accessibility3)
}

#Preview("C8 — Library error", traits: .sizeThatFitsLayout) {
    ErrorStateView(
        systemImage: "exclamationmark.triangle",
        title: "Couldn't load your library",
        message: "Check your connection and try again.",
        onRetry: {}
    )
    .preferredColorScheme(.dark)
    .dynamicTypeSize(.accessibility3)
}

#Preview("C11 — Playlist detail error", traits: .sizeThatFitsLayout) {
    ErrorStateView(
        systemImage: "exclamationmark.triangle",
        title: "Couldn't load this playlist",
        message: "Check your connection and try again.",
        onRetry: {}
    )
    .preferredColorScheme(.dark)
    .dynamicTypeSize(.accessibility3)
}

#Preview("C14 — History error", traits: .sizeThatFitsLayout) {
    ErrorStateView(
        systemImage: "exclamationmark.triangle",
        title: "Couldn't load history",
        message: "Try again in a moment.",
        onRetry: {}
    )
    .preferredColorScheme(.dark)
    .dynamicTypeSize(.accessibility3)
}
