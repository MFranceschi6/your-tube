import SwiftUI
import UIKit

// MARK: - SettingsOperationState

/// State for a single settings operation row (C15 / C16).
/// Covers: clear-cache, clear-history, export, import.
enum SettingsOperationState: Equatable {
    /// Idle — row shows chevron / normal affordance.
    case idle
    /// C15: operation in progress — spinner replaces chevron.
    case inProgress(statusText: String)
    /// C16: operation failed — inline error banner shown below the row.
    case failed(title: String, message: String)
}

// MARK: - SettingsScreen

/// Settings screen.
///
/// State catalog mapping (YT-0165 / YT-0073):
/// - C15: inline `ProgressView` on the triggering row (no full-screen overlay).
/// - C16: inline error banner attached to the row that failed.
///   Catalog copy verbatim per `copy.md`.
struct SettingsScreen: View {
    @AppStorage(SettingsKeys.audioQuality) private var audioQualityRaw: String = AudioQualityPreference.auto.rawValue

    // MARK: Operation states (C15 / C16)

    @State private var clearCacheState: SettingsOperationState = .idle
    @State private var showClearCacheConfirm = false

    var body: some View {
        NavigationStack {
            Form {
                // MARK: Playback
                Section("Playback") {
                    Picker("Audio Quality", selection: $audioQualityRaw) {
                        ForEach(AudioQualityPreference.allCases) { quality in
                            audioQualityRow(quality)
                        }
                    }
                    .pickerStyle(.navigationLink)
                    .accessibilityLabel("Audio quality")
                    .accessibilityHint("Select the preferred audio quality for streaming")
                }

                // MARK: Storage
                Section("Storage") {
                    // C15/C16: Clear Cache row with inline operation state
                    OperationRow(
                        label: "Clear Cache",
                        systemImage: "trash",
                        role: .destructive,
                        state: clearCacheState,
                        onTap: {
                            if case .inProgress = clearCacheState { return }
                            showClearCacheConfirm = true
                        },
                        onRetry: { runClearCache() },
                        onDismissError: { clearCacheState = .idle }
                    )
                }

                // MARK: About
                Section("About") {
                    LabeledContent("Version", value: SettingsStore.appVersion)
                        .accessibilityLabel("App version \(SettingsStore.appVersion)")

                    VStack(alignment: .leading, spacing: 6) {
                        Text("Personal Use Only")
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(.secondary)
                        Text("YourTube streams audio locally from YouTube for personal listening. Not affiliated with YouTube or Google. Not for commercial use.")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    .padding(.vertical, 4)
                    .accessibilityElement(children: .combine)
                }
            }
            .navigationTitle("Settings")
            .confirmationDialog(
                "Clear Cache?",
                isPresented: $showClearCacheConfirm,
                titleVisibility: .visible
            ) {
                Button("Clear Cache", role: .destructive) { runClearCache() }
                Button("Cancel", role: .cancel) {}
            } message: {
                Text("This removes temporary files from YourTube's extractor, artwork, and export cache. Your playlists and data are not affected.")
            }
        }
    }

    // MARK: - Operations

    private func runClearCache() {
        // C15: show in-progress spinner with catalog status text
        clearCacheState = .inProgress(statusText: "Clearing cache…")
        Task.detached {
            do {
                try SettingsStore.clearCache()
                await MainActor.run {
                    clearCacheState = .idle
                }
            } catch {
                await MainActor.run {
                    // C16: inline error per catalog copy.md C16
                    clearCacheState = .failed(
                        title: "Couldn't clear cache.",
                        message: "The app couldn't clear the cache. Try again, or restart the app."
                    )
                    // C16: assertive announcement (out-of-band failure)
                    UIAccessibility.post(
                        notification: .announcement,
                        argument: "Couldn't clear cache. The app couldn't clear the cache. Try again, or restart the app."
                    )
                }
            }
        }
    }

    // MARK: - Private Helpers

    private func audioQualityRow(_ quality: AudioQualityPreference) -> some View {
        Text(quality.displayName).tag(quality.rawValue)
    }
}

// MARK: - OperationRow

/// A settings row that drives C15 (in-progress) and C16 (failed) states inline.
///
/// When `state` is `.inProgress`, the trailing chevron is replaced with a
/// `ProgressView`. When `state` is `.failed`, an error banner appears below
/// the row per C16 spec.
private struct OperationRow: View {
    let label: String
    let systemImage: String
    var role: ButtonRole? = nil
    let state: SettingsOperationState
    let onTap: () -> Void
    let onRetry: () -> Void
    let onDismissError: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            // Primary row
            Button(action: onTap) {
                HStack {
                    Label(label, systemImage: systemImage)
                        .foregroundStyle(role == .destructive ? Color(.systemRed) : .primary)
                    Spacer()
                    trailingIndicator
                }
            }
            .buttonStyle(.plain)
            .accessibilityLabel(label)
            .disabled({ if case .inProgress = state { return true }; return false }())

