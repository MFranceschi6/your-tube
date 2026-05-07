import SwiftUI

// MARK: - PlaylistCover

/// Hybrid cover for a playlist following the handoff Q1 rule:
/// 0 tracks → gradient + music.note glyph
/// 1 track  → single full-bleed placeholder (artwork loading deferred to post-MVP)
/// 2–3      → gradient + first letter of playlist name
/// ≥4       → 2×2 grid of first four track placeholders
///
/// The gradient is deterministic: same hue pair every launch for the same playlist UUID.
struct PlaylistCover: View {
    let playlist: PlaylistEntity

    var body: some View {
        let count = playlist.orderedTracks.count
        Group {
            switch count {
            case 0:
                GradientCover(seed: gradientSeed, glyph: "music.note")
            case 1:
                SingleArtCover(track: playlist.orderedTracks[0])
            case 2, 3:
                GradientCover(seed: gradientSeed, initial: playlist.name.first)
            default:
                FourUpCover(tracks: Array(playlist.orderedTracks.prefix(4)))
            }
        }
        .accessibilityLabel("Playlist cover")
        .accessibilityHidden(true)
    }

    private var gradientSeed: Int {
        Self.gradientSeed(for: playlist.id)
    }

    /// Deterministic djb2 hash of `id`. Exposed `internal static` so tests
    /// can exercise the production seed function directly instead of
    /// duplicating the hash body — preventing silent drift between the
    /// production cover gradient and its test fixture (YT-0037 nit).
    static func gradientSeed(for id: String) -> Int {
        var hash: UInt32 = 5381
        for byte in id.utf8 {
            hash = ((hash << 5) &+ hash) &+ UInt32(byte)
        }
        return Int(hash)
    }
}

// MARK: - GradientCover

struct GradientCover: View {
    let seed: Int
    var glyph: String? = nil
    var initial: Character? = nil

    var body: some View {
        let (c1, c2) = gradientColors
        LinearGradient(colors: [c1, c2], startPoint: .topLeading, endPoint: .bottomTrailing)
            .overlay {
                if let g = glyph {
                    Image(systemName: g)
                        .font(.system(size: 22, weight: .medium))
                        .foregroundStyle(.white.opacity(0.28))
                } else if let i = initial {
                    Text(String(i).uppercased())
                        .font(.system(size: 28, weight: .bold, design: .rounded))
                        .foregroundStyle(.white.opacity(0.30))
                }
            }
    }

    // Deterministic hue pair from seed, approximating oklch lightness/chroma bands.
    // Color(hue:saturation:brightness:) is the iOS 17-compatible substitute for oklch.
    private var gradientColors: (Color, Color) {
        let h1 = Double(abs(seed) % 360) / 360.0
        let h2 = (h1 + 40.0 / 360.0).truncatingRemainder(dividingBy: 1.0)
        return (
            Color(hue: h1, saturation: 0.55, brightness: 0.45),
            Color(hue: h2, saturation: 0.50, brightness: 0.30)
        )
    }
}

// MARK: - SingleArtCover

private struct SingleArtCover: View {
    let track: TrackEntity

    var body: some View {
        // Async artwork loading is deferred to post-MVP; show a styled placeholder.
        Rectangle()
            .fill(Color(.secondarySystemBackground))
            .overlay(
                Image(systemName: "music.note")
                    .font(.system(size: 22, weight: .medium))
                    .foregroundStyle(Color(.tertiaryLabel))
            )
    }
}

// MARK: - FourUpCover

private struct FourUpCover: View {
    let tracks: [TrackEntity]

    var body: some View {
        GeometryReader { geo in
            let half = geo.size.width / 2
            VStack(spacing: 1) {
                HStack(spacing: 1) {
                    cell(at: 0).frame(width: half, height: half)
                    cell(at: 1).frame(width: half, height: half)
                }
                HStack(spacing: 1) {
                    cell(at: 2).frame(width: half, height: half)
                    cell(at: 3).frame(width: half, height: half)
                }
            }
        }
    }

    private func cell(at index: Int) -> some View {
        Rectangle()
            .fill(Color(.secondarySystemBackground))
            .overlay(
                Image(systemName: "music.note")
                    .imageScale(.small)
                    .foregroundStyle(Color(.tertiaryLabel))
            )
    }
}
