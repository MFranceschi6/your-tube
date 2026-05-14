# Coverage Audit — Symbol Map vs. Codebase

> **Purpose:** tracks which semantic actions from `symbol-map.md` are confirmed used in the codebase, which are inconsistent, and which are not yet implemented.
>
> **Method:** manual grep + review of files listed under "Sources inspected" below. This is not an automated report; update it when icons are added, changed, or confirmed consistent.
>
> **Date of last audit:** 2026-05-09
>
> **Auditor:** docs-maintainer (YT-0179)

---

## Sources inspected

| File | Platform | What was checked |
|---|---|---|
| `android/core/ui/src/main/kotlin/com/yourtube/core/ui/NowPlayingChrome.kt` | Android | Transport row, queue panel, top-bar icons |
| `design-system/handoff/YT-0013/compose-spec.md` | Android | NowPlaying transport row, action row, queue sheet |
| `design-system/handoff/YT-0014/compose-spec.md` | Android | Library FAB, PlaylistRow, PlaylistCover, TrackRow edit mode |
| `design-system/handoff/YT-0027/swiftui-spec.md` | iOS | NowPlaying transport row, action row, dismiss button |
| `design-system/handoff/YT-0028/swiftui-spec.md` | iOS | Library, PlaylistDetail, PlaylistCover, toolbar menus |
| `design-system/handoff/state-catalog/empty.md` | Both | Empty-state icons per screen (C2, C3, C7, C10, C13) |
| `design-system/handoff/state-catalog/README.md` | Both | Error-state icons (C4/C8/C11/C14/C16), offline (C5) |
| `android/core/player/src/main/kotlin/com/yourtube/core/player/PlaybackSessionCommand.kt` | Android | Lock-screen skip-next / skip-previous MediaSession icons |

iOS source files (`.swift`) were not directly grepped in this audit pass. Coverage for iOS is inferred from the handoff spec documents.

---

## Status key

- **Confirmed** — glyph matches `symbol-map.md`; accessibility label matches `accessibility.md` or is noted.
- **Partial** — glyph is correct but label, size, or weight is inconsistent or missing.
- **Missing** — semantic action is in `symbol-map.md` but no implementation was found.
- **Inconsistent** — a glyph was found but it does not match the map, or two screens use different glyphs for the same action.
- **Reserved** — action is in the map but intentionally not yet implemented (post-MVP or stub).

---

## Confirmed — Android

| Semantic Action | Glyph confirmed | Accessibility label | Source |
|---|---|---|---|
| play | `Icons.Rounded.PlayArrow` | `"Play"` | NowPlayingChrome.kt (play/pause branch) |
| pause | `Icons.Rounded.Pause` | `"Pause"` | NowPlayingChrome.kt (play/pause branch) |
| play/pause buffering | `CircularProgressIndicator` + `"Loading"` label | `"Loading"` | NowPlayingChrome.kt; YT-0196 branch |
| skip-next (in-app) | `Icons.Rounded.SkipNext` | `"Next track"` | NowPlayingChrome.kt |
| skip-previous (in-app) | `Icons.Rounded.SkipPrevious` | `"Previous track"` | NowPlayingChrome.kt |
| skip-next (lock-screen) | `media3_icon_next` (Media3 bundled drawable) | `"Skip to next"` | PlaybackSessionCommand.kt |
| skip-previous (lock-screen) | `media3_icon_previous` (Media3 bundled drawable) | `"Skip to previous"` | PlaybackSessionCommand.kt |
| shuffle | `Icons.Rounded.Shuffle` | `"Shuffle"` (static; no active-state label variant confirmed) | NowPlayingChrome.kt, YT-0013/compose-spec.md |
| repeat | `Icons.Rounded.Repeat` | `"Repeat"` (static; no active-state label variant confirmed) | YT-0013/compose-spec.md |
| queue (show queue) | `Icons.Rounded.QueueMusic` | `"Show queue"` | NowPlayingChrome.kt |
| collapse-player | `Icons.Rounded.KeyboardArrowDown` | `"Collapse player"` | NowPlayingChrome.kt |
| more-options | `Icons.Rounded.MoreVert` | `"More options"` | NowPlayingChrome.kt |
| move-up (queue row) | `Icons.Rounded.KeyboardArrowUp` | `"Move up"` | NowPlayingChrome.kt |
| move-down (queue row) | `Icons.Rounded.KeyboardArrowDown` | `"Move down"` | NowPlayingChrome.kt |
| remove-from-queue | `Icons.Rounded.Close` | `"Remove from queue"` | NowPlayingChrome.kt |
| add-to-favourites (inactive) | `Icons.Rounded.FavoriteBorder` | `"Add to favourites"` | NowPlayingChrome.kt |
| new-playlist (FAB) | `Icons.Rounded.Add` | n/a — FAB has visible text | YT-0014/compose-spec.md |
| playlist-cover-empty (0 tracks) | `Icons.Rounded.QueueMusic` | Composite label on cover Box | YT-0014/compose-spec.md |
| drag-handle | `Icons.Rounded.DragHandle` | `"Move {track title}, double-tap to enter reorder"` | YT-0014/compose-spec.md |
| remove-from-playlist | `Icons.Rounded.RemoveCircle` (error tint) | `"Remove {track.title}"` | YT-0014/compose-spec.md |
| share | `Icons.Rounded.Share` | `"Share"` | YT-0013/compose-spec.md (action row) |
| add-to-playlist | `Icons.Rounded.PlaylistAdd` | `"Add to playlist"` | YT-0013/compose-spec.md (action row) |
| overflow-more (playlist row) | `Icons.Rounded.MoreVert` | `"More"` (short label — see Partial note) | YT-0014/compose-spec.md |
| library-empty-state-icon | `Icons.Rounded.LibraryMusic` | decorative (null) | state-catalog/empty.md C7 |
| playlist-empty-state-icon | `Icons.Rounded.MusicNote` | decorative (null) | state-catalog/empty.md C10 |
| history-empty-state-icon | `Icons.Rounded.History` | decorative (null) | state-catalog/empty.md C13 |
| search-empty-state-icon | `Icons.Rounded.Search` | decorative (null) | state-catalog/empty.md C2 |
| search-no-results-icon | `Icons.Rounded.SearchOff` | decorative (null) | state-catalog/empty.md C3 |
| error-state-icon | `Icons.Rounded.Error` (outline preferred) | decorative (null) | state-catalog/README.md |
| offline-icon | `Icons.Rounded.WifiOff` | decorative (null) | state-catalog/README.md C5 |