            // In-progress status text (replaces row subtitle per C15)
            if case .inProgress(let statusText) = state {
                Text(statusText)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .padding(.top, 2)
            }

            // C16 — Inline error banner
            if case .failed(let title, let message) = state {
                SettingsErrorBanner(
                    title: title,
                    message: message,
                    onRetry: onRetry,
                    onDismiss: onDismissError
                )
                .padding(.top, 8)
            }
        }
    }

    @ViewBuilder
    private var trailingIndicator: some View {
        switch state {
        case .idle, .failed:
            Image(systemName: "chevron.right")
                .font(.caption.weight(.semibold))
                .foregroundStyle(Color(.tertiaryLabel))
        case .inProgress:
            // C15: spinner replaces chevron, .small control size (24 pt ~ 24 dp)
            ProgressView()
                .controlSize(.small)
        }
    }
}

// MARK: - SettingsErrorBanner

/// C16 — Inline error banner attached below a failed operation row.
/// Background is `--color-error-surface` (`rgba(255,69,58,0.12)`).
/// Uses assertive announcement on appear per spec.
private struct SettingsErrorBanner: View {
    let title: String
    let message: String
    let onRetry: () -> Void
    let onDismiss: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(alignment: .top, spacing: 8) {
                Image(systemName: "exclamationmark.triangle")
                    .font(.system(size: 16))
                    .foregroundStyle(Color(.systemRed))
                    .accessibilityHidden(true)

                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(.primary)
                    Text(message)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Spacer()
            }

            HStack {
                Spacer()
                Button("Try again", action: onRetry)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Color.accentColor)
                    .frame(minHeight: 44)
                    .accessibilityLabel("Try again")

                Button("Dismiss", action: onDismiss)
                    .font(.subheadline)
                    .foregroundStyle(Color.secondary)
                    .frame(minHeight: 44)
                    .accessibilityLabel("Dismiss")
            }
        }
        .padding(12)
        .background(Color(.systemRed).opacity(0.12))
        .clipShape(RoundedRectangle(cornerRadius: Tokens.Radius.sm, style: Tokens.cornerStyle))
        // Left accent strip (3 pt per spec)
        .overlay(alignment: .leading) {
            Rectangle()
                .fill(Color(.systemRed))
                .frame(width: 3)
                .clipShape(RoundedRectangle(cornerRadius: 1.5))
        }
    }
}

// MARK: - Preview

#Preview("SettingsScreen") {
    SettingsScreen()
}

#Preview("C15 — Clearing cache") {
    NavigationStack {
        Form {
            Section("Storage") {
                OperationRow(
                    label: "Clear Cache",
                    systemImage: "trash",
                    role: .destructive,
                    state: .inProgress(statusText: "Clearing cache…"),
                    onTap: {},
                    onRetry: {},
                    onDismissError: {}
                )
            }
        }
    }
    .preferredColorScheme(.dark)
}

#Preview("C16 — Clear cache failed") {
    NavigationStack {
        Form {
            Section("Storage") {
                OperationRow(
                    label: "Clear Cache",
                    systemImage: "trash",
                    role: .destructive,
                    state: .failed(
                        title: "Couldn't clear cache.",
                        message: "The app couldn't clear the cache. Try again, or restart the app."
                    ),
                    onTap: {},
                    onRetry: {},
                    onDismissError: {}
                )
            }
        }
    }
    .preferredColorScheme(.dark)
    .dynamicTypeSize(.accessibility3)
}
