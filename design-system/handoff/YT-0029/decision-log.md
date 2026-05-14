# YT-0029 — Recently Played — Decision Log (iOS)

> Senior iOS design consultant decisions. Builds on YT-0027 / YT-0028 aesthetic direction
> (calm, content-first, Podcasts-inspired). When in doubt: **do less**.
> Aesthetic tone is shared — see [`../YT-0027/aesthetic-direction.md`](../YT-0027/aesthetic-direction.md).
>
> **Sibling Android log:** [`../YT-0015/decision-log.md`](../YT-0015/decision-log.md). Every Q here is substantively identical to its Android counterpart — only the platform primitive differs.
>
> **Override convention.** Any decision overriding the original ask must (a) cite the ask, (b) name the alternative, (c) be tagged `override-pending-reviewer-approval`. None of Q1–Q10 are overrides — they document shipped behavior (`RecentlyPlayedScreen.swift`, `HistoryViewModel.swift`, `HistoryStore.swift`).

---

## Q1 — Layout shape: day-grouped sections with native sticky headers

**Decision: `List` with `Section(header: dayHeader)` per day group, newest-first within each group.** Group keys (in render order):

| Key | Label | Predicate |
|---|---|---|
| `today` | "Today" | `playedAt` is on the device's current local date |
| `yesterday` | "Yesterday" | `playedAt` is on the day before |
| `dd MMM yyyy` | e.g. "10 May 2026" | every other group, formatted via `Date.FormatStyle` honoring `Locale.current` |

A group with zero entries does not render. The first entry in the list always renders its day header (no flat run with no anchor).

```swift
List {
  ForEach(viewModel.dayGroups) { group in
    Section {
      ForEach(group.entries) { entry in
        TrackRow(entry: entry, timestamp: format(entry, now))
      }
    } header: {
      Text(group.label)
        .font(.subheadline)
        .foregroundStyle(.secondary)
        .textCase(nil)
    }
  }
}
.listStyle(.plain)
.scrollContentBackground(.hidden)
```

**HIG.** "Group related information; let the system handle the visual scaffolding." Section headers in a `List` get native sticky-on-scroll, native VoiceOver heading semantics, and native Dynamic Type — for free.

**Apple reference.** Mail (date-grouped messages). Photos (Library "By Day"). Messages (conversation date dividers).

**Anti-patterns.**
- ❌ Flat newest-first list — readable up to ~20 rows, breaks past that. Testers already hit 200+.
- ❌ "This week / Last week / Earlier" — week boundaries are locale-dependent and read as stale after one multi-hour session.
- ❌ Custom `LazyVStack` with hand-drawn pinned headers — costs the native sticky / VoiceOver heading affordance.
- ❌ Hidden headers ("Today" rendered as a subtle row hint) — defeats the date scale.

---

## Q2 — Row content: shared `TrackRow` + timestamp on subtitle

**Decision: reuse `TrackRow`** from `DesignSystem/Components/TrackRow` (same component as Library / Playlist Detail / Search results). The timestamp lives as a third line below channel · duration:

```
[ artwork 56pt ]   Track title
                    Channel · 3:42
                    2 minutes ago
                                                            [ ⋯ ]
```

`TrackRow` already exposes `supportingExtra: String?` — Library passes `nil`, History passes the formatted string.

### Timestamp format (same family on both platforms)

| Age (now − playedAt) | Format | Example |
|---|---|---|
| < 60 s | `"just now"` | `just now` |
| 1 – 59 minutes | `"{N} minute(s) ago"` | `2 minutes ago` |
| 1 – 23 hours | `"{N} hour(s) ago"` | `1 hour ago` |
| Same calendar day | `"Today, HH:mm"` | `Today, 14:32` |
| Calendar day = yesterday | `"Yesterday, HH:mm"` | `Yesterday, 09:15` |
| Older | `"d MMM, HH:mm"` | `12 May, 18:04` |

Implementation: a single `formatHistoryTimestamp(now:, playedAt:)` helper in `Core/Formatting/`. View re-derives on `body` recomputation; no observable cost at our list sizes.

**HIG.** "Show time information using formats people recognize. Be precise when the moment matters; relative when the gap matters."

