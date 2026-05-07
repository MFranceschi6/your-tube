import Foundation
import SwiftData

// MARK: - PlaylistStoreError

/// Errors thrown by ``PlaylistStore`` operations.
enum PlaylistStoreError: Error, Equatable {
    /// No playlist found for the given identifier.
    case notFound(String)
    /// A track with the given `videoId` is already in the playlist.
    case duplicateTrack(String)
    /// The requested source or destination index is out of range.
    case indexOutOfRange
}

// MARK: - PlaylistStore

/// Persistence service for playlists and their ordered track lists.
///
/// Pass an in-memory `ModelContainer` in tests:
/// ```swift
/// let container = try ModelContainer(
///     for: Schema(PersistenceSchema.models),
///     configurations: ModelConfiguration(isStoredInMemoryOnly: true)
/// )
/// let store = PlaylistStore(context: container.mainContext)
/// ```
///
/// For critical writes the store calls `context.save()` explicitly rather than
/// relying solely on SwiftData autosave.
struct PlaylistStore {

    // MARK: Dependencies

    private let context: ModelContext

    // MARK: Init

    init(context: ModelContext) {
        self.context = context
    }

    // MARK: - Create

    /// Create a new empty playlist and persist it.
    ///
    /// - Parameters:
    ///   - name: Display name for the playlist.
    ///   - id: Optional stable UUID string. If `nil`, a new UUID is generated.
    ///   - now: Timestamp for `createdAt` / `updatedAt`. Defaults to `.now`.
    ///   - sortIndex: Position in the Library list. Pass the current playlist count so the new
    ///     playlist is appended at the end. Defaults to 0 for backwards compatibility.
    /// - Returns: The new persisted ``PlaylistEntity``.
    @discardableResult
    func create(name: String, id: String? = nil, now: Date = .now, sortIndex: Int = 0) throws -> PlaylistEntity {
        let entity = PlaylistEntity(
            id: id ?? UUID().uuidString,
            name: name,
            createdAt: now,
            updatedAt: now,
            sortIndex: sortIndex
        )
        context.insert(entity)
        try context.save()
        return entity
    }

    /// Renormalise `sortIndex` for all playlists after a reorder.
    ///
    /// Call this when the Library view's `.onMove` fires. The caller passes the
    /// already-moved array (after `Array.move(fromOffsets:toOffset:)`) and this
    /// method writes the correct sequential indices back to the store.
    func reorderPlaylists(_ reorderedPlaylists: [PlaylistEntity], now: Date = .now) throws {
        for (index, playlist) in reorderedPlaylists.enumerated() {
            playlist.sortIndex = index
            playlist.updatedAt = now
        }
        try context.save()
    }

    // MARK: - Read

    /// Fetch all playlists, newest first.
    func fetchAll() throws -> [PlaylistEntity] {
        var descriptor = FetchDescriptor<PlaylistEntity>(
            sortBy: [SortDescriptor(\.createdAt, order: .reverse)]
        )
        descriptor.relationshipKeyPathsForPrefetching = [\.trackPositions]
        return try context.fetch(descriptor)
    }

    /// Fetch a single playlist by its stable `id`.
    ///
    /// - Throws: ``PlaylistStoreError/notFound(_:)`` if the ID does not exist.
    func fetch(id: String) throws -> PlaylistEntity {
        let descriptor = FetchDescriptor<PlaylistEntity>(
            predicate: #Predicate { $0.id == id }
        )
        guard let entity = try context.fetch(descriptor).first else {
            throw PlaylistStoreError.notFound(id)
        }
        return entity
    }

    // MARK: - Rename

    /// Rename a playlist, updating `updatedAt`.
    ///
    /// - Throws: ``PlaylistStoreError/notFound(_:)`` if the ID does not exist.
    func rename(id: String, to newName: String, now: Date = .now) throws {
        let entity = try fetch(id: id)
        entity.name = newName
        entity.updatedAt = now
        try context.save()
    }

    // MARK: - Delete

    /// Delete a playlist and all its ``PlaylistTrackEntity`` join rows (cascade).
    ///
    /// Orphaned ``TrackEntity`` records are **not** deleted; they may be referenced
    /// by other playlists or history entries.
    ///
    /// - Throws: ``PlaylistStoreError/notFound(_:)`` if the ID does not exist.
    func delete(id: String) throws {
        let entity = try fetch(id: id)
        context.delete(entity)
        try context.save()
    }

    // MARK: - Track operations

    /// Append a track to the end of a playlist.
    ///
    /// If a ``TrackEntity`` with the same `videoId` already exists in the store it is
    /// reused (upsert); otherwise a new one is inserted.
    ///
    /// - Throws: ``PlaylistStoreError/duplicateTrack(_:)`` if the track is already in the playlist.
    func addTrack(_ track: Track, toPlaylistId playlistId: String, now: Date = .now) throws {
        let playlist = try fetch(id: playlistId)

        // Guard against duplicates within this playlist.
        let alreadyPresent = playlist.trackPositions.contains { $0.track.videoId == track.videoId }
        if alreadyPresent {
            throw PlaylistStoreError.duplicateTrack(track.videoId)
        }

        let trackEntity = try upsertTrack(track)
        let nextPosition = playlist.trackPositions.count
        let join = PlaylistTrackEntity(track: trackEntity, position: nextPosition)
        join.playlist = playlist
        context.insert(join)

        playlist.updatedAt = now
        try context.save()
    }

