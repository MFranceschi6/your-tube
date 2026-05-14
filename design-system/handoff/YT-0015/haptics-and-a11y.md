# YT-0015 — Haptics & Accessibility (Android Recently Played)

> Covers row reads, swipe-to-delete announcement, "Clear all" overflow + confirm, day-header heading semantics, empty-state live region, haptics table.

## Haptics table

| UI event | Compose call | API floor / fallback |
|---|---|---|
| Row tap (play from here) | none — playback haptic owned by YT-0013 | — |
| Row long-press (open contextual sheet) | `LongPress` | always |
| Swipe-to-delete cross threshold (≥50% or ≥600 dp/s) | `SegmentTick` | API 35+; fallback `TextHandleMove` |
| Swipe-to-delete **commit** (row dismissed) | `Confirm` | API 34+; fallback `LongPress` |
| Swipe cancelled (spring-back below threshold) | none | — |
| Overflow menu opened (Clear watch history) | `TextHandleMove` | API 30+ |
| Sheet item tap (Play next / Add to queue / Add to playlist / Share) | `TextHandleMove` | API 30+ |
| Sheet item tap (Remove from history) | `Reject` | API 34+; fallback `LongPress` |
| Clear-all confirm tap (Clear button in dialog) | `Reject` | API 34+; fallback `LongPress` |
| Retry button tap (C14) | `TextHandleMove` | API 30+ |

No haptics on dialog / sheet open or close (system handles). No haptic on auto-dismiss of any surface — only on user action.

> **Confirm vs Reject.** `Confirm` for per-row swipe (single, low-stakes, recoverable by replay). `Reject` for the bulk "Clear" confirm and for the destructive sheet item — these announce a destructive commit on a wider scope.

## TalkBack contract — per row

```kotlin
TrackRow(
  modifier = Modifier.semantics(mergeDescendants = true) {
    contentDescription = "${entry.track.title}, ${entry.track.channel}, played ${spokenTime(entry, now)}"
    role = Role.Button
    onLongClick(label = "More actions") { onLongPress(); true }
    customActions = listOf(
      CustomAccessibilityAction("Remove from history") { onSwipeRemove(); true },
      CustomAccessibilityAction("Play next")           { onPlayNext(); true },
      CustomAccessibilityAction("Add to queue")        { onAddToQueue(); true },
      CustomAccessibilityAction("Add to playlist")     { onAddToPlaylist(); true },
      CustomAccessibilityAction("Share")               { onShare(); true },
    )
  }
)
```

`spokenTime(entry, now)` mirrors the visual formatter:

| Visual | Spoken |
|---|---|
| "just now" | "just now" |
| "2 minutes ago" | "2 minutes ago" |
| "1 hour ago" | "1 hour ago" |
| "Today, 14:32" | "today at 2:32 PM" |
| "Yesterday, 09:15" | "yesterday at 9:15 AM" |
| "12 May, 18:04" | "12 May at 6:04 PM" |

The spoken form uses local-locale time formatting (12 h vs 24 h follows the device setting). The visual form is 24 h for terseness; the spoken form should match what the user expects to hear in their locale — TalkBack's default time announcement, NOT a manual string-rebuild.

The **customActions** list duplicates the long-press sheet items as TalkBack-discoverable rotor actions, so a TalkBack user can trigger any of the 5 actions without performing the gesture (long-press is brittle on TalkBack).

## TalkBack — swipe-to-delete

`SwipeToDismissBox`'s default semantics announce "Swipe right to dismiss" on focus when the row is dismissible. Override to make the action explicit:

```kotlin
.semantics {
  // Trailing-swipe action label
  customActions = listOf(
    CustomAccessibilityAction(label = "Remove from history") { onSwipeRemove(); true },
    /* ... other customActions above ... */
  )
}
```

On commit, post a polite live-region announcement: `"Removed {trackTitle} from history"`. The announcement lives on a hidden `Spacer` in the screen root with `Modifier.semantics { liveRegion = LiveRegionMode.Polite }` so it doesn't steal focus.

`SwipeToDismissBoxValue.EndToStart` is announced as the destructive role; in practice TalkBack reads "Remove from history" because of the custom action label above.