---

## Confirmed — iOS (from handoff specs)

| Semantic Action | Glyph confirmed | Accessibility label | Source |
|---|---|---|---|
| play | `play.fill` | `"Play"` (inferred from `isPlaying` branch) | YT-0027/swiftui-spec.md |
| pause | `pause.fill` | `"Pause"` (inferred) | YT-0027/swiftui-spec.md |
| skip-next | `forward.fill` | not confirmed in spec | YT-0027/swiftui-spec.md |
| skip-previous | `backward.fill` | not confirmed in spec | YT-0027/swiftui-spec.md |
| shuffle | `shuffle` | not confirmed | YT-0027/swiftui-spec.md |
| repeat | `repeat` | not confirmed | YT-0027/swiftui-spec.md |
| queue (show queue) | `list.bullet` | not confirmed in spec | YT-0027/swiftui-spec.md |
| share | `square.and.arrow.up` | system `ShareLink` | YT-0027/swiftui-spec.md, YT-0028/swiftui-spec.md |
| add-to-playlist | `text.badge.plus` | not confirmed in spec | YT-0027/swiftui-spec.md |
| collapse-player | `chevron.down` | not confirmed in spec | YT-0027/swiftui-spec.md |
| more-options | `ellipsis.circle` | not confirmed in spec | YT-0028/swiftui-spec.md |
| delete-playlist | `trash` | `"Delete Playlist"` (via Label in Menu) | YT-0028/swiftui-spec.md |
| rename | `pencil` | `"Rename"` (via Label in Menu) | YT-0028/swiftui-spec.md |
| add-tracks | `plus` | `"Add Tracks"` (via Label in Menu) | YT-0028/swiftui-spec.md |
| remove-from-playlist (swipe) | `trash` | `"Remove"` (via Label in swipeActions) | YT-0028/swiftui-spec.md |
| playlist-cover-empty (0 tracks) | `music.note` (GradientCover glyph) | decorative | YT-0028/swiftui-spec.md |
| library-empty-state-icon | `music.note.list` | decorative | YT-0028/swiftui-spec.md, state-catalog/empty.md C7 |
| playlist-empty-state-icon | `music.note` | decorative | state-catalog/empty.md C10 |
| history-empty-state-icon | `clock.arrow.circlepath` | decorative | state-catalog/empty.md C13 |
| search-empty-state-icon | `magnifyingglass` | decorative | state-catalog/empty.md C2/C3 |
| error-state-icon | `exclamationmark.triangle` | decorative | state-catalog/README.md |
| offline-icon | `wifi.slash` | decorative | state-catalog/README.md C5 |
| new-playlist (iOS add) | `plus.circle.fill` | visible label | YT-0028/swiftui-spec.md AddToPlaylistSheet |
| checkmark (selection) | `checkmark` | n/a — decorative within combined row label | YT-0028/swiftui-spec.md |
| play (playlist action row) | `play.fill` (Label systemImage) | `"Play"` (Label text) | YT-0028/swiftui-spec.md |
| shuffle (playlist action row) | `shuffle` (Label systemImage) | `"Shuffle"` (Label text) | YT-0028/swiftui-spec.md |

