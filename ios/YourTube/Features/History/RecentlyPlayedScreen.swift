import SwiftUI
import SwiftData
import UIKit

// MARK: - RecentlyPlayedScreen

/// Lists the user's recently played tracks newest-first. Reachable from
/// ``LibraryScreen`` via a `NavigationLink`; never a top-level tab per the MVP
/// design.
///
/// Each tap replays the entry through the injected `onPlay` closure (the
/// shell forwards this to ``PlayerCoordinator/playNow(_:)``). A toolbar
/// "Clear" action wipes all entries via ``HistoryStore/clearAll()``.
///
/// History rows are rendered with the shared ``TrackRow`` component so the
/// styling matches search results and playlist details. The currently-playing
/// track gets the equalizer-bars affordance.
///
/// State catalog mapping (YT-0165 / YT-0073):
/// - C12: skeleton × 6 on initial load
/// - C13: "Nothing played yet" empty state
/// - C14: "Couldn't load history" error state
struct RecentlyPlayedScreen: View {

    // MARK: Inputs

    /// Currently-playing track id, used to highlight the matching row.
    let currentVideoId: String?
    /// Whether playback is actively in flight. Combined with `currentVideoId`
    /// to decide whether to animate EQ bars (YT-0192: id-match alone is
    /// insufficient — paused rows must remain visually inert).
    var isPlaying: Bool = false
    /// Closure invoked when the user taps a history entry. The shell maps this
    /// to ``PlayerCoordinator/playNow(_:)`` so playback resumes for that
    /// track. `nil` makes rows non-interactive (used in previews).
    let onPlay: ((Track) -> Void)?

    // MARK: Environment

    @Environment(\.modelContext) private var context

    // NOTE: @Query drives live SwiftData updates for the content path.
    // HistoryViewModel drives the initial loading/error/empty gate so that
    // store-level failures (migration errors, corrupted store, etc.) reach C14.
    @Query(sort: \HistoryEntryEntity.playedAt, order: .reverse)
    private var entries: [HistoryEntryEntity]

    // MARK: ViewModel (loading / error gate)

    /// Lazily created on first `.task` call so `modelContext` is available.
    @State private var viewModel: HistoryViewModel? = nil

    // MARK: Local UI state

    @State private var showClearConfirm = false
    /// `true` while the initial load gate is pending (C12 skeleton).
    @State private var isInitialLoading = true
    /// Non-nil when initial load fails (C14 error).
    @State private var loadError: Error? = nil

    // MARK: Body

