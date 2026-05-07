import Foundation
import SwiftUI

// MARK: - SearchScreenState

/// Discrete view states for the Search screen, mirroring the design mockup
/// (idle / loading / results / empty / error). Kept as a value type so
/// SwiftUI diffs cleanly and tests can `#expect` exact transitions.
enum SearchScreenState: Equatable {
    /// No active query — show idle suggestions (C2).
    case idle
    /// Search is in flight (C1).
    case loading
    /// Results returned — `[SearchResult]` is non-empty.
    case results([SearchResult])
    /// Search returned no results for the current query (C3).
    case empty(query: String)
    /// Search failed; `message` is user-safe. (C4 generic / C5 offline — see `isOffline`)
    case error(message: String)
}

// MARK: - SearchViewModel

/// Owns the Search screen's query string, in-flight search task, and state
/// machine. Networking is delegated to an injected ``YouTubeServiceProtocol``
/// so the model is fully testable with hand-written fakes.
///
/// The model stays platform-agnostic — it does not import SwiftData and never
/// touches the `PlayerCoordinator` directly. Playback and queue mutations are
/// surfaced as callbacks so the SwiftUI shell decides how to wire them
/// (typically through ``AppShellViewModel``).
///
/// Cancellation: a submit cancels any in-flight task before kicking off the
/// next one. Clearing the query also cancels and returns to `.idle`.
@Observable
@MainActor
final class SearchViewModel {

    // MARK: Inputs

    /// Bound to the search field. Mutating the query alone does not start a
    /// search — a search is only kicked off by ``submit(_:)``.
    var query: String = ""

    // MARK: Observed state

    /// Current view state. Always reflects the most recent completed (or
    /// in-flight) search call.
    private(set) var state: SearchScreenState = .idle

    /// `true` when the most recent error was caused by an offline/unreachable
    /// condition. Drives C5 vs. C4 view routing in `SearchScreen`.
    /// Reset to `false` on each new submit.
    private(set) var isOffline: Bool = false

    // MARK: Dependencies

    private let youtubeService: any YouTubeServiceProtocol
    private let maxResults: Int

    /// Tracks the most recent search; cancelled when superseded so stale
    /// responses cannot overwrite newer state.
    private var searchTask: Task<Void, Never>?

    /// Last submitted (non-empty) query. Used by the error-state retry button
    /// so the user does not have to retype.
    private(set) var lastQuery: String?

    // MARK: Init

    init(
        youtubeService: any YouTubeServiceProtocol,
        maxResults: Int = 20
    ) {
        self.youtubeService = youtubeService
        self.maxResults = maxResults
    }

    // MARK: Actions

    /// Submit the current `query` (or an explicit `query`) and run the search.
    ///
    /// Whitespace-only queries are treated as `clear()`. Subsequent submits
    /// cancel any in-flight search before starting a new one.
    func submit(_ override: String? = nil) {
        let raw = override ?? query
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)

        if let override { query = override }

        guard !trimmed.isEmpty else {
            clear()
            return
        }

        lastQuery = trimmed
        isOffline = false
        state = .loading

        searchTask?.cancel()
        let service = youtubeService
        let limit = maxResults

        searchTask = Task { @MainActor [weak self] in
            do {
                let results = try await service.search(query: trimmed, maxResults: limit)
                if Task.isCancelled { return }
                guard let self else { return }
                self.isOffline = false
                if results.isEmpty {
                    self.state = .empty(query: trimmed)
                } else {
                    self.state = .results(results)
                }
            } catch is CancellationError {
                return
            } catch let error as YouTubeServiceError {
                if Task.isCancelled { return }
                guard let self else { return }
                // C5: offline when the error indicates no connectivity.
                self.isOffline = (error == .networkFailure)
                self.state = .error(message: error.errorDescription ?? "Search failed.")
            } catch {
                if Task.isCancelled { return }
                self?.isOffline = false
                self?.state = .error(message: "Search failed.")
            }
        }
    }

    /// Re-runs the most recent submitted query. Used by the error-state retry
    /// affordance. No-op when there is no remembered query.
    func retry() {
        guard let lastQuery else { return }
        submit(lastQuery)
    }

    /// Clears the query, cancels any in-flight search, and returns to idle.
    func clear() {
        searchTask?.cancel()
        query = ""
        lastQuery = nil
        isOffline = false
        state = .idle
    }

    // MARK: Mapping

    /// Maps a `SearchResult` to a playable ``Track``. Centralised so playback
    /// and queue paths stay consistent and the mapping has a single test
    /// surface.
    ///
    /// `nonisolated` because the mapping is pure — no actor state is touched —
    /// so callers off the main actor (and synchronous test contexts) can
    /// invoke it without an `await`.
    nonisolated static func track(from result: SearchResult) -> Track {
        Track(
            videoId: result.videoId,
            title: result.title,
            channel: result.channel,
            durationSec: result.durationSec,
            thumbnailUrl: result.thumbnailUrl
        )
    }
}
