import Foundation
import AVFoundation
import MediaPlayer

// MARK: - AudioEngineProtocol

/// Full interface for the audio playback engine.
///
/// Implementations are `@MainActor` because `AVPlayer`,
/// `MPNowPlayingInfoCenter`, and `MPRemoteCommandCenter` are main-thread-bound.
/// Queue ownership and URL resolution live in YT-0024; this layer handles
/// raw transport (play/pause/seek) and now-playing metadata.
@MainActor
protocol AudioEngineProtocol: AnyObject {

    // MARK: State

    var currentTrack: Track? { get }
    var isPlaying: Bool { get }
    var currentTime: TimeInterval { get }
    var duration: TimeInterval { get }

    // MARK: Transport

    /// Begin playback of `track`. When `url` is non-nil, the engine loads it as
    /// the current `AVPlayerItem` and starts playback. When `url` is `nil`, the
    /// engine updates transport / now-playing state without loading a new item
    /// — useful for tests and previews that exercise the state machine without
    /// hitting AVFoundation. Production wiring (YT-0044) always passes the
    /// resolved stream URL from ``YouTubeServiceProtocol/resolveStreamURL(videoId:quality:)``.
    ///
    /// `resourceLoader` is YT-0157's HLS proxy delegate — non-nil exactly when
    /// the resolver wrapped a long fragmented-mp4 audio URL with the proxy.
    /// The engine binds it to the asset's `resourceLoader` AND retains it for
    /// the lifetime of the resulting `AVPlayerItem`. Dropping the loader
    /// while the item is alive immediately fails the asset.
    func play(track: Track, url: URL?, resourceLoader: HLSProxyLoader?)

    /// Prime the engine for a new track BEFORE its stream URL has resolved.
    ///
    /// Stops the previously-playing audio, clears the underlying `AVPlayer`
    /// item, and resets observable state (`currentTrack`, `currentTime`,
    /// `duration`, `isPlaying`) to `track`'s metadata. Now-Playing info is
    /// updated immediately so the lock screen / Control Center reflect the
    /// new track during the resolve window. Does not load any new media —
    /// the actual stream load still happens in ``play(track:url:)`` once
    /// ``YouTubeServiceProtocol/resolveStreamURL(videoId:quality:)`` returns.
    ///
    /// Called by `PlayerCoordinator` the moment the user selects a new
    /// track so that the previous audio cuts out instantly and the
    /// scrubber resets to 0:00 (YT-0049), and so Now Playing / MiniPlayer
    /// never render the previous track's duration against the new track's
    /// title (YT-0046 Bug B).
    func prepare(track: Track)
    func pause()
    func resume()
    func togglePlayPause()
    func seek(to time: TimeInterval)

    /// Stub — queue management is owned by YT-0024.
    func skipNext()
    /// Stub — queue management is owned by YT-0024.
    func skipPrevious()

    func stop()

    // MARK: Now Playing parity (YT-0027 Q10)
    //
    // Engines that integrate with `MPRemoteCommandCenter` mirror the shuffle
    // / repeat *state* so the lock screen and Control Center stay in sync
    // with the in-app UI. The default implementations are no-ops so test
    // fakes can opt out without ceremony.

    /// Mirror the shuffle indicator to the system Now Playing surfaces.
    func setShuffleMode(_ enabled: Bool)

    /// Mirror the repeat mode to the system Now Playing surfaces.
    func setRepeatMode(_ mode: RepeatMode)
}

// MARK: - Default no-op parity hooks

extension AudioEngineProtocol {
    func setShuffleMode(_ enabled: Bool) {}
    func setRepeatMode(_ mode: RepeatMode) {}

    /// Convenience that forwards to ``play(track:url:resourceLoader:)`` with
    /// `url: nil` and no resource loader so tests and previews that only need
    /// transport-state mutation can keep calling `play(track:)`. Production
    /// callers (`PlayerCoordinator`) always supply both the resolved stream
    /// URL and (for proxied audio-only streams) the loader.
    func play(track: Track) {
        play(track: track, url: nil, resourceLoader: nil)
    }

    /// Backwards-compatible convenience for call sites that don't carry a
    /// resource loader. Forwards to the full signature.
    func play(track: Track, url: URL?) {
        play(track: track, url: url, resourceLoader: nil)
    }
}
