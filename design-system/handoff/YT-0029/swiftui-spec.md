# YT-0029 — SwiftUI Implementation Spec

Concrete view hierarchy + token usage + state management for Recently Played. Pairs with `decision-log.md` (rationale) and `mockup.html` (visual target). Aesthetic tone: see [`../YT-0027/aesthetic-direction.md`](../YT-0027/aesthetic-direction.md).

---

## File layout

```
ios/YourTube/Features/History/
  RecentlyPlayedScreen.swift              ← view; toolbar Menu + alert + .swipeActions + .contextMenu
  HistoryViewModel.swift                  ← @Observable; loading / error / empty / content gate
  HistoryDayGrouping.swift                ← pure: [HistoryEntry] → [DayGroup]
  HistoryTimestampFormatter.swift         ← pure: format(now:, playedAt:) -> String
ios/YourTube/Core/Persistence/
  HistoryEntryEntity.swift                ← SwiftData @Model (exists)
  HistoryStore.swift                      ← dedup-by-videoId pipeline (per YT-0048)
ios/YourTube/DesignSystem/Components/
  TrackRow.swift                          ← shared row (exists; reused, NOT forked)
  SkeletonTrackRow.swift                  ← shared skeleton row (C12)
ios/YourTube/Features/Library/
  LibraryScreen.swift                     ← NavigationLink → RecentlyPlayedScreen (per YT-0028)
```

The dedup pipeline (Q3) already lives in `HistoryStore`. Three previous fixes (YT-0048 / YT-0154 / YT-0158) landed it there. Do not move it.

---

## SwiftData model — already shipped

```swift
@Model final class HistoryEntryEntity {
  @Attribute(.unique) var id: UUID = UUID()
  var videoId: String
  var trackTitle: String
  var channel: String
  var thumbnailURL: URL?
  var durationMs: Int
  var playedAt: Date

  init(videoId: String, trackTitle: String, channel: String, /* ... */ playedAt: Date) {
    self.videoId = videoId
    // ...
    self.playedAt = playedAt
  }
}
```

`HistoryStore` exposes:

```swift
@MainActor protocol HistoryStore {
  func entries() async throws -> [HistoryEntry]   // dedup'd by videoId, sorted desc
  func remove(_ entryId: UUID) async throws
  func clearAll() async throws
}
```

The `entries()` implementation applies dedup-by-videoId at the store boundary (Q3 — do not relitigate).

---

## ViewModel — `@Observable`

```swift
@Observable
final class RecentlyPlayedViewModel {
  // MARK: State
  enum Phase {
    case loading
    case empty
    case content([HistoryEntry])
    case error(Error)
  }

  private(set) var phase: Phase = .loading
  private let store: HistoryStore
  private let player: PlayerCoordinator
  private var refreshTask: Task<Void, Never>?

  init(store: HistoryStore, player: PlayerCoordinator) {
    self.store = store
    self.player = player
  }

  // MARK: Lifecycle
  func task() async {
    refreshTask?.cancel()
    refreshTask = Task { await load() }
    await refreshTask?.value
  }

  func retry() async { await load() }

  private func load() async {
    phase = .loading
    do {
      let entries = try await store.entries()
      phase = entries.isEmpty ? .empty : .content(entries)
    } catch {
      phase = .error(error)
    }
  }

  // MARK: Actions
  func play(_ entry: HistoryEntry)        { player.playNow(entry.track) }
  func playNext(_ entry: HistoryEntry)    { player.enqueueNext(entry.track) }
  func addToQueue(_ entry: HistoryEntry)  { player.enqueueLast(entry.track) }

  func remove(_ entry: HistoryEntry) {
    Task {
      try? await store.remove(entry.id)
      await load()
    }
  }

  func clearAll() {
    Task {
      try? await store.clearAll()
      phase = .empty
    }
  }

  // MARK: Derived
  var dayGroups: [DayGroup] {
    if case .content(let entries) = phase { return groupByDay(entries, now: .now) }
    return []
  }

  var hasEntries: Bool {
    if case .content = phase { return true } else { return false }
  }
}
```

> **Note: live `@Query` already drives the shipped `RecentlyPlayedScreen.swift`.** The ViewModel above is the gate for the loading / error phases — content updates flow through SwiftData live so `phase` re-derives whenever the store emits.

---

## View hierarchy

```
NavigationStack
└─ LibraryScreen
   └─ List { Section { NavigationLink { RecentlyPlayedScreen } } }   // YT-0028

RecentlyPlayedScreen
├─ Group (switch viewModel.phase)
│  ├─ .loading  →  HistorySkeleton                                    // C12
│  ├─ .empty    →  EmptyHistoryView                                   // C13
│  ├─ .error    →  ErrorHistoryView                                   // C14
│  └─ .content  →  HistoryList
│
├─ .navigationTitle("Recently Played")
├─ .toolbar {
│    ToolbarItem(.topBarTrailing) {
│      if viewModel.hasEntries { Menu { ClearAllButton } label: { Image(systemName: "ellipsis.circle") } }
│    }
│  }
├─ .alert("Clear watch history?", isPresented: $showClearConfirm) { ... }
├─ .safeAreaInset(.bottom) { MiniPlayer spacer }
└─ .task { await viewModel.task() }
```

