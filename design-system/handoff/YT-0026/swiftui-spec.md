# YT-0026 — SwiftUI Implementation Spec

> Companion to `decision-log.md`. This file is the technical contract — view hierarchy, state shape, animation specs, hit-targets, file layout. Open `mockup.html` and `mockup-states.html` side-by-side.

## File layout (suggested)

```
ios/YourTube/Features/Search/
  SearchScreen.swift              ← root view, NavigationStack + .searchable
  SearchViewModel.swift           ← @Observable, debounce, history MRU
  SearchUiState.swift             ← enum + Body cases
  SearchModule.swift              ← (existing) DI / module wiring
  SearchChipStrip.swift           ← horizontal chip strip (recent + curated)
  SearchResultRow.swift           ← TrackRow reuse + EQ overlay
  SearchEqIndicator.swift         ← TimelineView-driven 3-bar EQ
  SearchHistoryStore.swift        ← SwiftData @ModelActor MRU store
ios/YourTube/DesignSystem/Components/
  TrackRow.swift                  ← shared with NowPlaying / Library / History
  EmptyStateCells.swift           ← state-catalog C2 / C3 / C7 / C10 / C13 host
  ErrorStateCells.swift           ← state-catalog C4 / C5 / C8 / C11 / C14 host
  SkeletonTrackRow.swift          ← state-catalog C1 / C6 / C12 host
ios/YourTube/Core/YouTube/
  SearchRepository.swift          ← debounce-cancelable suggest + results, 30s cache
  NetworkMonitor.swift            ← isOnline AsyncStream consumed by SearchViewModel
```

## State shape

```swift
struct SearchUiState: Equatable {
  var query: String = ""
  var recentSuggestions: [String] = []                   // up to 6 visible, MRU
  let curatedFallback: [String] = ["lofi", "focus", "ambient", "podcasts"]
  var body: Body = .idle

  enum Body: Equatable {
    case idle                                            // C2 — chips visible
    case loading                                         // C1 — skeleton ×6
    case results([SearchResult])
    case noResults                                       // C3
    case error(Cause)                                    // C4 (generic) or C5 (offline)
  }
  enum Cause: Equatable { case generic, offline }

  /// Recents first (up to 6), then curated to fill.
  var visibleSuggestions: [Chip] {
    let recents = recentSuggestions.prefix(6).map { Chip(text: $0, isRecent: true) }
    let needed = max(0, 6 - recents.count)
    let curated = curatedFallback.prefix(needed).map { Chip(text: $0, isRecent: false) }
    return Array(recents) + Array(curated)
  }
  struct Chip: Identifiable, Equatable {
    var id: String { text }
    let text: String
    let isRecent: Bool
  }
}
```

## State machine

```
                                  (query.isEmpty && body == .idle)
                                              ↑
   tab activated  ──►  .idle ───── focus, chips visible ─────►  .idle (focused, IME open)
                          │
                          │  user types ≥ 1 char (after 400 ms debounce, OR Return)
                          ▼
                       .loading  ── results arrive ──►  .results([...])
                          │                                  │
                          │                                  │ user taps Cancel / clear (D9)
                          │                                  ▼
                          │                              .idle
                          ├── 0 matches ──►  .noResults  ── chip tap (D10) / clear ──► .idle / .loading
                          ├── HTTP fail + online ──►  .error(.generic)
                          └── HTTP fail + offline ──►  .error(.offline)

                       any error / no-results → "Try again" → .loading (re-run same query)
```

## SearchViewModel — @Observable + debounce

```swift
@MainActor @Observable
final class SearchViewModel {
  // Persisted across scenes
  var query: String = "" {
    didSet { onQueryChanged() }
  }
  // Derived view state
  private(set) var body: SearchUiState.Body = .idle
  private(set) var recentSuggestions: [String] = []

  private let repo: SearchRepository
  private let history: SearchHistoryStore
  private let net: NetworkMonitor
  private var debounceTask: Task<Void, Never>?

  init(repo: SearchRepository, history: SearchHistoryStore, net: NetworkMonitor) {
    self.repo = repo
    self.history = history
    self.net = net
    Task { await reloadRecents() }
  }

  // D2 — text-change debounce; D2 — Return key (submit()) bypasses
  private func onQueryChanged() {
    debounceTask?.cancel()
    let q = query
    if q.isEmpty { body = .idle; return }
    debounceTask = Task { @MainActor in
      try? await Task.sleep(for: .milliseconds(400))
      guard !Task.isCancelled else { return }
      await runSearch(q)
    }
  }

  func submit() {
    debounceTask?.cancel()
    Task { await runSearch(query) }
  }

  // D10
  func submitChip(_ text: String) {
    query = text                                          // also triggers didSet → debounce armed
    debounceTask?.cancel()                                // but we bypass for chips
    Task { await runSearch(text) }
  }

  // D3 long-press context menu
  func removeRecent(_ q: String) async {
    await history.delete(q)
    await reloadRecents()
  }

  private func runSearch(_ q: String) async {
    body = .loading
    let res = await repo.search(q)
    switch res {
    case .success(let items) where items.isEmpty:
      body = .noResults
    case .success(let items):
      body = .results(items)
      await history.upsert(q)
      await reloadRecents()
    case .failure where !net.isOnline:
      body = .error(.offline)
    case .failure:
      body = .error(.generic)
    }
  }

  private func reloadRecents() async {
    recentSuggestions = await history.recents(limit: 20)
  }
}
```

