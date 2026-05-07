import Foundation
import SwiftData

// MARK: - HistoryStore

/// Persistence service for recently played track history.
///
/// ``append(_:playedAt:)`` upserts by ``Track/videoId``: replaying a track that
/// already exists in history bumps its `playedAt` instead of inserting a new
/// row. The list therefore renders one entry per unique track, sorted by
/// most-recent play (the Apple Music / Spotify "Recently Played" pattern).
/// Callers still call ``trim(to:)`` to bound the table — each entry now
/// represents a unique track rather than a single play event.
///
/// Pass an in-memory `ModelContainer` in tests:
/// ```swift
/// let container = try ModelContainer(
///     for: Schema(PersistenceSchema.models),
///     configurations: ModelConfiguration(isStoredInMemoryOnly: true)
/// )
/// let store = HistoryStore(context: container.mainContext)
/// ```
struct HistoryStore {

    // MARK: Dependencies

    private let context: ModelContext

    // MARK: Init

    init(context: ModelContext) {
        self.context = context
    }

    // MARK: - Write

    /// Record a play event for `track` at `playedAt`, upserting by
    /// ``Track/videoId``.
    ///
    /// If an entry already exists for `track.videoId`, its `playedAt` is
    /// refreshed (and its title / channel / thumbnail snapshots are updated to
    /// reflect any metadata drift since the previous play). Otherwise a new
    /// entry is inserted. Saves immediately per the iOS rules for critical
    /// writes.
    @discardableResult
    func append(_ track: Track, playedAt: Date = .now) throws -> HistoryEntryEntity {
        let videoId = track.videoId
        var descriptor = FetchDescriptor<HistoryEntryEntity>(
            predicate: #Predicate { $0.videoId == videoId }
        )
        descriptor.fetchLimit = 1

        if let existing = try context.fetch(descriptor).first {
            existing.playedAt = playedAt
            existing.title = track.title
            existing.channel = track.channel
            existing.durationSec = track.durationSec
            existing.thumbnailUrl = track.thumbnailUrl
            try context.save()
            return existing
        }

        let entry = HistoryEntryEntity(track: track, playedAt: playedAt)
        context.insert(entry)
        try context.save()
        return entry
    }

    // MARK: - Read

    /// Fetch the most recent `limit` history entries, newest first.
    ///
    /// - Parameter limit: Maximum number of entries to return. `nil` returns all.
    func fetch(limit: Int? = nil) throws -> [HistoryEntryEntity] {
        var descriptor = FetchDescriptor<HistoryEntryEntity>(
            sortBy: [SortDescriptor(\.playedAt, order: .reverse)]
        )
        descriptor.fetchLimit = limit
        return try context.fetch(descriptor)
    }

    // MARK: - Trim

    /// Delete the oldest entries so that at most `maxCount` remain.
    ///
    /// No-op if the current count is already within the limit.
    func trim(to maxCount: Int) throws {
        let all = try fetch()
        guard all.count > maxCount else { return }
        // `fetch` returns newest-first, so drop the head and delete the tail.
        let toDelete = all.dropFirst(maxCount)
        for entry in toDelete {
            context.delete(entry)
        }
        try context.save()
    }

    // MARK: - Clear

    /// Delete all history entries.
    func clearAll() throws {
        try context.delete(model: HistoryEntryEntity.self)
        try context.save()
    }
}
