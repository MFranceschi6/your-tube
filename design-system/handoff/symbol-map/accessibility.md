# Icon Accessibility — contentDescription and accessibilityLabel Rules

> **Applies to:** all icons in `symbol-map.md` across Android and iOS.
> **Authority:** this file defines the accessibility labeling contract. Inline labels in per-surface handoff packages are normative but may lag this document; when they conflict, this document wins.
>
> The goal is that every icon-only control is usable with TalkBack (Android) and VoiceOver (iOS) without any sighted context. A screen-reader user who hears "Next track" must be able to infer the full action — not just the glyph name.

---

## Core principles

1. **Action name, not glyph name.** Say "Next track" not "Skip next" and not "Forward". Say "Shuffle" not "Shuffle arrows". The label describes *what the button does*, not *what the icon looks like*.
2. **State in the label for toggle controls.** "Shuffle on" and "Shuffle off" are preferable to "Shuffle" with no state signal. See the toggle rules below.
3. **Decorative icons get null/hidden, not an empty string.** An empty string is announced as nothing but still creates a focusable element in some assistive technologies. Use `null` (Compose) or `accessibilityHidden(true)` (SwiftUI) for genuinely decorative icons.
4. **Icons inside labeled containers do not need their own label.** If an icon is inside a button that has a visible text label, or inside an element with a composite accessibility label (e.g. a `ListItem` whose `headlineContent` + `supportingContent` provide full context), mark the icon decorative (`contentDescription = null` / `accessibilityHidden(true)`).
5. **Interactive elements always have a label.** No `IconButton` or `Button` containing only an icon may have a null or empty accessibility label. The button must carry the label if the icon is decorative inside it.

---

## Android — contentDescription patterns

### The two approaches

**Approach A — label on the `IconButton`:**
```kotlin
IconButton(
    modifier = Modifier.semantics { contentDescription = "Next track" },
    onClick = onSkipNextClick,
) {
    Icon(
        imageVector = Icons.Rounded.SkipNext,
        contentDescription = null,   // icon is decorative inside labeled button
    )
}
```

**Approach B — label directly on the `Icon`:**
```kotlin
IconButton(onClick = onSkipNextClick) {
    Icon(
        imageVector = Icons.Rounded.SkipNext,
        contentDescription = "Next track",
    )
}
```

Both are valid. Prefer Approach A when you need to include dynamic state in the label (see toggle examples below), because `Modifier.semantics` can be computed from state more cleanly than passing a string into `Icon`.

### Null / decorative

```kotlin
Icon(
    imageVector = Icons.Rounded.QueueMusic,
    contentDescription = null,   // parent ListItem already provides the label
)
```

Use `null` (not empty string `""`) for decorative icons inside a container that has its own label.

---

## iOS — accessibilityLabel patterns

### Static label

```swift
Button { onSkipNext() } label: {
    Image(systemName: "forward.fill")
        .accessibilityHidden(true)   // icon is decorative inside labeled button
}
.accessibilityLabel("Next track")
```

Or equivalently:

```swift
Button { onSkipNext() } label: {
    Image(systemName: "forward.fill")
}
.accessibilityLabel("Next track")
```

SF Symbols emit their localized description by default. Always override with `.accessibilityLabel()` when the default description does not match the action name ("Forward" is not "Next track").

### Decorative icon inside a composite element

```swift
Image(systemName: "music.note.list")
    .accessibilityHidden(true)
```

Use `.accessibilityHidden(true)` when the icon's parent view provides a full accessibility label via `.accessibilityElement(children: .combine)` or `.accessibilityLabel`.

---

## Label table — by semantic action