## View hierarchy (top-down)

```
SearchScreen (the Search tab's root)
└─ NavigationStack
   └─ ScrollView (or List, depending on body case)
      ├─ if body == .idle || body == .noResults:
      │    SearchChipStrip(chips: vm.visibleSuggestions, selectedQuery: vm.query)
      │
      └─ switch body:
           .idle        → ContentUnavailableView (state-catalog C2 hosted by EmptyStateCells.c2)
           .loading     → ForEach(0..<6) { SkeletonTrackRow(index: $0) }     // C1
           .results(let items) → List {
                          ForEach(items) { result in
                            SearchResultRow(result: result, playerState: player.state)
                              .listRowBackground(activeTint(for: result))
                              .listRowSeparator(.hidden)
                              .contextMenu { rowMenu(for: result) }
                          }
                        }
                        .listStyle(.plain)
                        .scrollDismissesKeyboard(.immediately)
           .noResults   → ContentUnavailableView.search(text: vm.query)      // C3 — system-provided variant
           .error(let cause) → ErrorStateCells.cell(
                              cause == .offline ? .c5 : .c4,
                              onRetry: { vm.submit() },
                              onSecondary: cause == .offline ? { tabRouter.go(.library) } : nil
                            )
}
.searchable(
  text: $vm.query,
  placement: .navigationBarDrawer(displayMode: .always),
  prompt: "Search YourTube"
)
.searchPresentationToolbarBehavior(.avoidHidingContent)
.onSubmit(of: .search) { vm.submit() }                     // D2 fast-path
.navigationTitle("Search")
```

## .searchable wiring detail

`.searchable` returns a binding to the text and provides the system's clear button + Cancel button (D9) for free. We do not draw either ourselves.

We do **NOT** use `.searchSuggestions { }` for the chips — that slot appears only when the field is focused, but our chip strip must be visible when the field is unfocused-and-idle too. Chips live as a top section in the scrollable body.

For iOS 17+:
```swift
@FocusState private var isSearchFocused: Bool

.searchFocused($isSearchFocused)
.onAppear {
  if vm.query.isEmpty { isSearchFocused = true }          // D7
}
```

For iOS 16 fallback (if you ever ship it), drop `.searchFocused` and rely on `.searchable`'s implicit focus. (`tokens.json` and the design system target iOS 17+; this fallback is non-blocking.)

## SearchChipStrip (D3)

```swift
struct SearchChipStrip: View {
  let chips: [SearchUiState.Chip]
  let selectedQuery: String
  let onTap: (String) -> Void
  let onRemove: (String) -> Void

  var body: some View {
    ScrollView(.horizontal, showsIndicators: false) {
      HStack(spacing: 8) {
        ForEach(chips) { chip in
          ChipButton(chip: chip, isSelected: chip.text == selectedQuery, onTap: onTap, onRemove: onRemove)
        }
      }
      .padding(.horizontal, 16)
      .padding(.vertical, 8)
    }
    .scrollClipDisabled()                                  // iOS 17+
  }
}

private struct ChipButton: View {
  let chip: SearchUiState.Chip
  let isSelected: Bool
  let onTap: (String) -> Void
  let onRemove: (String) -> Void

  var body: some View {
    Button {
      onTap(chip.text)                                     // D10 — fires search immediately
    } label: {
      HStack(spacing: 6) {
        if chip.isRecent {
          Image(systemName: "clock")
            .imageScale(.small)
            .foregroundStyle(.secondary)
        }
        Text(chip.text)
      }
      .font(.system(size: 14, weight: .medium))
    }
    .buttonStyle(isSelected ? .borderedProminent : .bordered)
    .buttonBorderShape(.capsule)
    .controlSize(.regular)
    .tint(isSelected ? .accent : Color(.tertiarySystemFill))
    .foregroundStyle(isSelected ? Color.white : Color.primary)
    .contextMenu {
      if chip.isRecent {
        Button(role: .destructive) {
          onRemove(chip.text)                              // D3 — toast announce
        } label: {
          Label("Remove from recent searches", systemImage: "trash")
        }
      }
    }
    .sensoryFeedback(.selection, trigger: isSelected)
  }
}
```

