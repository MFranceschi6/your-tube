import SwiftUI
import SwiftData
import UIKit

// MARK: - PlaylistDetailScreen

/// Playlist detail screen.
///
/// State catalog mapping (YT-0165 / YT-0073):
/// - C9: skeleton header + × 6 rows on initial load (brief flash)
/// - C10: "This playlist is empty" when zero tracks — header still visible
/// - C11: "Couldn't load this playlist" on store errors
struct PlaylistDetailScreen: View {
    @Bindable var playlist: PlaylistEntity
    /// Currently-playing track id, used to highlight the matching row. `nil`
    /// when nothing is loaded. Forwarded from ``LibraryScreen`` so the
    /// active-row tint matches Search and Recently Played behavior.
    var currentVideoId: String? = nil
    @Environment(\.modelContext) private var context
    @Environment(\.dismiss) private var dismiss
    @Environment(\.editMode) private var editMode

    @State private var collapseProgress: CGFloat = 0
    @State private var showRename = false
    @State private var renameText = ""
    @State private var showDelete = false
    @State private var showAddTracks = false
    @State private var trackToAddToOtherPlaylists: PlaylistTrackEntity? = nil
    @State private var sortByDateAdded = false
    @State private var undoToast: UndoToastItem? = nil
    /// `true` while the initial load gate is pending (C9 skeleton).
    @State private var isInitialLoading = true
    /// Non-nil when the initial load fails (C11 error).
    @State private var detailLoadError: Error? = nil
    /// Optional callback to switch to Search tab (C10 "Find tracks" action).
    var onSwitchToSearch: (() -> Void)? = nil

    // MARK: Export

    /// Serialise the current playlist to a temporary `.ytplaylist.json` file.
    /// Recomputed lazily on each access so the share file always matches the
    /// latest playlist edits.
    private var playlistExportURL: URL? {
        let payload = PlaylistPayload(
            schemaVersion: PlaylistCodec.supportedSchemaVersion,
            id: playlist.id,
            name: playlist.name,
            createdAt: playlist.createdAt,
            updatedAt: playlist.updatedAt,
            tracks: playlist.orderedTracks.map { $0.asTrack() }
        )
        return try? PlaylistExportService.writeTemporaryFile(for: payload)
    }

