import SwiftUI
import SwiftData
import UIKit
import UniformTypeIdentifiers

// MARK: - LibraryScreen

struct LibraryScreen: View {
    @Environment(\.modelContext) private var context
    @Query(sort: \PlaylistEntity.sortIndex, order: .forward) private var playlists: [PlaylistEntity]

    /// Currently-playing track id (for highlighting rows in subviews such as
    /// Recently Played). `nil` when nothing is loaded.
    var currentVideoId: String? = nil
    /// Callback invoked when a track row is tapped from a Library subroute
    /// (e.g. Recently Played). The shell maps this to
    /// ``PlayerCoordinator/playNow(_:)``. Optional so previews and unit-test
    /// hosts can render the screen without wiring playback.
    var onPlay: ((Track) -> Void)? = nil

    @State private var showCreate = false
    @State private var playlistToDelete: PlaylistEntity? = nil
    @State private var showDeleteConfirm = false
    @State private var playlistToRename: PlaylistEntity? = nil
    @State private var renameText = ""
    @State private var showRename = false

    // MARK: C6/C8 loading gate
    // @Query drives live updates for the content path. This loading flag gates
    // the C6 skeleton on cold launch and surfacing C8 on store errors.
    /// `true` while the initial store load is pending (C6 skeleton).
    @State private var isInitialLoading = true
    /// Non-nil when the initial load fails (C8 error).
    @State private var loadError: Error? = nil

    // MARK: Import (YT-0047)

    /// Drives the SwiftUI `.fileImporter` — UX backup for the system Files
    /// "Open in YourTube" action when iOS Files surfacing is unreliable.
    @State private var showImporter = false
    /// Successful import outcome to surface in an alert.
    @State private var importSuccess: PlaylistImportOutcome? = nil
    /// Import failure to surface in an alert.
    @State private var importFailure: PlaylistImportError? = nil

    var body: some View {
        NavigationStack {
            Group {
                if isInitialLoading {
                    // C6 — Library loading: skeleton × 4 playlist rows
                    libraryLoadingSkeleton
                } else if loadError != nil {
                    // C8 — Couldn't load your library (catalog copy verbatim)
                    ErrorStateView(
                        systemImage: "exclamationmark.triangle",
                        title: "Couldn't load your library",
                        message: "Check your connection and try again.",
                        onRetry: {
                            loadError = nil
                            isInitialLoading = true
                            Task { await loadLibrary() }
                        }
                    )
                    .onAppear {
                        // Pass the error title string so VoiceOver speaks it and
                        // focuses the element rather than defaulting to the first
                        // on-screen element (per state-catalog error.md spec).
                        UIAccessibility.post(notification: .screenChanged, argument: "Couldn't load your library")
                    }
                } else {
                    libraryList
                }
            }
            .navigationTitle("Library")
            .toolbar {
                if !isInitialLoading && loadError == nil {
                    ToolbarItem(placement: .topBarTrailing) { EditButton() }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        Button {
                            showCreate = true
                        } label: {
                            Label("New Playlist", systemImage: "plus")
                        }
                        .accessibilityIdentifier("library.menu.create")

                        Button {
                            showImporter = true
                        } label: {
                            Label("Import Playlist…", systemImage: "square.and.arrow.down")
                        }
                        .accessibilityIdentifier("library.menu.import")
                    } label: {
                        Image(systemName: "plus")
                            .accessibilityLabel("Add or import playlist")
                    }
                }
            }
        }
        .task { await loadLibrary() }
        .sheet(isPresented: $showCreate) {
            CreatePlaylistSheet(isPresented: $showCreate)
        }
        .alert("Rename playlist", isPresented: $showRename) {
            TextField("Name", text: $renameText)
                .textInputAutocapitalization(.words)
            Button("Save") {
                if let p = playlistToRename {
                    let name = renameText.trimmingCharacters(in: .whitespacesAndNewlines)
                    guard !name.isEmpty else { return }
                    try? PlaylistStore(context: context).rename(id: p.id, to: name)
                }
            }
            .disabled(renameText.trimmingCharacters(in: .whitespaces).isEmpty)
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Choose a new name for this playlist.")
        }
        .confirmationDialog(
            "Delete this playlist?",
            isPresented: $showDeleteConfirm,
            titleVisibility: .visible
        ) {
            Button("Delete Playlist", role: .destructive) {
                if let p = playlistToDelete {
                    try? PlaylistStore(context: context).delete(id: p.id)
                }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("The tracks will stay in your library. This can't be undone.")
        }
        // MARK: Import (YT-0047) — UX backup for system Files "Open in YourTube"
        .fileImporter(
            isPresented: $showImporter,
            allowedContentTypes: Self.importableContentTypes,
            allowsMultipleSelection: false
        ) { result in
            handleImporterResult(result)
        }
        .alert(
            "Playlist Imported",
            isPresented: Binding(
                get: { importSuccess != nil },
                set: { if !$0 { importSuccess = nil } }
            ),
            presenting: importSuccess
        ) { _ in
            Button("Done", role: .cancel) { importSuccess = nil }
        } message: { outcome in
            Text("\(outcome.name) was added to your library.")
        }
        .alert(
            importFailure?.alertTitle ?? "Import Failed",
            isPresented: Binding(
                get: { importFailure != nil },
                set: { if !$0 { importFailure = nil } }
            ),
            presenting: importFailure
        ) { _ in
            Button("OK", role: .cancel) { importFailure = nil }
        } message: { error in
            Text(error.userMessage)
        }
    }

