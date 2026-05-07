# YT-0073 — Loading variant per screen

> **Rule:** skeleton when the *shape of the result is known*. Spinner only when the shape is opaque.

---

## Skeleton vs. spinner — the decision

| Question | If yes → | If no → |
|---|---|---|
| Will the result render as rows/cards in a list this user has seen before? | **Skeleton** | spinner |
| Is the response time bounded and short (< 800 ms typical)? | spinner OK | **skeleton** |
| Is the operation opaque (export, file write, cache clear)? | **spinner** | skeleton |
| Is the screen blank before the request fires? | **skeleton** | spinner |

Default to skeleton. Spinner is a fallback for opaque operations.

---

## Shimmer rules (every skeleton, every screen)

- Background fill: `--skeleton-bg` (`#2C2C2E`).
- Highlight: `--skeleton-shimmer` (`rgba(255,255,255,0.06)`) — a 30% wide diagonal gradient sweeping left → right.
- Cycle: **1400 ms**, linear, infinite.
- Stagger: every row's shimmer is offset by `index × 80 ms`. Prevents the "marching wall" effect — staggered shimmer reads as living, synchronized shimmer reads as broken.
- Pause shimmer when `Reduce Motion` is enabled (Android `Settings.Global.TRANSITION_ANIMATION_SCALE == 0`, iOS `UIAccessibility.isReduceMotionEnabled`). Static `--skeleton-bg` blocks remain visible — the *shape* still communicates loading.
- Corner radius matches the production component it stands in for. Track row thumbnails: `--radius-sm` (8px). Playlist 4-up cover: `--radius-sm` (8px) outer, no inner gaps in skeleton form. Buttons: `--radius-full`.
- Skeletons are **never tappable**. Wrap in a container with `pointerEvents: none` (web) / `Modifier.semantics { invisibleToUser() }` (Compose) / `accessibilityHidden(true)` (SwiftUI). The list container itself carries `accessibilityValue("Loading")`.

---

## C1 — Search · Loading

**Variant:** skeleton, 6 rows.
**Trigger:** the user submitted a query (Return / search button / debounced 400 ms after last keystroke). Replaces the suggestion-chip idle state.

### Template
- 6 × skeleton track rows.
- Each row: 56dp/pt thumbnail block on the leading side, two stacked text lines on trailing side (line 1: 60% width, 14dp height; line 2: 35% width, 11dp height, 6dp gap). 16dp padding all sides.
- Suggestion chips above the list (idle state) **disappear** when loading begins — they don't co-exist with skeleton rows.

### Rationale
Search results are always a flat list of `TrackRow`s. The user has seen this shape on every successful search. Spinner would feel slower because it implies "we don't know what's coming". Skeleton implies "we know what's coming, just waiting on the wire."

---

## C6 — Library · Loading

**Variant:** skeleton, 4 rows.
**Trigger:** initial cold load of the Library tab. Subsequent visits read from in-memory cache and never show this state.

### Template
- "Recently Played" entry row stays rendered above (it's local-first, always available).
- 4 × skeleton playlist rows.
- Each row: 40dp/pt 4-up cover block (no inner grid lines in skeleton — solid block with `--radius-sm`), one text line (50% width, 14dp height), one smaller text line (25% width, 11dp height, 6dp gap).

### Rationale
Library is mostly local; this state is only seen on cold launch or after Settings → Clear cache. 4 rows is enough to communicate "list of playlists" without overpromising the count. Resist the temptation to show 8 rows — if the user only has 2 playlists, 4 skeletons → 2 real rows is acceptable; 8 → 2 looks like the load failed.

---

## C9 — Playlist Detail · Loading

**Variant:** skeleton header + skeleton body, 6 rows.
**Trigger:** navigating into a playlist whose tracks aren't in cache (rare — usually after import or fresh install).

### Template
- **Header skeleton:**
  - 140dp/pt 4-up cover block, centered, `--radius-md`.
  - 24dp gap.
  - Title placeholder: 60% width, 22dp height, centered.
  - Subtitle placeholder: 30% width, 13dp height, 8dp gap below title, centered.
  - 16dp gap.
  - Two button-shaped skeletons side-by-side (Play / Shuffle), 120dp/pt × 44dp/pt each, `--radius-full`.
- **Body skeleton:**
  - 6 × skeleton track rows (same template as C1).

### Rationale
The header is the most visible part of the screen and the most jarring if it pops in suddenly. Skeleton-ing the cover + title prevents layout shift when real data arrives. Two pill skeletons for Play/Shuffle confirm the layout the user has seen before.

---

## C12 — History · Loading

**Variant:** skeleton, 6 rows.
**Trigger:** initial load of the History destination.

### Template
- 6 × skeleton track rows. Identical to C1.
- "Clear" toolbar button is **hidden** during loading (it's only shown when history is non-empty, and we don't yet know).

### Rationale
History is structurally identical to search results — flat list of `TrackRow`. Reusing the template reduces visual surface area and component count.

---

## C15 — Settings · Operation in progress

**Variant:** inline spinner (24dp/pt) replacing the row's trailing chevron. Subtitle text below the row label updates to the in-progress copy from `copy.md` C15.

### Template
- The trailing `chevron.right` glyph swaps with a `CircularProgressIndicator(strokeWidth = 2dp)` (Compose) / `ProgressView()` with `.controlSize(.small)` (SwiftUI).
- Row stays at full opacity. The other rows on the page are **not** dimmed — only this row's affordance changes.
- Tapping the row again cancels (export, import). Clear cache and clear history rows ignore the second tap; they're committed once started.

### Rationale
- A full-screen spinner over the Settings page would block the user from scrolling or starting a *different* operation in parallel.
- A toast / snackbar is too transient — the user can dismiss it and lose track of what's still running.
- Inline-on-the-row is the only place that keeps the operation tied to the user's intent.

---

## What we explicitly rejected

| Rejected | Why |
|---|---|
| Lottie / animated illustration loaders | Heavyweight, off-brand, feels like a marketing app. |
| Full-screen branded splash | We have no marketing splash by design system rule. |
| Pull-to-refresh spinner as initial load | PTR is for refresh of *existing* content. Initial load needs skeleton because there's nothing to pull. |
| Indeterminate top progress bar (Material) over a skeleton | Two loading indicators at once is noise. Pick one. |
| Skeleton for Settings page rows | Settings rows render synchronously from local state. There is no loading. |
