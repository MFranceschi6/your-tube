# YT-0015 — Decision Log (Android Recently Played)

Android Recently Played for YourTube. Validated against `docs/design-system.md`, `design-system/tokens/tokens.json`, `design-system/handoff/state-catalog/`, `design-system/handoff/symbol-map/symbol-map.md`, and `design-system/handoff/toast-catalog/`. The combined mockup at `design-system/mockups/android/YT-0014-0015-0016-0017-library-history-settings-sharing.html` is layout reference only; this log overrides where they disagree.

Sibling iOS log: `design-system/handoff/YT-0029/decision-log.md`. Every Q here has a substantively identical Q there — only platform primitives differ.

> **Override convention.** Any decision overriding the original ask must (a) cite the ask, (b) name the alternative, (c) be tagged `override-pending-reviewer-approval`. None of Q1–Q10 are overrides — they document shipped behavior.

---

## Q1 — Layout shape: day-grouped with sticky headers

**Decision: `LazyColumn` with `stickyHeader` per day group, newest-first within each group.** Group keys (in render order):

| Key | Label | Predicate |
|---|---|---|
| `today` | "Today" | `playedAt` is on the device's current local date |
| `yesterday` | "Yesterday" | `playedAt` is on the day before |
| `dd MMM yyyy` | e.g. "10 May 2026" | every other group, formatted via `DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())` — DOES NOT default to en-US |

A group with zero entries does not render. The very first entry in the list always renders its day header (no "Today" → straight to "Yesterday" with no anchor).

**Rationale.** A flat newest-first list becomes unscannable past ~20 entries: the user has to scrub by relative position with no time anchor. Day-grouping with sticky headers gives the eye a date scale and a "where did I leave off" anchor that survives autoplay. Week-grouping was considered and rejected — week boundaries are calendar-locale-dependent and read as stale after a single multi-hour session.

**Anti-patterns.**
- ❌ Flat list — fine for ≤20 rows, breaks past that. We've already seen tester libraries with 200+ entries.
- ❌ "This week / Last week / Earlier" — same week-locale problem; also doesn't match the user mental model (people say "Tuesday" or "the 8th", not "last week").
- ❌ Sticky header that fades out on scroll — defeats the point of a sticky.
- ❌ Per-row date prefix on the title — duplicates the timestamp in the supportingContent (Q2) and bloats every row.

---

## Q2 — Row content: shared `TrackRow` + timestamp in supporting line

**Decision: reuse `TrackRow` from `core/ui`** (same component as Library / Playlist Detail / Search results). The timestamp lives in `supportingContent` as a second supporting line below the channel:

```
[ thumb 56dp ]   Headline (track title)
                  Channel · 3:42
                  2 minutes ago
                                                            [ overflow ⋮ ]
```

The same `TrackRow` is reused unmodified — the third line is just an extra `Text` inside `supportingContent` rendered via the existing slot.

### Timestamp format (same family on both platforms)

| Age (now − playedAt) | Format | Example |
|---|---|---|
| < 60 s | `"just now"` | `just now` |
| 1 – 59 minutes | `"{N} minute(s) ago"` | `2 minutes ago` |
| 1 – 23 hours | `"{N} hour(s) ago"` | `1 hour ago` |
| Same calendar day | `"Today, HH:mm"` | `Today, 14:32` |
| Calendar day = yesterday | `"Yesterday, HH:mm"` | `Yesterday, 09:15` |
| Older | `"d MMM, HH:mm"` | `12 May, 18:04` |

Format is computed by a single `formatHistoryTimestamp(now: Instant, playedAt: Instant): String` helper in `core/common`. The Compose row re-derives on every recomposition — there's no observable cost at our list sizes (<500 rows on screen at once).

**Anti-patterns.**
- ❌ Absolute timestamp for everything — "12 May, 14:32" for a track from 4 minutes ago is colder than "4 minutes ago".
- ❌ Relative timestamp for everything — "3 days ago" for an entry in a list that already groups by day is redundant and ambiguous.
- ❌ Custom row component — splits the `TrackRow` design across surfaces and rots when YT-0025's row spec evolves.
- ❌ A separate timestamp column on the right — competes with the trailing overflow icon for the eye and breaks at narrow widths.

---

## Q3 — Dedup-by-videoId contract (shipped behavior, do not relitigate)

**Decision: only the most recent occurrence of any `videoId` is visible in the list.** Older plays of the same video are hidden in the UI but may persist in the DB for stats (autoplay seeding, recommendation weights, future analytics).

Origin: **YT-0048**, with subsequent fixes **YT-0154** (initial-load dedup) and **YT-0158** (live-update dedup on new play event).