## SearchResultRow + EQ overlay (D4)

```swift
struct SearchResultRow: View {
  let result: SearchResult
  let playerState: PlayerState
  let onTap: () -> Void

  private var isActive: Bool { result.videoId == playerState.currentTrack?.videoId }
  private var isPlaying: Bool { isActive && playerState.isPlaying }

  var body: some View {
    Button(action: onTap) {
      HStack(spacing: 12) {
        thumb
        VStack(alignment: .leading, spacing: 2) {
          Text(result.title)
            .font(.system(size: 15, weight: .medium))
            .foregroundStyle(.primary)
            .lineLimit(1)
          Text("\(result.channel) · \(result.duration)")
            .font(.system(size: 13))
            .foregroundStyle(.secondary)
            .monospacedDigit()
            .lineLimit(1)
        }
        Spacer(minLength: 0)
        Image(systemName: "ellipsis")
          .foregroundStyle(.secondary)
          .frame(width: 44, height: 44)
          .contentShape(Rectangle())
      }
      .padding(.vertical, 4)
      .padding(.horizontal, 16)
      .contentShape(Rectangle())
    }
    .buttonStyle(.plain)
  }

  @ViewBuilder
  private var thumb: some View {
    ZStack(alignment: .bottomTrailing) {
      RoundedRectangle(cornerRadius: 6, style: .continuous)
        .fill(result.thumbnailGradient)
        .frame(width: 60, height: 44)
      if isActive {
        SearchEqIndicator(isPlaying: isPlaying)
          .frame(width: 22, height: 22)
          .background(Color.black.opacity(0.55), in: RoundedRectangle(cornerRadius: 4, style: .continuous))
          .padding(3)
      } else {
        Text(result.duration)
          .font(.system(size: 10, weight: .medium))
          .monospacedDigit()
          .foregroundStyle(.white)
          .padding(.horizontal, 4).padding(.vertical, 1)
          .background(Color.black.opacity(0.65), in: RoundedRectangle(cornerRadius: 3, style: .continuous))
          .padding(3)
      }
    }
  }
}
```

Active-row tint goes on `.listRowBackground` at the call site:

```swift
.listRowBackground(
  result.videoId == playerState.currentTrack?.videoId
    ? Color.accent.opacity(0.16)
    : Color.clear
)
```

## SearchEqIndicator (D4 detail) — TimelineView-driven

```swift
struct SearchEqIndicator: View {
  let isPlaying: Bool

  @Environment(\.accessibilityReduceMotion) private var reduceMotion

  private static let minH: CGFloat = 4
  private static let maxH: CGFloat = 14
  private static let phases: [Double] = [0.0, 0.2, 0.4]    // 0 ms / 120 ms / 240 ms over 600 ms period

  var body: some View {
    if reduceMotion || !isPlaying {
      // Frozen pattern — three bars at a fixed mid-height.
      HStack(alignment: .bottom, spacing: 2) {
        Capsule().fill(barColor).frame(width: 3, height: 12)
        Capsule().fill(barColor).frame(width: 3, height: 9)
        Capsule().fill(barColor).frame(width: 3, height: 11)
      }
      .accessibilityHidden(true)
      return
    }

    TimelineView(.animation(minimumInterval: 1.0 / 30.0)) { context in
      let t = context.date.timeIntervalSinceReferenceDate
      HStack(alignment: .bottom, spacing: 2) {
        ForEach(0..<3, id: \.self) { i in
          let phase = Self.phases[i]
          let normalized = 0.5 + 0.5 * sin(2 * .pi * (t / 0.6 + phase))
          let h = Self.minH + (Self.maxH - Self.minH) * normalized
          Capsule().fill(barColor).frame(width: 3, height: h)
        }
      }
    }
    .accessibilityHidden(true)
  }

  private var barColor: Color {
    isPlaying ? .accent : .accent.opacity(0.6)
  }
}
```

