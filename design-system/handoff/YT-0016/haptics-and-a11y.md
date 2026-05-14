# YT-0016 — Haptics & Accessibility (Android)

Companion to [`decision-log.md`](decision-log.md) decisions 7, 9, 10. TalkBack rules, hit targets, Dynamic Type, and the haptic table for every share / import event.

---

## Haptics — `LocalHapticFeedback` + `View.performHapticFeedback`

Use `LocalHapticFeedback.current` for tap-events; drop down to `View.performHapticFeedback(HapticFeedbackConstants.*)` only where the SDK constant matches a system-specific event (e.g. reorder, scrub tick — none of those apply here, so this surface stays on the Compose API).

| UI event | API |
|---|---|
| Open export sheet (Share… tapped from entry point) | `HapticFeedbackType.LongPress` |
| Tap **Share** in export sheet | `HapticFeedbackType.LongPress` (the "confident" type; on API 34+ swap to `HapticFeedbackType.Confirm`) |
| Tap **Save to Files** in export sheet | `HapticFeedbackType.LongPress` / `Confirm` (same — same op weight) |
| Tap **Copy link** in export sheet | `HapticFeedbackType.TextHandleMove` (lightweight, "small confirmation") |
| Long-press a playlist row → context menu opens | `HapticFeedbackType.LongPress` |
| Open file picker (Import… tapped) | `HapticFeedbackType.LongPress` |
| Import success preview sheet appears | (none — the sheet's enter animation + the toast carries the confirmation) |
| Tap **Done** in success sheet | `HapticFeedbackType.TextHandleMove` |
| Tap **View Playlist** in success sheet | `HapticFeedbackType.LongPress` (navigation feel) |
| Tap **Try Again** in an error variant | `HapticFeedbackType.TextHandleMove` |
| Tap **Open Play Store** in future-schema error | `HapticFeedbackType.LongPress` |
| Op loading overlay (C15) appears | (none — the overlay is a visual signal; doubling with haptic feels overstuffed) |
| Op loading overlay dismissed because op completed | (none — the success sheet's appearance is the haptic anchor) |
| Op loading overlay dismissed because op failed → error | (none — the error state's announcement carries it) |

**Do NOT** fire a haptic on every state transition in the import state machine — that produces a buzzy 3-haptic sequence (overlay-appears → overlay-dismisses → success-sheet-appears) on a normal-speed import. Anchor haptics to **user taps**, not state changes.

---

## TalkBack — labels, hints, live regions

### Sheet titles and roles

| Surface | Accessible role / semantics | Title announcement |
|---|---|---|
| Export sheet (root) | `Modifier.semantics { paneTitle = "Share playlist"; role = Role.Sheet }` (paneTitle is announced when the sheet appears) | "Share playlist" |
| Import success sheet | `paneTitle = "Imported playlist"` | "Imported playlist" |
| Op loading overlay (C15) | `liveRegion = LiveRegionMode.Polite; contentDescription = "Loading"` | "Loading" (on appear; nothing on dismiss — state-catalog C15 contract) |
| Error variant (parse / future-schema / wrong-extension / read-permission) | `liveRegion = LiveRegionMode.Assertive` on the error container (first-time appearance); subsequent re-renders use `Polite` | "Couldn't import playlist — file is invalid." (variant 1) etc. — the title carries the meaning |

The error variants use **Assertive** on first appearance because they require the user to abandon the current path and take a different action. Subsequent renders (e.g. after rotation) drop to Polite to avoid re-interrupting the user. State-catalog README § Live announcements section covers this rule.

### Per-element labels

| Element | `contentDescription` / `Text` | `Role` / hint |
|---|---|---|
| Sheet drag handle | `"Drag to dismiss"` | `Role.Button` |
| Playlist name in export sheet | (taken from displayed `Text`; mark as `semantics { heading() }`) | heading |
| File metadata row ("12 tracks · 4.2 KB · .ytplaylist.json") | `mergeDescendants = true`; combined label `"12 tracks, 4.2 kilobytes, dot-y-t-playlist-dot-jay-son file"` | (read as a single group) |
| **Share** button | `"Share \"{playlistName}\""` | `Role.Button` |
| **Save to Files** button | `"Save \"{playlistName}\" to Files"` | `Role.Button` |
| **Copy link** button | `"Copy link to \"{playlistName}\""` | `Role.Button` |
| Import success sheet title | (`semantics { heading() }`) | heading |
| Track preview row (in success sheet) | `"\"{title}\" by \"{channel}\""` | (no role — non-interactive informational row) |
| `+ N more tracks` row | `"plus {N} more tracks"` | (no role) |
| **Done** button | `"Done"` | `Role.Button` |
| **View Playlist** button | `"View imported playlist"` | `Role.Button` |
| Error variant icon | `Modifier.semantics { invisibleToUser() }` (decorative; title carries the meaning — state-catalog rule) | — |
| Error variant title | (heading) | heading |
| Error variant **Try Again** button | `"Try again — choose another file"` | `Role.Button` |
| Error variant **Open Play Store** button (variant 2 only) | `"Open Play Store to update YourTube"` | `Role.Button` |
| Op loading overlay scrim | `Modifier.semantics { invisibleToUser() }` (the container's liveRegion + contentDescription is enough) | — |

### Live announcement table

| Event | Channel | Politeness | Message |
|---|---|---|---|
| Export sheet appears | `paneTitle` | (auto) | "Share playlist" |
| Op loading overlay appears (export) | `liveRegion` | Polite | "Loading" |
| Op loading overlay appears (import) | `liveRegion` | Polite | "Loading" |
| Share/Save chooser opens | system-owned (`Intent.createChooser`) | system | (system announces) |
| Toast T05 fires after export | M3 `Snackbar` — auto-announced Polite | Polite | "Exported \"My Mix\"" |
| Import success sheet appears | `paneTitle` | (auto) | "Imported playlist" |
| Toast T06 fires after Done / View Playlist | Snackbar — auto-announced Polite | Polite | "Imported \"My Mix\" — 12 tracks" |
| Error variant appears (any) | `liveRegion` | **Assertive** (first appearance) | (the title — e.g. "Couldn't import playlist — file is invalid.") |

---

## Hit targets

All interactive elements meet the M3 48 dp minimum:

```kotlin
IconButton(onClick = ...) { Icon(...) }   // IconButton is 48 dp by default
Button(modifier = Modifier.heightIn(min = 56.dp), ...)   // Filled buttons in the sheet
OutlinedButton(modifier = Modifier.heightIn(min = 56.dp), ...)
TextButton(modifier = Modifier.heightIn(min = 48.dp), ...)
```

`Modifier.minimumInteractiveComponentSize()` is applied automatically by M3 `IconButton`/`Button`/`OutlinedButton` — do not strip it. The 56 dp on the sheet's primary/secondary action is the M3 "large button" pattern for sheet-anchored actions; do not collapse to 40 dp.

---

## Dynamic Type / font scaling

Android honors `fontScale` automatically when text styles come from `MaterialTheme.typography`. Per `colors_and_type.css` and the design-system Typography table:

| Element | Style | Behavior at largest scale (200%) |
|---|---|---|
| Sheet title (playlist name) | `typography.titleLarge` (22 sp) | scales freely; clamp `maxLines = 2` with `TextOverflow.Ellipsis` + `Modifier.basicMarquee()` for one-line marquee fallback |
| Metadata row | `typography.bodyMedium` (14 sp) | scales freely; wraps to multiple lines if needed |
| Button labels | `typography.labelLarge` (14 sp) | scales freely; button heights are `Min` not `Max` so they grow vertically |
| Import success preview rows — title | `typography.bodyLarge` (16 sp) | one-line ellipsis at AX5 only |
| Import success preview rows — channel | `typography.bodyMedium` (14 sp) | one-line ellipsis |
| Error variant title | `typography.titleMedium` (18 sp) | scales freely; `maxLines = 3` with `TextOverflow.Ellipsis` |
| Error variant body | `typography.bodyMedium` (14 sp) | scales freely; no clamp |
| Filename suffix in metadata (`.ytplaylist.json`) | inline within the body label | scales with body; no special handling |

The sheets are `verticalScroll(rememberScrollState())` so the full content remains reachable at AX5. Do not collapse the metadata row or hide the secondary buttons at large scale.

---

## Color contrast

All values verified against `colors_and_type.css` dark-theme tokens against the export sheet's `surfaceContainerHigh` background (~`#2A2A2C` dark):

| Pair | Ratio | Passes |
|---|---|---|
| Title `onSurface` (`#FFFFFF`) on sheet bg | ~14:1 | AAA |
| Metadata `onSurfaceVariant` (`rgba(235,235,245,0.80)`) on sheet bg | ~11:1 | AAA |
| Filled button label (`onPrimary` = white) on `primary` (`#8B5CF6`) | ~4.6:1 | AA (just over the floor — do not lighten primary) |
| Outlined button label (`primary`) on sheet bg | ~5.3:1 | AA non-text |
| Error variant title on `surface` (full-screen bg `#0F0F0F`) | ~19.5:1 | AAA |
| Error variant icon `onSurfaceVariant` (the tertiary tint per state-catalog rule) | ~4.1:1 | AA non-text |

**Do not tint the error icon red.** Cite state-catalog README § Tokens. Red is reserved for destructive confirmation (delete-playlist) — not for recoverable errors.

---

## TalkBack reading order

Export sheet (on appear, focus lands on title):
1. "Share playlist" (paneTitle, auto-announced)
2. "My Mix" (title, heading)
3. "12 tracks, 4.2 kilobytes, dot-y-t-playlist-dot-jay-son file"
4. "Share My Mix, button"
5. "Save My Mix to Files, button"
6. "Copy link to My Mix, button"
7. (swipe down → dismiss; `paneTitle` removed; focus returns to invoker)

Import success sheet:
1. "Imported playlist" (paneTitle)
2. "Imported \"My Mix\" — 12 tracks" (heading)
3. (preview rows — read as individual non-interactive items unless user explicitly swipes; merge each row with `mergeDescendants = true`)
4. "Done, button"
5. "View imported playlist, button"

Error variants (Assertive on first appearance interrupts to read the title; focus then lands on the title for further exploration):
1. *(announcement)* "Couldn't import playlist — file is invalid." (variant 1)
2. (body)
3. "Try again — choose another file, button"

---

## AirDrop / system import context

This surface has no Android equivalent to iOS AirDrop receive (cite decision 8). Files arrive via `Intent.ACTION_OPEN_DOCUMENT` regardless of source app. Accessibility behavior for the system file picker is OS-owned; do not override.

---

## Reduce motion

The export sheet, import success sheet, error variants, and op overlay use M3 default animations. Reduce-motion (`Settings.Global.TRANSITION_ANIMATION_SCALE == 0f`) is honored by the platform — M3 `ModalBottomSheet` snaps instead of sliding when transitions are disabled. Do not hand-roll an animation that bypasses the system setting.

Pattern used by C15 overlay (per state-catalog):

```kotlin
val reduceMotion = LocalReduceMotion.current
AnimatedVisibility(
    visible = visible,
    enter = if (reduceMotion) fadeIn(snap()) else fadeIn(tween(120)),
    exit  = if (reduceMotion) fadeOut(snap()) else fadeOut(tween(120)),
) { /* overlay */ }
```
