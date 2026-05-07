# YourTube Design System

## Overview

**YourTube** is a personal, no-backend YouTube audio player — a native mobile app available on both Android and iOS. It lets users search YouTube, play audio in the background, build playlists, and export/import those playlists as `.ytplaylist.json` files for cross-platform round-tripping. There is no server: stream URLs are resolved ephemerally at playback time using platform-specific extractors (NewPipeExtractor on Android, YouTubeKit on iOS).

**Status:** Early MVP scaffold — most feature modules are empty placeholders. The architecture, data contracts, and planning are well-defined; UI implementation is next.

---

## Sources

- **Codebase:** `your-tube/` (mounted local filesystem)
  - `your-tube/android/` — Kotlin, Jetpack Compose, Material 3, Media3
  - `your-tube/ios/` — Swift, SwiftUI, AVFoundation
  - `your-tube/docs/design-system.md` — canonical cross-platform design rules
  - `your-tube/docs/api-contracts.md` — shared data model contracts
  - `your-tube/docs/mvp-validation.md` — MVP acceptance checklist
  - `your-tube/obsidian-vault/` — local task planning (gitignored, 35 MVP tasks)
- **Figma:** None provided.

---

## Products

| Product | Platform | Stack |
|---|---|---|
| YourTube Android | Android 12+ | Kotlin, Jetpack Compose, Material 3 (Material You dynamic color), Media3 |
| YourTube iOS | iOS | Swift, SwiftUI, SF Symbols, AVFoundation |

Both share the same product design rules (see `your-tube/docs/design-system.md`) but implement them idiomatically per platform.

---

## App Architecture

**5 tabs:** Search · Library · History (from Library) · Player (MiniPlayer persistent) · Settings

**Key screens:**
- `SearchScreen` — search bar + results list (`TrackRow` components)
- `LibraryScreen` — playlists list (`PlaylistRow` components)
- `HistoryScreen` — recently played tracks
- `NowPlayingScreen` — full-screen player: artwork, scrubber, transport controls, queue
- `SettingsScreen` — theme toggle, export/import

**Persistent overlay:** `MiniPlayer` — always visible above the tab bar when a track is loaded. Thumbnail + title + play/pause. Tapping expands to `NowPlayingScreen`.

**Key data types:** `SearchResult`, `Track`, `PlaylistPayload` (see `your-tube/docs/api-contracts.md`)

---

## CONTENT FUNDAMENTALS

### Tone & Voice
- **Functional, minimal.** The UI copy is utility-first — it tells users what to do or what is happening, not marketing language.
- **No marketing fluff.** No taglines, hero copy, or "delight" messaging. This is a personal tool.
- **Second person, imperative.** "Search for something to start listening." "Add to playlist." "Retry." Not "We couldn't find anything" — just "Nothing found."
- **Lowercase sentence case** for most UI strings. Tab labels are title case ("Library", "Search") but body copy and action copy is sentence-case ("Add to queue", "Recently played").
- **No emoji** in UI copy. Emoji are absent from the design system.
- **Errors are direct.** "Couldn't load results. Retry." Not "Oops! Something went wrong."
- **Action labels are verbs.** "Import", "Export", "Retry", "Add", "Share".
- **Numbers are formatted** per platform convention (duration: `3:47`, not "3 minutes 47 seconds").
- **Accessibility labels are state-aware.** "Pause" not "Toggle playback". "Play, Bohemian Rhapsody by Queen" not "Play button".

### Examples
- Empty state: *"Search for something to start listening"*
- Error state: *"Couldn't load results. Retry."*
- Import conflict: *"A newer version of this playlist already exists."*
- Unsupported schema: *"This playlist was made with a newer version of YourTube. Please update the app."*
- Playlist renamed toast: *"Renamed to [name]"*

---

## VISUAL FOUNDATIONS

### Color Philosophy
**Dark-first.** The default theme is dark; light theme follows system preference. The accent is brand purple `#8B5CF6` (Violet 500 on Material scale), used for interactive elements, progress fills, and focus rings. Both platforms derive all surface colors from the system's dynamic color engine on supported OS versions — the purple is a fallback, not the only accent.

