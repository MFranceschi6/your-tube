# Symbol Map — Semantic Action Lookup Table

> **Source of truth.** This table overrides all inline icon references in per-surface handoff packages. See `README.md` for precedence rules.
>
> **Glyph parity is not pixel parity.** Android and iOS glyphs for the same action will not look identical. They communicate the same *meaning* using each platform's native vocabulary. That is correct.
>
> **Material Symbols style:** Rounded. Do not use Sharp, Outlined, or Two-tone variants unless a specific surface explicitly requires them (none currently do).
>
> **SF Symbols version:** SF Symbols 5+. All names listed are available in iOS 17+ and iOS 26. Where a symbol name changed between versions, the 5+ name is listed.

---

## Transport controls

| Semantic Action | Android (Material Symbols Rounded) | iOS (SF Symbols) | Notes |
|---|---|---|---|
| play | `Icons.Rounded.PlayArrow` | `play.fill` | Filled on iOS to match weight of `backward.fill` / `forward.fill` |
| pause | `Icons.Rounded.Pause` | `pause.fill` | Filled on iOS for weight parity |
| stop | `Icons.Rounded.Stop` | `stop.fill` | Not currently used in transport row; reserved |
| skip-next | `Icons.Rounded.SkipNext` | `forward.fill` | Android: uses lock-screen `media3_icon_next` drawable for MediaSession; iOS: `forward.fill` is the system convention |
| skip-previous | `Icons.Rounded.SkipPrevious` | `backward.fill` | Android: uses lock-screen `media3_icon_previous` drawable for MediaSession |
| shuffle | `Icons.Rounded.Shuffle` | `shuffle` | Not filled by default; active state: tinted to primary/accent (see `weight-and-size.md`) |
| repeat | `Icons.Rounded.Repeat` | `repeat` | Active = repeat-all; see repeat-one for single-track |
| repeat-one | `Icons.Rounded.RepeatOne` | `repeat.1` | Active state only; inactive defaults to `repeat` |
| seek-forward (future) | `Icons.Rounded.Forward10` | `goforward.10` | Not currently in the transport row; reserved for future seek-10s controls |
| seek-back (future) | `Icons.Rounded.Replay10` | `gobackward.10` | Not currently in the transport row; reserved |

---

## Queue and playback state

| Semantic Action | Android (Material Symbols Rounded) | iOS (SF Symbols) | Notes |
|---|---|---|---|
| queue | `Icons.Rounded.QueueMusic` | `list.bullet` | "Show queue." Android: bottom-sheet button in NowPlaying transport row. iOS: action-row button. |
| now-playing-indicator | *(animated equalizer bars — no single icon)* | *(animated equalizer bars — no single icon)* | Not a static symbol; use a custom animated view. Document the visual separately if needed. |
| drag-handle (reorder) | `Icons.Rounded.DragHandle` | System-rendered `line.3.horizontal` (or native `.onMove` drag grip) | iOS: in edit mode `List.onMove` renders the drag handle automatically; do not draw it manually |
| move-up (queue row) | `Icons.Rounded.KeyboardArrowUp` | `chevron.up` | Used in queue-panel row controls (NowPlayingChrome queue sheet) |
| move-down (queue row) | `Icons.Rounded.KeyboardArrowDown` | `chevron.down` | Used in queue-panel row controls |
| remove-from-queue | `Icons.Rounded.Close` | `xmark` | In-queue removal; destructive but inline (not full delete) |

---

## Library and playlist management

| Semantic Action | Android (Material Symbols Rounded) | iOS (SF Symbols) | Notes |
|---|---|---|---|
| library | `Icons.Rounded.LibraryMusic` | `music.note.list` | Empty-state icon for Library screen (C7); also tab bar |
| playlist | `Icons.Rounded.QueueMusic` | `music.note.list` | 0-track playlist cover fallback glyph; same as library on iOS |
| add-to-playlist | `Icons.Rounded.PlaylistAdd` | `text.badge.plus` | NowPlaying action row and context menu |
| new-playlist | `Icons.Rounded.Add` | `plus.circle.fill` | FAB icon (Android Extended FAB); iOS AddToPlaylistSheet "New Playlist" row |
| remove-from-playlist | `Icons.Rounded.RemoveCircle` | `trash` (swipe action) or `minus.circle` | Android: inline remove button in edit mode (error-tinted). iOS: swipe-to-delete renders `trash` automatically; `.swipeActions` label uses `trash` |
| delete-playlist | `Icons.Rounded.Delete` | `trash` | Whole-playlist destructive delete; confirm dialog on both platforms |
| rename (future) | `Icons.Rounded.Edit` | `pencil` | Not yet in the transport surface; used in iOS PlaylistDetail overflow menu |
| sort (future) | `Icons.Rounded.Sort` | `arrow.up.arrow.down` | Not yet implemented; reserved |
| filter (future) | `Icons.Rounded.FilterList` | `line.3.horizontal.decrease.circle` | Not yet implemented; reserved |
| check / done | `Icons.Rounded.Check` | `checkmark` | AddToPlaylist selection indicator (iOS); Android uses selection highlight |