**Apple reference.** Mail (relative under 24 h, absolute thereafter). Messages (time grouping per bubble cluster).

**Anti-patterns.**
- ❌ Absolute timestamp for everything — "12 May, 14:32" for a track from 4 minutes ago is colder than "4 minutes ago".
- ❌ Relative timestamp for everything — "3 days ago" inside a list already grouped by day is redundant.
- ❌ Custom row component for History — splits the design across surfaces.
- ❌ Trailing timestamp column — competes with `.swipeActions` reveal and breaks at narrow widths.

---

## Q3 — Dedup-by-videoId contract (shipped behavior, do not relitigate)

**Decision: only the most recent occurrence of any `videoId` is visible in the list.** Older plays of the same video are hidden in the UI but may persist in the SwiftData store for stats (autoplay seeding, recommendation weights).

Origin: **YT-0048**, with subsequent fixes **YT-0154** (initial-load dedup) and **YT-0158** (live-update dedup on new play event).

### Rule (precise)

Given the descending-by-`playedAt` query result `entries: [HistoryEntryEntity]`:

```swift
let visible = entries
  .reduce(into: ([HistoryEntry](), Set<String>())) { acc, e in
    guard !acc.1.contains(e.videoId) else { return }
    acc.0.append(e.toDomain())
    acc.1.insert(e.videoId)
  }
  .0
```

Older plays of the same `videoId` are silently filtered out of the rendered list. They remain queryable from `HistoryStore` by other surfaces but **MUST NOT** be surfaced in this screen.

### Why this lives in the design contract

Three separate fixes landed because implementers kept "correcting" the dedup as a bug. It is not a bug. A repeated play does not need to appear twice in history — the same row moves to the top on each replay. Document the rule so the next implementer doesn't make YT-0048 / YT-0154 / YT-0158 fix #4.

**Anti-patterns.**
- ❌ Listing every play occurrence — the same song you hit replay on three times reads as visual noise within minutes.
- ❌ Deduping at the SwiftUI view layer — must happen at the store boundary; the persistence layer keeps full play history for stats.
- ❌ Deduping by videoId+channelId+something — videoId is unique; over-keying re-introduces duplicates.
- ❌ Deleting older `HistoryEntryEntity` rows on dedup — destroys play-count data downstream surfaces consume.

---

## Q4 — Swipe-to-delete: native `.swipeActions(edge: .trailing)`

**Decision: native `.swipeActions(edge: .trailing) { Button(role: .destructive) }`,** with `allowsFullSwipe: true` so a full-flick commits without the user having to tap the revealed button.

```swift
.swipeActions(edge: .trailing, allowsFullSwipe: true) {
  Button(role: .destructive) {
    viewModel.remove(entry)
    haptics.notification(.success)
  } label: {
    Label("Remove", systemImage: "trash")
  }
}
```

`role: .destructive` gets the red background, the trash glyph, and the VoiceOver destructive-role announcement automatically. **Don't draw the metrics.**

### No toast-undo

YT-0028's playlist-track removal uses an undo snackbar. History is different:
1. History is a **long-running list**. A persistent banner cluttering the bottom while the user swipes through is friction-inducing.
2. Recovery is **trivial**: search the track, replay it, the entry is back. Entries are timestamped — no mental model of "I lost ordering" to repair.
3. The destructive surface that earns the friction is the **bulk** "Clear All" (Q6), not the per-row swipe.

### No leading swipe

`.swipeActions(edge: .leading)` is reserved for non-destructive primary actions (HIG). We have no primary swipe action here — the row's tap already plays. Adding a leading swipe ("Play next"? "Add to queue"?) doubles up with the long-press context menu (Q5).

**HIG.** "Use the simplest gesture for the task. System swipes carry the destructive role announcement and the haptic for free."

**Apple reference.** Mail (swipe trailing → Archive / Delete). Messages (swipe trailing → Delete). Notes (swipe trailing → Delete).

**Anti-patterns.**
- ❌ Bidirectional swipe — leading swipe has no clear semantic on History.
- ❌ `.alert` per row swipe — friction fatigue; the destructive role announcement already telegraphs the impact.
- ❌ Undo banner (YT-0028 pattern) — wrong surface, see above.
- ❌ Custom slide-to-delete with hand-rolled metrics — `swipeActions` exists.

