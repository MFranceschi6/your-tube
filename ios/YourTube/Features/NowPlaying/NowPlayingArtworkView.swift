import SwiftUI

// MARK: - NowPlayingArtworkView

/// Square Now Playing artwork with the YT-0027 Q2 / Q4 / Q9 motion contract and
/// YT-0167 transition corner-radius override:
///
/// - Resting playing state: `scale 1.0`, corner radius `12pt`.
/// - Paused: `scale 0.85`, corner radius `20pt` (per Q2).
/// - Scrubbing: `scale 0.92` (per Q4 — less than the pause shrink).
/// - Spring: `.spring(response: 0.45, dampingFraction: 0.78)`.
/// - Reduce Motion (Q9): pause-scale degrades to instant; radius stays.
///
/// **YT-0167 override**: when `transitionCornerRadius` is non-nil, it replaces
/// the play/pause-driven radius during the expand/collapse morph. The caller
/// (NowPlayingView) drives this from `progress` 0→1, giving a smooth 4→12 pt
/// interpolation tied to the single `progress` state rather than a separate
/// `withAnimation` block.
///
/// **Artwork failure** (AC6 / spec §6): `AsyncImage` failure phase renders
/// `Color.secondary.opacity(0.3)` + centered `music.note` at 40% secondary —
/// the placeholder is animated in the hero transition; cross-fade to resolved
/// bitmap over 160 ms when it arrives.
///
/// `matchedGeometryEffect` source ownership is driven from the parent so
/// the same artwork hero-transitions out of the MiniPlayer.
struct NowPlayingArtworkView: View {
    let track: Track
    let isPlaying: Bool
    let isScrubbing: Bool
    let namespace: Namespace.ID

    /// YT-0167: when set, overrides the play/pause-driven `radius` during
    /// the expand/collapse transition. Nil = use play/pause logic (settled state).
    var transitionCornerRadius: CGFloat? = nil

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private var scale: CGFloat {
        if isScrubbing { return 0.92 }
        return isPlaying ? 1.0 : 0.85
    }

    /// Effective corner radius: transition override takes priority when active.
    private var radius: CGFloat {
        if let r = transitionCornerRadius { return r }
        return isPlaying && !isScrubbing ? 12 : 20
    }

    var body: some View {
        AsyncImage(url: URL(string: track.thumbnailUrl)) { phase in
            switch phase {
            case .success(let image):
                image
                    .resizable()
                    .scaledToFill()
                    // AC6/spec §6: cross-fade resolved bitmap over 160 ms after settle.
                    .transition(.opacity.animation(.easeInOut(duration: 0.16)))
            default:
                artworkFailurePlaceholder
            }
        }
        .aspectRatio(1, contentMode: .fit)
        .clipShape(RoundedRectangle(cornerRadius: radius, style: Tokens.cornerStyle))
        .matchedGeometryEffect(id: NowPlayingHero.artworkID, in: namespace)
        .scaleEffect(scale, anchor: .center)
        .animation(NowPlayingAnimation.artworkScale(reduceMotion: reduceMotion), value: scale)
        // Radius animation: when transitionCornerRadius is set, the parent's
        // animation context governs; when nil (settled), use the design-system curve.
        .animation(transitionCornerRadius == nil ? NowPlayingAnimation.radiusCurve : nil, value: radius)
        .accessibilityLabel("Artwork for \(track.title)")
        .accessibilityHidden(false)
    }

    /// Placeholder per spec §6 and AC6: `Color.secondary.opacity(0.3)` fill
    /// + centered `music.note` at 40% secondary. Matches the MiniPlayer placeholder
    /// so the hero transition reads as a single object growing even on failure.
    private var artworkFailurePlaceholder: some View {
        ZStack {
            Color.secondary.opacity(0.3)
            Image(systemName: "music.note")
                .font(.system(size: 64, weight: .medium))
                .foregroundStyle(Color.secondary.opacity(0.4))
        }
    }
}

// MARK: - NowPlayingHero

/// Single source for the `matchedGeometryEffect` identifier so MiniPlayer
/// and Now Playing cannot drift. Defined here to keep YT-0025 (which holds
/// the namespace at shell scope) and YT-0027 in lockstep.
enum NowPlayingHero {
    static let artworkID: String = "yt-0027.artwork"
}