### Rule (precise)

Given the list `entries: List<HistoryEntry>` sorted by `playedAt` descending:

```
visible = entries.distinctBy { it.videoId }
```

`distinctBy` keeps the first occurrence in iteration order — which, given descending sort, is the most recent play. Older plays of the same `videoId` are silently filtered out of the rendered list. They remain queryable from DB by other surfaces (recommendations, stats) but **MUST NOT** be surfaced in this screen.

### Why this lives in the design contract

Three separate fixes landed because implementers kept "correcting" the dedup as a bug. It is not a bug. A repeated play does not need to appear twice in the user's history — the same row simply moves to the top on each replay. Document the rule here so the next implementer doesn't make YT-0048 / YT-0154 / YT-0158 fix #4.

**Anti-patterns.**
- ❌ Listing every play occurrence — clutters the list with duplicates of the song you just hit replay on three times; visual noise dominates within minutes.
- ❌ Deduping in the UI only — must happen at the repository / view-model boundary; the DB layer keeps full play history for stats (YT-0048 separates these concerns).
- ❌ Deduping by videoId+channelId+something — videoId is unique; over-keying re-introduces duplicates.
- ❌ Removing older entries from the DB on dedup — destroys play-count data downstream surfaces consume.

---

## Q4 — Swipe-to-delete: end-to-start with confirm-on-release

**Decision: `SwipeToDismissBox` from `androidx.compose.material3`,** configured `enableDismissFromEndToStart = true` only. Threshold for commit: ≥50% of row width OR fling velocity ≥600 dp/s. Below that, the row springs back.

Background of the swipe reveals an `Icons.Rounded.Delete` glyph 24 dp on a `colorScheme.errorContainer` ground, with the glyph tinted `colorScheme.onErrorContainer`. The label "Remove" is implied (no text) — the trash glyph is unambiguous and matches the symbol-map `delete` semantic.

### State machine

```
idle ──drag past 50% or fling──> dismissed ──auto-remove from list──> committed
idle ──drag <50% release──────> idle (spring back)
```

On `dismissed`, the row is immediately filtered out of the rendered list and `viewModel.removeEntry(entryId)` deletes it from the DB. No two-stage write — history removal is per-row and individually cheap to recover (the user can replay the track via Search to re-create the entry).

### No toast-with-undo

The Library v2 spec (YT-0014 Q3) uses Snackbar-with-undo for single-track removes from a playlist. History is different:
1. History is a **long-running list**. A persistent toast cluttering the bottom of a list the user is actively swiping through is friction-inducing.
2. Recovery is **trivial**: search the track, replay it, the entry is back. No mental model of "I lost ordering" — entries are timestamped.
3. The destructive surface is the **bulk** "Clear all" (Q6), not the per-row swipe. Per-row swipe deserves the lightest possible interaction.

**Anti-patterns.**
- ❌ Bidirectional swipe — leading swipe is reserved for non-destructive primary actions (Material 3 swipe-action guidance); we have no primary swipe action here.
- ❌ Confirm-dialog per swipe — friction fatigue; the user is already swiping past entries they don't want to see.
- ❌ Toast-undo (YT-0014 Q3 pattern) — wrong surface, see above.
- ❌ Drag-handle visible at rest — that's edit mode (YT-0014 Q3 pattern); History has no reorder concept (chronological by definition).

---

## Q5 — Long-press / context menu: 5 actions

**Decision: long-press on a row opens a `ModalBottomSheet`** with these actions, in this order:

| Action | Symbol-map semantic | Glyph (Android) |
|---|---|---|
| Play next | `play` | `Icons.Rounded.PlaylistPlay` |
| Add to queue | `queue` | `Icons.Rounded.QueueMusic` |
| Add to playlist | `add-to-playlist` | `Icons.Rounded.PlaylistAdd` |
| Remove from history | `remove-from-history` | `Icons.Rounded.Delete` (tinted `colorScheme.error`) |
| Share | `share` | `Icons.Rounded.Share` |

A `HorizontalDivider` separates `Remove from history` from the rest; the destructive item uses `colorScheme.error` for both icon tint and label color.

The sheet uses `ModalBottomSheet` defaults (M3 `sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)` — short content; partial expansion adds nothing).

Haptic on open: `HapticFeedbackType.LongPress`. See `haptics-and-a11y.md`.

**Anti-patterns.**
- ❌ Including "Show channel" — out of scope for MVP per repo audit; reserved.
- ❌ Inlining the actions on the row via a trailing icon strip — clutters every row for a rarely-used affordance.
- ❌ Different action set than iOS — `design-system/handoff/YT-0029/decision-log.md#q5` lists the same five actions; cross-platform parity is the goal.