---

## Q5 — Long-press / context menu: 5 actions

**Decision: `.contextMenu` per row** with these actions, in this order:

| Action | SF Symbol |
|---|---|
| Play next | `text.insert` |
| Add to queue | `text.append` |
| Add to playlist | `plus.rectangle.on.rectangle` |
| Remove from history | `trash` (role: `.destructive`) |
| Share | `square.and.arrow.up` (via `ShareLink`) |

```swift
.contextMenu {
  Button { vm.playNext(entry) } label: { Label("Play Next", systemImage: "text.insert") }
  Button { vm.addToQueue(entry) } label: { Label("Add to Queue", systemImage: "text.append") }
  Button { addToPlaylistTarget = entry } label: { Label("Add to Playlist", systemImage: "plus.rectangle.on.rectangle") }
  Divider()
  Button(role: .destructive) { vm.remove(entry) } label: {
    Label("Remove from History", systemImage: "trash")
  }
  Divider()
  ShareLink(item: entry.track.shareURL) { Label("Share", systemImage: "square.and.arrow.up") }
}
```

`role: .destructive` on Remove gets the red glyph + label tint automatically. `ShareLink` renders the native share sheet — don't roll your own.

**HIG.** "Context menus are for actions specific to one item — they keep the canvas calm and surface the long-tail."

**Apple reference.** Mail row long-press. Music track long-press (Play Next / Add to Queue / Add to Playlist / Share).

**Anti-patterns.**
- ❌ Including "Show channel" — out of scope for MVP; reserved.
- ❌ Different action set than Android — `../YT-0015/decision-log.md#q5` lists the same five; cross-platform parity is the goal.
- ❌ Action sheet (`.confirmationDialog`) on long-press — wrong primitive for inline row actions; `confirmationDialog` is for destructive confirmation.
- ❌ Pre-iOS-13 `.actionSheet` — deprecated for this use case.

---

## Q6 — "Clear All" destructive action

**Decision: trailing toolbar `Menu` (ellipsis circle) opening a single `.alert`.**

```swift
.toolbar {
  ToolbarItem(placement: .topBarTrailing) {
    if !viewModel.entries.isEmpty {
      Menu {
        Button(role: .destructive) { showClearConfirm = true } label: {
          Label("Clear All", systemImage: "trash")
        }
      } label: {
        Image(systemName: "ellipsis.circle")
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
```

The ellipsis is **conditionally rendered** based on `viewModel.entries.isEmpty == false`. When the list is empty, the toolbar item disappears.

### Why `.alert`, not `.confirmationDialog`

YT-0028 Q7 chooses `.confirmationDialog` for whole-playlist delete. History is different:
- Playlist delete has more nuance ("the tracks stay in your library" is a meaningful side-message that an action sheet exposes naturally).
- History clear is single-purpose — "this clears history, full stop". `.alert` is the smallest possible surface for a yes/no destructive question.
- `.alert` works equally well in compact size class; `.confirmationDialog` on iPad becomes a popover anchored to the originating ellipsis.

Both are valid; `.alert` is the smaller surface and matches the question shape.

### Why dialog, not toast-with-undo

`Clear All` destroys an unbounded amount of metadata in one tap. The undo-banner pattern (YT-0028 track removal) is sized for low-cost reversible per-item actions. Bulk-clear with a ~4 s undo window is the wrong scale.

**HIG.** "Confirm destructive actions, but only when undo is impossible or expensive."

**Apple reference.** Safari → Clear History (`.alert` with question title + destructive button). Mail → Empty Trash (`.alert`).

**Anti-patterns.**
- ❌ Inline "Clear" text button in the toolbar — burns toolbar real estate for a rare destructive action.
- ❌ Toast-with-undo — wrong scale.
- ❌ Ellipsis menu rendered when list is empty — discoverability anti-pattern; the action is a no-op.
- ❌ Putting "Clear All" in a row context menu — wrong scope.

---

## Q7 — Empty state copy: C13 verbatim

**Decision: consume `design-system/handoff/state-catalog/empty.md` cell C13 verbatim — no per-screen override.** Use `ContentUnavailableView`.

