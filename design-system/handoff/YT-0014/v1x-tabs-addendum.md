# YT-0014 — v1.x Tabs Addendum (post-MVP, NOT the MVP build target)

> This file preserves the original 4-tab Library spec from the YT-0072 first run. **It is not the MVP scope.** The MVP is Playlists-only; see `decision-log.md` and `compose-spec.md`. Use this addendum only when the post-MVP epic for Downloads / Liked / multi-tab Library opens.

## Why this lives in the repo

The first run of YT-0014 (under YT-0072) shipped a polished 4-tab spec (Playlists · Downloads · History · Liked) that the reviewer rejected as out-of-scope for MVP. The redo (YT-0075) trims back to Playlists-only. Rather than delete the 4-tab work, it's parked here so a future implementer doesn't reinvent it.

When the multi-tab epic opens:
1. Promote the relevant sections of this addendum into `decision-log.md` / `compose-spec.md` proper.
2. Re-run the design loop on whatever has drifted since (`docs/design-system.md` tokens, M3 version, AppShell contract).
3. Reconcile the History tab with the standalone `HistoryScreen` from YT-0015 — pick one home, not both.

## Original 4-tab decisions (verbatim from v1, dimmed)

### v1 Q1 — Top-level structure

> A single `LibraryScreen` Composable with a M3 `SecondaryTabRow` and four pages: Playlists · Downloads · History · Liked. Each page is its own Composable + ViewModel + Paging source. The pager state lives in `rememberPagerState()` driving a `HorizontalPager`. Last-tab is persisted in DataStore. First-launch default tab: Downloads.

### v1 Q2 — Tab order and naming

> Playlists · Downloads · History · Liked. "Playlists" includes user-created and auto-created (Watch Later auto-playlist). "Downloads" is local files, not a saved-for-later concept. "History" is watch history (chronological, descending). "Liked" is the materialized "Liked Videos" list.

### v1 Q3 — Downloads source-of-truth: Room, file-system as cache

> A `DownloadsDao` row exists for every downloaded video, with `localPath`, `bytes`, `downloadedAt`, `state`, `progress`. The actual file is cache. Three row states: completed, in-progress (with `LinearProgressIndicator(progress = { row.progress })`), missing-file (0.6 alpha + warning glyph). Reconciliation worker on app start.

### v1 Q4 — Empty states (per tab)

> Playlists / Downloads / History / Liked each had a custom empty state with glyph + headline + body + optional CTA.

### v1 Q5 — Sort & filter

> Per-tab `Row` of `FilterChip`s. Selection persisted in `LibraryPrefsDataStore`. Single-select per tab.

### v1 Q6 — Long-press contextual menu

> Long-press → `ModalBottomSheet` with row-specific actions. Destructive at the bottom.

### v1 Q9 — Paging

> Each tab has its own `Pager` from `androidx.paging:paging-compose`. Don't aggregate.

### v1 Q10 — Mockup web-isms

> 4-photo collage for empty playlist cover dropped in favor of single-color tile. (**This decision was reversed by YT-0075** — the 4-up cover is required for MVP. The single-color tile is a 0-track fallback only.)

## What changes when the multi-tab epic opens

| Aspect | v1 (this file) | MVP redo (active) | Multi-tab future |
|---|---|---|---|
| Top-level | `SecondaryTabRow` + pager | none | re-introduce, but **Playlists default**, not Downloads |
| Default tab | Downloads | n/a | Playlists (corrected) |
| Empty playlist cover | single-color tile | 4-up cover with fallbacks | same as MVP |
| Edit mode | absent | full (drag-handle + remove + undo) | same as MVP |
| FAB | absent | Extended FAB (Playlists only) | Extended FAB visible only on Playlists tab; hides on others |
| MiniPlayer-aware padding | absent | required | required (already shipped) |
| Per-tab sort chips | yes | n/a | yes — apply per tab |
| Downloads reconciliation | spec'd | n/a | spec is reusable; verify Room schema hasn't drifted |

## Deferred file-system / Room contracts

The v1 spec called for these — keep the shape when the post-MVP epic resumes:

- `DownloadEntity { id, videoId, localPath, bytes, downloadedAt, state, progress, fileExists }` with state enum `{ Queued, Downloading, Paused, Completed, Failed }`.
- `LikedDao.likedFlow(sort)` paged 50/page.
- `LibraryPrefsDataStore` with `lastTab`, `playlistsSort`, `downloadsSort`, `likedSort`, `historyWindow`.

None of these are MVP. Do not implement until the multi-tab epic opens.

## Drift to watch when promoting this work

- M3 version: `SecondaryTabRow` lambda overloads have shifted between Compose 1.6 and 1.7; verify the indicator slot.
- `AppShell` `LocalMiniPlayerState` (introduced in MVP) is now the single source for safe bottom inset — the old per-page `Spacer(Modifier.height(64.dp))` hack from v1 is dead, do not bring it back.
- `HistoryScreen` (YT-0015) ships standalone before this epic. Decide whether the History tab pulls a slice of its content or replaces the standalone screen.
