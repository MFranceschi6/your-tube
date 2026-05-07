import SwiftUI

// MARK: - PlaybackErrorBanner

/// Compact, dismissable banner surfaced when ``PlayerCoordinator/state`` is
/// `.error(message:)`. Stateless — callers pass `message` and
/// `onRetry` / `onDismiss` callbacks. Renders inline above the MiniPlayer
/// (or inline inside NowPlayingView) so the user gets a visible diagnosis
/// path instead of the silent "spinner → nothing" failure mode that
/// motivated YT-0070.
///
/// Visual treatment per `docs/design-system.md` error patterns:
/// - Tinted background (`Theme.error.opacity(0.12)`) + thin red border.
/// - Leading SF Symbol `exclamationmark.triangle.fill` at `Theme.error`.
/// - Body text in primary on the surface, message in
///   `Theme.onBackgroundSecondary`.
/// - Trailing "Retry" button + "X" dismiss; both 44×44pt hit targets.
///
/// Accessibility:
/// - The banner itself is announced to VoiceOver via the surrounding
///   container's accessibility focus shift. Retry / Dismiss have explicit
///   labels and hints.
/// - Honors Reduce Motion via `Animation` defaults (no implicit motion in
///   this component; transitions are owned by the caller's `if hasError`).
struct PlaybackErrorBanner: View {
    let message: String
    var retryTitle: String = "Retry"
    let onRetry: () -> Void
    let onDismiss: () -> Void

    var body: some View {
        HStack(alignment: .top, spacing: Tokens.Spacing.sm) {
            icon
            messageText
            Spacer(minLength: Tokens.Spacing.xs)
            retryButton
            dismissButton
        }
        .padding(.horizontal, Tokens.Spacing.md)
        .padding(.vertical, Tokens.Spacing.sm)
        .background(
            RoundedRectangle(cornerRadius: Tokens.Radius.md, style: Tokens.cornerStyle)
                .fill(Theme.error.opacity(0.12))
        )
        .overlay(
            RoundedRectangle(cornerRadius: Tokens.Radius.md, style: Tokens.cornerStyle)
                .strokeBorder(Theme.error.opacity(0.45), lineWidth: 0.5)
        )
        .padding(.horizontal, Tokens.Spacing.sm + 2)
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Playback error. \(message)")
    }

    // MARK: Subviews

    private var icon: some View {
        Image(systemName: "exclamationmark.triangle.fill")
            .font(Theme.iconMedium)
            .foregroundStyle(Theme.error)
            .padding(.top, 2)
            .accessibilityHidden(true)
    }

    private var messageText: some View {
        Text(message)
            .font(Theme.bodyMedium)
            .foregroundStyle(Theme.onBackground)
            .lineLimit(3)
            .multilineTextAlignment(.leading)
    }

    private var retryButton: some View {
        Button(action: onRetry) {
            Text(retryTitle)
                .font(Theme.bodyMedium.weight(.semibold))
                .foregroundStyle(Theme.error)
                .padding(.horizontal, Tokens.Spacing.sm + 2)
                .frame(minHeight: Tokens.HitTarget.minimum)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(retryTitle)
        .accessibilityHint("Attempts the previous track again")
    }

    private var dismissButton: some View {
        Button(action: onDismiss) {
            Image(systemName: "xmark")
                .font(Theme.iconSmall.weight(.semibold))
                .foregroundStyle(Theme.onBackgroundSecondary)
                .frame(width: Tokens.HitTarget.minimum, height: Tokens.HitTarget.minimum)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Dismiss error")
    }
}

// MARK: - Preview

#Preview("PlaybackErrorBanner") {
    VStack(spacing: Tokens.Spacing.lg) {
        PlaybackErrorBanner(
            message: "HTTP 403: Forbidden",
            onRetry: {},
            onDismiss: {}
        )
        PlaybackErrorBanner(
            message: "Playback failed.",
            onRetry: {},
            onDismiss: {}
        )
    }
    .padding(.vertical)
    .background(Theme.background)
}
