import SwiftUI
import SwiftData
import UIKit

// MARK: - SearchScreen

/// Search tab screen. Owns the ``SearchViewModel`` for the lifetime of the
/// view; networking, submit handling, and state transitions live there. The
/// view only translates ``SearchScreenState`` into idiomatic SwiftUI subviews
/// and forwards playback intents to the shell.
///
/// State map per YT-0165 / YT-0073 catalog:
/// - `.idle` → C2: "Search YourTube" + suggestion chips
/// - `.loading` → C1: skeleton × 6 rows
/// - `.results` → content: grouped track list with row context menu
/// - `.empty` → C3: "No results for {query}" + "Clear search" action
/// - `.error(.offline)` → C5: offline variant with "Go to Library" secondary action
/// - `.error(.other)` → C4: generic error "Couldn't search"
struct SearchScreen: View {

    /// Currently playing track so result rows can highlight the active item.
    var currentTrack: Track?
    /// Invoked when the user taps a row or selects "Play" from the context menu.
    var onPlay: (Track) -> Void = { _ in }
    /// Invoked when the user selects "Add to Queue" from the row context menu.
    var onAddToQueue: (Track) -> Void = { _ in }
    /// Invoked when the user taps "Go to Library" in the C5 offline error state.
    var onGoToLibrary: (() -> Void)? = nil

    @State private var viewModel: SearchViewModel
    @State private var trackPendingPlaylistAdd: Track?

