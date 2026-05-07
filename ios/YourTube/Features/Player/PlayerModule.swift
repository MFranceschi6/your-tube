import Foundation

// MARK: - PlayerModule

/// Composition root for the Player feature. Owns the wiring between the
/// observable ``PlayerCoordinator``, the ``AudioEngineProtocol``
/// implementation, and the ``YouTubeServiceProtocol`` used for stream URL
/// resolution.
///
/// Kept as a small factory rather than a singleton so previews and tests can
/// substitute their own engines and services.
enum PlayerModule {

    /// Builds the production coordinator using ``AVPlayerAudioEngine`` and
    /// ``LiveYouTubeService``. Reads `AudioQuality` from `UserDefaults` via
    /// the default ``PlayerCoordinator/defaultQualityProvider``.
    ///
    /// Wires up YT-0027 Q10 remote-command callbacks so lock-screen toggles
    /// flow back into the in-app shuffle / repeat / next / previous state.
    @MainActor
    static func makeCoordinator() -> PlayerCoordinator {
        let engine = AVPlayerAudioEngine()
        let coordinator = PlayerCoordinator(
            audioEngine: engine,
            youtubeService: LiveYouTubeService()
        )
        engine.onRemoteShuffleChange = { [weak coordinator] enabled in
            coordinator?.setShuffleEnabled(enabled)
        }
        engine.onRemoteRepeatChange = { [weak coordinator] mode in
            coordinator?.setRepeatMode(mode)
        }
        engine.onRemoteSkipNext = { [weak coordinator] in
            coordinator?.next()
        }
        engine.onRemoteSkipPrevious = { [weak coordinator] in
            coordinator?.previous()
        }
        // YT-0070: AVPlayerItem `.failed` propagates into the coordinator's
        // `.error(message:)` state so the MiniPlayer / NowPlaying error
        // banner can render. Without this hook AVPlayer parks on
        // `AVPlayerWaitingWhileEvaluatingBufferingRateReason` indefinitely
        // when the resolved stream URL returns a CDN 403, and the user
        // sees a silent broken state.
        engine.onItemFailure = { [weak coordinator] error in
            // Use the localized description when available — `CFNetwork`/
            // `CoreMedia` produce user-readable strings (e.g. "HTTP 403:
            // Forbidden"). Fall back to a generic message otherwise. We
            // never log or surface the stream URL itself per
            // `.claude/rules/security.md`.
            let message = (error as NSError?)?.localizedDescription
                ?? "Playback failed."
            coordinator?.reportError(message)
        }
        return coordinator
    }
}
