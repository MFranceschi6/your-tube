import Foundation

// MARK: - ListUiState

/// Canonical list UI state machine shared by every list-driven screen.
///
/// The status contract (from `design-system/handoff/state-catalog/README.md`):
/// - Exactly one state is active at any time. Never two at once.
/// - `content → loading` (refresh) must keep stale data visible — callers do
///   this by NOT setting `.loading` when content already exists; they show an
///   inline indicator instead.
/// - `error → loading` on retry.
/// - `empty → loading` on refetch.
///
/// `T` is the collection type — e.g. `[SearchResult]`, `[PlaylistEntity]`.
enum ListUiState<T: Equatable>: Equatable {
    /// Initial load in flight. Show skeleton rows.
    case loading
    /// Load succeeded, result is empty.
    case empty
    /// Load succeeded, result is non-empty.
    case content(T)
    /// Load failed. `cause` drives copy selection; not shown to user directly.
    case error(cause: Error)

    static func == (lhs: ListUiState<T>, rhs: ListUiState<T>) -> Bool {
        switch (lhs, rhs) {
        case (.loading, .loading): return true
        case (.empty, .empty): return true
        case (.content(let a), .content(let b)): return a == b
        case (.error, .error): return true
        default: return false
        }
    }
}

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