## TalkBack — "Clear all" overflow + confirm

### Overflow `IconButton`

```kotlin
IconButton(onClick = { menuOpen = true }) {
  Icon(
    Icons.Rounded.MoreVert,
    contentDescription = "More options",
  )
}
```

The label "More options" matches the Material Symbols convention.

### Dropdown menu item

```kotlin
DropdownMenuItem(
  text = { Text("Clear watch history") },
  onClick = { menuOpen = false; showClearDialog = true },
  leadingIcon = { Icon(Icons.Rounded.DeleteSweep, contentDescription = null) },
)
```

TalkBack reads: `"Clear watch history, button"`. Item label IS the affordance — explicit, no rephrasing.

### Confirm dialog announces destructive impact

```kotlin
AlertDialog(
  onDismissRequest = ...,
  title = { Text("Clear watch history?", modifier = Modifier.semantics { heading() }) },
  text  = { Text(
    "This permanently removes all entries from your history. Tracks themselves stay in your library.",
    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive }
  )},
  confirmButton = { TextButton(onClick = ...) {
    Text("Clear", color = colorScheme.error)
    /* `Modifier.semantics { stateDescription = "Destructive" }` is implicit via the error tint
       + button label; do NOT add a redundant "Are you sure" hint */
  }},
  dismissButton = { TextButton(onClick = ...) { Text("Cancel") } },
)
```

`LiveRegionMode.Assertive` on the body interrupts TalkBack to ensure the user hears "This permanently removes all entries from your history" BEFORE focus lands on the Clear button — the destructive impact must be announced.

Focus on dialog open: lands on the title (heading semantics). Tab order: title → body → Cancel → Clear.

## Day section headers — `Modifier.semantics { heading() }`

```kotlin
@Composable
private fun DayHeader(key: DayKey) {
  Text(
    text     = key.label,
    style    = typography.titleSmall,
    color    = colorScheme.onSurfaceVariant,
    modifier = Modifier.semantics { heading() },
  )
}
```

Adding `heading()` makes TalkBack expose the day headers as navigation anchors via the rotor / by-headings reading mode. A user can swipe through "Today", "Yesterday", "10 May 2026" without listening to every row.

## Empty state (C13) — announcement contract

Per state-catalog (`README.md` § Accessibility § Live announcements):

> **Empty state does NOT auto-announce** in the general case — it appears in response to a user action they just performed and they will discover it via natural navigation.

History's empty state appears either (a) on first launch (user opened History from Library — the heading "Recently Played" has already announced via title) or (b) immediately after a successful "Clear all" (the confirm dialog dismissed; focus returns to the toolbar; the user knows what they just did). In both cases the empty state does not need to interrupt — focus lands naturally on the title heading via the standard screen-change announcement.

The body copy "Tracks you play will show up here." renders inside an `EmptyState` composable whose root has `mergeDescendants = true` so TalkBack reads the full title + body + action button on focus, without re-announcing on every focus change.

## Error state (C14) — auto-announce

Per state-catalog: **errors auto-announce on appear.**

```kotlin
ErrorState(
  /* ... */
  modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
)
```

`Polite` (not `Assertive`): the user is not in a destructive flow; informing them gracefully is correct. If the error state replaces a Loading state in a foreground tab on initial load, that single transition uses `Assertive` per state-catalog § Live announcements.

## Focus order on screen open

1. Top app bar title ("Recently Played")
2. Overflow button (if present)
3. First day header
4. First row
5. ... subsequent rows / day headers in scroll order

The back navigation icon (top-bar leading) is reachable via TalkBack rotor → "Navigation" but does not appear in the linear focus order before the title (Material 3 convention).

## MiniPlayer overlap — focus order

The screen consumes `LocalMiniPlayerState.current.safeBottomInset` via `LazyColumn`'s `contentPadding(bottom = inset)`. **TalkBack focus order must not let the user land on a row hidden under the MiniPlayer.** The `contentPadding` route ensures the last row's bounds clear the MiniPlayer; the row remains a focus target only if visible.

The MiniPlayer itself owns its own a11y group; no work needed here.

## System text scaling (`fontScale`)

