# YT-0026 — Decision Log

iOS Search screen for YourTube. The Search screen is the most-touched surface in the app and had no decision log before today. This file locks the shipped behavior. Each decision below is validated against `docs/design-system.md`, `design-system/tokens/tokens.json`, the state-catalog (`design-system/handoff/state-catalog/`), and the symbol-map (`design-system/handoff/symbol-map/symbol-map.md`).

> **Original UI; behavioral inspiration only from iOS system search affordances.** Do not recreate any specific third-party app's surface.

Cross-platform sibling: `design-system/handoff/YT-0012/` — same decisions, Android idioms.

---

## 1 — Search-bar shape and placement — native `.searchable(text:)` on the navigation root

**Decision: use SwiftUI's native `.searchable(text:)` modifier on the root `NavigationStack` of the Search tab.** Do **not** roll a custom `TextField` with a rounded background.

```swift
NavigationStack {
  SearchBody(...)
    .navigationTitle("Search")
}
.searchable(
  text: $vm.queryBinding,
  placement: .navigationBarDrawer(displayMode: .always),
  prompt: "Search YourTube"
)
.searchPresentationToolbarBehavior(.avoidHidingContent)   // iOS 17+
.searchSuggestions { /* chips (D3) */ }                    // iOS 17+
.onSubmit(of: .search) { vm.submit() }                     // bypasses debounce (D2)
```

**Why native, not custom.**
- `.searchable` provides the system's appearance (translucent fill, leading `magnifyingglass`, trailing `xmark.circle.fill`), automatic Cancel button, automatic keyboard management, automatic VoiceOver labeling, and on iOS 26 it participates in Liquid Glass when appropriate — all for free.
- A custom `TextField` with `RoundedRectangle(... .continuous)` background reimplements every one of those for a worse result. The only thing it gains is custom artwork in the leading slot, which we do not need (D8 — no voice mic in MVP).
- `.navigationBarDrawer(displayMode: .always)` keeps the field visible at all times (not pinned only on scroll). For Search — the primary action of the screen — always-visible is correct.

**Anti-patterns.**
- ❌ Custom `TextField` with `RoundedRectangle(cornerRadius: 18, style: .continuous)` + leading `magnifyingglass` + trailing `xmark.circle.fill` — reimplements the system's surface, drifts under iOS 26 Liquid Glass.
- ❌ `.searchable(... placement: .toolbar)` on iOS 17 — that placement reads as auxiliary; Search-as-tab-root needs `.navigationBarDrawer`.
- ❌ Hoisting `.searchable` to a parent `TabView` — affects all tabs; we only want Search.

---

## 2 — Debounce — 400 ms on `onChange`, Return key bypasses

**Decision: 400 ms debounce on text changes. The keyboard Return key (`.onSubmit(of: .search)`) fires immediately, bypassing the debounce.**

Implementation in `SearchViewModel`:

```swift
@Observable final class SearchViewModel {
  var query: String = "" {
    didSet { scheduleDebouncedSearch() }
  }
  private var debounceTask: Task<Void, Never>?

  private func scheduleDebouncedSearch() {
    debounceTask?.cancel()
    let q = query
    if q.isEmpty { body = .idle; return }
    debounceTask = Task { @MainActor in
      try? await Task.sleep(for: .milliseconds(400))
      if Task.isCancelled { return }
      await runSearch(q)
    }
  }

  func submit() {                            // .onSubmit(of: .search)
    debounceTask?.cancel()
    Task { await runSearch(query) }          // bypasses debounce
  }
}
```

Empty query (`query.isEmpty`) cancels in-flight requests and snaps to idle. It does NOT race to no-results.

**Rationale.** 400 ms matches the Android sibling (`YT-0012` §D2). The Return-key fast-path is what makes physical-keyboard users (iPad + Magic Keyboard, Stage Manager) not feel stuck.

**Anti-patterns.**
- ❌ Sub-200 ms debounce — flood the suggest endpoint on every keystroke.
- ❌ Re-firing the debounce after Return — fires the same query twice; one wins, the other gets cancelled, and a race ensues.
- ❌ Debouncing the cancel — empty-query should clear results synchronously.

---

## 3 — Suggestion chips (idle state) — max 6, recent + curated fallback