---

## `RecentlyPlayedScreen`

```swift
struct RecentlyPlayedScreen: View {
  @State var viewModel: RecentlyPlayedViewModel
  @State private var showClearConfirm = false
  @State private var addToPlaylistTarget: HistoryEntry?
  @Environment(\.miniPlayerVisible) private var miniPlayerVisible

  var body: some View {
    Group {
      switch viewModel.phase {
      case .loading:        HistorySkeleton()
      case .empty:          EmptyHistoryView { /* switch to Search tab */ }
      case .error:          ErrorHistoryView { Task { await viewModel.retry() } }
      case .content:        HistoryList(viewModel: viewModel,
                                        onAddToPlaylist: { addToPlaylistTarget = $0 })
      }
    }
    .navigationTitle("Recently Played")
    .navigationBarTitleDisplayMode(.large)
    .toolbar {
      ToolbarItem(placement: .topBarTrailing) {
        if viewModel.hasEntries {
          Menu {
            Button(role: .destructive) { showClearConfirm = true } label: {
              Label("Clear All", systemImage: "trash")
            }
          } label: {
            Image(systemName: "ellipsis.circle")
              .accessibilityLabel("More options")
          }
        }
      }
    }
    .alert("Clear watch history?", isPresented: $showClearConfirm) {
      Button("Cancel", role: .cancel) {}
      Button("Clear", role: .destructive) { viewModel.clearAll() }
    } message: {
      Text("This permanently removes all entries from your history. Tracks themselves stay in your library.")
    }
    .sheet(item: $addToPlaylistTarget) { entry in
      AddToPlaylistSheet(track: entry.track)
    }
    .safeAreaInset(edge: .bottom) {
      Color.clear.frame(height: miniPlayerVisible ? 60 : 0)
    }
    .task { await viewModel.task() }
  }
}
```

---

## `HistoryList` — day-grouped sections (Q1)

```swift
struct HistoryList: View {
  let viewModel: RecentlyPlayedViewModel
  let onAddToPlaylist: (HistoryEntry) -> Void
  let now: Date = .now            // re-computed on body recomputation

  var body: some View {
    List {
      ForEach(viewModel.dayGroups) { group in
        Section {
          ForEach(group.entries) { entry in
            HistoryRow(entry: entry, now: now)
              .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                Button(role: .destructive) {
                  viewModel.remove(entry)
                } label: {
                  Label("Remove", systemImage: "trash")
                }
              }
              .contextMenu {
                Button { viewModel.playNext(entry) }   label: { Label("Play Next", systemImage: "text.insert") }
                Button { viewModel.addToQueue(entry) } label: { Label("Add to Queue", systemImage: "text.append") }
                Button { onAddToPlaylist(entry) }     label: { Label("Add to Playlist", systemImage: "plus.rectangle.on.rectangle") }
                Divider()
                Button(role: .destructive) { viewModel.remove(entry) } label: {
                  Label("Remove from History", systemImage: "trash")
                }
                Divider()
                ShareLink(item: entry.track.shareURL) { Label("Share", systemImage: "square.and.arrow.up") }
              }
              .onTapGesture { viewModel.play(entry) }
          }
        } header: {
          Text(group.label)
            .font(.subheadline)
            .foregroundStyle(.secondary)
            .textCase(nil)                          // headers keep "Today", not "TODAY"
            .accessibilityAddTraits(.isHeader)
        }
      }
    }
    .listStyle(.plain)
    .scrollContentBackground(.hidden)
    .background(Color(.systemBackground))
  }
}
```

### Why `.listStyle(.plain)` and not `.insetGrouped`

YT-0028 uses `.insetGrouped` for Library / PlaylistDetail (form-style). History is a single long list of equally-weighted entries — `.plain` is the right rhythm. `.insetGrouped` would force every section into a separate card with internal padding, breaking the date-scale continuity.

### Liquid Glass note (iOS 26+)

We DO NOT apply a glass material to the list background. Tab bar + nav bar already carry Liquid Glass; the list body stays on `Color(.systemBackground)` so the section headers don't ride a translucent ground. Adding `.background(.regularMaterial)` would (a) double up the glass treatment with the chrome and (b) reduce sticky-header text contrast.

---

## `HistoryRow`

`TrackRow` is the shared component from `DesignSystem/Components/TrackRow`. History composes it with a non-nil `supportingExtra` (the formatted timestamp). The wrapper exists only to compute the timestamp:

