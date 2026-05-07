import Foundation

// MARK: - YouTubeServiceError

/// User-safe errors produced by `YouTubeServiceProtocol` implementations.
/// Never expose raw `YouTubeKitError` or network error details to UI consumers.
enum YouTubeServiceError: Error, Equatable, Sendable {
    /// The device had no network connectivity or the request timed out.
    case networkFailure
    /// The video is unavailable (deleted, private, age-restricted, region-blocked, etc.).
    case videoUnavailable
    /// No playable audio stream (or muxed fallback) could be found for the video.
    case noStreamFound
    /// The search request failed for a non-network reason (e.g. response parsing error).
    case searchFailed
}

extension YouTubeServiceError: LocalizedError {
    var errorDescription: String? {
        switch self {
        case .networkFailure:
            return "Unable to connect to YouTube. Check your internet connection."
        case .videoUnavailable:
            return "This video is unavailable."
        case .noStreamFound:
            return "No playable audio stream was found for this video."
        case .searchFailed:
            return "The search could not be completed. Please try again."
        }
    }
}