| Semantic Action | Android contentDescription | iOS accessibilityLabel | Notes |
|---|---|---|---|
| play | `"Play"` | `"Play"` | Only when content is not already playing |
| pause | `"Pause"` | `"Pause"` | Only when content is playing |
| play/pause (toggle) | `"Play"` or `"Pause"` (state-dependent) | `"Play"` or `"Pause"` (state-dependent) | Never `"Toggle play pause"` |
| play/pause (buffering) | `"Loading"` | `"Loading"` | When `isBuffering` is true; control should also be disabled |
| stop | `"Stop"` | `"Stop"` | |
| skip-next | `"Next track"` | `"Next track"` | |
| skip-previous | `"Previous track"` | `"Previous track"` | |
| shuffle (inactive) | `"Shuffle off"` | `"Shuffle"` | |
| shuffle (active) | `"Shuffle on"` | `"Shuffle on"` | State suffix indicates current state |
| repeat (off) | `"Repeat off"` | `"Repeat off"` | State suffix indicates current state |
| repeat (all, active) | `"Repeat all"` | `"Repeat on"` | |
| repeat (one, active) | `"Repeat one"` | `"Repeat one"` | |
| queue | `"Show queue"` | `"Show queue"` | |
| add-to-playlist | `"Add to playlist"` | `"Add to playlist"` | |
| remove-from-playlist | `"Remove {track title}"` | `"Remove {track title}"` | Interpolate the track title so screen-reader users know which item they're removing |
| remove-from-queue | `"Remove from queue"` | `"Remove from queue"` | |
| delete-playlist | `"Delete playlist"` | `"Delete playlist"` | Appears in confirm dialog; the dialog title carries the meaning; the button label is "Delete" |
| drag-handle | `"Move {track title}, double-tap to enter reorder"` | system-provided | Android: explicit label as per YT-0014/compose-spec.md. iOS: `.onMove` renders system drag handle; do not label manually |
| move-up | `"Move up"` | `"Move up"` | Queue panel row control |
| move-down | `"Move down"` | `"Move down"` | Queue panel row control |
| collapse-player | `"Collapse player"` | `"Collapse player"` | NowPlaying top-bar dismiss button |
| more-options | `"More options"` | `"More options"` | Three-dot / ellipsis button |
| search (tab) | `"Search"` | system tab label | Tab bar label carries the meaning; icon is decorative |
| history (tab) | `"History"` | system tab label | Same as above |
| library (tab) | `"Library"` | system tab label | Same as above |
| settings (tab) | `"Settings"` | system tab label | Same as above |
| share | `"Share"` | `"Share"` | `ShareLink` on iOS provides this automatically |
| export | `"Export playlist"` | `"Export playlist"` | |
| import | `"Import playlist"` | `"Import playlist"` | |
| add-to-favourites (inactive) | `"Add to favourites"` | `"Add to favourites"` | Note: UK spelling per NowPlayingChrome.kt |
| add-to-favourites (active) | `"Remove from favourites"` | `"Remove from favourites"` | |
| close / dismiss (sheet) | `"Close"` | `"Close"` | |
| new-playlist (FAB) | n/a — `ExtendedFloatingActionButton` with visible label | n/a — Button with visible label | FAB has visible text "New playlist"; icon is decorative |
| error state icon | decorative (null) | decorative (`accessibilityHidden(true)`) | The error title carries the meaning; the icon is redundant |
| empty state icon | decorative (null) | decorative (`accessibilityHidden(true)`) | Same — title carries the meaning per state-catalog/README.md |
| playlist cover (interactive) | `"{playlist name}, cover from first {n} tracks"` or `"{playlist name}, no tracks yet"` | Composed via `.accessibilityElement(children: .combine)` on `PlaylistRow` | See YT-0014/compose-spec.md `PlaylistCover` implementation |
| playlist cover (decorative, in detail header) | null | `accessibilityHidden(true)` | Header context provides title; cover is decorative here |

---

## Toggle label convention

For controls that have an active/inactive toggle state (shuffle, repeat, favourites):

**Android:**
```kotlin
val shuffleDesc = if (shuffleOn) "Shuffle on" else "Shuffle off"
IconButton(
    modifier = Modifier.semantics { contentDescription = shuffleDesc },
    onClick = { vm.setShuffleMode(!shuffleOn) },
) {
    Icon(Icons.Rounded.Shuffle, contentDescription = null)
}
```

**iOS:**
```swift
Button {
    vm.setShuffleMode(!shuffleOn)
} label: {
    Image(systemName: "shuffle")
        .foregroundStyle(shuffleOn ? Color.accentColor : .secondary)
}
.accessibilityLabel(shuffleOn ? "Shuffle on" : "Shuffle")
```

The state is in the label, not appended as a separate value or hint. Prefer label over `accessibilityValue` for simple on/off states.

---

## When action name = label

For the vast majority of icon-only controls, the action name (what the button does) equals the accessibility label. The table above encodes this. The only exceptions are:

- **Tab bar icons:** the label matches the tab name, which the system manages in `TabView` / `NavigationBar`.
- **Drag handles on iOS:** the system `List` with `.onMove` renders the drag handle with a system-provided accessibility action ("Reorder"); do not override it.
- **Extended FAB:** the visible text is the label; the icon is null/decorative.
- **Empty / error state icons:** always decorative; the title heading is the accessible announcement.

---

## Seek bar (scrubber) — special case

The scrubber is not an icon, but it is an icon-adjacent control that requires explicit accessibility markup:

**Android:**
```kotlin
Slider(
    modifier = Modifier.semantics {
        contentDescription = "Seek bar, ${formatSeconds(currentSec)} of ${formatSeconds(durationSec)}"
    },
    ...
)
```

**iOS:**

Use `Slider` or the custom `DragGesture` scrubber with:
```swift
.accessibilityLabel("Seek bar")
.accessibilityValue("\(timeString(elapsed)) of \(timeString(duration))")
```

The label identifies the control; the value is the dynamic position. This maps cleanly to VoiceOver's expected "Seek bar, 1 minute 32 seconds of 3 minutes 45 seconds" announcement pattern.
