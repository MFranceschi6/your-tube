# YT-0014 — Decision Log (v2 / MVP — Playlists only)

Android Library tab for YourTube. Validated against `docs/design-system.md` and the YT-0072 acceptance criteria. Mockup at `design-system/mockups/android/YT-0014-0015-0016-0017-library-history-settings-sharing.html` is layout reference only; this log overrides where they disagree.

> **MVP scope: Playlists tab only.** No `SecondaryTabRow`, no pager. Downloads / Liked are post-MVP (`v1x-tabs-addendum.md`). History is YT-0015 (separate screen).

> **Override convention.** Any decision overriding the original YT-0072 ask must (a) cite the ask, (b) name the alternative, (c) be tagged `override-pending-reviewer-approval`. The four blockers from YT-0075 are not overrides — they are now satisfied.

---

## Q1 — Top-level structure (Playlists only)

**Decision: a single `LibraryScreen` Composable. No tabs, no pager.** Top app bar (`LargeTopAppBar`, title "Library") + body `LazyColumn` of playlists + `ExtendedFloatingActionButton` anchored bottom-end.

Secondary navigation goes to `PlaylistDetailScreen` via `navController.navigate("playlist/${playlistId}")`. History is a top-app-bar action (`Icons.Rounded.History`) that navigates to YT-0015's screen.

**Rationale.** YT-0072 acceptance for MVP includes only the Playlists surface; Downloads and Liked do not exist in the codebase. A `SecondaryTabRow` for one tab is meaningless chrome.

**Anti-patterns.**
- ❌ `SecondaryTabRow` with a single tab "stub" + 3 disabled tabs — confuses users about what's coming.
- ❌ Conflating History into the Library screen — History is its own destination per YT-0015.
- ❌ Top-app-bar overflow as the create-playlist entry point — duplicates the FAB and hides creation from new users.

---

## Q2 — Playlist cover: 4-up grid with fallbacks

**Decision: 2×2 grid of the first four track thumbnails**, corner radius `MaterialTheme.shapes.medium = 12.dp`. Cover composable `PlaylistCover(tracks, size)` resolves the layout from the track count.

| Track count | Layout |
|---|---|
| **0** | Single-color tile from `colorScheme.surfaceContainerHighest` + `Icons.Rounded.QueueMusic` glyph 32 dp `colorScheme.onSurfaceVariant` centered |
| **1** | 1-up — first thumbnail fills the full square |
| **2** | Vertical split — left half = thumb 1, right half = thumb 2 |
| **3** | T-split — top half = thumb 1, bottom-left = thumb 2, bottom-right = thumb 3 |
| **4 or more** | 2×2 grid — thumbs 1–4 (top-left, top-right, bottom-left, bottom-right). Track 5+ are not shown on the cover. |

Internal seams: 2 dp `colorScheme.surface` gutters between tiles (visible "gap" between thumbnails).
Outer corner: `RoundedCornerShape(12.dp)` on the parent `Box`; inner tiles clip via the parent's clip — do not round individual tiles.

**Loading & async resolution.** Each tile is an `AsyncImage` with `placeholder = ColorPainter(colorScheme.surfaceVariant)`. When all four images resolve, `Crossfade(tween(180))` from the placeholder grid → resolved grid. Reduce-motion: `snap()`.

**Accessibility.** `contentDescription = "{playlistName}, cover from first ${trackCount.coerceAtMost(4)} tracks"`. Empty case: `"{playlistName}, no tracks yet"`.

**Override note (this is a YT-0075 reversal of v1 Q10).**
- Original ask (YT-0072 acceptance): "playlist cover 4-up rules".
- v1 alternative: single-color tile + glyph (rejected).
- v2 (this file): 4-up as the brief; single-color tile is the 0-track fallback only.

**Anti-patterns.**
- ❌ Showing a play-button overlay on the cover at rest — competes with the row's tap target and the FAB.
- ❌ Stacking different tile sizes for "visual interest" — layout has to be predictable for the 1/2/3 fallback math.
- ❌ Caching the cover as a generated bitmap server-side — the four thumbs are already cached; compose them at render time.

---

## Q3 — Edit mode: in-place reorder + remove with undo