```swift
struct HistoryRow: View {
  let entry: HistoryEntry
  let now: Date

  var body: some View {
    TrackRow(
      track: entry.track,
      supportingExtra: formatHistoryTimestamp(now: now, playedAt: entry.playedAt)
    )
    .accessibilityElement(children: .combine)
    .accessibilityLabel(Self.a11yLabel(entry: entry, now: now))
    .accessibilityHint("Plays this track")
  }

  static func a11yLabel(entry: HistoryEntry, now: Date) -> String {
    "\(entry.track.title), \(entry.track.channel), played \(spokenTime(entry: entry, now: now))"
  }
}
```

`spokenTime(...)` is in `HistoryTimestampFormatter.swift`. See `haptics-and-a11y.md` for the spoken-vs-visual format table.

---

## `HistoryTimestampFormatter` (pure)

```swift
func formatHistoryTimestamp(now: Date, playedAt: Date) -> String {
  let delta = now.timeIntervalSince(playedAt)
  if delta < 60 { return "just now" }
  if delta < 3600 {
    let m = Int(delta / 60)
    return "\(m) minute\(m == 1 ? "" : "s") ago"
  }
  if delta < 86_400 {
    let h = Int(delta / 3600)
    return "\(h) hour\(h == 1 ? "" : "s") ago"
  }

  let cal = Calendar.current
  let timeFmt = Date.FormatStyle().hour().minute()       // honors locale 12/24h
  if cal.isDateInToday(playedAt)     { return "Today, \(playedAt.formatted(timeFmt))" }
  if cal.isDateInYesterday(playedAt) { return "Yesterday, \(playedAt.formatted(timeFmt))" }

  return playedAt.formatted(Date.FormatStyle().day().month(.abbreviated)) +
         ", " + playedAt.formatted(timeFmt)
}
```

The visual form is terse — 24 h or 12 h based on locale; the spoken form uses `Date.FormatStyle()` with the relative configuration to honor VoiceOver's time announcement conventions. See `haptics-and-a11y.md`.

---

## `HistoryDayGrouping` (pure)

```swift
struct DayGroup: Identifiable {
  enum Key: Hashable { case today, yesterday, date(DateComponents) }
  let id: AnyHashable
  let label: String
  let entries: [HistoryEntry]
}

func groupByDay(_ entries: [HistoryEntry], now: Date,
                calendar: Calendar = .current,
                locale: Locale = .current) -> [DayGroup] {
  var buckets: [DayGroup.Key: [HistoryEntry]] = [:]
  var orderedKeys: [DayGroup.Key] = []

  for e in entries {
    let key: DayGroup.Key
    if calendar.isDateInToday(e.playedAt) { key = .today }
    else if calendar.isDateInYesterday(e.playedAt) { key = .yesterday }
    else {
      let comps = calendar.dateComponents([.year, .month, .day], from: e.playedAt)
      key = .date(comps)
    }
    if buckets[key] == nil { orderedKeys.append(key) }
    buckets[key, default: []].append(e)
  }

  let absFmt = Date.FormatStyle().day().month(.abbreviated).year().locale(locale)

  return orderedKeys.map { key in
    let label: String
    switch key {
    case .today: label = "Today"
    case .yesterday: label = "Yesterday"
    case .date(let comps):
      let d = calendar.date(from: comps)!
      label = d.formatted(absFmt)
    }
    return DayGroup(id: AnyHashable(key), label: label, entries: buckets[key] ?? [])
  }
}
```

Pure, unit-tested in `Features/History/HistoryDayGroupingTests.swift`.

---

## Skeleton (C12)

```swift
struct HistorySkeleton: View {
  var body: some View {
    List {
      ForEach(0..<8) { _ in
        SkeletonTrackRow()
          .listRowSeparator(.hidden)
      }
    }
    .listStyle(.plain)
    .scrollContentBackground(.hidden)
    .background(Color(.systemBackground))
    .accessibilityValue("Loading")
  }
}
```

`SkeletonTrackRow` lives in `DesignSystem/Components/`. Shimmer cycle 1400 ms linear infinite via a custom modifier; Reduce Motion swaps shimmer for static placeholder fill.

No section headers in the skeleton — the date scale isn't known until data arrives (Q10).

---

## Empty (C13)

```swift
struct EmptyHistoryView: View {
  let onBrowse: () -> Void
  var body: some View {
    ContentUnavailableView {
      Label("Nothing played yet", systemImage: "clock.arrow.circlepath")
    } description: {
      Text("Tracks you play will show up here.")
    } actions: {
      Button("Browse search") { onBrowse() }
        .buttonStyle(.borderedProminent)
        .tint(.accent)
        .controlSize(.large)
    }
  }
}
```