---

## Search and discovery

| Semantic Action | Android (Material Symbols Rounded) | iOS (SF Symbols) | Notes |
|---|---|---|---|
| search | `Icons.Rounded.Search` | `magnifyingglass` | Search tab bar icon; search-bar leading icon; C2 empty-state icon |
| search-no-results | `Icons.Rounded.SearchOff` | `magnifyingglass` | C3: iOS has no clean "search off" symbol; same glyph as search, different copy carries the meaning |
| history | `Icons.Rounded.History` | `clock.arrow.circlepath` | History tab bar icon; C13 empty-state icon |
| clear-history (future) | `Icons.Rounded.DeleteSweep` | `trash.slash` | Not yet implemented in toolbar; reserved |

---

## Navigation and shell

| Semantic Action | Android (Material Symbols Rounded) | iOS (SF Symbols) | Notes |
|---|---|---|---|
| back | `Icons.Rounded.ArrowBack` | System back (SwiftUI `NavigationStack` chevron) | iOS: do not draw a custom back button unless overriding the system back item explicitly |
| close / dismiss | `Icons.Rounded.Close` | `xmark` | Sheet dismiss and NowPlaying collapse button on Android uses `KeyboardArrowDown`; see collapse below |
| collapse-player | `Icons.Rounded.KeyboardArrowDown` | `chevron.down` | NowPlaying top-bar collapse; Android uses `KeyboardArrowDown`; iOS uses `chevron.down` |
| more-options | `Icons.Rounded.MoreVert` | `ellipsis.circle` | Three-dot overflow menu on Android; iOS uses ellipsis-in-circle for toolbar placement |
| settings | `Icons.Rounded.Settings` | `gearshape` or `gearshape.fill` | Settings tab bar icon; gearshape.fill for active state on iOS |

---

## Feedback, status, and utility

| Semantic Action | Android (Material Symbols Rounded) | iOS (SF Symbols) | Notes |
|---|---|---|---|
| error | `Icons.Rounded.Error` (outline variant preferred) | `exclamationmark.triangle` | State-catalog error states C4/C8/C11/C14/C16; tint at `--color-fg-tertiary`, NOT red |
| warning | `Icons.Rounded.Warning` | `exclamationmark.triangle.fill` | Inline warning; differentiate from error by context and copy, not glyph alone |
| info | `Icons.Rounded.Info` | `info.circle` | Informational tooltip or inline explainer |
| offline | `Icons.Rounded.WifiOff` | `wifi.slash` | C5 search offline state |
| download (future) | `Icons.Rounded.Download` | `arrow.down.circle` | Not yet implemented; reserved |
| download-done (future) | `Icons.Rounded.DownloadDone` | `checkmark.circle.fill` | Not yet implemented; reserved |
| share | `Icons.Rounded.Share` | `square.and.arrow.up` | NowPlaying action row and context menus; iOS uses system `ShareLink` which renders this symbol automatically |
| export (future) | `Icons.Rounded.Upload` | `square.and.arrow.up.on.square` | Playlist export; distinct from share (file export vs. share sheet) |
| import (future) | `Icons.Rounded.Download` | `square.and.arrow.down` | Playlist import; consistent with download metaphor |
| add-to-favourites (stub) | `Icons.Rounded.FavoriteBorder` (inactive) / `Icons.Rounded.Favorite` (active) | `heart` (inactive) / `heart.fill` (active) | NowPlayingChrome heart button; stub action (no-op in current build) |

---

## Playlist cover fallbacks

| Semantic Action | Android (Material Symbols Rounded) | iOS (SF Symbols) | Notes |
|---|---|---|---|
| playlist-cover-empty (0 tracks) | `Icons.Rounded.QueueMusic` | `music.note` (via GradientCover glyph) | Centered in the fallback cover tile at 32dp/22pt; tint at `onSurfaceVariant` / `.white.opacity(0.28)` |
| library-empty-state-icon | `Icons.Rounded.LibraryMusic` | `music.note.list` | C7; 56dp/56pt |
| playlist-empty-state-icon | `Icons.Rounded.MusicNote` | `music.note` | C10; 56dp/56pt |
| history-empty-state-icon | `Icons.Rounded.History` | `clock.arrow.circlepath` | C13; 56dp/56pt |
| search-empty-state-icon | `Icons.Rounded.Search` | `magnifyingglass` | C2/C3; 56dp/56pt |
