import Foundation
import SwiftData

/// Empty placeholder schema. Real `@Model` types (Playlist, Track, History) added later.
enum PersistenceSchema {
    static let models: [any PersistentModel.Type] = []
}