**Decision: When `query.isEmpty`, the body renders state-catalog **C2** cell with a horizontally-scrolling chip strip of up to 6 chips.**

Chip source priority (same as Android):
1. **Recent searches** — up to 20 stored locally (D6), surface the **6 most recent**, deduplicated and trimmed.
2. **Curated fallback** — when there are fewer than 6 recents, fill with `"lofi"`, `"focus"`, `"ambient"`, `"podcasts"` (per state-catalog C2). Static; do not personalize in MVP.

Layout: a horizontal `ScrollView(.horizontal, showsIndicators: false)` containing an `HStack(spacing: 8)`. **Reject `LazyVGrid` wrapping** — wrapping chips breaks visual rhythm against the C2 cell below.

Where to host: chips render **between the SearchBar drawer and the body**, NOT inside `.searchSuggestions { }`. The `.searchSuggestions` slot appears only when the field is focused — we want chips visible even when the field is unfocused and idle. They sit in the body view as a top section above the C2 cell.

Interaction:
- **Tap** fills the query and fires search immediately (D10) via `vm.submitChip(text)`. Also dismisses the keyboard via `.searchSuggestions`'s behavior — but because we render chips outside that slot, we explicitly call `dismissSearch` via `@Environment(\.dismissSearch)` only if results need to populate above the keyboard.
- **Context menu (long-press / right-click)** on a recent chip exposes "Remove from recent searches"; tap fires `vm.removeRecent(text)` + `.sensoryFeedback(.warning, ...)`. Curated chips have no context menu.
- **Selected state** — when the chip's text matches the current query (e.g. just tapped, results loading), the chip renders `.buttonStyle(.borderedProminent)` with `.tint(.accent)`.

**Anti-patterns.**
- ❌ More than 6 chips — strip stops feeling like accelerators.
- ❌ Mixing chip types (recent + trending + categories) in one row — user can't tell why a chip is there.
- ❌ Hosting chips inside `.searchSuggestions` — they vanish when the field blurs, which is wrong for our idle state.

---

## 4 — Currently-playing EQ indicator on result rows

**Decision: When a result row matches the currently-playing track, the row tints with `Color.accent.opacity(0.16)` and overlays an animated 3-bar EQ indicator in place of the leading "duration" badge.**

Condition:
```swift
let isActive = result.videoId == playerState.currentTrack?.videoId
let isPlaying = isActive && playerState.isPlaying
```

Visual:
- Three vertical `Capsule()`s, each 3 pt wide, 2 pt spacing. Total width ≈ 13 pt.
- Container: `24 × 24 pt`, bars bottom-aligned and centered horizontally.
- Color: `Color.accent` (asset catalog → `#8B5CF6` dark / `#7C3AED` light).
- Animation: `TimelineView(.animation(minimumInterval: 1.0 / 30.0, paused: !isPlaying))`. Each bar's height is `mix(min, max, 0.5 + 0.5 * sin(2π · (t / 0.6 + phase)))` where `phase ∈ {0, 0.2, 0.4}` — produces the 0/120/240 ms stagger that matches the Android sibling.

Paused state (`isActive && !isPlaying`):
- Tint **stays** (the row remains the active row).
- `TimelineView`'s `paused: true` freezes the bars at their current height (TimelineView re-evaluates only on resume; iOS handles the snapshot).
- Bar color drops to `Color.accent.opacity(0.6)` to read as "queued, not active".

The active-row tint value `0.16` matches the YT-0027 web-isms table and the NowPlaying active-row tint. **This is a single token, not a per-screen value.**

**Anti-patterns.**
- ❌ A different active-row tint per screen — defeats the purpose of having a token.
- ❌ Three independent `withAnimation { }` loops — drift over time; the `TimelineView` clock is the only reliable source.
- ❌ All bars in lockstep — reads as a single bar.
- ❌ Hiding the duration badge when active without also showing the EQ — the row loses its rightmost weight.

---

## 5 — Empty / loading / error states — consume state catalog verbatim

**Decision: Search consumes state-catalog cells C1, C2, C3, C4, C5 directly. No per-screen redefinition.**

