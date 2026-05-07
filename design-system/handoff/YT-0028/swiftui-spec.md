# YT-0028 — SwiftUI Implementation Spec

Concrete view hierarchy + token usage + state management for Library & Playlist
Management. Pairs with `decision-log.md` (rationale) and `mockup.html` (visual
target). Aesthetic tone: see [`../YT-0027/aesthetic-direction.md`](../YT-0027/aesthetic-direction.md).

---

## SwiftData models

```swift
@Model final class Playlist {
  @Attribute(.unique) var id: UUID = UUID()
  var name: String
  var createdAt: Date = Date()
  var sortIndex: Int          // for .onMove ordering
  @Relationship var tracks: [PlaylistTrack] = []
  // computed
  var totalDuration: Duration { tracks.reduce(.zero) { $0 + $1.track.duration } }
  var coverGradientSeed: Int { abs(id.hashValue) }
}

@Model final class PlaylistTrack {
  var track: Track
  var addedAt: Date = Date()
  var sortIndex: Int          // for .onMove inside playlist
  var playlist: Playlist?
}
```

`@Query(sort: \Playlist.sortIndex)` drives the Library list. Reorder writes
back to `sortIndex` in `.onMove`.

---

## View hierarchy

```
RootTabView
└─ NavigationStack (Library tab)
   └─ LibraryScreen
      ├─ List (.insetGrouped)
      │  ├─ Section { RecentlyPlayedRow → push YT-0029 }
      │  └─ Section("Playlists") { ForEach(playlists) PlaylistRow }
      ├─ overlay: ContentUnavailableView (when empty)
      ├─ toolbar: EditButton · "+" Button
      └─ .safeAreaInset(.bottom) { MiniPlayer spacer }

   └─ PlaylistDetailScreen (push)
      ├─ ScrollView
      │  ├─ ParallaxCoverHeader(playlist) — 240pt → 64pt
      │  ├─ TitleBlock — name + count + duration
      │  ├─ HStack(spacing: 12) { PlayButton · ShuffleButton }
      │  └─ LazyVStack { ForEach(tracks) TrackRow }
      ├─ toolbar:
      │  ├─ .principal: collapsedTitle (only when scrolled past threshold)
      │  └─ .topBarTrailing: EditButton · ellipsis Menu
      ├─ .safeAreaInset(.bottom) { MiniPlayer spacer }
      └─ sheets: .alert(rename) · .confirmationDialog(delete) · sheet(addTracks)
```

---

## Token usage

```swift
extension Color {
  static let bg          = Color(.black)               // page bg
  static let surface     = Color(red: 0.110, green: 0.110, blue: 0.118)   // #1C1C1E
  static let surface2    = Color(red: 0.173, green: 0.173, blue: 0.180)   // #2C2C2E
  static let fg          = Color.white
  static let fg2         = Color.white.opacity(0.6)
  static let fg3         = Color.white.opacity(0.4)
  static let hairline    = Color.white.opacity(0.08)
  static let accent      = Color(red: 0.545, green: 0.361, blue: 0.965)   // #8B5CF6
}
```

Apply `.tint(.accent)` at `RootTabView` so accent propagates down.

---

## PlaylistRow

```swift
struct PlaylistRow: View {
  let playlist: Playlist

  var body: some View {
    HStack(spacing: 14) {
      PlaylistCover(playlist: playlist)
        .frame(width: 56, height: 56)
        .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))

      VStack(alignment: .leading, spacing: 2) {
        Text(playlist.name)
          .font(.system(size: 17, weight: .semibold))
          .foregroundStyle(.primary)
          .lineLimit(1)

        Text("^[\(playlist.tracks.count) track](inflect: true)")
          .font(.system(size: 13))
          .foregroundStyle(.secondary)
          .monospacedDigit()
      }

      Spacer(minLength: 0)
    }
    .contentShape(Rectangle())
    .accessibilityElement(children: .combine)
    .accessibilityLabel("\(playlist.name), \(playlist.tracks.count) tracks")
    .accessibilityHint("Opens playlist details")
  }
}
```

---

## PlaylistCover (hybrid logic)

