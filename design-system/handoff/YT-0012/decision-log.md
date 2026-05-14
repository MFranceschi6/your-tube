# YT-0012 — Decision Log

Android Search screen for YourTube. The Search screen is the most-touched surface in the app and had no decision log before today. This file locks the shipped behavior. Each decision below is validated against `docs/design-system.md`, `design-system/tokens/tokens.json`, the state-catalog (`design-system/handoff/state-catalog/`), and the symbol-map (`design-system/handoff/symbol-map/symbol-map.md`).

> **Original UI; behavioral inspiration only from on-device search affordances on Android.** Do not recreate any specific third-party app's surface.

Cross-platform sibling: `design-system/handoff/YT-0026/` — same decisions, iOS idioms.

---

## D1 — Search-bar shape and placement

**Decision: M3 `SearchBar` (pill, 56 dp), docked in the `LargeTopAppBar` `actions`/title slot of `SearchScreen`**, not a custom `TextField` with a rounded background.

The bar is full-width with 16 dp horizontal screen padding, leading `Icons.Rounded.Search` (16 sp glyph, 24 dp slot), trailing clear/cancel `IconButton` (24 sp glyph in a 48 dp hit). Fill is `colorScheme.surfaceVariant`; on focus the M3 elevation overlay applies (`tonalElevation = 3.dp`). Shape is the `SearchBar` default — **do not override** `shape =`.

**Rationale.** M3 `SearchBar` already encodes the platform's expected metrics, IME interaction, focus animation, and state-layer ripple. A custom `TextField` reimplements all of it and breaks on Material You re-theming. Docking inside `LargeTopAppBar` keeps the scroll behavior consistent with the rest of the shell (per YT-0011 app shell).

**Anti-patterns.**
- ❌ Custom `TextField` with `RoundedCornerShape(28.dp)` background — duplicates `SearchBar`, drifts under Material You.
- ❌ `SearchBar` floating above the body with a separate scrim — collides with `LargeTopAppBar`'s expand/collapse.
- ❌ Two icons in the leading slot ("search" + "voice") — voice is deferred (D8); the leading slot is single-icon.

---

## D2 — Debounce — 400 ms on text change, IME `Search` bypasses

**Decision: 400 ms debounce on `onValueChange`. The IME `Search` action (or hardware `Enter` on a Bluetooth keyboard) fires the query immediately, bypassing the debounce.**

Implementation: `snapshotFlow { query }.debounce(400.milliseconds).distinctUntilChanged().collectLatest { vm.search(it) }` in a `LaunchedEffect(Unit)` keyed inside the `SearchScreen`. The IME action invokes `vm.search(query)` directly from `KeyboardActions(onSearch = { ... })`, which **cancels** the pending debounce coroutine via the `collectLatest` semantics.

Empty query (`query.isBlank()`) cancels in-flight requests and snaps to the idle state (D3); it does not race to the no-results cell.

**Rationale.** 400 ms is the documented YourTube cost-vs-feel sweet spot — fast enough to feel live, slow enough to skip three-keystroke bursts. The IME-action fast-path is what makes voice typists and physical-keyboard users not curse the app.

**Anti-patterns.**
- ❌ Sub-200 ms debounce — fires on every keystroke during normal typing; floods the suggest endpoint.
- ❌ No IME fast-path — feels stuck after hitting the keyboard `Search` button.
- ❌ Debouncing the cancel — empty query should clear results synchronously.

---

## D3 — Suggestion chips (idle state) — max 6, recent + curated fallback

**Decision: When the bar is empty (or the screen first opens), the body renders the state-catalog **C2** cell: search icon + "Search YourTube" copy + a horizontally-scrolling `LazyRow` of up to 6 suggestion chips below the SearchBar.**

Chip source priority:
1. **Recent searches** — up to 20 stored locally (D6), surface the **6 most recent**, deduplicated and trimmed.
2. **Curated fallback** — when there are fewer than 6 recents, fill the remaining slots with curated queries: `"lofi"`, `"focus"`, `"ambient"`, `"podcasts"` (per state-catalog C2). The curated list is static; do not personalize in MVP.