**Decision: in-place edit mode on `PlaylistDetailScreen`.** Top-app-bar shows an "Edit" `TextButton`; tapping it toggles `isEditing` (also exposed via predictive-back / "Done" replacing "Edit"). When `isEditing == true`, every `TrackRow` renders:

| Slot | Content |
|---|---|
| Leading | `Icons.Rounded.DragHandle` 24 dp glyph in `colorScheme.onSurfaceVariant`; **48 dp hit target** (`Modifier.size(48.dp)`); thumbnail hides while editing |
| Headline / supporting | Same as read-only mode |
| Trailing | `IconButton` with `Icons.Rounded.RemoveCircle` 24 dp, `tint = colorScheme.error`, 48 dp hit target |

Read-only mode keeps the leading thumbnail and removes both edit affordances.

### Reorder gesture

Hand-rolled `LongPressDraggable` pattern:
- Long-press on the drag handle (or anywhere on the row) starts a drag — Compose has no stable list-reorder primitive yet, do **not** add a third-party dep (e.g. `sh.calvin.reorderable`); roll it.
- Per-row `Animatable<Float, AnimationVector1D>` for the y-offset.
- During drag, items above/below shift by the row's height (animate via `tween(180, FastOutSlowInEasing)`).
- **Commit on release:** single `viewModel.reorderPlaylist(playlistId, from, to)` call wired to a Room UPDATE on a gap-based `position` column. Never per-frame.
- Haptic on drag start: `HapticFeedbackType.LongPress`. Haptic on drop: `HapticFeedbackType.GestureEnd`.

### Remove flow — toast with undo (NOT confirm dialog)

Tap on the remove button:
1. Optimistically remove the row from the list (in-memory) and call `viewModel.queueRemoval(playlistId, trackId, oldPosition)`.
2. Show a `Snackbar` via `SnackbarHostState.showSnackbar(message = "Removed \"${trackTitle}\"", actionLabel = "Undo", duration = SnackbarDuration.Short)` — Short = 4 s on M3 default; bump to `Long` (10 s) for a 6-second-equivalent target on devices with longer accessibility timeouts.
3. If user taps "Undo" → re-insert at `oldPosition`, cancel the pending Room delete.
4. If snackbar is dismissed (timeout or another snackbar) → commit the Room delete.

Multiple removes within the snackbar window collapse: the snackbar message becomes "Removed N items"; "Undo" restores all of them in order.

The trash-can on a whole playlist is a *different* destructive action and uses an `AlertDialog` (Q8) — undo isn't enough, the user destroys the playlist itself.

### Why undo, not confirm

Per Material 3 destructive-action guidance: low-cost reversible removes pair with `Snackbar` + Undo; high-cost irreversible deletes pair with confirmation dialogs. Removing a single track from a playlist is the former — the dialog is friction.

