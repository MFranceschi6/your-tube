import Foundation
import SwiftData

// MARK: - LibraryViewModel

/// Owns the Library screen's async data loading state for playlist list
/// and exposes it as `ListUiState<[PlaylistEntity]>`.
///
/// Library is local-first (SwiftData). Loading is instantaneous in the happy
/// path. A `.loading` flash is intentional on cold launch or after cache-clear
/// to match catalog cell C6. An error state (C8) surfaces on store-level
/// failures (corruption, migration).
///
/// State contract (from `design-system/handoff/state-catalog/README.md`):
/// - `loading → content` on success with ≥1 playlists
/// - `loading → empty` on success with 0 playlists
/// - `loading → error` on store failure
/// - `error → loading` on retry
/// - `content → loading` on refresh (keeps stale content during re-fetch — NOT
///   a full skeleton swap per the status contract)
@Observable
@MainActor
final class LibraryViewModel {

    // MARK: Observed state

    private(set) var uiState: ListUiState<[PlaylistEntity]> = .loading

    // MARK: Dependencies

    private let context: ModelContext

    // MARK: Init

    init(context: ModelContext) {
        self.context = context
    }

    // MARK: Actions

    /// Loads playlists from the store. Transitions through loading → content/empty/error.
    func load() {
        uiState = .loading
        do {
            let descriptor = FetchDescriptor<PlaylistEntity>(
                sortBy: [SortDescriptor(\.sortIndex)]
            )
            let playlists = try context.fetch(descriptor)
            uiState = playlists.isEmpty ? .empty : .content(playlists)
        } catch {
            uiState = .error(cause: error)
        }
    }

    /// Re-runs `load()` — used by the error-state retry button.
    func retry() {
        load()
    }
}