---

## Q6 — "Clear all" destructive action

**Decision: top-app-bar overflow `IconButton` (`Icons.Rounded.MoreVert`) opens a `DropdownMenu` with a single item "Clear watch history".** Tapping the item dismisses the menu and shows an `AlertDialog`.

### Dialog copy (verbatim)

| Slot | Copy |
|---|---|
| Title | **Clear watch history?** |
| Body | This permanently removes all entries from your history. Tracks themselves stay in your library. |
| Dismiss button | **Cancel** (`TextButton`, `colorScheme.primary`) — left |
| Confirm button | **Clear** (`TextButton`, `colorScheme.error`) — right |

Tapping **Clear** calls `viewModel.clearAll()` (which Room-deletes all entries) and dismisses the dialog. The list immediately renders C13 (empty).

### Overflow visibility

The overflow `IconButton` is **conditionally rendered** based on `uiState.entries.isNotEmpty()`. When the list is empty, the overflow disappears — there is nothing to clear, and surfacing the action on an empty screen is a discoverability anti-pattern (the user is told "Nothing played yet" while staring at a destructive control).

### Why dialog, not toast-with-undo

`Clear all` destroys an unbounded amount of metadata in one tap. The toast-with-undo pattern is sized for low-cost reversible per-item actions (Material 3 destructive-action guidance, also YT-0014 Q3). Bulk-clear with a 6-second undo window is the wrong scale — a user who taps Clear, switches apps, and comes back has lost the affordance. Dialog is the right surface; the friction is the design.

**Anti-patterns.**
- ❌ Inline "Clear all" text button in the top-app-bar — burns toolbar real estate for a rare destructive action.
- ❌ Toast-with-undo — wrong scale, see above.
- ❌ Confirming with a second dialog ("Are you really sure?") — patronizing, breaks Material 3 confirm conventions.
- ❌ Overflow item rendered even when list is empty — discoverability anti-pattern; the action is a no-op on empty.
- ❌ Putting "Clear all" in a long-press menu on a row — wrong scope (a row action that affects all rows is semantically broken).

---

## Q7 — Empty state copy: C13 verbatim

**Decision: consume `design-system/handoff/state-catalog/empty.md` cell C13 verbatim — no per-screen override.**

| Slot | Source |
|---|---|
| Icon | `Icons.Rounded.History` (symbol-map `history` semantic) |
| Icon size | 56 dp |
| Icon tint | `colorScheme.onSurfaceVariant` |
| Title | "Nothing played yet" |
| Body | "Tracks you play will show up here." |
| Primary action | "Browse search" → navigates to Search tab |

Layout is the shared `EmptyState` composable from `core/ui` (per state-catalog conventions). The overflow "Clear watch history" action (Q6) is **not** rendered in this state.

**Anti-patterns.**
- ❌ Adding a sub-line "or paste a YouTube link" — out of scope, breaks state-catalog parity.
- ❌ Custom illustration — state-catalog is icon-only by design.
- ❌ Linking the body string to the Pause-history toggle inline (see Q8).

---

## Q8 — Pause history switch: deferred from empty state

**Decision: the empty state does NOT inline-link to the Settings → Data → "Pause history" toggle in MVP.** The body stays copy-only ("Tracks you play will show up here.").

