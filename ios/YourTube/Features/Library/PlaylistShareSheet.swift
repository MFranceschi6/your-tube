import SwiftUI
import UIKit

// MARK: - PlaylistShareSheet

/// SwiftUI bridge around `UIActivityViewController` for sharing a playlist
/// export file (`.ytplaylist.json`).
///
/// SwiftUI's `ShareLink` is preferred for in-place share affordances (used by
/// the playlist detail menu). Use this representable when the share trigger
/// is itself a sheet-presented view that needs explicit completion handling
/// (e.g. clearing transient state, refreshing UI).
struct PlaylistShareSheet: UIViewControllerRepresentable {

    /// Activity items handed to UIKit. Typically a single file URL.
    let activityItems: [Any]
    /// Optional UTI strings to exclude from the system share grid.
    var excludedActivityTypes: [UIActivity.ActivityType]?
    /// Called when the share sheet is dismissed (with success/failure).
    var onCompletion: ((Bool) -> Void)?

    func makeUIViewController(context: Context) -> UIActivityViewController {
        let controller = UIActivityViewController(
            activityItems: activityItems,
            applicationActivities: nil
        )
        controller.excludedActivityTypes = excludedActivityTypes
        controller.completionWithItemsHandler = { _, completed, _, _ in
            onCompletion?(completed)
        }
        return controller
    }

    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {
        // Activity items are immutable for the lifetime of a share session.
    }
}
