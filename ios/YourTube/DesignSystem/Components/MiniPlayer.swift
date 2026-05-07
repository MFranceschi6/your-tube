import SwiftUI

// MARK: - MiniPlayer

/// Persistent mini-player bar docked above the tab bar.
/// Hosted via `safeAreaInset(edge: .bottom)` on the root TabView.
/// Stateless — callers pass all state and callbacks.
struct MiniPlayer: View {
    let track: Track
    var isPlaying: Bool
    var progress: Double          // 0.0 – 1.0
    /// YT-0055: `true` while the coordinator is resolving the stream URL or
    /// the engine is pre-rolling. Drives the loading affordance — a circular
    /// `ProgressView` replaces the play/pause glyph and VoiceOver hears
    /// "Loading <track title>" via `accessibilityValue`. Decorative spinner
    /// is hidden from VoiceOver (`accessibilityHidden(true)`); the row
    /// remains tappable to expand the now-playing surface.
    var isLoading: Bool = false
    var onTogglePlayPause: () -> Void = {}
    var onSkipForward: () -> Void = {}
    var onExpand: () -> Void = {}
    /// Namespace stub for YT-0027 `matchedGeometryEffect` hero transition.
    /// Currently unused; YT-0027 will animate with this namespace — do not remove.
    var nowPlayingNamespace: Namespace.ID? = nil

    var body: some View {
        VStack(spacing: 0) {
            card
        }
        .padding(.horizontal, Tokens.Spacing.sm + 2)
        .padding(.bottom, Tokens.Spacing.xs + 2)
        // YT-0045: claim hit-testing across the entire MiniPlayer footprint.
        // Without an explicit content shape the iOS 26 Liquid Glass tab bar
        // accepts touches that pass through the surrounding padding, which
        // caused taps near the card to switch tabs unexpectedly. The inner
        // `Button(action: onExpand)` still owns the card-tap behavior.
        .contentShape(Rectangle())
    }

    // MARK: Card

    private var card: some View {
        Button(action: onExpand) {
            VStack(spacing: 0) {
                content
                progressBar
            }
            .background(.regularMaterial, in: RoundedRectangle(cornerRadius: Tokens.Radius.lg, style: Tokens.cornerStyle))
            .overlay(
                RoundedRectangle(cornerRadius: Tokens.Radius.lg, style: Tokens.cornerStyle)
                    .strokeBorder(Color.primary.opacity(0.08), lineWidth: 0.5)
            )
        }
        .buttonStyle(.plain)
        .shadow(color: .black.opacity(0.3), radius: 12, x: 0, y: 4)
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Now playing: \(track.title) by \(track.channel)")
        // YT-0055: VoiceOver announces "Loading <track title>" during the
        // resolve window. `accessibilityValue` is read after the label, so
        // the user hears "Now playing: <title> by <channel>, Loading <title>".
        // When playback resumes the value returns to empty and the
        // announcement falls back to the label alone.
        .accessibilityValue(isLoading ? "Loading \(track.title)" : "")
        .accessibilityHint("Double-tap to open full player")
    }

    private var content: some View {
        HStack(spacing: Tokens.Spacing.sm + 2) {
            thumbnail

            VStack(alignment: .leading, spacing: 2) {
                Text(track.title)
                    .font(Theme.bodyLarge)
                    .fontWeight(.medium)
                    .foregroundStyle(Theme.onBackground)
                    .lineLimit(1)
                Text(track.channel)
                    .font(Theme.bodyMedium)
                    .foregroundStyle(Theme.onBackgroundSecondary)
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            transportButtons
        }
        .padding(.horizontal, Tokens.Spacing.md - 4)
        .padding(.top, Tokens.Spacing.sm + 2)
        .padding(.bottom, Tokens.Spacing.sm)
    }

    @ViewBuilder
    private var thumbnail: some View {
        let base = ZStack {
            RoundedRectangle(cornerRadius: Tokens.Radius.sm + 1, style: Tokens.cornerStyle)
                .fill(Theme.accent.opacity(0.15))
                .frame(width: Tokens.Thumbnail.mini, height: Tokens.Thumbnail.mini)
            Image(systemName: "music.note")
                .imageScale(.medium)
                .foregroundStyle(Theme.accent)
        }
        // YT-0027 Q1: hero source for the artwork transition. Applied only
        // when a namespace is supplied (production code from `ContentView`)
        // so the standalone preview keeps working without a namespace.
        if let ns = nowPlayingNamespace {
            base.matchedGeometryEffect(id: NowPlayingHero.artworkID, in: ns)
        } else {
            base
        }
    }

    private var transportButtons: some View {
        HStack(spacing: 0) {
            Button(action: onTogglePlayPause) {
                ZStack {
                    // YT-0055: swap the play/pause glyph for a circular
                    // ProgressView while the engine is resolving the stream.
                    // Tapping the button still forwards to
                    // `PlayerCoordinator.togglePlayPause()` which, per the
                    // existing `.loading` branch (YT-0049), cancels the in-
                    // flight resolve and parks state in `.paused`.
                    if isLoading {
                        ProgressView()
                            .progressViewStyle(.circular)
                            .controlSize(.small)
                            .tint(Theme.onBackground)
                            .accessibilityHidden(true)
                    } else {
                        Image(systemName: isPlaying ? "pause.fill" : "play.fill")
                            .imageScale(.large)
                            .foregroundStyle(Theme.onBackground)
                    }
                }
                .frame(width: Tokens.HitTarget.minimum, height: Tokens.HitTarget.minimum)
            }
            // YT-0055 reviewer follow-up (AC#7): while loading, override the
            // accessibility label so VoiceOver matches the visible state
            // (spinner) instead of announcing "Pause". Tap is preserved
            // because the coordinator's `.loading` branch (YT-0049) cancels
            // the in-flight resolve and parks state at `.paused` — the
            // documented tap-while-loading semantics.
            .accessibilityLabel(isLoading ? "Loading, double-tap to cancel" : (isPlaying ? "Pause" : "Play"))

            Button(action: onSkipForward) {
                Image(systemName: "forward.fill")
                    .imageScale(.large)
                    .foregroundStyle(Theme.onBackground)
                    .frame(width: Tokens.HitTarget.minimum, height: Tokens.HitTarget.minimum)
            }
            .accessibilityLabel("Skip forward")
        }
        .buttonStyle(.plain)
    }

    private var progressBar: some View {
        GeometryReader { geo in
            ZStack(alignment: .leading) {
                Rectangle()
                    .fill(Theme.surfaceVariant)
                    .frame(height: 2.5)
                Rectangle()
                    .fill(Theme.accent)
                    .frame(width: geo.size.width * CGFloat(progress), height: 2.5)
                    .animation(.linear(duration: 1), value: progress)
            }
        }
        .frame(height: 2.5)
        .clipShape(RoundedRectangle(cornerRadius: 1.5, style: Tokens.cornerStyle))
        .accessibilityHidden(true)
    }
}

// MARK: - Preview

#Preview("MiniPlayer") {
    let track = Track(
        videoId: "abc",
        title: "lofi hip hop radio – beats to relax/study to",
        channel: "Lofi Girl",
        durationSec: 3600,
        thumbnailUrl: ""
    )

    VStack {
        Spacer()
        MiniPlayer(track: track, isPlaying: true, progress: 0.35)
    }
    .background(Theme.background)
}