| State | Cell | When |
|---|---|---|
| Idle (no query yet) | **C2** | `SearchScreen` first appears OR `query.isEmpty` |
| Loading | **C1** — 6 skeleton track rows | A non-empty query is in flight |
| No results | **C3** | Server returned 0 matches |
| Generic error | **C4** | Non-2xx HTTP, decode failure, timeout (with `NetworkMonitor.isOnline == true`) |
| Offline | **C5** | Request failed AND `NetworkMonitor.isOnline == false` |
| Results | (own cell) | ≥1 match |

All copy, icon, button label, retry contract, accessibility announce, and skeleton shimmer rules are inherited from the state catalog. **Do not re-author them in this folder.**

Use `ContentUnavailableView` for C2/C3, the catalog's `ErrorView` host for C4/C5. Code in `swiftui-spec.md`.

**Anti-patterns.**
- ❌ Custom "Hmm, nothing found 🤔" copy — overrides catalog C3.
- ❌ Showing chips ALONGSIDE the loading skeleton (chips disappear during loading per state-catalog C1 and D3).
- ❌ Inline error banner on stale results — initial-load failure is a full screen (state-catalog status contract).

---

## 6 — Search history persistence — 20 most recent, local-only

**Decision: 20 most recent queries, stored locally via SwiftData (`SearchHistoryEntry` model). No iCloud sync, no PII beyond the query string, no timestamps surfaced.**

```swift
@Model final class SearchHistoryEntry {
  @Attribute(.unique) var query: String        // dedup by exact (trimmed) text
  var lastSearchedAt: Date                     // MRU ordering only
}
```

MRU: on every successful search submit, upsert with `Date.now`. On idle render, `@Query(sort: \.lastSearchedAt, order: .reverse)` limited to 20; chip strip surfaces the first 6.

**Clearing history.** Per-chip remove (D3 context menu) lives here. **Full "Clear search history"** lives in **Settings § Data** — `YT-0031/` owns that surface. Long-press of a single chip surfaces the toast-catalog "Removed from recent searches" with Undo (4 s).

**Anti-patterns.**
- ❌ iCloud sync of recent searches — YourTube MVP is local-first.
- ❌ Surfacing the timestamp on the chip ("3h ago") — leaks behavior; chips are accelerators not journals.
- ❌ Full-clear button on the Search screen — duplicates Settings; users hunt for it in one place.

---

## 7 — Focus and keyboard behavior

**Decision: On Search tab activation with `query.isEmpty`, the field gains focus immediately and the software keyboard opens.**

Mechanism: `@FocusState private var isSearchFocused: Bool` plus `.searchFocused($isSearchFocused)` (iOS 17+). On `onAppear` (guarded by `initialQuery.isEmpty`), set `isSearchFocused = true`. Set `.scrollDismissesKeyboard(.immediately)` on the results `List` so scrolling dismisses the IME.

