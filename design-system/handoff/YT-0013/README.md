# YT-0013 — Android Now Playing · Handoff Package

> **Audience:** Claude Code, implementing the Android Now Playing screen in Jetpack Compose + Material 3.
> **Source mockup:** `design-system/mockups/android/YT-0011-0012-0013-shell-search-player.html` — combined shell/search/player canvas. Open in browser; toggle the wallpaper-color picker to see Material You behavior.
> **Design system:** `docs/design-system.md` (product-level) and `design-system/colors_and_type.css` (web-prototype tokens).
> **Not a binding visual spec** — match the *behavior* and the M3 *tokens*. Use Compose idioms.

This handoff has **precedence** over the legacy combined HTML mockup. Where the mockup and the files in this folder disagree, the handoff wins. The combined HTML stays as a layout reference; it predates the Q1–Q12 decisions below and the post-implementation audit (`design-system/handoff/YT-0011/audit.md`).

## What's in this folder

| File | Purpose |
|---|---|
| `README.md` | This file. Read first. |
| `decision-log.md` | Q1–Q12 decisions with rationale, written by the design consultant. M3 tokens, Material You behavior, anti-patterns called out. |
| `compose-spec.md` | View hierarchy, key composables, surface/elevation tokens from `docs/design-system.md`, Compose snippets. CSS-mockup → Compose mapping. |
| `haptics-and-a11y.md` | `HapticFeedback` / `performHapticFeedback` table, system text scaling end-to-end, reduce-motion contract, TalkBack labels and content descriptions for state changes. |
| `media3-parity.md` | `MediaSession`, `MediaController`, lock-screen + notification mirror contract. Referenced from YT-0062a Q11. |
| `mockup.html` | Pointer to the legacy combined mockup (`design-system/mockups/android/YT-0011-0012-0013-shell-search-player.html`) with explicit precedence call-out. The HTML in the mockups folder is the layout reference; this folder is the behavioral source of truth. |

## Implementation order (do not skip)

1. NowPlaying as a Navigation destination + `SharedTransitionLayout` host (depends on YT-0061 AppShell refactor).
2. `NowPlayingArtwork` with `Animatable` pause-scale spring + corner-radius lerp. Native artwork morph from MiniPlayer is **60% of the win** — don't shortcut it.
3. Custom scrubber on `Modifier.draggable` + `Canvas`, with commit-on-release. Reject `Slider`.
4. Transport row with size-hierarchy `FilledIconButton` play-pause (72 dp, no chrome).
5. Action row + Cast (`MediaRouteButton` via `AndroidView`).
6. **`MediaSession` + lock-screen / notification parity.** Don't let this slip — the difference between a player and a toy on Android.
7. Queue as `ModalBottomSheet` with hand-rolled long-press reorder + `SwipeToDismissBox` removal.
8. v1.1 (flagged): wallpaper-derived background tint via `surfaceColorAtElevation` + harmonized accent.

Ship 1–6 before worrying about 7–8. A perfect Now Playing screen with a broken `MediaNotification` is a worse experience than a plain Now Playing screen with flawless system integration.

## Tokens — non-negotiable

- Accent: `MaterialTheme.colorScheme.primary` — Material You on Android 12+ via `dynamicDarkColorScheme(LocalContext.current)`; brand fallback `#8B5CF6` (`#D0BCFF` in dark) when the user has disabled wallpaper colors. Set once at `MaterialTheme(...)` root.
- Surface: `MaterialTheme.colorScheme.surface` (NowPlaying body), `surfaceContainer` (queue sheet), `primaryContainer` (MiniPlayer only).
- Spring (artwork pause-scale): `spring(stiffness = 380f, dampingRatio = 0.78f)` — see `compose-spec.md` for the `Animatable` wiring.
- Tap targets: 48 dp minimum, set via `Modifier.minimumInteractiveComponentSize()` or an explicit `Modifier.size(48.dp).clip(CircleShape)` ripple, decoupled from glyph size.
- Corner style: M3 `RoundedCornerShape` with **continuous-feeling** values per the design system (`shapes.medium = 12.dp`, `shapes.large = 20.dp`); no `CircleShape` on the artwork.

## Mockup web-isms — do NOT translate literally

| Mockup CSS | Compose / M3 equivalent |
|---|---|
| `backdrop-filter: blur(20px)` | M3 doesn't have a stable backdrop-blur; drop it. Use `surfaceContainerHighest` instead of "frosted". |
| `box-shadow` under artwork | Drop. On dark M3 surfaces, shadows don't read; M3 uses tonal elevation (`surfaceColorAtElevation`). |
| `border-radius: 12px` | `RoundedCornerShape(12.dp)` — equivalent to `MaterialTheme.shapes.medium`. |
| `rgba(255,255,255,0.6)` | `MaterialTheme.colorScheme.onSurfaceVariant` (do not hardcode alpha). |
| `pal.primaryContainer + '22'` row tint | `colorScheme.primaryContainer.copy(alpha = 0.16f)` *only* for the active TrackRow; everything else uses tokens at full opacity. |
| `cubic-bezier(...)` | `spring(stiffness, dampingRatio)` for physical motion; `tween(durationMillis, easing = FastOutSlowInEasing)` for non-physical. |
| `transition: width 1s linear` (MiniPlayer progress) | `LinearProgressIndicator` is the canonical M3 path for the seam progress; use `progress = { … }` lambda overload (Compose 1.6+). |

See `compose-spec.md` for the full mapping including FAB / bottom-sheet / `SwipeToDismissBox` web-isms.

## Aesthetic decisions binding

When implementation has an open question and the spec is silent: **do less, not more**. Default tie-breakers: native M3 control > custom; Material Symbols (rounded) > hand-drawn glyph; size hierarchy > chrome; tonal elevation > drop shadows; `LocalReduceMotion` honored everywhere.
