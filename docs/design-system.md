# Design system

Shared product-level design rules. Each platform implements these idiomatically — Material 3 on Android, native SwiftUI on iOS. Do not force one platform's idioms onto the other.

## Implementation references

- `.agents/skills/yourtube-design/SKILL.md` defines the project-facing `yourtube-design` skill; `design-system/SKILL.md` remains the design-system source from the generated folder.
- `design-system/mockups/MOCKUP_INDEX.md` maps UI task IDs to their primary HTML mockups and lists the handoff packages.
- `design-system/colors_and_type.css` and `design-system/preview/` are token and component references for prototyping.
- `design-system/ui_kits/app/` contains reusable web prototype components; production code should translate the intent into Compose or SwiftUI idioms.

## Handoff packages

Per-surface implementation specs live under `design-system/handoff/<TICKET>/`. Each folder is the **source of truth** for its surface — it overrides the corresponding HTML mockup wherever the two disagree. Read `README.md` first, then `decision-log.md`, then `compose-spec.md` (Android) or `swiftui-spec.md` (iOS).

- `design-system/handoff/YT-0011/` — Android app shell (audit + decisions)
- `design-system/handoff/YT-0013/` — Android Now Playing
- `design-system/handoff/YT-0014/` — Android Library (Playlists-only MVP; post-MVP tabs in `v1x-tabs-addendum.md`)
- `design-system/handoff/YT-0027/` — iOS Now Playing
- `design-system/handoff/YT-0028/` — iOS Library
- `design-system/handoff/state-catalog/` — Cross-platform empty / loading / error state catalog
- `design-system/handoff/YT-0074/` — Cross-platform MiniPlayer ↔ NowPlaying motion spec (linked from § Motion below)

Index across all handoffs: `design-system/mockups/MOCKUP_INDEX.md`.

## Foundations

### Color
- **Dark-first.** Default theme is dark. Light theme supported, follows system.
- **Accent**: per-platform system tint.
  - Android: Material 3 dynamic color (Material You) on Android 12+; fallback brand purple `#8B5CF6`.
  - iOS: `Color.accentColor` driven from asset catalog; default brand purple `#8B5CF6`.
- Surfaces:
  - `background` — base.
  - `surface` — cards, sheets.
  - `surfaceVariant` — list rows on hover/press.
- Semantic: `error` (system red), `success` (system green), `onX` for content-on-surface pairs.

### Typography
- Platform default font: Roboto (Android), SF Pro (iOS).
- Roles:
  - `displayLarge` — now-playing track title.
  - `titleMedium` — screen titles, playlist names.
  - `bodyLarge` — track row title.
  - `bodyMedium` — channel name, secondary metadata.
  - `labelSmall` — duration, badges.
- Honor system font scaling: Android `fontScale`, iOS Dynamic Type. Never hard-code text size in px/pt.

### Spacing
4 dp/pt grid. Tokens: `xs=4`, `sm=8`, `md=16`, `lg=24`, `xl=32`.

### Iconography
- Android: Material Symbols (rounded).
- iOS: SF Symbols.
- Match weight to surrounding text.

## Components

Names are conceptual; each platform implements its own type.

| Component         | Purpose                                          |
| ----------------- | ------------------------------------------------ |
| `TrackRow`        | One track in a list. Thumbnail, title, channel, duration, overflow menu. |
| `MiniPlayer`      | Persistent bottom-docked player above tab bar. Thumbnail, title, play/pause. Tap → `NowPlayingScreen`. |
| `NowPlayingScreen`| Full-screen player. Artwork, scrubber, transport controls, queue access. |
| `SearchBar`       | Top-of-screen text input with debounce, clear button, submit. |
| `PlaylistRow`     | Playlist in library list. Cover (4-up thumbnail), name, track count. |
| `EmptyState`      | Icon + title + body + optional action button.    |
| `ErrorState`      | Same shape as `EmptyState` with retry action.    |

## Interaction

- **Tap targets**: minimum 48 dp (Android) / 44 pt (iOS).
- **Long-press**: opens contextual menu (download, add to playlist, share). Both platforms.
- **Swipe**: swipe-to-delete on history and queue rows.
- **Haptics**: light tap on play/pause, success on download complete.

## Motion

- Standard easing, 200–300 ms for most transitions.
- Now-playing entry: shared-element transition from `MiniPlayer` artwork to `NowPlayingScreen` artwork on both platforms. See `design-system/handoff/YT-0074/motion-spec.md` for canonical durations, easings, reduce-motion fallback, and per-platform mapping.
- Reduce-motion respected (Android `Settings.Global.TRANSITION_ANIMATION_SCALE`, iOS `UIAccessibility.isReduceMotionEnabled`).

## Accessibility

- Every interactive element has a content description (Android) / accessibility label (iOS).
- Player controls expose state in label: "Pause" not "Toggle".
- Color is never the only signal — pair with icon or text.
- Min contrast 4.5:1 for body, 3:1 for large text.
- Honor system text scaling end-to-end; layouts must not clip at largest scale.
- VoiceOver / TalkBack: now-playing screen reads track title, channel, position, total.

## Empty / loading / error

Three required states for every list-driven screen:
- **Loading** — skeleton rows (preferred) or spinner.
- **Empty** — `EmptyState` with actionable copy ("Search for something to start listening").
- **Error** — `ErrorState` with retry.

Canonical per-screen catalog with copy, icons, and retry contracts: `design-system/handoff/state-catalog/`.

## Out of scope (for now)

- Custom fonts, custom illustrations, marketing splash screens.
- Branding kit (logo variations, store assets) — defer until v1.x.
