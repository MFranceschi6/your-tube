import SwiftUI
import UIKit

// MARK: - NowPlayingActionRow

/// Four-icon, icon-only action row per YT-0027 Q7. **Order is locked:**
/// `AirPlay · Queue · Share · Add`. Labels are explicitly *not* shown so
/// the row reads as a player, not a settings list (Q7 rationale).
///
/// AirPlay is rendered via the system `AVRoutePickerView` bridge — never a
/// custom button — so users get the OS route sheet.
struct NowPlayingActionRow: View {
    let track: Track
    let onShowQueue: () -> Void
    let onAddToPlaylist: () -> Void

    /// Resolved system tint colour for the AirPlay glyph. Resolved via
    /// `UIColor` so the inactive state matches the SwiftUI `.tint` set at
    /// the screen root.
    private var airPlayTint: UIColor {
        UIColor(Theme.accent)
    }

    var body: some View {
        HStack {
            airPlayCell
            Spacer()
            queueButton
            Spacer()
            shareButton
            Spacer()
            addButton
        }
        .font(.system(size: 22, weight: .medium))
        .foregroundStyle(Color.white)
        .padding(.horizontal, 32)
    }

    // MARK: AirPlay (Q7 — first, system-provided)

    private var airPlayCell: some View {
        AirPlayRoutePicker(tintColor: airPlayTint)
            .frame(width: Tokens.HitTarget.minimum, height: Tokens.HitTarget.minimum)
            // The bridge owns its own accessibility — do not override.
    }

    // MARK: Queue

    private var queueButton: some View {
        Button(action: onShowQueue) {
            Image(systemName: "list.bullet")
                .frame(width: Tokens.HitTarget.minimum, height: Tokens.HitTarget.minimum)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Show queue")
    }

    // MARK: Share

    private var shareButton: some View {
        // ShareLink's default label is the system share sheet glyph.
        // We explicitly render the SF Symbol so the row icons all share
        // the same visual weight regardless of OS rendering quirks.
        ShareLink(item: shareURL) {
            Image(systemName: "square.and.arrow.up")
                .frame(width: Tokens.HitTarget.minimum, height: Tokens.HitTarget.minimum)
                .contentShape(Rectangle())
        }
        .accessibilityLabel("Share")
    }

    /// Best-effort YouTube URL for the share sheet. Falls back to a benign
    /// `https://youtube.com` URL when the videoId is missing — never logs
    /// the URL anywhere (per the iOS rules: stream URLs are sensitive, but
    /// public watch URLs are fine to share).
    private var shareURL: URL {
        URL(string: "https://www.youtube.com/watch?v=\(track.videoId)")
            ?? URL(string: "https://www.youtube.com")!
    }

    // MARK: Add to playlist

    private var addButton: some View {
        Button(action: onAddToPlaylist) {
            Image(systemName: "text.badge.plus")
                .frame(width: Tokens.HitTarget.minimum, height: Tokens.HitTarget.minimum)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Add to playlist")
    }
}