---

## Partial — label or state inconsistency

| Semantic Action | Platform | Issue | Location |
|---|---|---|---|
| shuffle (active state) | Android | `NowPlayingChrome.kt` has no active-state label; `contentDescription = "Shuffle"` regardless of `isOn`. Should be `"Shuffle on"` when active. | NowPlayingChrome.kt; YT-0013/compose-spec.md `TransportIcon` wrapper |
| repeat (active state) | Android | Same issue — static `"Repeat"` label regardless of active mode. Should be `"Repeat on"` or `"Repeat one"`. | YT-0013/compose-spec.md `TransportIcon` wrapper |
| shuffle (active state) | iOS | Spec does not confirm whether `.accessibilityLabel` changes with state. | YT-0027/swiftui-spec.md |
| repeat (active state) | iOS | Same — spec silent on state-dependent label. | YT-0027/swiftui-spec.md |
| overflow-more (PlaylistRow) | Android | `contentDescription = "More"` is shorter than the preferred `"More options"`. Minor — both are acceptable, but normalize to `"More options"` for consistency. | YT-0014/compose-spec.md |
| skip-next / skip-previous | iOS | Spec code shows `transportButton(symbol: "forward.fill", ...)` without confirming `.accessibilityLabel`. SF Symbols default localization would say "Forward" or "Next Item" — must be overridden to `"Next track"` / `"Previous track"`. | YT-0027/swiftui-spec.md |
| collapse-player | iOS | Spec has `DismissButton (chevron.down, 22pt glyph, 44pt hit)` but does not state `.accessibilityLabel`. Must be `"Collapse player"`. | YT-0027/swiftui-spec.md |
| add-to-playlist (iOS action row) | iOS | `Image(systemName: "text.badge.plus")` with no explicit `.accessibilityLabel` confirmed. SF Symbols default localization may say "Text Badge Plus" — override required. | YT-0027/swiftui-spec.md |

---

## Missing — not yet implemented

| Semantic Action | Platform | Notes |
|---|---|---|
| repeat-one | Android | `Icons.Rounded.RepeatOne` is in the map but the active toggle branch in YT-0013 only shows `Icons.Rounded.Repeat` tinted; RepeatOne variant not confirmed as implemented |
| repeat-one | iOS | `repeat.1` is in the map; not confirmed in spec |
| stop | Both | Not in any current transport surface; reserved |
| seek-forward (10s) | Both | Reserved post-MVP |
| seek-back (10s) | Both | Reserved post-MVP |
| download | Both | Reserved post-MVP |
| download-done | Both | Reserved post-MVP |
| export (playlist) | Both | Reserved; appears in iOS overflow menu as "Share" placeholder for now |
| import (playlist) | Both | Not yet in any surface |
| sort | Both | Reserved post-MVP |
| filter | Both | Reserved post-MVP |
| clear-history | Both | Reserved; toolbar button referenced in state-catalog but not implemented |
| now-playing-indicator (animated) | Both | Custom animated view needed; not a static symbol |

---

## Inconsistencies / mismatches vs. symbol-map.md

| Semantic Action | Platform | Found | Map says | Action |
|---|---|---|---|---|
| close / dismiss (NowPlaying top) | Android | `Icons.Rounded.KeyboardArrowDown` | `symbol-map.md` correctly maps this as `collapse-player`; the `close` entry maps to `Icons.Rounded.Close`. No mismatch — two different actions. | No fix needed |
| queue (NowPlaying action row vs. transport row) | Android | YT-0013 action row uses `Icons.Rounded.QueueMusic` for the queue button; NowPlayingChrome.kt transport row also uses `Icons.Rounded.QueueMusic` for the same action. | Consistent | No fix needed |
| playlist-cover-empty glyph | Android vs iOS | Android: `Icons.Rounded.QueueMusic`; iOS: `music.note` (GradientCover glyph). | `symbol-map.md` lists both; these are intentional platform-native variants, not an error. | Documented; no fix needed |

---

## Follow-up actions (not in this task's scope)

1. Fix the active-state accessibility labels for shuffle and repeat on Android (tracked in the Partial section above). Spawn a new fix task referencing this audit when the done tasks are confirmed.
2. Confirm iOS `transportButton` wrapper has `.accessibilityLabel` overrides for skip-next, skip-previous, collapse, and add-to-playlist once iOS implementation tasks start.
3. Implement repeat-one toggle (both platforms) — update this audit when done.
4. Re-audit against actual Swift source files once iOS feature tasks are implemented (current audit is spec-only for iOS).