| Element | Role | At `fontScale = 2.0f` |
|---|---|---|
| Top app bar title "Recently Played" | `LargeTopAppBar` default | scales freely; collapses to small on scroll regardless of scale |
| Day header label ("Today", "Yesterday", "12 May 2026") | `titleSmall` | scales freely; max 1 line — clip with ellipsis if locale extends |
| Track title (`TrackRow` headline) | `bodyLarge` | scales freely; `maxLines = 1` + ellipsis |
| Channel + duration line | `bodyMedium` | scales freely; `maxLines = 1` + ellipsis |
| Timestamp line (`supportingExtra`) | `bodyMedium` | scales freely; `maxLines = 1` + ellipsis |
| Skeleton row | matches `TrackRow` heights at base scale | height grows with text scale via the live row template, NOT a hardcoded skeleton height |
| Empty / error title | `titleLarge` (state-catalog default) | scales freely |
| Empty / error body | `bodyMedium` | scales freely |
| Empty / error action button label | `labelLarge` | scales freely; min 48 dp hit target preserved |
| Dialog title / body / buttons | M3 defaults | scale freely |
| Sheet item label | `bodyLarge` | scales freely |

Verify at `Settings → Accessibility → Display size and text` set to maximum on a 360-dp-wide device — no text clips out of the row, no day-header label vanishes.

## Reduce-motion contract

| Animation | Default | Reduce Motion |
|---|---|---|
| `SwipeToDismissBox` spring-back (cancelled swipe) | `spring()` | `snapTo` (M3 reduced-motion variant) |
| Row disappear after commit (visual gap collapse) | `tween(180)` | instant `snapTo` |
| Sheet enter / exit | M3 `ModalBottomSheet` default | M3 reduced-motion variant (cross-fade) |
| Skeleton shimmer | `1400ms linear infinite` | static gray rectangles (no shimmer) |
| Empty state fade-in (state transition) | `tween(220)` | instant |

Source: `LocalReduceMotion.current` (per YT-0013 contract — same provider).

## Tap targets

- Row: 56 dp default for `ListItem` — meets 48 dp minimum.
- Overflow `IconButton`: 48 dp.
- Sheet items: 56 dp default for `ListItem`.
- Dialog buttons: M3 default — already 48 dp tall.
- Retry button (C14): 48 dp via `Button` defaults.
- Swipe handle: the entire row IS the swipe target — no separate handle hit area.

## Color contrast

- Track title on `surface`: `colorScheme.onSurface` ≈ 16:1 — passes AAA.
- Channel / timestamp on `surface`: `colorScheme.onSurfaceVariant` ≈ 7:1 — passes AA body.
- Day header label on `surface` (`onSurfaceVariant`): ≈ 7:1 — passes AA.
- Swipe-action glyph on `errorContainer`: `colorScheme.onErrorContainer` on dark M3 ≈ 7.5:1 — passes AA non-text + AAA text.
- Clear button label `colorScheme.error` on `surface` (dialog ground): ≈ 5:1 on dark M3 — passes AA.

## Predictive back

`navController.popBackStack()` on system back. With predictive back enabled (API 34+), the gesture animation runs automatically. Reduce-motion fallback: instant back.

No custom back handler — let the system handle. The screen has no in-place modal state that would override back (the dialog and the sheet both consume back to dismiss themselves first, then back returns to Library — that's the system default for `AlertDialog` and `ModalBottomSheet`).

## Acceptance checklist — verify before ship

- [ ] Row TalkBack reads: "Sunset Avenue, Lofi Girl, played 2 minutes ago"
- [ ] Day headers expose `heading()` semantics — verify in TalkBack rotor → "Headings"
- [ ] Swipe-to-commit announces "Removed Sunset Avenue from history" via polite live region
- [ ] Clear-all confirm dialog announces full body via assertive live region; focus lands on title
- [ ] Empty state does not auto-announce; focus lands on title via screen-change
- [ ] Error state auto-announces politely on initial-load failure (assertive in foreground tab)
- [ ] Reduce Motion turned on: no swipe spring-back animation; instant row removal; skeleton static
- [ ] Display size & text at max: no clipping anywhere; min 48 dp hit targets preserved