**Rationale.** The Pause-history Settings toggle (Settings § Data — owned by YT-0173 Settings handoff when it lands) is a separate surface. The empty state body is consumed verbatim from C13 (Q7); inlining a link would (a) fork the canonical empty-state copy, (b) point the user at a switch most users never need, and (c) confuse the "I haven't played anything yet" case with the "I paused history" case (they're different empty-state subtypes that we are intentionally collapsing for MVP).

When YT-0173 ships, revisit. Two surfaces are candidates for surfacing the toggle:
1. The empty state body, gated on "history is paused" being true.
2. A toolbar pill ("Paused") rendered when the toggle is on and history is non-empty.

Neither is in MVP scope. Document the deferral here so the next implementer doesn't add the link unilaterally.

**Anti-patterns.**
- ❌ Adding the link unilaterally — forks state-catalog C13.
- ❌ A toolbar "Paused" pill in MVP — out of scope.

---

## Q9 — Navigation entry from Library

**Decision: per YT-0014 v2 Q1, the Library top-app-bar exposes an `Icons.Rounded.History` action that navigates to this screen.**

```kotlin
// In LibraryScreen.kt's TopAppBar(actions = { ... }):
IconButton(onClick = {
  navController.navigate("history") {
    launchSingleTop = true       // see "Single-instance" below
    restoreState = true
  }
}) {
  Icon(
    Icons.Rounded.History,
    contentDescription = "Recently played",
  )
}
```

### Single-instance navigation

`launchSingleTop = true` ensures repeated taps on the Library → History action don't stack multiple History instances on the back stack. Combined with `restoreState = true`, scroll position survives a round-trip back to Library and forward to History.

### Back behavior

Predictive-back returns to Library (`navController.popBackStack()`). System back does the same.

### What this is NOT

- Not a tab. History is a destination, not a peer of Library / Search / Settings.
- Not also reachable from Settings → Data. (Settings → Data has a "Clear history" entry, but that's `viewModel.clearAll()` directly — not a navigate.)
- Not reachable from MiniPlayer or NowPlaying. Those are playback surfaces; History is a library surface.

**Anti-patterns.**
- ❌ Making History a fourth NavBar tab — burns shell real estate for a feature most users open <1×/day.
- ❌ Letting the Library → History action push a new instance on each tap — back-stack pollution.
- ❌ Reaching History from Search results — wrong surface; Search is for finding, History is for revisiting.

---

## Q10 — Loading / error states: state-catalog C12 + C14; offline is N/A

**Decision: consume `design-system/handoff/state-catalog/loading.md` cell C12 and `error.md` cell C14 verbatim.**

| State | Source | Render |
|---|---|---|
| Loading | C12 | 8 skeleton rows matching `TrackRow` proportions — 56 dp thumb placeholder + two text-line placeholders. Shimmer cycle 1400ms linear infinite. No day headers in the skeleton (the date scale isn't known yet). |
| Error | C14 | Single `ErrorState` composable: 56 dp `Icons.Rounded.Error` tinted `colorScheme.onSurfaceVariant`, title "Couldn't load history", body "Try again in a moment.", primary action "Try again" (`Button` with `colorScheme.primary`). |

### Offline behavior — N/A (document explicitly)

History is **local data**. The list is sourced from a local Room table (`HistoryEntryEntity`); no network call exists. Therefore:
- There is no offline error variant.
- C5 (search offline) does NOT apply.
- A network change does NOT trigger a refetch or state transition.

This is documented here so a future implementer doesn't add a network-monitor branch "for parity" — it would be code that can never fire.

### Stale-content refresh

History updates live via a `Flow<List<HistoryEntry>>` from the repository. A new play event prepends an entry (after dedup-by-videoId, Q3); a tap-clear empties the list. No pull-to-refresh — the data is already live.

**Anti-patterns.**
- ❌ Adding a network-offline branch — dead code; history is local.
- ❌ Pull-to-refresh — no refresh source; data is already live via Flow.
- ❌ Per-screen error copy override — see Q7 anti-patterns for the same rationale.
- ❌ Showing skeleton AND empty simultaneously on first launch — state-machine rule from state-catalog: one state at a time.

---

## Mockup web-isms — translate to Compose

| Mockup CSS / pattern | Compose / M3 |
|---|---|
| `pal.primary` / `--primary` | `colorScheme.primary` (dynamic on API 31+; brand fallback `#8B5CF6`) |
| `pal.surface` / `--surface` | `colorScheme.surface` |
| `pal.surfaceVariant` | `colorScheme.surfaceVariant` (skeleton blocks, day-header subtle bg if any) |
| `pal.error` | `colorScheme.error` (destructive button label, remove icon tint in long-press sheet) |
| `pal.errorContainer` | `colorScheme.errorContainer` (swipe-action background) |
| `--radius-md: 12px` | `MaterialTheme.shapes.medium = RoundedCornerShape(12.dp)` |
| `--radius-sm: 8px` | `RoundedCornerShape(8.dp)` (thumbnail) |
| Sticky day header CSS `position: sticky; top: 0` | `LazyColumn { stickyHeader { ... } }` |
| Skeleton shimmer `@keyframes` | `Modifier.placeholder(visible = true, highlight = PlaceholderHighlight.shimmer())` (Accompanist or hand-rolled) |
| Swipe-action peek CSS `transform: translateX(-72px)` | `SwipeToDismissBox` — do NOT simulate metrics manually |
| Confirm dialog modal | `AlertDialog` |
| Bottom-sheet contextual menu | `ModalBottomSheet` |
| Snackbar with action | NOT used in this screen — see Q4, Q6 |

---

## Tag legend

- No tags in this log. No decisions are overrides. If a future change introduces an override, it must be tagged `override-pending-reviewer-approval` until the reviewer signs off (see `README.md` § Override convention).
