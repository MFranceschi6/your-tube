import Foundation
import SwiftData

/// Central registry of all SwiftData `@Model` types for this app.
///
/// Pass `PersistenceSchema.models` to `Schema(...)` when constructing the `ModelContainer`
/// at the app root. For tests, use `ModelContainer.inMemory(for:)` instead.
enum PersistenceSchema {
    static let models: [any PersistentModel.Type] = [
        PlaylistEntity.self,
        TrackEntity.self,
        PlaylistTrackEntity.self,
        HistoryEntryEntity.self,
    ]
}