    // MARK: - C6/C8 helpers

    /// C6 — Library loading: 4 skeleton playlist rows.
    @ViewBuilder
    private var libraryLoadingSkeleton: some View {
        VStack(spacing: 0) {
            ForEach(0..<4, id: \.self) { i in
                SkeletonPlaylistRow(index: i)
            }
            Spacer()
        }
        .background(Theme.background)
        .accessibilityValue("Loading")
    }

    /// Content list (C7 empty overlay and track list).
    @ViewBuilder
    private var libraryList: some View {
        List {
            // MARK: Section 1 — Recently Played
            Section {
                NavigationLink {
                    RecentlyPlayedScreen(
                        currentVideoId: currentVideoId,
                        onPlay: onPlay
                    )
                } label: {
                    Label("Recently Played", systemImage: "clock")
                }
                .accessibilityLabel("Recently Played")
                .accessibilityHint("Opens your recently played tracks")
            }

            // MARK: Section 2 — Playlists
            Section("Playlists") {
                ForEach(playlists) { playlist in
                    NavigationLink(
                        destination: PlaylistDetailScreen(
                            playlist: playlist,
                            currentVideoId: currentVideoId
                        )
                    ) {
                        PlaylistRow(playlist: playlist)
                    }
                    .swipeActions(edge: .trailing) {
                        Button(role: .destructive) {
                            playlistToDelete = playlist
                            showDeleteConfirm = true
                        } label: {
                            Label("Delete", systemImage: "trash")
                        }
                    }
                }
                .onMove(perform: movePlaylists)
                .onDelete(perform: deletePlaylists)
            }
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .background(Color.black)
        .overlay {
            if playlists.isEmpty {
                // C7 — Library empty: "No playlists yet" (catalog copy verbatim)
                ContentUnavailableView {
                    Label {
                        Text("No playlists yet")
                    } icon: {
                        Image(systemName: "music.note.list")
                            .font(.system(size: 56))
                            .foregroundStyle(Color.secondary)
                            .accessibilityHidden(true)
                    }
                } description: {
                    Text("Create one to organize tracks for offline listening.")
                } actions: {
                    Button("Create playlist") { showCreate = true }
                        .buttonStyle(.borderedProminent)
                        .tint(.accentColor)
                }
                .accessibilityElement(children: .combine)
                .accessibilityLabel("No playlists yet. Create one to organize tracks for offline listening. Create playlist button.")
            }
        }
    }

    /// Gates the initial loading state. Library is local-first so this resolves
    /// near-instantly (C6 spec: cold launch or after Settings → Clear cache).
    /// Any store-level error surfaces as C8. Called from `.task` which auto-cancels
    /// on view disappear.
    @MainActor
    private func loadLibrary() async {
        do {
            // One-frame yield so SwiftUI renders the C6 skeleton briefly.
            await Task.yield()
            // Validate that the SwiftData context is readable (catches corruption,
            // migration failures, file-permissions — the C8 trigger cases).
            let descriptor = FetchDescriptor<PlaylistEntity>()
            _ = try context.fetch(descriptor)
            withAnimation { isInitialLoading = false }
        } catch {
            loadError = error
            withAnimation { isInitialLoading = false }
        }
    }

    // MARK: - Import handling (YT-0047)

    /// Allowed UTTypes for the Library `.fileImporter`.
    ///
    /// We declare both the custom playlist UTI and `.json` so users can pick
    /// playlists saved to Files even when iOS resolves the multi-segment
    /// `.ytplaylist.json` extension as plain JSON. ``handleImporterResult(_:)``
    /// re-validates the suffix before forwarding to ``PlaylistImportService``.
    private static let importableContentTypes: [UTType] = {
        var types: [UTType] = [.json]
        if let custom = UTType("com.matteofranceschi.yourtube.playlist") {
            types.insert(custom, at: 0)
        }
        return types
    }()

    /// Routes a `.fileImporter` result through the same
    /// ``PlaylistImportService`` call path used by the scene-root
    /// `.onOpenURL` handler — there is intentionally no parallel parsing.
    private func handleImporterResult(_ result: Result<[URL], Error>) {
        switch result {
        case .failure(let error):
            importFailure = .fileRead(error.localizedDescription)
            importSuccess = nil
        case .success(let urls):
            guard let url = urls.first else { return }
            // Defensive: the JSON UTType is broad; only accept files whose
            // filename actually ends in the documented suffix.
            guard url.lastPathComponent.lowercased().hasSuffix(".ytplaylist.json") else {
                importFailure = .invalidSchema("Not a YourTube playlist file")
                importSuccess = nil
                return
            }
            let store = PlaylistStore(context: context)
            do {
                importSuccess = try PlaylistImportService.importPlaylist(from: url, into: store)
                importFailure = nil
            } catch let error as PlaylistImportError {
                importFailure = error
                importSuccess = nil
            } catch {
                importFailure = .invalidSchema(error.localizedDescription)
                importSuccess = nil
            }
        }
    }

    private func movePlaylists(from source: IndexSet, to destination: Int) {
        var arr = playlists
        arr.move(fromOffsets: source, toOffset: destination)
        try? PlaylistStore(context: context).reorderPlaylists(arr)
    }

    private func deletePlaylists(offsets: IndexSet) {
        let store = PlaylistStore(context: context)
        for i in offsets { try? store.delete(id: playlists[i].id) }
    }
}

// MARK: - CreatePlaylistSheet

private struct CreatePlaylistSheet: View {
    @Binding var isPresented: Bool
    @Environment(\.modelContext) private var context
    @Query(sort: \PlaylistEntity.sortIndex, order: .forward) private var existing: [PlaylistEntity]
    @State private var name = ""

    var body: some View {
        NavigationStack {
            Form {
                TextField("Playlist name", text: $name)
                    .textInputAutocapitalization(.words)
            }
            .navigationTitle("New Playlist")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Cancel") { isPresented = false }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Create") {
                        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
                        guard !trimmed.isEmpty else { return }
                        try? PlaylistStore(context: context).create(name: trimmed, sortIndex: existing.count)
                        isPresented = false
                    }
                    .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty)
                    .fontWeight(.semibold)
                }
            }
        }
        .presentationDetents([.medium])
    }
}

// MARK: - Preview

#Preview("LibraryScreen") {
    LibraryScreen()
}