| Token | Dark value | Light value | Usage |
|---|---|---|---|
| `--color-accent` | `#8B5CF6` | `#7C3AED` | Primary interactive, CTA, progress |
| `--color-accent-dim` | `#6D28D9` | `#5B21B6` | Pressed accent state |
| `--color-bg` | `#0F0F0F` | `#FAFAFA` | Base page background |
| `--color-surface` | `#1C1C1E` | `#FFFFFF` | Cards, sheets, modals |
| `--color-surface-variant` | `#2C2C2E` | `#F2F2F7` | List rows hover/press |
| `--color-border` | `#3A3A3C` | `#D1D1D6` | Dividers, subtle borders |
| `--color-fg-primary` | `#FFFFFF` | `#000000` | Primary text |
| `--color-fg-secondary` | `#EBEBF5CC` | `#3C3C4399` | Secondary/metadata text |
| `--color-fg-tertiary` | `#EBEBF566` | `#3C3C4366` | Disabled/placeholder |
| `--color-error` | `#FF453A` | `#FF3B30` | Error state |
| `--color-success` | `#30D158` | `#34C759` | Download complete |

### Typography
- **Android:** Roboto (system default via Material 3)
- **iOS:** SF Pro (system default via SwiftUI)
- **Web mockups:** Use "Geist" (close to SF Pro feel) with Inter as fallback. Loaded from Google Fonts.

| Role | Size | Weight | Usage |
|---|---|---|---|
| `displayLarge` | 28–32sp | 600 | Now-playing track title |
| `titleMedium` | 16–18sp | 600 | Screen titles, playlist names |
| `bodyLarge` | 16sp | 400 | Track row title |
| `bodyMedium` | 14sp | 400 | Channel name, secondary metadata |
| `labelSmall` | 12sp | 400 | Duration badges, captions |

### Spacing
4dp/pt grid. All spacing is a multiple of 4.

| Token | Value |
|---|---|
| `xs` | 4dp |
| `sm` | 8dp |
| `md` | 16dp |
| `lg` | 24dp |
| `xl` | 32dp |

### Shape & Borders
- **Corner radius:** Cards and sheets use `12dp`. Track row thumbnails use `8dp`. The MiniPlayer sheet uses `16dp` on top corners. Buttons use fully-rounded (`999dp`).
- **Borders:** Subtle 1dp border at `--color-border` on surfaces in light mode; usually none in dark mode.
- **No accent-left-border cards.**

### Cards
- Surface color background (`#1C1C1E` dark / `#FFFFFF` light)
- `12dp` corner radius
- No drop shadow in dark mode. Light mode: very subtle `box-shadow: 0 1px 4px rgba(0,0,0,0.08)`.
- Inner dividers via `--color-border` (1px) rather than card stacking.

### Backgrounds & Imagery
- **Pure flat backgrounds.** No gradients on the background itself. Surfaces are flat.
- **Artwork protection:** When track artwork is used as a full-bleed background (now-playing), a dark gradient overlay (`rgba(0,0,0,0.6)`) protects text readability.
- **Thumbnail images:** YouTube `mqdefault` quality (`320×180`). Displayed in `16:9` ratio with a surface-colored placeholder. Corner radius `8dp`.
- **No hand-drawn illustrations.** No custom illustrations or SVG artwork.

### Animation & Motion
- **Standard easing**, 200–300ms for most transitions.
- **Shared element:** MiniPlayer artwork expands/morphs into NowPlayingScreen artwork.
- **Skeleton loading** for lists (preferred over spinner).
- **Reduce-motion respected** on both platforms.
- No bouncy/spring animations. No heavy particle effects.

### Hover / Press / Focus States
- **Hover (web mockups):** Surface variant tint (`--color-surface-variant`).
- **Press:** Darker surface (`opacity 0.7` or `--color-surface-variant`).
- **Focus:** Accent-colored outline ring, 2dp, offset 2dp.
- **Disabled:** `opacity 0.38` per Material 3.

### Iconography
- **Android:** Material Symbols (Rounded variant). Never mixed with emoji or custom SVG.
- **iOS:** SF Symbols. Weight matched to surrounding text.
- **Web mockups:** Lucide Icons (CDN). Same stroke-width aesthetic as Material Symbols Rounded.
- No emoji used as icons anywhere.

