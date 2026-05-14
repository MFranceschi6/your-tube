# YT-0015 — Android Recently Played · Handoff Package

> **Audience:** Claude Code, implementing the Android Recently Played screen in Jetpack Compose + Material 3.
> **Source mockup:** `design-system/mockups/android/YT-0014-0015-0016-0017-library-history-settings-sharing.html` — combined library/history/settings/sharing canvas (layout reference only).
> **Design system:** `docs/design-system.md` and `design-system/tokens/tokens.json`.
> **Sibling iOS handoff:** `design-system/handoff/YT-0029/` — same shipped behavior, idiomatic per platform.
> **Scope:** Recently Played list screen, reached from the Library top-app-bar `history` action.

This handoff has **precedence** over the HTML mockup. Where they disagree, the handoff wins.

> **Status: documents shipped behavior.** The Compose screen, `RecentlyPlayedViewModel`, dedup-by-videoId pipeline (per YT-0048 / YT-0154 / YT-0158), and Library entry-point (per YT-0014 v2) all already ship. This package locks them in — no behavior changes, no overrides.

## What's in this folder

| File | Purpose |
|---|---|
| `README.md` | This file. Read first. |
| `decision-log.md` | Q1–Q10 decisions: layout shape, row content, dedup-by-videoId, swipe-to-delete, long-press menu, "Clear all", empty state, Pause-history deferral, navigation entry, loading/error states. |
| `compose-spec.md` | `RecentlyPlayedScreen` view hierarchy, `RecentlyPlayedUiState`, `RecentlyPlayedViewModel`, `SwipeToDismissBox` state machine, sticky day headers, "Clear all" dialog, file layout. |
| `haptics-and-a11y.md` | Haptics table, TalkBack contract for row reads / swipe / "Clear all" / day headers, live regions, fontScale, reduce-motion. |
| `mockup.html` | Populated state, Android-360 frame. |
| `mockup-states.html` | All 8 states: populated · single-row swiped · "Clear all" confirm · empty (C13) · error (C14) · loading (C12) · long-press menu · edge-case single row. |

## Sibling iOS package

`design-system/handoff/YT-0029/` mirrors this folder for iOS. Decisions Q1–Q10 are kept identical in substance; the only platform-adapted items are the swipe primitive (`SwipeToDismissBox` ↔ `swipeActions`), the menu primitive (`ModalBottomSheet` ↔ `contextMenu`), and the destructive-button styling (`colorScheme.error` ↔ `.destructive` role).

## Implementation order

1. `RecentlyPlayedViewModel` — exposes `RecentlyPlayedUiState { entries: List<HistoryEntry>, isLoading, error }`, loads from `HistoryRepository` already wired into `feature/history/` per YT-0048.
2. `RecentlyPlayedScreen` shell — `Scaffold` with `TopAppBar` (title "Recently Played", overflow action "Clear watch history").
3. Body branches off `RecentlyPlayedUiState`: skeleton (C12) → empty (C13) → error (C14) → content.
4. Content: `LazyColumn` with `stickyHeader` per day group + `items(entries) { SwipeableHistoryRow(...) }`.
5. `SwipeToDismissBox` per row — end-to-start with confirm-on-release threshold ≥50% width or velocity ≥600 dp/s.
6. Long-press contextual `ModalBottomSheet` — Play next · Add to queue · Add to playlist · Remove from history · Share.
7. "Clear watch history?" `AlertDialog` from the overflow menu.
8. MiniPlayer-aware bottom padding via `LocalMiniPlayerState.current.safeBottomInset` (carry from YT-0014).

## Tokens — non-negotiable

- Surface: `colorScheme.surface` (body), `surfaceContainer` (dialog).
- Top app bar: `LargeTopAppBar` defaults; on scroll collapses to small.
- Sticky day header background: `colorScheme.surface` (full opacity — sticky must be opaque or the row text reads through).
- Day header text: `typography.titleSmall`, `colorScheme.onSurfaceVariant`.
- Row reuse: `TrackRow` from `core/ui` — leading thumbnail 56 dp, headline `bodyLarge`, supportingContent `bodyMedium` `colorScheme.onSurfaceVariant`.
- Swipe-action background: `colorScheme.errorContainer` with leading `Icons.Rounded.Delete` tinted `colorScheme.onErrorContainer`.
- Destructive button (Clear / Delete): `colorScheme.error`.
- Tap targets: 56 dp default for `ListItem`; 48 dp for icon-only overflow / sheet actions.
- `safeBottomInset` math (carry from YT-0014 Q4): NavBar 80 dp + MiniPlayer 64 dp + gap 8 dp = **152 dp** visible / **88 dp** hidden.

## Aesthetic decisions binding

- **Day-grouped with sticky headers** (decision Q1) — same on both platforms. A flat list is unscannable past ~20 entries; week-grouping is unconventional and forces stale dates after one bingewatch.
- **Timestamp lives in the row's supporting line, not as a column** (Q2). Relative <24 h, absolute otherwise. Same format string family on both platforms.
- **"Clear all" is a dialog, not a toast-with-undo** (Q6). History is a long-running list with semantic value (autoplay seeding, "what was that song"), so destructive bulk-clear earns its friction; single-row removes do not — they are reversible by replaying.
- **State-catalog cells C12 / C13 / C14 are consumed verbatim** (Q7 + Q10). No per-screen copy override. If a string changes, change it in the state-catalog and we inherit.
- **Dedup-by-videoId is shipped behavior, not a design choice to relitigate** (Q3). Cite YT-0048 / YT-0154 / YT-0158. Implementers must not "fix" it.

## Override convention

There are no overrides in this package — every decision documents shipped behavior. If a future task proposes a change, the override entry in `decision-log.md` must:
1. Cite the original decision verbatim.
2. Name the alternative and where it lives.
3. Tag the decision **`override-pending-reviewer-approval`** until the reviewer signs off.

See `decision-log.md` for full rationale.
