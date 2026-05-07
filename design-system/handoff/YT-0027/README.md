# YT-0027 — iOS Now Playing · Handoff Package

> **Audience:** Claude Code, implementing the iOS Now Playing screen in SwiftUI.
> **Source mockup:** `handoff/YT-0027/mockup.html` (open in browser, toggle the panels on the right to see each decision in context).
> **Design system:** `colors_and_type.css` and `README.md` at repo root.
> **Not a binding visual spec** — match the *behavior* and *tokens*. Use SwiftUI idioms.

## What's in this folder

| File | Purpose |
|---|---|
| `mockup.html` | Interactive mockup with toggles for each decision (background, queue, reduce-motion, etc). Includes haptic markers as visual toasts so you can see when each `.sensoryFeedback` should fire. |
| `decision-log.md` | The 10 decisions with rationale, written by the design consultant. Read first. |
| `swiftui-spec.md` | View hierarchy, key SwiftUI APIs, animation curves, hit-target rules, file layout. |
| `haptics-and-a11y.md` | `.sensoryFeedback` table, Dynamic Type behavior, reduce-motion contract, VoiceOver labels. |
| `system-parity.md` | `MPNowPlayingInfoCenter`, `MPRemoteCommandCenter`, Live Activity / Dynamic Island contract. |
| `aesthetic-direction.md` | **Q11 — binding tone.** Read when an open question isn't covered elsewhere. Default answer: do less. |
| `mockup-states.html` | Static states the interactive mockup doesn't show frame-by-frame: scrubber-dragging (Q4) and Up Next pushed destination (Q6). |

## Implementation order (do not skip)

1. `.fullScreenCover` shell + drag-to-dismiss + `matchedGeometryEffect` from MiniPlayer.
2. Artwork view + title/channel + scrubber. Native scrubber feel is **60% of the win** — don't shortcut it.
3. Transport row with size-hierarchy play-pause.
4. Action row + AirPlay (`AVRoutePickerView`).
5. **`MPNowPlayingInfoCenter` + remote commands.** Don't let this slip — the difference between a player and a toy.
6. Up Next inline preview + pushed `QueueView` destination.
7. Live Activity / Dynamic Island.
8. v1.1 (flagged): extracted-color background.

Ship 1–5 before worrying about 7–8. A perfect Now Playing screen with a broken lock screen is a worse experience than a plain Now Playing screen with flawless system integration.

## Tokens — non-negotiable

- Accent: `Color(red: 0.545, green: 0.361, blue: 0.965)` (#8B5CF6) — set as `.tint` at the screen root, not hardcoded downstream.
- Surface: `Color(uiColor: .systemBackground)` falls back to `#1C1C1E` in dark.
- Spring (artwork pause-scale): `.spring(response: 0.45, dampingFraction: 0.78)`.
- Tap targets: 44pt minimum, set via `.contentShape(Rectangle())`, decoupled from glyph size.
- Corner style: **always** `.continuous`. `.circular` (the default) is wrong here.

## Mockup web-isms — do NOT translate literally

| Mockup CSS | Native equivalent |
|---|---|
| `backdrop-filter: blur(20px)` | `.background(.ultraThinMaterial)` — by name, not by radius |
| `box-shadow` under artwork | Drop it. On dark BG, shadows don't read. |
| `border-radius: 12px` | `RoundedRectangle(cornerRadius: 12, style: .continuous)` |
| `rgba(255,255,255,0.6)` | `.foregroundStyle(.secondary)` |
| `rgba(139,92,246,0.2)` for accent tints | `Color.accentColor.opacity(0.2)` after setting `.tint(...)` |
| `cubic-bezier(...)` | `.spring(response:dampingFraction:)` — iOS motion is spring-first |

See `swiftui-spec.md` for the full mapping.