    init(
        currentTrack: Track? = nil,
        onPlay: @escaping (Track) -> Void = { _ in },
        onAddToQueue: @escaping (Track) -> Void = { _ in },
        onGoToLibrary: (() -> Void)? = nil,
        youtubeService: any YouTubeServiceProtocol = LiveYouTubeService()
    ) {
        self.currentTrack = currentTrack
        self.onPlay = onPlay
        self.onAddToQueue = onAddToQueue
        self.onGoToLibrary = onGoToLibrary
        _viewModel = State(initialValue: SearchViewModel(youtubeService: youtubeService))
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                searchBar
                    .padding(.horizontal, Tokens.Spacing.md)
                    .padding(.top, Tokens.Spacing.sm)
                    .padding(.bottom, Tokens.Spacing.md)

                stateContent
            }
        }
        .background(Theme.background)
        .navigationTitle("Search")
        .navigationBarTitleDisplayMode(.large)
        // Add-to-playlist sheet lives at the screen root so the sheet content
        // can read `\.modelContext` cleanly.
        .sheet(item: $trackPendingPlaylistAdd) { track in
            AddToPlaylistSheet(track: track) { trackPendingPlaylistAdd = nil }
        }
    }

    // MARK: Search bar

    private var searchBar: some View {
        SearchField(
            text: Binding(
                get: { viewModel.query },
                set: { viewModel.query = $0 }
            ),
            placeholder: "Search",
            onSubmit: { viewModel.submit() },
            onCancel: { viewModel.clear() }
        )
        .accessibilityIdentifier("search.field")
    }

    // MARK: State router

    @ViewBuilder
    private var stateContent: some View {
        switch viewModel.state {
        case .idle:
            suggestionsSection
                .accessibilityIdentifier("search.idle")
        case .loading:
            loadingSkeleton
                .accessibilityIdentifier("search.loading")
        case .results(let results):
            resultsList(results)
                .accessibilityIdentifier("search.results")
        case .empty(let query):
            emptyState(query: query)
                .accessibilityIdentifier("search.empty")
        case .error(let message):
            errorCard(message: message)
                .accessibilityIdentifier("search.error")
        }
    }

    // MARK: C2 — Search idle ("Search YourTube" + suggestion chips)

    /// Catalog chips per `empty.md` C2: "lofi", "focus", "ambient", "podcasts".
    private let catalogChips = ["lofi", "focus", "ambient", "podcasts"]

    private var suggestionsSection: some View {
        VStack(alignment: .leading, spacing: Tokens.Spacing.sm) {
            // C2 empty state content (no action button — search input IS the affordance)
            ContentUnavailableView {
                Label {
                    Text("Search YourTube")
                } icon: {
                    Image(systemName: "magnifyingglass")
                        .font(.system(size: 56))
                        .foregroundStyle(Color.secondary)
                        .accessibilityHidden(true)
                }
            } description: {
                Text("Find tracks, channels, and topics from your subscriptions.")
            }
            .accessibilityElement(children: .combine)
            .accessibilityLabel("Search YourTube. Find tracks, channels, and topics from your subscriptions.")

            // Suggestion chips (catalog-specified: lofi, focus, ambient, podcasts)
            Text("Suggestions")
                .font(.caption)
                .fontWeight(.semibold)
                .textCase(.uppercase)
                .tracking(1)
                .foregroundStyle(Theme.onBackgroundTertiary)
                .padding(.horizontal, Tokens.Spacing.md)

            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: Tokens.Spacing.sm) {
                    ForEach(catalogChips, id: \.self) { chip in
                        Button {
                            viewModel.submit(chip)
                        } label: {
                            Text(chip)
                                .font(Theme.bodyMedium)
                                .foregroundStyle(Theme.onBackground)
                                .padding(.horizontal, Tokens.Spacing.md)
                                .padding(.vertical, Tokens.Spacing.sm - 1)
                                .background(Theme.surface, in: Capsule())
                                .overlay(
                                    Capsule()
                                        .strokeBorder(Theme.separator, lineWidth: 0.5)
                                )
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel(chip)
                        .accessibilityHint("Search for \(chip)")
                    }
                }
                .padding(.horizontal, Tokens.Spacing.md)
            }
        }
    }

    // MARK: Loading — skeletons (C1: 6 skeleton track rows)

    private var loadingSkeleton: some View {
        VStack(spacing: 0) {
            ForEach(0..<6, id: \.self) { i in
                SkeletonRow(index: i)
                if i < 5 {
                    Divider()
                        .padding(.leading, Tokens.Thumbnail.row + Tokens.Spacing.md * 2)
                }
            }
        }
        .padding(.top, Tokens.Spacing.sm)
        // The list container carries accessibilityValue("Loading") per AC10.
        // Do NOT announce the loading transition.
        .accessibilityValue("Loading")
    }

    // MARK: Results

    private func resultsList(_ results: [SearchResult]) -> some View {
        // LazyVStack keeps memory bounded for long result sets and avoids the
        // grouped-list chrome the design mockup intentionally drops in favour
        // of edge-to-edge rows on a `Theme.surface` band.
        LazyVStack(spacing: 0) {
            ForEach(Array(results.enumerated()), id: \.element.videoId) { index, result in
                let track = SearchViewModel.track(from: result)
                TrackRow(
                    track: track,
                    isPlaying: currentTrack?.videoId == result.videoId,
                    onTap: { onPlay(track) }
                )
                .contextMenu { contextMenu(for: track) }
                .accessibilityIdentifier("search.row.\(result.videoId)")

                if index < results.count - 1 {
                    Divider()
                        .padding(.leading, Tokens.Thumbnail.row + Tokens.Spacing.md * 2)
                }
            }
        }
        .background(Theme.surface)
    }

    @ViewBuilder
    private func contextMenu(for track: Track) -> some View {
        Button {
            onPlay(track)
        } label: {
            Label("Play", systemImage: "play.fill")
        }

        Button {
            onAddToQueue(track)
        } label: {
            Label("Add to Queue", systemImage: "text.badge.plus")
        }

        Button {
            trackPendingPlaylistAdd = track
        } label: {
            Label("Add to Playlist…", systemImage: "music.note.list")
        }
    }

    // MARK: C3 — No results (empty, query submitted, zero matches)

    private func emptyState(query: String) -> some View {
        // Catalog copy C3 verbatim. Title truncates query at 32 chars with ellipsis.
        let displayQuery = query.count > 32 ? String(query.prefix(32)) + "…" : query
        let view = EmptyStateView(
            systemImage: "magnifyingglass",
            title: "No results for \"\(displayQuery)\"",
            message: "Check your spelling or try a different search.",
            actionTitle: "Clear search",
            action: { viewModel.clear() }
        )
        // C3 announces politely on appear — the user is waiting for an answer.
        return view
            .onAppear {
                UIAccessibility.post(notification: .announcement, argument: "No results for \(displayQuery).")
            }
    }

    // MARK: C4 / C5 — Error states

    /// Returns C5 (offline) or C4 (generic) based on the error message convention
    /// from `SearchViewModel`. Offline detection is delegated to the view model;
    /// the view reads the `isOffline` flag on the view model.
    private func errorCard(message: String) -> some View {
        // SearchViewModel sets isOffline when the underlying error is an offline error.
        let isOffline = viewModel.isOffline
        let symbol = isOffline ? "wifi.slash" : "exclamationmark.triangle"
        let title = isOffline ? "You're offline" : "Couldn't search"
        let body = isOffline
            ? "Connect to the internet to search. Your saved playlists are still available in Library."
            : "Something went wrong on our end. Try again in a moment."

        let secondaryAction: ButtonAction? = isOffline
            ? ButtonAction(title: "Go to Library", action: { onGoToLibrary?() })
            : nil

        let retryAction: (() -> Void)? = viewModel.lastQuery != nil ? { viewModel.retry() } : nil

        return ErrorStateView(
            systemImage: symbol,
            title: title,
            message: body,
            onRetry: retryAction,
            secondaryAction: secondaryAction
        )
        // AC10: UIAccessibility.post(.screenChanged) when error replaces loading.
        .onAppear {
            UIAccessibility.post(notification: .screenChanged, argument: nil)
        }
    }

}

