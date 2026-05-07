import Foundation
import SwiftData

// MARK: - PlaylistEntity

/// Persisted playlist record. Owns an ordered set of ``PlaylistTrackEntity`` join rows.
///
/// - `id` is the canonical UUID string from the export/import contract (see `PlaylistPayload.id`).
///   Last-write-wins upsert uses `updatedAt` to resolve conflicts.
/// - Do not add business logic here — put it in ``PlaylistStore``.
@Model
final class PlaylistEntity {

    // MARK: Identity & metadata

    /// Stable UUID string, preserved across export/import (matches `PlaylistPayload.id`).
    @Attribute(.unique) var id: String
    var name: String
    var createdAt: Date
    var updatedAt: Date
    /// Zero-based ordering index used by `@Query(sort: \PlaylistEntity.sortIndex)` in Library views.
    /// Set to the count of existing playlists at creation time so new playlists appear at the end.
    var sortIndex: Int

    // MARK: Relationship

    /// Ordered join rows. Cascade-deleted when this playlist is deleted.
    @Relationship(deleteRule: .cascade, inverse: \PlaylistTrackEntity.playlist)
    var trackPositions: [PlaylistTrackEntity] = []

    // MARK: Init

    init(id: String, name: String, createdAt: Date, updatedAt: Date, sortIndex: Int = 0) {
        self.id = id
        self.name = name
        self.createdAt = createdAt
        self.updatedAt = updatedAt
        self.sortIndex = sortIndex
    }
}

// MARK: - Convenience

extension PlaylistEntity {
    /// Sorted track positions, ascending by `position`.
    var orderedPositions: [PlaylistTrackEntity] {
        trackPositions.sorted { $0.position < $1.position }
    }

    /// Ordered tracks, derived from `orderedPositions`.
    var orderedTracks: [TrackEntity] {
        orderedPositions.map(\.track)
    }
}