Layout: `LazyRow` with `Arrangement.spacedBy(8.dp)` and 16 dp horizontal contentPadding. **Reject `FlowRow`** — chips wrapping breaks the visual rhythm and competes with the empty-state copy below for vertical space.

Interaction:
- **Tap** fills the bar with the chip's text and **fires a search immediately** (no extra "Search" press; see D10). Updates the recent-searches MRU.
- **Long-press** removes the chip from history (recent chips only; curated chips ignore long-press). Confirmed by `HapticFeedbackType.Reject` and a snackbar `"Removed from recent searches"` with an `Undo` action (per toast-catalog).
- **Selection state** — when the chip's text is also the active query (e.g. tapped, results loading), the chip renders as M3 `FilterChip` `selected = true` with `colorScheme.secondaryContainer` fill.

**Anti-patterns.**
- ❌ More than 6 chips — the strip stops feeling like accelerators and starts feeling like a category index.
- ❌ Mixing chip types in one row (recent + trending + categories) — the user can't tell why a chip is there.
- ❌ Long-press menu instead of direct remove — friction on a recoverable action; the snackbar Undo is the recovery surface.

---

## D4 — Currently-playing EQ indicator on result rows

**Decision: When a result row matches the currently-playing track, the row tints with `colorScheme.primaryContainer.copy(alpha = 0.16f)` and overlays an animated 3-bar EQ indicator in place of the leading "duration" badge.**

Condition (single boolean — both must be true):
```kotlin
val isActive = result.videoId == playerState.currentTrack?.videoId
val isPlaying = isActive && playerState.isPlaying
```

Visual:
- Three vertical bars, each `3.dp` wide, with a gap of `2.dp` between them. Total width ≈ `13.dp`.
- Container: `24.dp × 24.dp`, the bars bottom-aligned and centered horizontally.
- Color: `colorScheme.primary` (Material You-driven; brand `#8B5CF6` fallback).
- Animation: `rememberInfiniteTransition(label = "eq")` driving three `animateFloat`s — heights `[6.dp..18.dp]`, `[4.dp..16.dp]`, `[8.dp..14.dp]` — each with `infiniteRepeatable(tween(600, easing = FastOutSlowInEasing), repeatMode = RepeatMode.Reverse)` and **per-bar `initialStartOffset`** of `0ms`, `120ms`, `240ms` so the three bars don't tick in lockstep.

Paused state (`isActive && !isPlaying`):
- Tint **stays** (the row remains the active row).
- Bars **freeze** at their current height — call `infiniteTransition....animateFloat(...)`'s `targetValue` switch logic to a single static value derived from `LocalContext`'s last frame (in practice: store the last `value` in a `remember` block and switch the bar `Modifier.height(lastHeight)` when `!isPlaying`).
- Bar color drops to `colorScheme.primary.copy(alpha = 0.6f)` to read as "queued, not active".

The active-row tint value `0.16f` matches YT-0013 §Q12 web-isms table and the NowPlaying active-row tint. **This is a single token, not a per-screen value.**

**Anti-patterns.**
- ❌ A different active-row tint per screen — defeats the purpose of having a token.
- ❌ Animating bar color (cycling through hues) — distracting; the height animation is enough.
- ❌ All bars in lockstep — reads as a single bar, not three; the staggered offset is non-negotiable.
- ❌ Hiding the duration badge when active without also showing the EQ — the row loses its rightmost weight and feels misaligned.

---

## D5 — Empty / loading / error states — consume the state catalog verbatim

**Decision: Search consumes state-catalog cells C1, C2, C3, C4, C5 directly. No per-screen redefinition.**

| State | Cell | When |
|---|---|---|
| Idle (no query yet) | **C2** | SearchScreen first open OR query is blank |
| Loading | **C1** — 6 skeleton track rows | A non-empty query is in flight (debounce already cleared) |
| No results | **C3** | Server returned 0 matches for the submitted query |
| Generic error | **C4** | Non-2xx HTTP, parse failure, timeout (and `NetworkMonitor.isOnline == true`) |
| Offline | **C5** | Request failed AND `NetworkMonitor.isOnline == false` |
| Results | (own cell, not state-catalog) | ≥1 match |

