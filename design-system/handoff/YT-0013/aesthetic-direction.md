# YT-0013 — Aesthetic Direction (Q12)

> **Binding tone.** When implementation has an open question and the spec is silent, this file decides. The default answer is always: **let the system do it, do less.**

---

## The one sentence

**Quiet, focused, Material-You-native — the Compose surface is a thin, faithful renderer of `MediaSession` state, the wallpaper-derived palette is the only color story, and every animation is information about playback rather than decoration.**

YourTube on Android isn't competing with YouTube Music's storefront energy. It is a personal listening tool. The "wow" is the system integration (lock-screen scrubber parity, Pixel-quality dynamic color, notification responsiveness on AVRCP), **not the chrome**. Brand purple `#8B5CF6` is the *fallback* — it appears only when the user has system tint disabled — so on Pixel devices in the wild, this app inherits the user's wallpaper and looks like it ships with the OS.

**Android-native inspiration:** **Google Podcasts** (chrome restraint, calm motion) layered with **Pixel Recorder** (Material You confidence, type as the focal point). Not YouTube Music (too storefront, too vibrant), not Wear OS Media (too compressed), not Pixel's own media notification chrome (too utilitarian for an in-app destination).

---

## What this means concretely

### Surfaces
- Solid `MaterialTheme.colorScheme.surface` background. **Bind to the role**, never hardcode `#1C1C1E`.
- No glass or `Surface(tonalElevation = ...)` capsules around transports.
- No decorative gradients anywhere on chrome.
- No drop shadows on dark surfaces — they don't read.
- Borders only where they separate content groups (queue row dividers if used; never decorative).

### Color
- Brand purple `#8B5CF6` is the *fallback only*. On Material You, the accent is the user's `colorScheme.primary` — and the screen reads as theirs.
- Active states use `colorScheme.primary`. Inactive states use `colorScheme.onSurfaceVariant`. There is no third tier.
- The artwork is the only multi-color element. Let it be.
- **Do not** introduce a second accent for any state (red for record, green for active, blue for casting). Use the same `primary` and let the icon/glyph carry the meaning.

### Motion
- Spring on artwork pause-scale and the shared-element hero (information about playback state and spatial continuity, respectively).
- `tween(180, FastOutSlowInEasing)` on scrubber affordances (utility — track grow, thumb grow, label grow).
- `Crossfade(tween(140))` on the play/pause icon swap.
- Everything else: instant or M3 default. **No bounce, no overshoot, no decorative entrance animations.**
- M3 motion *expressive* tokens are tempting; resist on this screen. Save expressive motion for surfaces where the user is browsing (search, library), not where they are listening.

### Type
- One typeface (Roboto via M3 typography roles). Roboto Flex variable axes if available; otherwise base Roboto.
- Two weights of Roboto in active use: 400 (body, channel, time labels) and 600 (titles, active states). Skip 500 except as the *transition* state on Material Symbols `wght` axis.
- **Tabular numerals** (`fontFeatureSettings = "tnum"`) on every time / duration / queue counter. Never on body copy — destroys reading rhythm.
- Title (`displayLarge`) gets at most 2 lines, ellipsis at the end. Don't shrink the artwork to fit a long title; let the title ellipsize.

### Iconography
- **Material Symbols Rounded** only. One stroke weight per resting context.
- Active state: `wght = 500` + `colorScheme.primary` tint.
- Inactive state: `wght = 400` + `colorScheme.onSurfaceVariant` tint.
- Play/pause filled circle: `wght = 600` glyph in `surface` (dark) on an `onSurface` (white) circle.
- No emoji. No custom glyphs. If a glyph doesn't exist in Material Symbols, the feature doesn't exist.

### Copy
- Lowercase sentence-case for every body and action string.
- "Up next" is two words, lowercase, used as a queue-section label.
- State-aware accessibility labels: "pause", not "toggle playback".
- No marketing chrome. No "now playing" hero text — the screen *is* now playing.

---

## Refuse list (literal)

When tempted, refuse:

- ❌ Palette-extracted background in v1 (the wallpaper already extracted; this would compete).
- ❌ Glass / tonal-elevation capsule around the transport row.
- ❌ Linear gradient anywhere on chrome.
- ❌ Vinyl-disc artwork mode.
- ❌ A second accent color for state (red, green, blue).
- ❌ A "premium" badge, "now playing" hero text, or any marketing chrome.
- ❌ Decorative motion (rotating disc, pulsing equalizer bars *on the main screen* — fine in the Live Activity / notification's small surface).
- ❌ A custom share sheet (use `Intent.ACTION_SEND` + system chooser).
- ❌ A custom Cast button (use `MediaRouteButton` via `AndroidView`).
- ❌ A custom volume slider with bespoke haptics (system owns volume).
- ❌ Hardcoded brand purple in any Composable. Bind to `colorScheme.primary`.
- ❌ Rolling your own `MediaStyle` notification on minSdk 31.

---

## Where the polish budget goes

If chrome is austere, the *system integration* must be flawless. Spend the polish budget on:

1. **`MediaSession` correctness.** Every metadata field set on every track change. Shuffle/repeat callbacks implemented. No direct `ExoPlayer` calls from the UI.
2. **Notification responsiveness.** `PlayerNotificationManager` from Media3, compact + expanded actions, tap target opens Now Playing with correct back-stack.
3. **Lock-screen artwork.** Highest-resolution thumbnail available; `artworkUri` so the system caches per-surface.
4. **Material You correctness.** Pixel running a green wallpaper → app reads as green. Don't fight it. Don't override `primary` per-screen.
5. **MiniPlayer ↔ Now Playing shared element.** The artwork *flies* between the two. No jump, no fade overlap, springs symmetric on enter and exit.
6. **AVRCP responsiveness.** Bluetooth headset play/pause updates in-app within 1 frame.
7. **Reduce-motion variants.** Cross-fades that match Android's own system motion when reduced. Not "no animation" — *intentional* reduced animation.

A user can't articulate why one Android music app feels native and another feels ported. The reason is almost always: the system surfaces (lock screen, notification, AVRCP, Wear, Auto, Cast) match the in-app surface frame-for-frame, and the in-app palette inherits from the wallpaper without override. That's where the budget goes.

---

## Tie-breaker rules

| Open question | Default answer |
|---|---|
| Add this nice flourish? | No. |
| Add a second accent for this state? | No, use opacity or weight. |
| Add a label to this icon? | No, the icon is enough. |
| Add a divider here? | Only if it separates two distinct content groups. |
| Add a shadow here? | Not on dark surfaces. |
| Animate this change? | Cross-fade or nothing. |
| Make this bigger to draw attention? | Yes — size > chrome. |
| Replace a custom control with a system one? | Always yes. |
| Override `colorScheme.primary` for this screen? | No. Material You owns the palette. |
| Hardcode a hex from the mockup? | No. Bind to the role. |

---

## Compared to YT-0027 (iOS)

| Aspect | iOS (YT-0027) | Android (YT-0013) |
|---|---|---|
| Presentation | `.fullScreenCover` + drag-to-dismiss | full-screen Compose destination + predictive-back |
| Hero transition | `matchedGeometryEffect` | `SharedTransitionLayout` + `sharedBounds` |
| Background | Solid `#1C1C1E` | Solid `colorScheme.surface` (Material You-derived) |
| Accent | `Color.accentColor` (brand purple, set as `.tint`) | `colorScheme.primary` (Material You; brand purple as fallback only) |
| Scrubber | Custom `DragGesture` on `GeometryReader` | Custom `Modifier.draggable` on `Canvas` |
| Transport center | 72 pt `play.circle.fill` SF Symbol | 72 dp `FilledIconButton` with Material Symbols |
| Queue | Inline preview + pushed `NavigationStack` | `ModalBottomSheet` with `SwipeToDismissBox` |
| Cast | `AVRoutePickerView` (UIKit interop) bottom-left of action row | `MediaRouteButton` (`AndroidView` interop) bottom-left of action row |
| Haptics | `.sensoryFeedback` (iOS 17+) | `LocalHapticFeedback` (API 30+) |
| Notification ownership | `MPNowPlayingInfoCenter` | Media3 `PlayerNotificationManager` |
| Reduce-motion source | `@Environment(\.accessibilityReduceMotion)` | `Settings.Global.TRANSITION_ANIMATION_SCALE` + `AccessibilityManager` |

The two apps share the design system's *tokens* and *information architecture*. They diverge on platform idiom — and they should. A SwiftUI app that imitates Android shared-element morphs feels uncanny; a Compose app that imitates SF Symbols and `matchedGeometryEffect` springs feels ported. Commit to the platform idiom; let the brand be the tokens, not the motion.