Why `TimelineView`? It binds animation to the system clock, so resuming after `paused: true` snaps to the correct frame, multiple instances stay in lockstep, and `accessibilityReduceMotion` cleanly short-circuits to the static render path.

## State-catalog cell integration points

| Cell | Used in | Component | Reusable? |
|---|---|---|---|
| **C1** loading skeleton ×6 | `.loading` branch | `SkeletonTrackRow` (`DesignSystem/Components`) | shared with C6 / C12 |
| **C2** idle empty | `.idle` branch (chips above) | `ContentUnavailableView { Label("Search YourTube", systemImage: "magnifyingglass") } description: { ... }` | shared |
| **C3** no results | `.noResults` branch | `ContentUnavailableView.search(text: query)` — system-provided iOS 17+ variant; copy is the catalog's "No results for \"\(query)\"" via overridden init OR custom `ContentUnavailableView` |
| **C4** generic error | `.error(.generic)` | `ErrorStateCells.cell(.c4, onRetry: ...)` | shared |
| **C5** offline | `.error(.offline)` | `ErrorStateCells.cell(.c5, onRetry: ..., onSecondary: navToLibrary)` | shared — only cell with a secondary CTA |

**For C3 specifically:** `ContentUnavailableView.search(text:)` is the iOS 17+ system convenience that renders the catalog's intended layout (magnifyingglass + "No results for \"X\"" + body). It accepts no body override, so we ship the long-form `ContentUnavailableView` initializer to match the catalog copy exactly:

```swift
ContentUnavailableView {
  Label("No results for \"\(vm.query)\"", systemImage: "magnifyingglass")
} description: {
  Text("Check your spelling or try a different search.")
} actions: {
  Button("Clear search") { vm.query = "" }
    .buttonStyle(.borderedProminent)
    .tint(.accent)
    .controlSize(.large)
}
```

## Token mapping (mockup CSS → SwiftUI)

| Mockup / `pal.*` | SwiftUI |
|---|---|
| `--color-surface-variant: #2C2C2E` | `Color(.tertiarySystemFill)` |
| `--color-bg: #0F0F0F` | `Color(.systemBackground)` (auto-adapts) |
| `--color-accent: #8B5CF6` | `Color.accent` (asset catalog) |
| `rgba(139,92,246,0.16)` active row | `Color.accent.opacity(0.16)` |
| `rgba(139,92,246,0.30)` chip selected | `.tint(.accent)` + `.buttonStyle(.borderedProminent)` (system folds the opacity) |
| `--radius-md: 12px` | `RoundedRectangle(cornerRadius: 12, style: .continuous)` |
| `--font-sans` SF Pro | system default — don't import Geist on iOS |
| `font-feature-settings: "tnum"` | `.monospacedDigit()` |
| `@keyframes eq` 600ms | `TimelineView(.animation)` + `sin(...)` |
| Hand-drawn search SVG | `Image(systemName: "magnifyingglass")` |
| Hand-drawn search-off SVG | `Image(systemName: "magnifyingglass").symbolVariant(.slash)` |
| Hand-drawn wifi-off SVG | `Image(systemName: "wifi.slash")` |
| Hand-drawn close SVG | system clear (via `.searchable`) |
| Skeleton block `#2C2C2E` | inherit from `SkeletonTrackRow` — do not redefine |

## What the existing combined mockup gets wrong (do not port)

The legacy combined mockup at `design-system/mockups/ios/YT-0025-0026-0027-shell-search-player.html` differs from production:

1. **Chip strip:** mockup wraps via flexbox — production is a horizontal `ScrollView` (D3).
2. **Chip max count:** mockup shows 8+ — production caps at 6 (D3).
3. **Active row tint:** mockup uses solid `#3B2A5C` — production uses `Color.accent.opacity(0.16)` (D4), same as NowPlaying active row.
4. **EQ indicator:** mockup is a static SVG — production is `TimelineView(.animation)` with three phase-offset bars (D4).
5. **No-results copy:** mockup has bespoke copy — production consumes state-catalog C3 (D5).
6. **History clearing:** mockup shows a "Clear history" inline button — production defers full clear to Settings § Data (D6).
7. **Voice search:** mockup shows a mic icon in the trailing slot — production hides voice for MVP (D8).
8. **Cancel button:** mockup draws a custom "Cancel" pill — production uses the system-provided one from `.searchable` (D9).

Don't port the mockup styling to SwiftUI 1:1. Use it for layout reference only. This folder is the source of truth.