    /// Remove a track from a playlist by `videoId`, then renormalise positions.
    ///
    /// - Throws: ``PlaylistStoreError/notFound(_:)`` if the `videoId` is not in the playlist.
    func removeTrack(videoId: String, fromPlaylistId playlistId: String, now: Date = .now) throws {
        let playlist = try fetch(id: playlistId)

        guard let join = playlist.trackPositions.first(where: { $0.track.videoId == videoId }) else {
            throw PlaylistStoreError.notFound(videoId)
        }

        context.delete(join)
        playlist.updatedAt = now

        // Renormalise remaining positions.
        let remaining = playlist.trackPositions
            .filter { $0.track.videoId != videoId }
            .sorted { $0.position < $1.position }
        for (index, row) in remaining.enumerated() {
            row.position = index
        }

        try context.save()
    }

    /// Move a track from `fromIndex` to `toIndex` within a playlist (zero-based).
    ///
    /// Both indices must be within `0..<trackCount`. After the move, positions are
    /// renormalised so they are contiguous from 0.
    ///
    /// - Throws: ``PlaylistStoreError/indexOutOfRange`` if either index is out of bounds.
    func reorderTrack(
        inPlaylistId playlistId: String,
        fromIndex: Int,
        toIndex: Int,
        now: Date = .now
    ) throws {
        let playlist = try fetch(id: playlistId)
        var rows = playlist.orderedPositions

        guard fromIndex >= 0, fromIndex < rows.count,
              toIndex >= 0, toIndex < rows.count else {
            throw PlaylistStoreError.indexOutOfRange
        }

        guard fromIndex != toIndex else { return }

        let moved = rows.remove(at: fromIndex)
        rows.insert(moved, at: toIndex)

        // Renormalise.
        for (index, row) in rows.enumerated() {
            row.position = index
        }

        playlist.updatedAt = now
        try context.save()
    }

    // MARK: - Import upsert (last-write-wins on updatedAt)

    /// Import a ``PlaylistPayload``, upserting by `id` using last-write-wins on `updatedAt`.
    ///
    /// - If no playlist with the given `id` exists, a new one is created.
    /// - If one exists and `payload.updatedAt > existing.updatedAt`, the existing record
    ///   is overwritten: the playlist name and full track list are replaced.
    /// - If one exists and `payload.updatedAt <= existing.updatedAt`, the import is a no-op.
    ///
    /// - Returns: The upserted ``PlaylistEntity``.
    @discardableResult
    func importPlaylist(_ payload: PlaylistPayload) throws -> PlaylistEntity {
        if let existing = try? fetch(id: payload.id) {
            // Last-write-wins.
            guard payload.updatedAt > existing.updatedAt else {
                return existing
            }
            existing.name = payload.name
            existing.updatedAt = payload.updatedAt

            // Replace track list entirely.
            for join in existing.trackPositions {
                context.delete(join)
            }
            for (index, track) in payload.tracks.enumerated() {
                let entity = try upsertTrack(track)
                let join = PlaylistTrackEntity(track: entity, position: index)
                join.playlist = existing
                context.insert(join)
            }

            try context.save()
            return existing
        } else {
            // New playlist — append to end of Library list.
            let existingCount = (try? fetchAll())?.count ?? 0
            let playlist = PlaylistEntity(
                id: payload.id,
                name: payload.name,
                createdAt: payload.createdAt,
                updatedAt: payload.updatedAt,
                sortIndex: existingCount
            )
            context.insert(playlist)

            for (index, track) in payload.tracks.enumerated() {
                let entity = try upsertTrack(track)
                let join = PlaylistTrackEntity(track: entity, position: index)
                join.playlist = playlist
                context.insert(join)
            }

            try context.save()
            return playlist
        }
    }

    // MARK: - Private helpers

    /// Fetch an existing ``TrackEntity`` by `videoId` or insert a new one.
    private func upsertTrack(_ track: Track) throws -> TrackEntity {
        let vid = track.videoId
        let descriptor = FetchDescriptor<TrackEntity>(
            predicate: #Predicate { $0.videoId == vid }
        )
        if let existing = try context.fetch(descriptor).first {
            // Update metadata fields (title/thumbnail may change over time).
            existing.title = track.title
            existing.channel = track.channel
            existing.durationSec = track.durationSec
            existing.thumbnailUrl = track.thumbnailUrl
            return existing
        } else {
            let entity = TrackEntity(track: track)
            context.insert(entity)
            return entity
        }
    }
}