`onBrowse` posts a notification or calls into the shell's tab coordinator to switch to the Search tab. The toolbar ellipsis (Q6) is hidden by the conditional `if viewModel.hasEntries` in `RecentlyPlayedScreen`.

---

## Error (C14)

```swift
struct ErrorHistoryView: View {
  let onRetry: () -> Void
  var body: some View {
    ContentUnavailableView {
      Label("Couldn't load history", systemImage: "exclamationmark.triangle")
        .foregroundStyle(.secondary)         // icon stays secondary — NOT red
    } description: {
      Text("Try again in a moment.")
    } actions: {
      Button("Try Again") { onRetry() }
        .buttonStyle(.borderedProminent)
        .tint(.accent)
        .controlSize(.large)
    }
    .accessibilityElement(children: .combine)
  }
}
```

The icon stays on `.secondary` foreground per state-catalog § "Error icon is NOT red". Red is reserved for destructive surfaces; a failed list load is recoverable and benign, and the retry button carries the affordance.

---

## Navigation entry (Q9) — wired from Library

```swift
// LibraryScreen.swift
List {
  Section {
    NavigationLink {
      RecentlyPlayedScreen(viewModel: .init(store: store, player: player))
    } label: {
      HStack(spacing: 14) {
        Image(systemName: "clock.arrow.circlepath")
          .foregroundStyle(.accent)
          .frame(width: 32, height: 32)
          .background(Color(.systemGray6), in: RoundedRectangle(cornerRadius: 8))
        Text("Recently Played")
        Spacer(minLength: 0)
      }
    }
  }
  // ... Playlists section
}
```

`NavigationStack` (provided by the Library root) handles push / back. System swipe back works automatically; the back-chevron uses the Library title as the back label.

---

## MiniPlayer coexistence

```swift
.safeAreaInset(edge: .bottom) {
  Color.clear.frame(height: miniPlayerVisible ? 60 : 0)
}
```

Reads `miniPlayerVisible` from environment (provided by the shell). Don't hardcode 60 pt as padding.

---

## Token mapping (mockup → SwiftUI)

| Mockup `C.*` / CSS | Native iOS |
|---|---|
| `C.bg` `#000` | `Color(.systemBackground)` (dark `Color.black`) |
| `C.surface` `#1C1C1E` | `Color(.secondarySystemBackground)` for list separators / muted bg |
| `C.fg1` white | `.primary` (auto-adapts to system theme) |
| `C.fg2` 60 % white | `.secondary` (timestamps, section headers) |
| `C.accent` `#8B5CF6` | `.accent` — propagate via `.tint(.accent)` at the `RootTabView` |
| `C.red` `#FF453A` | `Color(.systemRed)` / `role: .destructive` |
| Radius row thumb 8 px | `RoundedRectangle(cornerRadius: 8, style: .continuous)` |
| Sticky CSS day header | Native `Section { } header: { Text(...) }` |
| Swipe peek `translateX(-Xpx)` | `.swipeActions(edge: .trailing) { Button(role: .destructive) }` |
| Bottom-sheet contextual menu | `.contextMenu` |
| Confirm dialog modal | `.alert("...", isPresented:) { Button("Clear", role: .destructive) ... }` |

---

## Accessibility

- `HistoryRow` combines children and exposes a composed label including count + status (see `haptics-and-a11y.md`).
- Dynamic Type: row text uses `.font(.body)` / `.font(.subheadline)` so types scale; clamp at `.accessibility3` for layout-critical labels via `.dynamicTypeSize(...DynamicTypeSize.accessibility3)`.
- Section header gets `.accessibilityAddTraits(.isHeader)` so the VoiceOver rotor exposes "Today", "Yesterday", etc. as heading anchors.
- 44 pt minimum hit targets — `TrackRow` already meets this.
- Reduce Motion: shimmer becomes static placeholder fill; swipe spring-back becomes instant snap.
- VoiceOver focus on screen change: lands on the nav title; subsequent navigation reads headers + rows in order.

---

## Build order

1. `RecentlyPlayedViewModel` (or align with the shipped one in `Features/History/HistoryViewModel.swift`).
2. `HistoryTimestampFormatter` + `HistoryDayGrouping` (pure helpers; unit-test first).
3. `HistoryRow` composition over `TrackRow`.
4. `HistoryList` with `Section` + `swipeActions` + `contextMenu`.
5. `RecentlyPlayedScreen` shell, `.toolbar`, `.alert`.
6. `HistorySkeleton`, `EmptyHistoryView`, `ErrorHistoryView`.
7. Library `NavigationLink` (per YT-0028 — already shipped).
8. Accessibility pass (Dynamic Type, VoiceOver row reads, rotor headings).

A working History list with native swipe-to-delete beats a polished one with custom slide metrics. **Spend the polish budget on the day-grouping helper and the timestamp formatter** — those are where perceived quality lives.
