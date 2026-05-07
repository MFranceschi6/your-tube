import Foundation

// MARK: - PlayerState

/// High-level transport state surfaced to UI.
///
/// Kept separate from `AudioEngine.isPlaying` because the coordinator needs to
/// distinguish "loading the next item" / "buffering" / "stopped on error" from
/// the raw transport bool.
enum PlayerState: Equatable, Sendable {
    /// No track loaded; queue may or may not be empty.
    case idle
    /// Stream URL resolution or initial buffer is in flight.
    case loading
    /// Audio is actively playing.
    case playing
    /// Track is loaded but paused.
    case paused
    /// Engine is stalled while refilling its buffer.
    case buffering
    /// Last operation failed; `message` is user-safe.
    case error(message: String)
}