    var body: some View {
        Group {
            if isInitialLoading {
                // C12 — History loading: skeleton × 6
                VStack(spacing: 0) {
                    ForEach(0..<6, id: \.self) { i in
                        SkeletonRow(index: i)
                        if i < 5 {
                            Divider()
                                .padding(.leading, Tokens.Thumbnail.row + Tokens.Spacing.md * 2)
                        }
                    }
                    Spacer()
                }
                .background(Theme.background)
                .accessibilityValue("Loading")
            } else if let _ = loadError {
                // C14 — Couldn't load history
                ErrorStateView(
                    title: "Couldn't load history",
                    message: "Try again in a moment.",
                    onRetry: {
                        loadError = nil
                        isInitialLoading = true
                        loadEntries()
                    }
                )
                .onAppear {
                    // Pass the error title string so VoiceOver speaks it and
                    // focuses the element rather than defaulting to the first
                    // on-screen element (per state-catalog error.md spec).
                    UIAccessibility.post(notification: .screenChanged, argument: "Couldn't load history")
                }
            } else if entries.isEmpty {
                // C13 — Nothing played yet
                ScrollView {
                    ContentUnavailableView {
                        Label {
                            Text("Nothing played yet")
                        } icon: {
                            Image(systemName: "clock.arrow.circlepath")
                                .font(.system(size: 56))
                                .foregroundStyle(Color.secondary)
                                .accessibilityHidden(true)
                        }
                    } description: {
                        Text("Tracks you play will show up here.")
                    } actions: {
                        if let switchToSearch = onSwitchToSearch {
                            Button("Browse search", action: switchToSearch)
                                .buttonStyle(.borderedProminent)
                                .tint(.accentColor)
                        }
                    }
                    .accessibilityElement(children: .combine)
                    .accessibilityLabel("Nothing played yet. Tracks you play will show up here. Browse search button.")
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(Theme.background)
            } else {
                // Content: list of history entries
                List {
                    ForEach(entries) { entry in
                        let track = entry.asTrack()
                        // YT-0192: combine id-match AND isPlaying so EQ bars
                        // stay inert when the player is paused.
                        let rowIsPlaying = isPlaying && track.videoId == currentVideoId
                        TrackRow(
                            track: track,
                            isPlaying: rowIsPlaying,
                            onTap: { onPlay?(track) }
                            // YT-0194 (History): onMoreTap deferred. A
                            // per-entry "Remove from history" action does not
                            // yet exist on HistoryViewModel — swipe-to-delete
                            // and toolbar "Clear" are the only removal paths
                            // in the current MVP. Wire onMoreTap once a
                            // removeEntry(videoId:) method is added to
                            // HistoryViewModel / HistoryStore.
                        )
                        .listRowInsets(EdgeInsets())
                        .listRowBackground(Theme.background)
                        .accessibilityHint("Double-tap to play again")
                    }
                }
                .listStyle(.plain)
                .scrollContentBackground(.hidden)
                .background(Theme.background)
            }
        }
        .navigationTitle("Recently Played")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            // "Clear" only visible when history is non-empty (AC8 in C13 spec)
            if !entries.isEmpty && !isInitialLoading {
                ToolbarItem(placement: .topBarTrailing) {
                    Button(role: .destructive) {
                        showClearConfirm = true
                    } label: {
                        Text("Clear")
                    }
                    .accessibilityLabel("Clear recently played")
                    .accessibilityHint("Removes every entry from your history")
                }
            }
        }
        .confirmationDialog(
            "Clear History?",
            isPresented: $showClearConfirm,
            titleVisibility: .visible
        ) {
            Button("Clear All", role: .destructive) {
                try? HistoryStore(context: context).clearAll()
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("This will remove all recently played entries.")
        }
        .task {
            loadEntries()
        }
    }

    // MARK: Inputs (optional navigation callbacks)

    /// Optional callback to switch to the Search tab (C13 "Browse search" action).
    var onSwitchToSearch: (() -> Void)? = nil

    // MARK: Helpers

    private func loadEntries() {
        // Lazily wire HistoryViewModel on first call when modelContext is available.
        // HistoryViewModel.load() runs a real FetchDescriptor which can throw on
        // store corruption / migration failures — that's the live C14 error path.
        Task { @MainActor in
            let vm: HistoryViewModel
            if let existing = viewModel {
                vm = existing
            } else {
                let created = HistoryViewModel(context: context)
                viewModel = created
                vm = created
            }
            // One-frame yield so SwiftUI renders the C12 skeleton briefly.
            await Task.yield()
            vm.load()
            if case .error(let cause) = vm.uiState {
                loadError = cause
            }
            withAnimation {
                isInitialLoading = false
            }
        }
    }
}

// MARK: - Preview

#Preview("RecentlyPlayedScreen — populated") {
    let container = try! ModelContainer(
        for: Schema(PersistenceSchema.models),
        configurations: ModelConfiguration(isStoredInMemoryOnly: true)
    )
    let store = HistoryStore(context: container.mainContext)
    let now = Date()
    try? store.append(
        Track(videoId: "h1", title: "Stairway to Heaven", channel: "Led Zeppelin", durationSec: 482, thumbnailUrl: ""),
        playedAt: now
    )
    try? store.append(
        Track(videoId: "h2", title: "Hotel California", channel: "Eagles", durationSec: 438, thumbnailUrl: ""),
        playedAt: now.addingTimeInterval(-3600)
    )
    try? store.append(
        Track(videoId: "h3", title: "Bohemian Rhapsody", channel: "Queen", durationSec: 355, thumbnailUrl: ""),
        playedAt: now.addingTimeInterval(-86_400)
    )

    return NavigationStack {
        RecentlyPlayedScreen(currentVideoId: "h1", onPlay: { _ in })
    }
    .modelContainer(container)
}

#Preview("C13 — RecentlyPlayedScreen empty") {
    NavigationStack {
        RecentlyPlayedScreen(currentVideoId: nil, onPlay: nil)
    }
    .modelContainer(
        try! ModelContainer(
            for: Schema(PersistenceSchema.models),
            configurations: ModelConfiguration(isStoredInMemoryOnly: true)
        )
    )
    .preferredColorScheme(.dark)
    .dynamicTypeSize(.accessibility3)
}