```swift
struct PlaylistCover: View {
  let playlist: Playlist

  var body: some View {
    let count = playlist.tracks.count
    Group {
      switch count {
      case 0:           GradientCover(seed: playlist.coverGradientSeed, glyph: "music.note")
      case 1:           SingleArtCover(track: playlist.tracks[0].track)
      case 2, 3:        GradientCover(seed: playlist.coverGradientSeed, initial: playlist.name.first)
      default:          FourUpCover(tracks: Array(playlist.tracks.prefix(4)))
      }
    }
  }
}

struct GradientCover: View {
  let seed: Int
  var glyph: String? = nil
  var initial: Character? = nil

  var body: some View {
    let (h1, h2) = hashHues(seed: seed)
    LinearGradient(
      colors: [
        Color(oklch: (l: 0.45, c: 0.12, h: h1)),
        Color(oklch: (l: 0.30, c: 0.12, h: h2))
      ],
      startPoint: .topLeading, endPoint: .bottomTrailing
    )
    .overlay {
      if let g = glyph {
        Image(systemName: g)
          .font(.system(size: 22, weight: .medium))
          .foregroundStyle(.white.opacity(0.28))
      } else if let i = initial {
        Text(String(i).uppercased())
          .font(.system(size: 28, weight: .bold, design: .rounded))
          .foregroundStyle(.white.opacity(0.30))
      }
    }
  }

  private func hashHues(seed: Int) -> (Double, Double) {
    let h1 = Double(abs(seed) % 360)
    let h2 = (h1 + 40).truncatingRemainder(dividingBy: 360)
    return (h1, h2)
  }
}
```

---

## ParallaxCoverHeader

```swift
struct ParallaxCoverHeader: View {
  let playlist: Playlist
  @Binding var collapseProgress: CGFloat  // 0 = full, 1 = collapsed

  var body: some View {
    GeometryReader { geo in
      let minY = geo.frame(in: .named("scroll")).minY
      let progress = max(0, min(1, -minY / 180))   // collapse over 180pt of scroll

      PlaylistCover(playlist: playlist)
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
        .frame(width: 240 - (240 - 64) * progress,
               height: 240 - (240 - 64) * progress)
        .frame(maxWidth: .infinity)
        .padding(.top, 8)
        .onChange(of: progress) { _, new in collapseProgress = new }
    }
    .frame(height: 240)
  }
}
```

Linear interpolation tied to scroll offset — never `.spring`.

---

## Action row (Play / Shuffle)

```swift
HStack(spacing: 12) {
  Button { player.play(playlist) } label: {
    Label("Play", systemImage: "play.fill")
      .frame(maxWidth: .infinity)
  }
  .buttonStyle(.borderedProminent)
  .tint(.accent)
  .controlSize(.large)

  Button { player.shuffle(playlist) } label: {
    Label("Shuffle", systemImage: "shuffle")
      .frame(maxWidth: .infinity)
  }
  .buttonStyle(.bordered)
  .tint(.white.opacity(0.15))
  .foregroundStyle(.white)
  .controlSize(.large)
}
.padding(.horizontal)
.sensoryFeedback(.impact(weight: .medium), trigger: lastPlayAction)
```

Both buttons: 50pt min height (`.controlSize(.large)`), capsule via `.buttonBorderShape(.capsule)` if needed.

---

## Toolbar — PlaylistDetail

```swift
.toolbar {
  ToolbarItem(placement: .principal) {
    if collapseProgress > 0.7 {
      Text(playlist.name)
        .font(.system(size: 17, weight: .semibold))
        .transition(.opacity)
    }
  }
  ToolbarItem(placement: .topBarTrailing) {
    EditButton()
  }
  ToolbarItem(placement: .topBarTrailing) {
    Menu {
      ShareLink(item: playlistExportURL) { Label("Share", systemImage: "square.and.arrow.up") }
      Button { showAddTracks = true } label: { Label("Add Tracks", systemImage: "plus") }
      Button { showRename = true } label: { Label("Rename", systemImage: "pencil") }
      Picker("Sort By", selection: $sort) { /* ... */ }
      Divider()
      Button(role: .destructive) { showDelete = true } label: {
        Label("Delete Playlist", systemImage: "trash")
      }
    } label: { Image(systemName: "ellipsis.circle") }
  }
}
```

`ShareLink` over `Button + share()` — gives system share-sheet for free, including AirDrop / Messages / saved-to-Files for the `.ytplaylist.json` export.

---

## Edit mode (native)

```swift
ForEach(playlist.tracks) { pt in
  TrackRow(track: pt.track)
    .swipeActions(edge: .trailing, allowsFullSwipe: true) {
      Button(role: .destructive) {
        remove(pt)
        showUndo("Removed \(pt.track.title)")
      } label: { Label("Remove", systemImage: "trash") }
    }
    .contextMenu {
      Button { showAddTracks = true; targetTrack = pt.track } label: {
        Label("Add to Playlist", systemImage: "plus")
      }
      ShareLink(item: pt.track.shareURL) { Label("Share", systemImage: "square.and.arrow.up") }
      Divider()
      Button(role: .destructive) { remove(pt) } label: {
        Label("Remove from Playlist", systemImage: "trash")
      }
    }
}
.onMove { source, destination in
  var arr = playlist.tracks
  arr.move(fromOffsets: source, toOffset: destination)
  for (i, pt) in arr.enumerated() { pt.sortIndex = i }
}
```

`EditButton` toggles `.environment(\.editMode)` — system renders leading `−` and trailing triple-bar drag glyphs automatically. **Don't draw them.**