```swift
ContentUnavailableView {
  Label("Nothing played yet", systemImage: "clock.arrow.circlepath")
} description: {
  Text("Tracks you play will show up here.")
} actions: {
  Button("Browse search") { onSwitchTab(.search) }
    .buttonStyle(.borderedProminent)
    .tint(.accent)
}
```

SF Symbol: `clock.arrow.circlepath` per `design-system/handoff/symbol-map/symbol-map.md` (`history` semantic).

The toolbar ellipsis (Q6) is **not** rendered in this state.

**HIG.** "Empty states explain why and how — clearly, without judgment."

**Apple reference.** Reminders, Photos, Notes — all `ContentUnavailableView` with a prominent action.

**Anti-patterns.**
- ❌ Adding a sub-line "or paste a YouTube link" — out of scope, breaks state-catalog parity.
- ❌ Custom illustration — state-catalog is icon-only by design.
- ❌ Patronizing copy ("Your history will appear here once you've listened to your first track" — too long, condescending).

---

## Q8 — Pause history switch: deferred from empty state

**Decision: the empty state does NOT inline-link to the Settings → Data → "Pause history" toggle in MVP.** Body stays copy-only ("Tracks you play will show up here.").

**Rationale.** The Pause-history Settings toggle (Settings § Data — owned by YT-0173 when it lands) is a separate surface. Inlining a link in the empty state would (a) fork the canonical empty-state copy, (b) point the user at a switch most users never need, and (c) conflate two distinct empty-state subtypes (never played vs. paused).

When YT-0173 ships, revisit. Two candidate surfaces:
1. Empty state body, gated on "history is paused" being true.
2. A toolbar pill ("Paused") rendered only when the toggle is on and history is non-empty.

Neither is in MVP scope. Document the deferral here so the next implementer doesn't add the link unilaterally.

**Anti-patterns.**
- ❌ Adding the link unilaterally — forks state-catalog C13.
- ❌ A toolbar "Paused" pill in MVP — out of scope.

---

## Q9 — Navigation entry from Library

**Decision: per YT-0028 README + Q10 / "Recently Played row", the Library `List` surfaces a Recently Played row that pushes this screen via `NavigationLink`.**

```swift
// In LibraryScreen.swift:
NavigationLink {
  RecentlyPlayedScreen(currentVideoId: player.currentVideoId, onPlay: player.playNow)
} label: {
  HStack(spacing: 14) {
    Image(systemName: "clock.arrow.circlepath")
      .foregroundStyle(.accent)
      .frame(width: 32, height: 32)
      .background(Color(.systemGray6), in: RoundedRectangle(cornerRadius: 8))
    Text("Recently Played")
    Spacer()
  }
}
```

`NavigationStack` handles back-stack management; system back swipe / chevron returns to Library.

### What this is NOT

- Not a tab. History is a destination, not a peer of Search / Library / Settings.
- Not reachable from Settings → Data. (Settings has a "Clear history" entry, but that calls `HistoryStore.clearAll()` directly — no navigate.)
- Not reachable from MiniPlayer or NowPlaying. Those are playback surfaces; History is a library surface.

**HIG.** "Push for destinations the user expects to return from; reserve modals for tasks that interrupt."

**Apple reference.** Music → Library → Recently Added (push). Photos → Albums → Recents (push).

**Anti-patterns.**
- ❌ Making History a tab — burns shell real estate for a feature opened <1×/day for most users.
- ❌ Presenting History as a `.sheet` — wrong primitive; user reads-and-returns, not interrupts.
- ❌ Reaching History from Search — wrong surface; Search is for finding, History is for revisiting.

---

## Q10 — Loading / error states: C12 + C14; offline N/A

**Decision: consume `design-system/handoff/state-catalog/loading.md` cell C12 and `error.md` cell C14 verbatim.**

| State | Source | Render |
|---|---|---|
| Loading | C12 | 8 skeleton rows matching `TrackRow` proportions — 56 pt thumb placeholder + two text-line placeholders. `.redacted(reason: .placeholder)` + shimmer modifier. Shimmer cycle 1400 ms linear infinite. No section headers in skeleton. |
| Error | C14 | `ContentUnavailableView` with title "Couldn't load history", body "Try again in a moment.", primary `Button("Try Again")` calling `viewModel.retry()`. Icon `exclamationmark.triangle` tinted `.secondary` (NOT red — see state-catalog § "Error icon is NOT red"). |

