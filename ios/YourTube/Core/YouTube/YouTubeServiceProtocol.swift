import Foundation

// MARK: - Audio quality

/// Requested audio quality for stream resolution.
/// Cases are ordered: lower raw values map to lower bitrates.
enum AudioQuality: Int, Comparable, Sendable {
    /// ~48 kbps — lowest quality, lowest data usage.
    case low = 48
    /// ~128 kbps — standard quality.
    case medium = 128
    /// ~160–256 kbps — high quality.
    case high = 256

    static func < (lhs: AudioQuality, rhs: AudioQuality) -> Bool {
        lhs.rawValue < rhs.rawValue
    }
}

// MARK: - Resolved stream

/// The resolved URL for a playable audio stream, annotated with stream type
/// and an optional ``HLSProxyLoader`` when YT-0157's HLS proxy is in front of
/// a long fragmented-mp4 audio track.
struct ResolvedStream: Sendable {
    /// URL the engine hands to AVPlayer. Either the direct googlevideo URL
    /// (muxed fallback / HLS livestream / short audio-only paths) or the
    /// `yt-prefetch://yt/playlist.m3u8` URL the proxy synthesises for long
    /// fragmented-mp4 audio. Do NOT cache or log — direct URLs carry
    /// short-lived signatures.
    let url: URL
    /// Whether the stream is audio-only (DASH adaptive) or muxed (progressive audio+video).
    let isMuxedFallback: Bool
    /// `AVAssetResourceLoaderDelegate` the engine MUST retain for the lifetime
    /// of the resulting `AVPlayerItem`. Non-nil exactly when ``url`` is a
    /// proxy playlist URL. `HLSProxyLoader` conforms to `@unchecked Sendable`
    /// so this struct stays `Sendable` for cross-actor hops in
    /// `PlayerCoordinator`.
    let proxyLoader: HLSProxyLoader?

    init(url: URL, isMuxedFallback: Bool, proxyLoader: HLSProxyLoader? = nil) {
        self.url = url
        self.isMuxedFallback = isMuxedFallback
        self.proxyLoader = proxyLoader
    }
}

// MARK: - YouTubeServiceProtocol

/// Service protocol for YouTube search and stream extraction.
/// All methods are async and throw; keep conformances fakeable in tests.
protocol YouTubeServiceProtocol: Sendable {
    /// Searches YouTube for the given query and returns up to `maxResults` video results.
    func search(query: String, maxResults: Int) async throws -> [SearchResult]

    /// Resolves a playable audio stream URL for the given video.
    ///
    /// Resolution prefers audio-only streams at or below `quality`. If none are found
    /// it falls back to the best available muxed (progressive) stream.
    /// - Throws: `YouTubeServiceError.noStreamFound` when neither audio-only nor muxed streams are available.
    func resolveStreamURL(videoId: String, quality: AudioQuality) async throws -> ResolvedStream
}
