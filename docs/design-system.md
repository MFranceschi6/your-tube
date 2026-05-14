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
- `design-system/handoff/YT-0012/` — Android Search (SearchBar shape, debounce, suggestion chips, EQ indicator, state-catalog C1–C5)
- `design-system/handoff/YT-0013/` — Android Now Playing
- `design-system/handoff/YT-0014/` — Android Library (Playlists-only MVP; post-MVP tabs in `v1x-tabs-addendum.md`)
- `design-system/handoff/YT-0015/` — Android Recently Played (day-grouped list; swipe-to-delete; "Clear All" dialog; cross-references state-catalog C12 / C13 / C14 and the YT-0014 v2 nav entry)
- `design-system/handoff/YT-0016/` — Android Playlist Sharing (export bottom-sheet, import file-picker flow, entry points, round-trip contract; locks in YT-0068 MIME-array narrowing)
- `design-system/handoff/YT-0025/` — iOS app shell (tab structure, MiniPlayer docking, `@Namespace`, NowPlaying presentation)
- `design-system/handoff/YT-0026/` — iOS Search (mirrors Android YT-0012; `.searchable`, suggestion chips, EQ indicator, Liquid Glass note)
- `design-system/handoff/YT-0027/` — iOS Now Playing
- `design-system/handoff/YT-0028/` — iOS Library
- `design-system/handoff/YT-0029/` — iOS Recently Played (Section-per-day List; `.swipeActions(role: .destructive)`; trailing toolbar Menu → `.alert`; mirrors Android YT-0015 substantively, idiomatic per platform)
- `design-system/handoff/YT-0030/` — iOS Playlist Sharing (export `.sheet` w/ `ShareLink`, `.fileImporter` flow, AirDrop receive via `onOpenURL`; locks in YT-0047 Files-app + AirDrop fixes)
- `design-system/handoff/light-theme/` — Light-theme audit + canonical dark/light token table (tokens.md, audit.md, state-catalog-light-overlay.md, component-light.html)
- `design-system/handoff/state-catalog/` — Cross-platform empty / loading / error state catalog
- `design-system/handoff/symbol-map/` — Cross-platform Material Symbols Rounded ↔ SF Symbols ↔ semantic-action map (linked from § Iconography below)
- `design-system/handoff/toast-catalog/` — Cross-platform toast / snackbar copy and behavior catalog
- `design-system/handoff/YT-0074/` — Cross-platform MiniPlayer ↔ NowPlaying motion spec (linked from § Motion below)
- `design-system/handoff/app-icon/` — App icon production (Android adaptive + iOS icon set + favicons; per YT-0180)

Index across all handoffs: `design-system/mockups/MOCKUP_INDEX.md`.

## Foundations

### Color
- **Dark-first.** Default theme is dark. Light theme is at parity with dark, audited per surface, and tracks the system setting by default. A three-option override (System / Light / Dark) lives in **Settings → Appearance → Theme**.
- **Accent**: per-platform system tint. Android uses Material 3 dynamic color (Material You) on Android 12+ with the brand purple as the fallback; the runtime probes the generated primary against the 4.5:1 floor and falls back to `#7C3AED` if Material You fails. iOS uses `Color.accentColor` driven from the asset catalog — ships two variants (Any Appearance `#8B5CF6`, Light `#7C3AED`) so the accent stays above the 4.5:1 floor on white.
- Surfaces follow a `background` / `surface` / `surface-variant` / `surface-container` / `surface-container-highest` hierarchy with semantic `error`, `success`, `warning`, and `on-*` content-on-surface pairs.
- **M3 tonal elevation in light is capped at Level 1.** Above Level 1 use `shadow.*` instead — saturated primary tint at M3 Levels 2–5 muddies surface separation in light.

Source of truth: `design-system/tokens/` — see `tokens.json` (machine-readable) and `tokens.md` (table). Do not duplicate token values here.

**Light-theme audit + canonical mapping:** `design-system/handoff/light-theme/`
- `tokens.md` — dark / light / M3 / SF ancestry per token, organized by category.
- `audit.md` — per-screen surface stack and contrast verification. Defects flagged with proposed fix.
- `state-catalog-light-overlay.md` — per-cell (C1–C16) confirm/override sheet.
- `component-light.html` — every production component rendered in light theme on both device frames.

### Typography
- Platform default font: Roboto (Android), SF Pro (iOS).
- Roles cover display, title, body, and label tiers used across now-playing, list rows, secondary metadata, and badges.
- Honor system font scaling: Android `fontScale`, iOS Dynamic Type. Never hard-code text size in px/pt.

Source of truth: `design-system/tokens/` — see `tokens.json` (machine-readable) and `tokens.md` (table). Do not duplicate token values here.

### Spacing
4 dp/pt grid with named scale steps used across both platforms.

Source of truth: `design-system/tokens/` — see `tokens.json` (machine-readable) and `tokens.md` (table). Do not duplicate token values here.

### Iconography
- Android: Material Symbols (rounded). iOS: SF Symbols. Match weight to surrounding text.

Canonical icon ↔ semantic-action mapping (cross-platform): `design-system/handoff/symbol-map/`.

Source of truth: `design-system/tokens/` — see `tokens.json` (machine-readable) and `tokens.md` (table). Do not duplicate token values here.

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
- Branding kit (logo variations, additional store assets beyond the app icon) — defer until v1.x. The app icon itself is in scope and lives at `design-system/handoff/app-icon/`.