All copy, icon, button label, retry contract, accessibility announce, and skeleton shimmer rules are inherited from the state catalog. **Do not re-author them in this folder.** When the catalog changes a cell, this screen inherits the change for free — that is the entire point.

**Anti-patterns.**
- ❌ Custom "No results found 😢" copy — overrides catalog C3, breaks copy review.
- ❌ Showing the suggestion chips ALONGSIDE the loading skeleton — D3 explicitly says chips disappear when loading begins (matches state-catalog loading.md C1).
- ❌ Showing an inline error banner on stale results — initial-load failure is a full screen (per state-catalog status contract); a stale-refresh failure is the only case that uses an inline banner.

---

## D6 — Search history persistence — 20 most recent, local-only

**Decision: The Search screen persists the **20 most recent queries** locally via Room (`SearchHistoryDao`). No sync, no PII beyond the query string, no timestamps surfaced to the user.**

Storage shape:
```kotlin
@Entity(tableName = "search_history")
data class SearchHistoryEntry(
  @PrimaryKey val query: String,         // dedup by exact match (trimmed, case-preserved)
  val lastSearchedAt: Long                // epoch ms, used for MRU ordering only
)
```

MRU semantics: on every successful search submit, `INSERT OR REPLACE` with the current `System.currentTimeMillis()`. On cold start, `SELECT * FROM search_history ORDER BY lastSearchedAt DESC LIMIT 20`. The chip strip surfaces the first 6.

**Clearing history.** This screen owns **per-chip long-press remove** (D3) only. **Full "Clear search history"** lives in **Settings § Data** — `YT-0017/` will own that surface when it lands; until then leave the screen as-is. Long-press of a single chip surfaces the toast-catalog "Removed from recent searches" with Undo (4 s).

**Anti-patterns.**
- ❌ Storing query timestamps on the visible chip — leaks user behavior; the recents are an accelerator, not a journal.
- ❌ Cross-device sync of recent searches — explicit non-goal of YourTube MVP; the app is local-first.
- ❌ Full-clear button surfaced on the Search screen — duplicates Settings; users hunt for it in one place.

---

## D7 — Focus and keyboard behavior

**Decision: On Search tab activation, the `SearchBar` gains focus immediately and the IME (software keyboard) opens.**

Mechanism: `LaunchedEffect(Unit) { focusRequester.requestFocus() }` on the `SearchBar` — guarded by an `if (savedQuery.isEmpty())` so re-entering the tab with a stale query (D7 back-restore rule below) **does not** re-open the keyboard.

