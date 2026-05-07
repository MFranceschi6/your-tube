import Foundation
import SwiftData

// MARK: - HistoryViewModel

/// Owns History screen loading state exposed as `ListUiState<[HistoryEntryEntity]>`.
///
/// History is local-first (SwiftData). The `.loading` flash (C12) fires on
/// initial load only. Error (C14) surfaces on store-level failures.
/// Retry re-fetches the history list.
///
/// AC9: refresh-while-content keeps stale data + shows subtle inline indicator
/// rather than replacing with a skeleton.
@Observable
@MainActor
final class HistoryViewModel {

    // MARK: Observed state

    private(set) var uiState: ListUiState<[HistoryEntryEntity]> = .loading

    // MARK: Dependencies

    private let context: ModelContext

    // MARK: Init

    init(context: ModelContext) {
        self.context = context
    }

    // MARK: Actions

    func load() {
        uiState = .loading
        do {
            let descriptor = FetchDescriptor<HistoryEntryEntity>(
                sortBy: [SortDescriptor(\.playedAt, order: .reverse)]
            )
            let entries = try context.fetch(descriptor)
            uiState = entries.isEmpty ? .empty : .content(entries)
        } catch {
            uiState = .error(cause: error)
        }
    }

    func retry() {
        load()
    }
}