| User action | Keyboard | Query | Results |
|---|---|---|---|
| Tab activated, blank state | opens | blank | C2 idle |
| Tap a result row | dismisses (handled by `NavigationLink` push) | preserved | scroll position preserved |
| Tap system Cancel (D9) | dismisses | cleared | C2 idle |
| Tap chip | dismisses (D10) — chip submit fires search, no further typing needed | filled with chip text | C1 → results |
| Pop back from NowPlaying to Search (results visible) | closed (don't reopen) | restored | scroll position restored |

The query persists in `@SceneStorage("search.query")` so process death / state restoration / back-stack pop all restore. Results are NOT cached in `@SceneStorage` (they refetch on restore; cheap with the 30 s in-memory repo cache).

**Anti-patterns.**
- ❌ Auto-opening the IME on every Search tab activation — aggressive when the user came back to read a stale result.
- ❌ `.scrollDismissesKeyboard(.never)` — keyboard stays parked over results; users complain it covers the bottom.
- ❌ Clearing the query on result tap — user wants to come back and tap a different result; losing the query forces a re-type.

---

## 8 — Voice search — explicitly deferred to post-MVP

**Decision: No voice-search button ships in MVP. The system keyboard's built-in dictation key is the only voice path.**

The `SearchScreen.swift` file may carry a `// TODO(post-MVP): voice search trigger` comment but **no UI element is rendered**. When voice ships, it will live as a trailing `searchToolbar(Placement.trailing)` button with `Image(systemName: "mic")` and a custom `SFSpeechRecognizer` flow.

**Rationale.** iOS already surfaces dictation via the system keyboard's `mic` glyph. Shipping our own mic button implies on-device speech recognition we don't have — a promise we can't keep.

**Anti-patterns.**
- ❌ Disabled mic button with "coming soon" — looks broken; promises a date we don't have.
- ❌ Overriding the system keyboard's mic — interferes with user IME preferences (Gboard / SwiftKey on iOS, system keyboard).

---

## 9 — Cancel / clear — system-provided, two affordances

**Decision: Use the system-provided clear button (`xmark.circle.fill`) on the field, and the system-provided **Cancel** button to the right of the field when focused. Do not override either.**

| State | Clear glyph | Cancel button |
|---|---|---|
| Unfocused, blank | hidden | hidden |
| Focused, blank | hidden | visible (system) |
| Focused, non-blank | visible (system) — tap clears query, focus preserved | visible (system) — tap clears query AND dismisses keyboard AND returns to previous screen state |
| Unfocused, non-blank | visible (system) — tap clears query | hidden |

The behavioral difference between clear and Cancel:
- **Clear** (`xmark.circle.fill`) — empties the field, keyboard stays, focus stays. User can immediately retype.
- **Cancel** — empties the field, dismisses keyboard, restores the previous screen state (if the user navigated into Search via tab switch, that's idle C2; if via a deep link from another tab, may pop).

This contrasts with Android (YT-0012 §D9), which has **only a clear glyph** — Android dismisses the keyboard via system back, not via a per-screen Cancel button.

**Anti-patterns.**
- ❌ Hiding the system Cancel button (`.searchableToolbarBehavior(.alwaysHidden)` or similar) — robs the user of the muscle-memory dismiss.
- ❌ Replacing `xmark.circle.fill` with a custom glyph — visual drift from the rest of iOS.

---

## 10 — Submit-on-chip-tap — single explicit decision

**Decision: Tapping a suggestion chip fills the query and fires a search immediately. No Return key needed, no second tap.**

Both platforms behave identically. This is recorded explicitly because iOS 18 vs 26 historically had inconsistent behavior across platform apps — we lock our app's behavior to "tap = search" everywhere.

Implementation: chip's `Button` action calls `vm.submitChip(text)` which sets `vm.query = text` and `await vm.runSearch(text)` in the same call. The `.searchable` binding observes the change and re-renders the field's text mid-frame.

If the chip text equals the current query, the tap is a **re-search** (re-runs the request — useful for retry / refresh).

**Anti-patterns.**
- ❌ Chip tap that requires a follow-up Return press — historically iOS-flavored, never shipped in YourTube.
- ❌ Chip tap that opens a category page — chips are accelerators, not categories; misreading them as nav primitives breaks the mental model.

---

## Mockup web-isms — translate to SwiftUI

| Mockup CSS / pattern | SwiftUI |
|---|---|
| `background: #2C2C2E` chip fill | `.tint(Color(.tertiarySystemFill))` + `.buttonStyle(.bordered)` |
| `background: rgba(139,92,246,0.16)` row tint | `Color.accent.opacity(0.16)` |
| `background: rgba(139,92,246,0.30)` chip selected | `.tint(.accent)` + `.buttonStyle(.borderedProminent)` |
| `box-shadow` under chips | Drop. iOS chips have no elevation. |
| `border-radius: 999px` chip | `.buttonBorderShape(.capsule)` |
| `transition: background 200ms` chip-press | system state-layer — built-in |
| `@keyframes eq` 600ms | `TimelineView(.animation)` driving `sin(2π · (t / 0.6 + phase))` |
| `font-feature-settings: "tnum"` | `.monospacedDigit()` |
| `width: 80%; height: 14px; background: #2C2C2E` skeleton | inherit from state-catalog `SkeletonTrackRow` — do not re-author |
| Hand-drawn search SVG | `Image(systemName: "magnifyingglass")` |
| Hand-drawn search-off SVG | `Image(systemName: "magnifyingglass").symbolVariant(.slash)` |
| Hand-drawn wifi-off SVG | `Image(systemName: "wifi.slash")` |
| Hand-drawn close SVG | system clear (provided by `.searchable`) |