| User action | Keyboard | Query | Results |
|---|---|---|---|
| Tab activated, blank state | opens | blank | C2 idle |
| Tap a result row | dismisses (results stay scrollable) | preserved | scroll position preserved |
| Tap Cancel/Back (D9) | dismisses | cleared | C2 idle |
| Tap chip | stays open or dismisses per platform IME — do not force | filled with chip text | C1 → results |
| Back navigation to NowPlaying then return to Search | closed (don't reopen) | restored | restored |

The query persists in `SavedStateHandle["search.query"]` so process death / config change / back-stack pop all restore. Results are NOT cached in the handle — they refetch on restore (cheap; covered by 30 s in-memory cache in `SearchRepository`).

**Anti-patterns.**
- ❌ Auto-opening the IME on every Search tab activation — feels aggressive when the user came back to read a stale result.
- ❌ Persisting the IME open state through process death — Android system handles it; don't override.
- ❌ Clearing the query on result tap — the user wants to come back and tap a different result; losing the query forces a re-type.

---

## D8 — Voice search — explicitly deferred to post-MVP

**Decision: No voice-search microphone button ships in the MVP SearchBar.**

The composable file may carry a `// TODO(post-MVP): voice search trigger` comment near the trailing slot but **no UI element is rendered**. When voice ships, it will live as a trailing `IconButton` in the SearchBar with `Icons.Rounded.Mic`, mapped via `symbol-map.md` (which currently does not enumerate `mic` — that addition lands with the voice ticket).

**Rationale.** Voice typing on Android is already a per-app capability via the system IME's microphone button. The user can long-press the comma key on Gboard. Shipping a dedicated mic button in our own SearchBar implies on-device speech recognition that we don't have and won't ship in MVP — that's a promise the app can't keep.

**Anti-patterns.**
- ❌ Shipping a disabled mic button with a "coming soon" tooltip — looks broken; promises a date we don't have.
- ❌ Replacing the system IME mic button with our own (`android:imeOptions="flagNoExtractUi"` shenanigans) — interferes with Gboard / Samsung Keyboard / SwiftKey behavior.

---

## D9 — Cancel / clear — trailing `IconButton`, two behaviors

**Decision: The trailing slot of the SearchBar holds a single `IconButton` whose icon and action depend on focus state.**

| Focus + query | Glyph | Action |
|---|---|---|
| Unfocused, blank | (hidden) | n/a |
| Focused, blank | (hidden) | n/a |
| Focused or unfocused, non-blank query | `Icons.Rounded.Close` | Clears query → returns to C2 idle. Focus is **preserved**. Keyboard stays open. |

There is **no separate "Cancel" button** on Android (iOS has one — see YT-0026 §D9). Android users dismiss the keyboard via system back / down-chevron on the navigation bar; we don't reinvent the affordance.

**On system back:**
- If results are visible → returns to C2 idle (clears query, preserves recent chips).
- If C2 idle is visible and the user came from another tab → pops back to that tab via the bottom navigation back stack.

The clear glyph appears via M3's built-in trailing slot when `query.isNotBlank()`. Don't animate its appearance — M3 cross-fades it for you.

**Anti-patterns.**
- ❌ A "Cancel" text button on Android — that's the iOS idiom; Android uses back navigation.
- ❌ Clear-and-also-blur — blurring the SearchBar requires a re-tap to start a new search; cumulative friction.
- ❌ Animating the clear glyph in/out manually — M3 already handles it; double animation feels nervous.

---

## D10 — Submit-on-chip-tap — single explicit decision

**Decision: Tapping a suggestion chip fills the query and fires a search immediately. No "Search" key press required, no IME interaction needed, no debounce.**

Both platforms behave identically. This decision is recorded explicitly because iOS 18 vs 26 historically had inconsistent behavior across the platform's own apps — we lock our app's behavior to "tap = search" everywhere.

Implementation: chip `onClick` calls `vm.setQuery(chipText)` and `vm.search(chipText)` in the same call; the SearchBar's `query` state observes the change via state hoisting and renders the new value mid-frame.

If the chip is the same as the current query, the tap is a **re-search** (re-runs the request, useful for retry / refresh).

**Anti-patterns.**
- ❌ Chip tap that requires a follow-up `Search` IME press — historically iOS-flavored, never shipped on Android, but worth recording as the explicit not-this.
- ❌ Chip tap that opens a category page — chips are accelerators, not categories; misreading them as nav primitives breaks the mental model.

---

## Mockup web-isms — translate to Compose

| Mockup CSS / pattern | Compose / M3 |
|---|---|
| `background: #2C2C2E` chip fill | `colorScheme.surfaceVariant` |
| `background: rgba(139,92,246,0.16)` row tint (active) | `colorScheme.primaryContainer.copy(alpha = 0.16f)` |
| `background: rgba(139,92,246,0.30)` chip selected | `colorScheme.secondaryContainer` |
| `box-shadow` under chips | Drop. M3 chips ship without elevation by default. |
| `border-radius: 999px` chip | M3 `SuggestionChip` / `FilterChip` default — do not override. |
| `transition: background-color 200ms` chip-press | M3 state-layer ripple — built-in. |
| `@keyframes eq` 600ms infinite | `rememberInfiniteTransition(label = "eq")` + `animateFloat(... infiniteRepeatable(tween(600, easing = FastOutSlowInEasing), repeatMode = RepeatMode.Reverse))` with per-bar `initialStartOffset` |
| `font-feature-settings: "tnum"` on duration badge | `Text(..., fontFeatureSettings = "tnum")` |
| `width: 80%; height: 14px; background: #2C2C2E` skeleton | Inherit state-catalog C1 skeleton row template — do not re-author |
| Hand-drawn search SVG | `Icons.Rounded.Search` |
| Hand-drawn search-off SVG | `Icons.Rounded.SearchOff` |
| Hand-drawn wifi-off SVG | `Icons.Rounded.WifiOff` |