### Offline behavior — N/A (document explicitly)

History is **local data**. The list is sourced from a local SwiftData store (`HistoryStore` / `HistoryEntryEntity`); no network call exists. Therefore:
- No offline error variant.
- C5 (search offline) does NOT apply.
- A network change does NOT trigger a refetch.

Documented here so a future implementer doesn't add a `NetworkMonitor` branch "for parity" — it would be code that can never fire.

### Stale-content refresh

`@Query(sort: \HistoryEntryEntity.playedAt, order: .reverse)` drives live SwiftData updates. A new play event prepends (after dedup, Q3); a `clearAll()` empties. No pull-to-refresh — the data is already live.

**HIG.** "Don't ask users to pull when the data is already current."

**Anti-patterns.**
- ❌ Adding a `NetworkMonitor` offline branch — dead code; history is local.
- ❌ Pull-to-refresh — no refresh source.
- ❌ Per-screen error copy override — see Q7.
- ❌ Showing skeleton AND empty simultaneously — state-machine rule from state-catalog: one state at a time.

---

## Mockup web-isms — translate to SwiftUI

| Mockup CSS / pattern | Native iOS |
|---|---|
| Custom row ✕ / trash glyph at rest | `.swipeActions(edge: .trailing) { Button(role: .destructive) }` — destructive role draws the trash. |
| Flat newest-first list | `List { ForEach(viewModel.dayGroups) { group in Section { ... } header: { Text(group.label) } } }`. |
| Custom sticky day header (`position: sticky`) | Native `Section { } header: { Text(...) }` — sticky for free. |
| Toolbar standalone "Clear" text | `.toolbar { ToolbarItem(.topBarTrailing) { Menu { Button(role: .destructive) } label: { Image(systemName: "ellipsis.circle") } } }`. |
| Bottom-sheet contextual menu | `.contextMenu`. |
| `border-radius: 8px` on thumbnails | `RoundedRectangle(cornerRadius: 8, style: .continuous)`. |
| `box-shadow: 0 8px 24px rgba(0,0,0,0.4)` on row | Drop on dark surfaces — doesn't read; let `List` row separators carry the structure. |
| `font-weight: 600` for row title | `.font(.body.weight(.semibold))` + `.dynamicTypeSize(...DynamicTypeSize.accessibility3)`. |
| Empty-state illustration | `ContentUnavailableView` + SF Symbol. |
| `rgba(255,69,58,1)` red destructive | `Color(.systemRed)` / `role: .destructive`. |
| `transform: translateX(-72px)` swipe peek | Native `.swipeActions` — don't simulate metrics. |
| Confirm dialog modal | `.alert(...) { Button("Cancel", role: .cancel) {}; Button("Clear", role: .destructive) {} }`. |

---

## MiniPlayer coexistence

The persistent MiniPlayer adds ~60 pt above the tab bar. Apply:

```swift
.safeAreaInset(edge: .bottom) {
  Color.clear.frame(height: miniPlayerVisible ? 60 : 0)
}
```

**Don't** hardcode 60 pt as content padding — `safeAreaInset` adjusts scroll-indicator and Dynamic Type metrics correctly; padding doesn't.

---

## Build order

1. `RecentlyPlayedViewModel` (`@Observable`) backed by `HistoryStore` (per YT-0048).
2. `RecentlyPlayedScreen` shell — `NavigationStack` with `.navigationTitle("Recently Played")`, toolbar ellipsis conditional on `!entries.isEmpty`.
3. Body branches: C12 skeleton, C13 `ContentUnavailableView`, C14 error, content.
4. Content: `List` with `Section` per day group + `swipeActions` + `contextMenu`.
5. `.alert` for Clear All confirm.
6. `.safeAreaInset` for MiniPlayer.
7. Accessibility pass (Dynamic Type up to `.accessibility3`, VoiceOver row reads per `haptics-and-a11y.md`, 44 pt min hit targets).

A working History list with the native swipe-to-delete beats a polished one with custom slide metrics. **Spend the polish budget on the day-grouping helper and the timestamp formatter** — those are where perceived quality lives.