---

## Rename — `.alert` (iOS 16+)

```swift
.alert("Rename playlist", isPresented: $showRename) {
  TextField("Name", text: $newName)
    .textInputAutocapitalization(.words)
  Button("Save") {
    playlist.name = newName.trimmingCharacters(in: .whitespacesAndNewlines)
  }
  .disabled(newName.trimmingCharacters(in: .whitespaces).isEmpty)
  Button("Cancel", role: .cancel) {}
} message: {
  Text("Choose a new name for this playlist.")
}
```

---

## Delete — `.confirmationDialog`

```swift
.confirmationDialog(
  "Delete this playlist?",
  isPresented: $showDelete,
  titleVisibility: .visible
) {
  Button("Delete Playlist", role: .destructive) {
    modelContext.delete(playlist)
    dismiss()
  }
  Button("Cancel", role: .cancel) {}
} message: {
  Text("The tracks will stay in your library. This can't be undone.")
}
```

---

## AddToPlaylistSheet

```swift
struct AddToPlaylistSheet: View {
  let track: Track
  @Query(sort: \Playlist.sortIndex) var playlists: [Playlist]
  @State private var selected: Set<Playlist.ID> = []
  @Environment(\.dismiss) var dismiss

  var body: some View {
    NavigationStack {
      List {
        Section {
          Button { createNewAndAdd() } label: {
            Label("New Playlist", systemImage: "plus.circle.fill")
              .foregroundStyle(.accent)
          }
        }
        Section("Playlists") {
          ForEach(playlists) { pl in
            HStack {
              PlaylistCover(playlist: pl).frame(width: 36, height: 36)
                .clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))
              Text(pl.name)
              Spacer()
              if selected.contains(pl.id) {
                Image(systemName: "checkmark").foregroundStyle(.accent)
              }
            }
            .contentShape(Rectangle())
            .onTapGesture { toggle(pl.id) }
          }
        }
      }
      .navigationTitle("Add to playlist")
      .navigationBarTitleDisplayMode(.inline)
      .toolbar {
        ToolbarItem(placement: .topBarLeading) { Button("Cancel") { dismiss() } }
        ToolbarItem(placement: .topBarTrailing) {
          Button("Done") { commit() }
            .disabled(selected.isEmpty)
            .fontWeight(.semibold)
        }
      }
    }
    .presentationDetents([.medium, .large])
    .presentationDragIndicator(.visible)
  }
}
```

---

## Undo snackbar (track removal only)

A lightweight banner anchored above the MiniPlayer — auto-dismisses after 4s.
Don't use `.alert`; don't block input.

```swift
if let undo = undoToast {
  HStack {
    Text(undo.message)
    Spacer()
    Button("Undo") { undo.action() }
      .fontWeight(.semibold)
      .foregroundStyle(.accent)
  }
  .padding(.horizontal, 16).padding(.vertical, 12)
  .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
  .padding(.horizontal, 16)
  .transition(.move(edge: .bottom).combined(with: .opacity))
}
```

---

## Empty state

```swift
ContentUnavailableView {
  Label("No playlists yet", systemImage: "music.note.list")
} description: {
  Text("Create one to organize tracks for offline listening.")
} actions: {
  Button("Create Playlist") { showCreate = true }
    .buttonStyle(.borderedProminent)
    .tint(.accent)
    .controlSize(.large)
}
```

Visible only when `playlists.isEmpty` AND no Recently Played items. Recently Played row stays visible even when no playlists exist (it's a separate destination).

---

## Accessibility

- All rows: `.accessibilityElement(children: .combine)` + composed label
  including count + status. Hint describes destination.
- Dynamic Type: every text view uses `.font(.system(size: ..., weight: ...))`
  so Type Sizes scale; clamp at `.accessibility3` for layout-critical labels
  via `.dynamicTypeSize(...DynamicTypeSize.accessibility3)`.
- VoiceOver focus order on PlaylistDetail: cover → name → counts → Play →
  Shuffle → first track row.
- 44pt minimum hit targets — wrap small icon-only buttons with `.frame(minWidth: 44, minHeight: 44).contentShape(Rectangle())`.
- Reduce Motion: parallax cover snaps instead of interpolating; cross-fade
  for screen pushes.
- VoiceOver labels for the cover gradient fallback: "Playlist cover, gradient" — skip the decorative gradient, don't read its hues.

---

## Build order

1. `LibraryScreen` shell — empty state + Recently Played row.
2. `PlaylistRow` + `PlaylistCover` (single + 4-up + gradient fallback).
3. Create flow.
4. `PlaylistDetailScreen` — parallax + Play/Shuffle.
5. Edit mode (`.onMove`, `.onDelete`, `.swipeActions`).
6. AddToPlaylistSheet.
7. Rename / Delete dialogs + Undo snackbar.
8. A11y pass.