// MARK: - Track : Identifiable shim for `.sheet(item:)`

/// `Track` lives in `Core/Models/` and intentionally stays a plain DTO. Adding
/// `Identifiable` here is a screen-local convenience keyed by `videoId`, which
/// is unique across search results.
extension Track: Identifiable {
    public var id: String { videoId }
}

// MARK: - AddToPlaylistSheet

/// Sheet listing the user's existing playlists and appending the chosen
/// ``Track`` to the selected one via ``PlaylistStore``.
///
/// Kept private to the Search feature because the design system does not yet
/// own a reusable "pick a playlist" component. When YT-0028 ships a generic
/// flow we can promote this.
private struct AddToPlaylistSheet: View {
    let track: Track
    let onDismiss: () -> Void

    @Environment(\.modelContext) private var context
    @Environment(\.dismiss) private var dismiss
    @Query(sort: \PlaylistEntity.sortIndex, order: .forward) private var playlists: [PlaylistEntity]

    @State private var error: String?

    var body: some View {
        NavigationStack {
            Group {
                if playlists.isEmpty {
                    EmptyStateView(
                        systemImage: "music.note.list",
                        title: "No playlists yet",
                        message: "Create a playlist from the Library tab to add tracks."
                    )
                    .frame(maxHeight: .infinity)
                } else {
                    List(playlists) { playlist in
                        Button {
                            add(to: playlist)
                        } label: {
                            HStack {
                                Image(systemName: "music.note.list")
                                    .foregroundStyle(Theme.accent)
                                VStack(alignment: .leading) {
                                    Text(playlist.name)
                                        .foregroundStyle(Theme.onBackground)
                                    Text("\(playlist.trackPositions.count) tracks")
                                        .font(.caption)
                                        .foregroundStyle(Theme.onBackgroundSecondary)
                                }
                            }
                        }
                        .accessibilityIdentifier("search.addToPlaylist.\(playlist.id)")
                    }
                }
            }
            .navigationTitle("Add to Playlist")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Cancel") {
                        dismiss()
                        onDismiss()
                    }
                }
            }
            .alert(
                "Couldn't add",
                isPresented: Binding(
                    get: { error != nil },
                    set: { if !$0 { error = nil } }
                ),
                presenting: error
            ) { _ in
                Button("OK", role: .cancel) { error = nil }
            } message: { message in
                Text(message)
            }
        }
        .presentationDetents([.medium, .large])
    }

    private func add(to playlist: PlaylistEntity) {
        let store = PlaylistStore(context: context)
        do {
            try store.addTrack(track, toPlaylistId: playlist.id)
            dismiss()
            onDismiss()
        } catch PlaylistStoreError.duplicateTrack {
            error = "This track is already in \(playlist.name)."
        } catch {
            // We never log raw error contents; surface a generic user-safe message.
            self.error = "Couldn't add this track. Please try again."
        }
    }
}

// MARK: - Preview

#Preview("SearchScreen — idle") {
    NavigationStack {
        SearchScreen(youtubeService: PreviewSearchService(mode: .results))
    }
}

/// Preview-only fake. Production never instantiates this.
private struct PreviewSearchService: YouTubeServiceProtocol {
    enum Mode { case idle, results, empty, error }
    let mode: Mode

    func search(query: String, maxResults: Int) async throws -> [SearchResult] {
        switch mode {
        case .idle, .results:
            return [
                SearchResult(videoId: "abc", title: "lofi hip hop radio", channel: "Lofi Girl", durationSec: 0, thumbnailUrl: ""),
                SearchResult(videoId: "def", title: "Bohemian Rhapsody", channel: "Queen", durationSec: 355, thumbnailUrl: "")
            ]
        case .empty:
            return []
        case .error:
            throw YouTubeServiceError.networkFailure
        }
    }

    func resolveStreamURL(videoId: String, quality: AudioQuality) async throws -> ResolvedStream {
        ResolvedStream(url: URL(string: "https://example.invalid/preview")!, isMuxedFallback: false)
    }
}