### Blur & Transparency
- Used sparingly. The MiniPlayer may use a `backdrop-filter: blur(20px)` tinted sheet in iOS style for the now-playing background.
- Tab bars on iOS use system translucency.

---

## ICONOGRAPHY

### Approach
YourTube uses platform-native icon systems — no custom icon font, no PNG icons, no SVG sprites.

- **Android:** [Material Symbols](https://fonts.google.com/icons) — Rounded variant. Loaded via `com.google.android.material` / `androidx.compose.material3`.
- **iOS:** SF Symbols — system-native, no import needed.
- **Web mockups (this design system):** [Lucide](https://lucide.dev) via CDN. Closest match to Material Symbols Rounded in stroke weight and visual style.

### Key icon mappings

| UI element | Material Symbol | SF Symbol | Lucide |
|---|---|---|---|
| Search tab | `search` | `magnifyingglass` | `Search` |
| Library tab | `music_note_list` | `music.note.list` | `Library` |
| History tab | `history` | `clock` | `History` |
| Player tab | `play_circle` | `play.circle` | `PlayCircle` |
| Settings | `settings` | `gear` | `Settings` |
| Play | `play_arrow` | `play.fill` | `Play` |
| Pause | `pause` | `pause.fill` | `Pause` |
| Skip next | `skip_next` | `forward.end.fill` | `SkipForward` |
| Skip prev | `skip_previous` | `backward.end.fill` | `SkipBack` |
| Queue | `queue_music` | `list.bullet` | `ListMusic` |
| Overflow menu | `more_vert` | `ellipsis` | `MoreVertical` |
| Download | `download` | `arrow.down.circle` | `Download` |
| Add to playlist | `playlist_add` | `plus.circle` | `ListPlus` |
| Share | `share` | `square.and.arrow.up` | `Share2` |
| Delete / Remove | `delete` | `trash` | `Trash2` |
| Error | `error` | `exclamationmark.circle` | `AlertCircle` |
| Success | `check_circle` | `checkmark.circle` | `CheckCircle2` |

No assets to copy — all icon systems are runtime-linked.

---

## Files in This Design System

```
README.md                    ← This file
SKILL.md                     ← Agent skill definition
colors_and_type.css          ← CSS variables: colors, spacing, radii, type, component tokens

preview/                     ← Design System tab cards (registered assets)
  colors-dark.html           ← Dark theme color swatches
  colors-light.html          ← Light theme color swatches
  colors-semantic.html       ← Semantic / state colors + chips
  type-scale.html            ← Type scale: displayLarge → labelSmall
  spacing-tokens.html        ← Spacing token bar chart (4dp grid)
  spacing-in-use.html        ← Skeleton loading + motion tokens
  shadows-radii.html         ← Corner radii + elevation shadows
  component-trackrow.html    ← TrackRow: default, playing, skeleton
  component-miniplayer.html  ← MiniPlayer: default + frosted glass
  component-nowplaying.html  ← NowPlaying: artwork, scrubber, controls
  component-searchbar.html   ← SearchBar states + PlaylistRow
  component-states.html      ← Buttons, EmptyState, ErrorState
  iconography.html           ← Lucide icon set (web mockup mapping)

assets/                      ← (no binary assets — icons are runtime CDN/native)

ui_kits/
  app/
    README.md                ← UI kit notes
    index.html               ← Interactive click-thru prototype (390×844, iPhone)
    TrackRow.jsx             ← Track list item component
    MiniPlayer.jsx           ← Persistent bottom player
    NowPlaying.jsx           ← Full-screen player overlay
    SearchScreen.jsx         ← Search tab with results + suggestions
    LibraryScreen.jsx        ← Library tab with playlists + import/export
    HistoryScreen.jsx        ← Recently played + Settings screens
```

## Quick-start for AI agents

1. Read this README for product/brand context
2. Read `colors_and_type.css` for all design tokens
3. Copy `ui_kits/app/*.jsx` components into your prototype
4. Load `preview/*.html` cards to visually verify components
5. Use Lucide (CDN) for web mockup icons; see ICONOGRAPHY section above for mappings