    var body: some View {
        Group {
            if isInitialLoading {
                // C9 — Playlist Detail loading: skeleton header + × 6 track rows
                ScrollView {
                    PlaylistDetailSkeletonHeader()
                    VStack(spacing: 0) {
                        ForEach(0..<6, id: \.self) { i in
                            SkeletonRow(index: i)
                            if i < 5 {
                                Divider()
                                    .padding(.leading, Tokens.Thumbnail.row + Tokens.Spacing.md * 2)
                            }
                        }
                    }
                }
                .accessibilityValue("Loading")
                .task {
                    do {
                        await Task.yield()
                        // Validate the playlist is still accessible (guards against
                        // concurrent deletion, SwiftData migration errors, etc.).
                        let playlistId = playlist.id
                        let descriptor = FetchDescriptor<PlaylistEntity>(
                            predicate: #Predicate { $0.id == playlistId }
                        )
                        let found = try context.fetch(descriptor)
                        if found.isEmpty {
                            struct PlaylistNotFoundError: Error {}
                            throw PlaylistNotFoundError()
                        }
                        withAnimation { isInitialLoading = false }
                    } catch {
                        detailLoadError = error
                        withAnimation { isInitialLoading = false }
                    }
                }
            } else if detailLoadError != nil {
                // C11 — Couldn't load this playlist (catalog copy verbatim)
                ErrorStateView(
                    systemImage: "exclamationmark.triangle",
                    title: "Couldn't load this playlist",
                    message: "Check your connection and try again.",
                    onRetry: {
                        detailLoadError = nil
                        isInitialLoading = true
                    }
                )
                .onAppear {
                    // Pass the error title string so VoiceOver speaks it and
                    // focuses the element rather than defaulting to the first
                    // on-screen element (per state-catalog error.md spec).
                    UIAccessibility.post(notification: .screenChanged, argument: "Couldn't load this playlist")
                }
            } else {
                List {
                    // MARK: Parallax cover + title + action row
                    PlaylistDetailHeader(playlist: playlist, collapseProgress: $collapseProgress)
                        .listRowInsets(EdgeInsets())
                        .listRowSeparator(.hidden)
                        .listRowBackground(Color.clear)

                    if displayedPositions.isEmpty {
                        // C10 — This playlist is empty (header stays visible above)
                        ContentUnavailableView {
                            Label {
                                Text("This playlist is empty")
                            } icon: {
                                Image(systemName: "music.note")
                                    .font(.system(size: 56))
                                    .foregroundStyle(Color.secondary)
                                    .accessibilityHidden(true)
                            }
                        } description: {
                            Text("Add tracks from search or your history.")
                        } actions: {
                            if let onSwitchToSearch {
                                Button("Find tracks", action: onSwitchToSearch)
                                    .buttonStyle(.borderedProminent)
                                    .tint(.accentColor)
                            }
                        }
                        .accessibilityElement(children: .combine)
                        .accessibilityLabel("This playlist is empty. Add tracks from search or your history. Find tracks button.")
                        .listRowInsets(EdgeInsets())
                        .listRowSeparator(.hidden)
                        .listRowBackground(Color.clear)
                    } else {
                        // MARK: Track list
                        ForEach(displayedPositions) { position in
                            TrackRow(
                                track: position.track.asTrack(),
                                isPlaying: currentVideoId == position.track.videoId
                            )
                                .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                                    Button(role: .destructive) { removeTrack(position) } label: {
                                        Label("Remove", systemImage: "trash")
                                    }
                                }
                                .contextMenu {
                                    Button { trackToAddToOtherPlaylists = position } label: {
                                        Label("Add to Playlist", systemImage: "plus")
                                    }
                                    if let url = URL(string: "https://youtu.be/\(position.track.videoId)") {
                                        ShareLink(item: url, subject: Text(position.track.title)) {
                                            Label("Share Track", systemImage: "square.and.arrow.up")
                                        }
                                    }
                                    Divider()
                                    Button(role: .destructive) { removeTrack(position) } label: {
                                        Label("Remove from Playlist", systemImage: "trash")
                                    }
                                }
                        }
                        .onMove(perform: moveTracks)
                        .onDelete { offsets in
                            offsets.forEach { removeTrack(displayedPositions[$0]) }
                        }
                    }
                }
                .listStyle(.plain)
            }
        }
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .principal) {
                if collapseProgress > 0.7 {
                    Text(playlist.name)
                        .font(.system(size: 17, weight: .semibold))
                        .opacity(Double((collapseProgress - 0.7) / 0.3))
                }
            }
            ToolbarItem(placement: .topBarTrailing) { EditButton() }
            ToolbarItem(placement: .topBarTrailing) {
                Menu {
                    if let exportURL = playlistExportURL {
                        ShareLink(
                            item: exportURL,
                            preview: SharePreview(
                                playlist.name,
                                image: Image(systemName: "music.note.list")
                            )
                        ) {
                            Label("Share", systemImage: "square.and.arrow.up")
                        }
                    }
                    Button {} label: {
                        Label("Add Tracks", systemImage: "plus")
                    }
                    Button { renameText = playlist.name; showRename = true } label: {
                        Label("Rename", systemImage: "pencil")
                    }
                    Button { editMode?.wrappedValue = .active } label: {
                        Label("Edit", systemImage: "pencil.circle")
                    }
                    Button { sortByDateAdded.toggle() } label: {
                        Label(
                            sortByDateAdded ? "Sort: A–Z" : "Sort: Manual",
                            systemImage: "arrow.up.arrow.down"
                        )
                    }
                    Divider()
                    Button(role: .destructive) { showDelete = true } label: {
                        Label("Delete Playlist", systemImage: "trash")
                    }
                } label: {
                    Image(systemName: "ellipsis.circle")
                }
            }
        }
        .alert("Rename playlist", isPresented: $showRename) {
            TextField("Name", text: $renameText)
                .textInputAutocapitalization(.words)
            Button("Save") {
                let name = renameText.trimmingCharacters(in: .whitespacesAndNewlines)
                guard !name.isEmpty else { return }
                try? PlaylistStore(context: context).rename(id: playlist.id, to: name)
            }
            .disabled(renameText.trimmingCharacters(in: .whitespaces).isEmpty)
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Choose a new name for this playlist.")
        }
        .confirmationDialog(
            "Delete this playlist?",
            isPresented: $showDelete,
            titleVisibility: .visible
        ) {
            Button("Delete Playlist", role: .destructive) {
                try? PlaylistStore(context: context).delete(id: playlist.id)
                dismiss()
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("The tracks will stay in your library. This can't be undone.")
        }
        .sheet(item: $trackToAddToOtherPlaylists) { position in
            AddToPlaylistSheet(track: position.track.asTrack(), excludingPlaylistId: playlist.id)
        }
        .overlay(alignment: .bottom) {
            if let toast = undoToast {
                UndoSnackbar(toast: toast) {
                    withAnimation { undoToast = nil }
                }
                .padding(.bottom, 16)
                .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .animation(.easeInOut(duration: 0.25), value: undoToast?.id)
    }

    // MARK: Derived display order

    private var displayedPositions: [PlaylistTrackEntity] {
        if sortByDateAdded {
            return playlist.orderedPositions.sorted { $0.track.title < $1.track.title }
        }
        return playlist.orderedPositions
    }

    // MARK: Actions

    private func removeTrack(_ position: PlaylistTrackEntity) {
        let track = position.track.asTrack()
        let store = PlaylistStore(context: context)
        try? store.removeTrack(videoId: track.videoId, fromPlaylistId: playlist.id)
        let toast = UndoToastItem(message: "Removed \"\(track.title)\"") {
            try? store.addTrack(track, toPlaylistId: playlist.id)
        }
        undoToast = toast
        Task {
            try? await Task.sleep(for: .seconds(4))
            if undoToast?.id == toast.id {
                withAnimation { undoToast = nil }
            }
        }
    }

    private func moveTracks(from source: IndexSet, to destination: Int) {
        var positions = playlist.orderedPositions
        positions.move(fromOffsets: source, toOffset: destination)
        for (i, pos) in positions.enumerated() { pos.position = i }
        try? context.save()
    }
}

// MARK: - PlaylistDetailSkeletonHeader

/// C9 skeleton header: cover block + title/subtitle lines + two pill buttons.
/// Matches the `loading.md` C9 template dimensions.
private struct PlaylistDetailSkeletonHeader: View {
    var body: some View {
        VStack(spacing: 0) {
            // 140 pt cover block, centered
            SkeletonBlock(cornerRadius: Tokens.Radius.md)
                .frame(width: 140, height: 140)
                .padding(.top, 24)

            // Title placeholder: 60% width, 22 pt height
            SkeletonBlock(cornerRadius: 4)
                .frame(height: 22)
                .frame(maxWidth: 220)
                .padding(.top, 24)

            // Subtitle placeholder: 30% width, 13 pt height
            SkeletonBlock(cornerRadius: 4)
                .frame(height: 13)
                .frame(maxWidth: 110)
                .padding(.top, 8)

            // Play + Shuffle pill buttons
            HStack(spacing: 12) {
                SkeletonBlock(cornerRadius: Tokens.Radius.pill)
                    .frame(width: 120, height: 44)
                SkeletonBlock(cornerRadius: Tokens.Radius.pill)
                    .frame(width: 120, height: 44)
            }
            .padding(.top, 16)
            .padding(.bottom, 12)
        }
        .frame(maxWidth: .infinity)
        .accessibilityHidden(true)
    }
}

// MARK: - PlaylistDetailHeader

private struct PlaylistDetailHeader: View {
    let playlist: PlaylistEntity
    @Binding var collapseProgress: CGFloat
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var playTrigger = false
    @State private var shuffleTrigger = false

    var body: some View {
        VStack(spacing: 0) {
            GeometryReader { geo in
                let minY = geo.frame(in: .global).minY
                let scrolled = max(0, 150 - minY)
                let progress = max(0, min(1, scrolled / 180.0))
                // Reduce Motion: snap at threshold instead of interpolating.
                let effectiveProgress = reduceMotion ? (progress > 0.5 ? 1.0 : 0.0) : progress
                let size = 240 - (240 - 64) * effectiveProgress

                PlaylistCover(playlist: playlist)
                    .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                    .frame(width: size, height: size)
                    .frame(maxWidth: .infinity, alignment: .center)
                    .padding(.top, 8)
                    .onChange(of: effectiveProgress) { _, new in collapseProgress = new }
            }
            .frame(height: 240)

            // Title + track count
            VStack(spacing: 4) {
                Text(playlist.name)
                    .font(.system(size: 22, weight: .bold))
                    .foregroundStyle(.primary)
                    .multilineTextAlignment(.center)

                let count = playlist.orderedTracks.count
                Text("^[\(count) track](inflect: true)")
                    .font(.system(size: 14))
                    .foregroundStyle(.secondary)
                    .monospacedDigit()
            }
            .padding(.horizontal)
            .padding(.top, 12)

            // Play + Shuffle (two equal-width pills; play actions wired in YT-0024)
            HStack(spacing: 12) {
                Button { playTrigger.toggle() } label: {
                    Label("Play", systemImage: "play.fill")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .sensoryFeedback(.impact(weight: .medium), trigger: playTrigger)

                Button { shuffleTrigger.toggle() } label: {
                    Label("Shuffle", systemImage: "shuffle")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                .sensoryFeedback(.impact(weight: .light), trigger: shuffleTrigger)
                .controlSize(.large)
            }
            .padding(.horizontal)
            .padding(.top, 16)
            .padding(.bottom, 12)
        }
    }
}

// MARK: - AddToPlaylistSheet

private struct AddToPlaylistSheet: View {
    let track: Track
    let excludingPlaylistId: String
    @Query(sort: \PlaylistEntity.sortIndex) private var allPlaylists: [PlaylistEntity]
    @State private var selected: Set<String> = []
    @Environment(\.dismiss) private var dismiss
    @Environment(\.modelContext) private var context

    private var eligible: [PlaylistEntity] {
        allPlaylists.filter { $0.id != excludingPlaylistId }
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Button {
                        let store = PlaylistStore(context: context)
                        let count = allPlaylists.count
                        try? store.create(name: "New Playlist", sortIndex: count)
                        dismiss()
                    } label: {
                        Label("New Playlist", systemImage: "plus.circle.fill")
                            .foregroundStyle(Color.accentColor)
                    }
                }
                Section("Playlists") {
                    ForEach(eligible) { pl in
                        HStack {
                            PlaylistCover(playlist: pl)
                                .frame(width: 36, height: 36)
                                .clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))
                            Text(pl.name)
                            Spacer()
                            if selected.contains(pl.id) {
                                Image(systemName: "checkmark").foregroundStyle(Color.accentColor)
                            }
                        }
                        .contentShape(Rectangle())
                        .onTapGesture {
                            if selected.contains(pl.id) { selected.remove(pl.id) }
                            else { selected.insert(pl.id) }
                        }
                    }
                }
            }
            .navigationTitle("Add to playlist")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") {
                        let store = PlaylistStore(context: context)
                        for playlistId in selected {
                            try? store.addTrack(track, toPlaylistId: playlistId)
                        }
                        dismiss()
                    }
                    .disabled(selected.isEmpty)
                    .fontWeight(.semibold)
                }
            }
        }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
    }
}

// MARK: - Undo snackbar

private struct UndoToastItem: Identifiable {
    let id = UUID()
    let message: String
    let action: () -> Void
}

private struct UndoSnackbar: View {
    let toast: UndoToastItem
    let onDismiss: () -> Void

    var body: some View {
        HStack {
            Text(toast.message).foregroundStyle(.primary)
            Spacer()
            Button("Undo") { toast.action(); onDismiss() }
                .fontWeight(.semibold)
                .foregroundStyle(Color.accentColor)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        .padding(.horizontal, 16)
    }
}

#Preview("PlaylistDetailScreen") {
    Text("Requires SwiftData container")
}