**Override note.**
- Original ask (YT-0072 acceptance): "edit mode (remove + drag handle)".
- v1: long-press contextual sheet only (rejected — that's *contextual actions*, not in-place edit).
- v2 (this file): full in-place edit with drag-handle + remove + undo. Long-press contextual sheet for read-mode actions (Q6) is preserved; it does NOT replace edit mode.

**Anti-patterns.**
- ❌ Per-frame reorder writes — floods the DB and racks up `MediaController` events on the playing track.
- ❌ Reorder via tap-on-up / tap-on-down arrows — fails accessibility for low-vision users *and* does not respect M3 list-reorder convention.
- ❌ Remove without undo — irreversible by accident; YT-0075 specifies the toast-with-undo pattern explicitly.
- ❌ Hidden "Done" button — the edit toggle must replace "Edit" with "Done" in the same slot.

---

## Q4 — MiniPlayer-aware bottom padding

**Decision: a single `LocalMiniPlayerState` provider in `AppShell`** exposes `isVisible: Boolean` and `safeBottomInset: Dp`. Every Library list reads it via `LocalMiniPlayerState.current.safeBottomInset` and passes it as `LazyColumn(contentPadding = PaddingValues(bottom = inset))`.

### Math

```
NavBar height               = 80.dp   (M3 NavigationBar default; verify against AppShell)
MiniPlayer height           = 64.dp   (per docs/design-system.md)
gap                         =  8.dp
─────────────────────────────────
safeBottomInset (visible)   = 152.dp
safeBottomInset (hidden)    =  88.dp  (NavBar + gap only)
```

`isVisible` is driven from `PlayerState.currentTrack != null` (no track loaded → MiniPlayer hidden → 88 dp; track loaded → 152 dp).

### FAB lift

`ExtendedFloatingActionButton` parent `Box` applies:
```kotlin
Modifier.padding(
  end = 16.dp,
  bottom = LocalMiniPlayerState.current.safeBottomInset - 80.dp + 16.dp,
)
```
i.e. the FAB sits **16 dp above** whichever surface (MiniPlayer or NavBar) is uppermost. The `-80.dp` removes the NavBar offset (the FAB rides above the body, the NavBar is below `Scaffold`'s `bottomBar`). When MiniPlayer is hidden, FAB sits 16 dp above the NavBar; when visible, 16 dp above the MiniPlayer + 8 dp gap.

### Provider shape

```kotlin
data class MiniPlayerState(val isVisible: Boolean, val safeBottomInset: Dp)
val LocalMiniPlayerState = compositionLocalOf<MiniPlayerState> {
  error("LocalMiniPlayerState not provided")
}
```

`AppShell` provides it once: `CompositionLocalProvider(LocalMiniPlayerState provides state) { … }`.

**Anti-patterns.**
- ❌ Per-screen `Spacer(Modifier.height(64.dp))` at the bottom of every list — rots when MiniPlayer height changes.
- ❌ Reading `WindowInsets` for this — system insets don't know about our MiniPlayer.
- ❌ Calculating the inset inside each list independently — drift across screens.

**Override note.** Original ask (YT-0072 acceptance): "MiniPlayer-aware bottom padding". v1: not addressed. v2 (this file): satisfied via `LocalMiniPlayerState`.

---

## Q5 — Extended FAB: New playlist

**Decision: `ExtendedFloatingActionButton` anchored bottom-end of `LibraryScreen`.** Icon `Icons.Rounded.Add`, label "New playlist". Default M3 colors. Fixed at all times — does **not** collapse on scroll (justification below).

Tap → opens a `NewPlaylistDialog` (`AlertDialog` with a single `OutlinedTextField` for the name + Create / Cancel buttons; Create disabled while name is blank). Posting the form → `viewModel.createPlaylist(name)` → navigate to the new `PlaylistDetailScreen`.

### Why no collapse-on-scroll

M3 supports `expanded = false` for FABs that collapse to icon-only when the user scrolls down. We **do not** apply it here:
1. The Library list is rarely long enough for collapse to matter (typical user has <30 playlists).
2. Collapse hides the only "create" affordance behind a glyph — bad for new users on the empty state.
3. The MiniPlayer-aware lift (Q4) already handles the chrome conflict the collapse pattern was designed to solve.

If user testing shows FAB-blocks-content complaints, revisit — but ship MVP fixed.

### Empty-state CTA

When `playlists.isEmpty()`, the empty state copy ends "Tap **New playlist** to start." The phrase "New playlist" is bold but **not** a separate button — the FAB is the only entry point per the binding decision in `README.md`. A second button next to the FAB would split affordance.

**Override note.** Original ask (YT-0072 acceptance): "Extended FAB 'New Playlist'". v1: dropped. v2 (this file): satisfied.

**Anti-patterns.**
- ❌ Plain `FloatingActionButton` (icon-only) — loses the "New playlist" affordance verbatim from the brief.
- ❌ Top-app-bar `+` action duplicating the FAB — splits user attention.
- ❌ Showing a `+` cell as the first row of the playlists list — that's iOS Music's pattern, not M3.

---

## Q6 — Long-press contextual menu (read-only mode)

**Decision: long-press on a `PlaylistRow` (read-only) opens a `ModalBottomSheet`** with these actions:

- Play next
- Add to queue
- Rename
- Delete playlist (destructive — uses `AlertDialog` confirm; see Q8)
- Share

`HorizontalDivider` separates Delete from the rest; Delete content uses `colorScheme.error`.

Inside `PlaylistDetailScreen`, **edit mode replaces this menu for tracks**. Long-press on a track in read-only mode opens the contextual sheet (Play next / Add to queue / Show channel / Add to another playlist / Share). In edit mode, long-press is consumed by the reorder gesture instead.

Haptic on sheet open: `HapticFeedbackType.LongPress`.

---

## Q7 — Read-only `PlaylistDetailScreen` layout

`Scaffold` body is a single `LazyColumn` with a stickied header section:

| Index | Item |
|---|---|
| 0 | Cover (240 dp square, centered) |
| 1 | Title (`displaySmall`, max 2 lines) + meta (`bodyMedium`, `colorScheme.onSurfaceVariant`) — "${count} tracks · ${formatDuration(total)}" |
| 2 | Action row — `Button(Icons.Rounded.PlayArrow, "Play")` + `OutlinedButton(Icons.Rounded.Shuffle, "Shuffle")`, `Arrangement.spacedBy(12.dp)` |
| 3..N | `TrackRow` items |

Top app bar: title (collapses on scroll via `LargeTopAppBar`), navigation icon (back), actions: `Icons.Rounded.Edit` (toggles edit mode) + overflow (`more_vert`). Overflow contains: Rename · Delete (with confirm) · Share.

`safeBottomInset` is consumed via `LocalMiniPlayerState` (Q4). No FAB on this screen — Add tracks is post-MVP and lives on the queue's "Add to playlist" affordance.

---

## Q8 — Destructive confirmation: whole-playlist delete

**Decision: `AlertDialog` with title "Delete playlist?", body "This permanently removes \"{playlistName}\". The tracks themselves stay in your library."** Buttons: `TextButton("Cancel")` (left, `colorScheme.primary`), `TextButton("Delete")` (right, `colorScheme.error`).

This is the **only** destructive action in MVP that uses a confirm dialog. Single-track removes use the toast-with-undo (Q3).

Why dialog here, undo there: deleting a playlist destroys metadata (name, ordering, sharing state) that's harder to reconstruct mentally than a single track removal. Undo on a 6-second window is insufficient confidence for that scale.

---

## Q9 — Empty state

Single empty state for "No playlists yet":

| Slot | Content |
|---|---|
| Glyph | `Icons.Rounded.QueueMusic` 64 dp `colorScheme.onSurfaceVariant` |
| Headline | "No playlists yet" (`titleLarge`, `colorScheme.onSurface`, centered) |
| Body | "Save groups of tracks for offline plays, sharing, or just to find them again. Tap **New playlist** to start." (`bodyMedium`, `colorScheme.onSurfaceVariant`, centered, `text-wrap: pretty` equivalent — Compose just needs the width constraint) |
| CTA | none — the FAB is already on screen and the body copy points at it |

The empty state composable also reserves `safeBottomInset` so the FAB doesn't overlap the body copy.

---

## Q10 — Mockup web-isms — translate to Compose

| Mockup CSS / pattern | Compose / M3 |
|---|---|
| `pal.primary` | `colorScheme.primary` (dynamic on API 31+ via `dynamicDarkColorScheme`; brand fallback `#8B5CF6`) |
| `pal.surface` | `colorScheme.surface` |
| `pal.surfaceVariant` | `colorScheme.surfaceVariant` (skeletons, tile placeholders) |
| `pal.surfaceContainerHighest` | same in Compose (Q2 0-track tile) |
| `pal.onSurface` / `pal.onSurfaceVariant` | same names in Compose |
| `--radius-md: 12px` | `RoundedCornerShape(12.dp)` = `MaterialTheme.shapes.medium` |
| `--radius-sm: 8px` (track thumbnail) | `RoundedCornerShape(8.dp)` |
| 4-photo collage | `PlaylistCover(tracks, size)` (Q2). Don't recreate the CSS grid — clip a single `Box` and lay the four `AsyncImage`s in a 2×2 with 2 dp gutters. |
| FAB pill | `ExtendedFloatingActionButton` — don't roll your own |
| Drag handle CSS `cursor: grab` | n/a on touch; haptic + visual lift instead |
| Snackbar with action | M3 `SnackbarHost` + `SnackbarHostState.showSnackbar(actionLabel = "Undo")` |
| Confirm dialog modal | M3 `AlertDialog` |

---

## Tag legend

- `override-pending-reviewer-approval` — used in this file when v2 reverses a v1 decision; reviewer must accept the reversal explicitly. Q2 (4-up cover) and Q3 (edit mode replacing long-press as the edit affordance) and Q5 (FAB restored) are tagged accordingly until sign-off.
