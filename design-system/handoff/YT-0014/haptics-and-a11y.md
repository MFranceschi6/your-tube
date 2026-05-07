# YT-0014 — Haptics & Accessibility (v2 / MVP)

> Trimmed for the Playlists-only MVP. Adds edit-mode coverage (drag-handle, remove, undo) and MiniPlayer-overlap focus order.

## Haptics table

| UI event | Compose call | API floor / fallback |
|---|---|---|
| FAB tap (open New playlist dialog) | `haptics.performHapticFeedback(HapticFeedbackType.LongPress)` | always |
| Playlist row long-press (open context sheet) | `LongPress` | always |
| Track row tap (read-only, play from here) | none — playback haptic owned by YT-0013 | — |
| Edit toggle tap (Edit / Done) | `TextHandleMove` | API 30+ |
| Drag-handle long-press (start reorder) | `LongPress` | always |
| Reorder drop | `GestureEnd` | API 30+; fallback `LongPress` |
| Reorder threshold cross (item shifts past midpoint) | `SegmentTick` | API 35+; fallback `TextHandleMove` |
| Remove button tap | `Reject` | API 34+; fallback `LongPress` |
| Snackbar "Undo" tap | `Confirm` | API 34+; fallback `LongPress` |
| Whole-playlist Delete confirm | `Reject` | API 34+; fallback `LongPress` |

No haptics on dialog open/close — system handles it. No haptics on snackbar auto-dismiss — silent commit.

## Edit-mode TalkBack contract

The drag-handle is the entry point for accessibility-driven reorder. It must announce a custom action so TalkBack users can move tracks without the long-press gesture:

```kotlin
Modifier.semantics {
  contentDescription = "Move ${track.title}, double-tap to enter reorder"
  customActions = listOf(
    CustomAccessibilityAction("Move up") { vm.moveTrack(track.id, by = -1); true },
    CustomAccessibilityAction("Move down") { vm.moveTrack(track.id, by = +1); true },
    CustomAccessibilityAction("Move to top") { vm.moveTrack(track.id, toIndex = 0); true },
    CustomAccessibilityAction("Move to bottom") { vm.moveTrack(track.id, toIndex = Int.MAX_VALUE); true },
  )
}
```

Remove button:
```kotlin
contentDescription = "Remove ${track.title} from playlist"
// stateDescription not needed — irreversible from a11y standpoint per snackbar window
```

When a remove fires, announce via `LiveRegionMode.Polite` on a hidden Spacer near the snackbar host: "Removed ${track.title}. Tap Undo to restore."

## MiniPlayer overlap — focus order

Every Library screen consumes `LocalMiniPlayerState.current.safeBottomInset` (Q4). The `LazyColumn`'s `contentPadding(bottom = inset)` ensures the last list item lifts above the MiniPlayer; **TalkBack focus order must not let the user land on a row hidden under the MiniPlayer.**

Pattern: leave the inset on the `LazyColumn`, do **not** apply `Modifier.padding(bottom = inset)` to the parent `Scaffold` content — that would shift the snackbar host off-screen too. The `contentPadding` route preserves the snackbar host position.

For the FAB, set `Modifier.semantics { traversalIndex = 0f }` on the FAB so TalkBack reaches it before the list when scanning forward (the FAB is the primary action, the list is secondary).

## System text scaling (`fontScale`)

| Element | Role | At `fontScale = 2.0f` |
|---|---|---|
| Playlist name (`PlaylistRow`) | `bodyLarge` | scales freely; `maxLines = 2` + ellipsis |
| Track count subtitle | `bodyMedium` | scales freely |
| `PlaylistDetailScreen` title | `displaySmall` | scales freely; `LargeTopAppBar` collapse handles overflow |
| FAB label "New playlist" | `labelLarge` | clamp via `Modifier.fontScalingClamp(max = 1.5f)` — pill width breaks otherwise |
| Snackbar message + "Undo" action | M3 default | scales freely (single-line truncation handled by `Snackbar`) |
| Drag-handle "label" (TalkBack only) | n/a — `contentDescription` reads aloud | unaffected by fontScale |
| Confirm dialog body | `bodyMedium` | scales freely |

Verify at `Settings → Accessibility → Display size and text` set to maximum.

## Reduce-motion contract

| Animation | Default | Reduce Motion |
|---|---|---|
| Cover async crossfade (Q2) | `Crossfade(tween(180))` | `snap()` |
| Reorder shift on drag | `tween(180, FastOutSlowInEasing)` per row | instant `snapTo` |
| Reorder spring-back (cancel) | `spring()` | `snapTo` |
| Snackbar enter/exit | M3 default slide | M3 reduced-motion variant (cross-fade) |
| Edit-mode toggle leading slot swap (thumbnail → drag handle) | `Crossfade(tween(140))` | `Crossfade(snap())` |
| Empty-state fade-in | `tween(220)` | instant |

Source: `LocalReduceMotion.current` (per YT-0013 contract — same provider).

## Tap targets

- `PlaylistRow` (`ListItem`): 56 dp default — already meets 48 dp minimum.
- `TrackRow`: 56 dp.
- Drag handle: 24 dp glyph in 48 dp `Modifier.size` hit area (`Modifier.size(48.dp).padding(12.dp)` so the visible glyph centers).
- Remove button: 24 dp glyph in 48 dp `IconButton`.
- FAB: M3 default — already comfortable.
- Snackbar action: M3 default — already 48 dp tall.

## Color contrast

- Playlist name on `surface`: `colorScheme.onSurface` ≈ 16:1 — passes AAA.
- Track count subtitle on `surface`: `colorScheme.onSurfaceVariant` ≈ 7:1 — passes AA body.
- FAB label on `primaryContainer`: `colorScheme.onPrimaryContainer` — Material You guarantees ≥4.5:1 on dynamic schemes; do not override.
- Remove icon `colorScheme.error` on `surface`: ≈ 5:1 on dark M3 — passes AA non-text.
- Drag-handle `colorScheme.onSurfaceVariant` on `surface`: passes AA non-text. Only used on the off-state of an interactive element; the meaning is duplicated by the edit-mode container shift.

## Edit-mode focus management

When `isEditing` toggles `false → true`:
1. Move TalkBack focus to the first drag handle in the visible list (not the "Done" button — users have just tapped Edit and expect to start reordering).
2. Announce via `LiveRegionMode.Polite`: "Edit mode. Reorder or remove tracks."

When `isEditing` toggles `true → false`:
1. Move focus back to the screen title.
2. Announce "Edit mode off. Saved."

Pattern: `Modifier.focusRequester(...)` on the first drag handle + `LaunchedEffect(isEditing) { if (isEditing) requester.requestFocus() }`.
