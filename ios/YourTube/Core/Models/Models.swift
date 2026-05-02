import Foundation

// MARK: - SearchResult

/// Transient DTO for a single video search result. Never persisted.
struct SearchResult: Codable, Equatable {
    /// YouTube video identifier, e.g. "dQw4w9WgXcQ".
    let videoId: String
    let title: String
    let channel: String
    /// Duration in whole seconds. 0 when unknown (live stream).
    let durationSec: Int
    /// HTTPS thumbnail URL; prefer mqdefault quality.
    let thumbnailUrl: String
}

// MARK: - Track

/// A resolved, playable item. Persisted in playlists, queue, and history.
/// Intentionally identical to `SearchResult` today; will diverge (e.g. addedAt, lastPlayedAt).
struct Track: Codable, Equatable {
    let videoId: String
    let title: String
    let channel: String
    /// Duration in whole seconds. 0 when unknown (live stream).
    let durationSec: Int
    /// HTTPS thumbnail URL; prefer mqdefault quality.
    let thumbnailUrl: String
}

// MARK: - PlaylistPayload

/// Export/import DTO for a playlist file (.ytplaylist.json).
/// This is a pure data container; persistence types live in Core/Persistence/.
struct PlaylistPayload: Codable, Equatable {
    /// Bump on any breaking change to the export shape. Current supported version: 1.
    let schemaVersion: Int
    /// Stable UUID string, preserved across export/import for upsert matching.
    let id: String
    let name: String
    let createdAt: Date
    let updatedAt: Date
    let tracks: [Track]
}
