# YT-0074 — MiniPlayer ↔ NowPlaying motion (handoff)

Canonical motion specification for the shared-element transition between
`MiniPlayer` and `NowPlayingScreen`. Cross-platform (iOS + Android), single
source of truth, reduce-motion as a contract.

## Files

| File | What it is |
|---|---|
| `motion-spec.md` | The canonical spec. Tokens, choreography, per-platform mapping, edge cases, a11y. |
| `mockup.html` | Animated playback of the transition. Both phones in lockstep, scrubbable timeline, reduce-motion toggle, direction toggle, keyframe strip. |
| `colors_and_type.css` | Local copy of design-system tokens. |

## How to review

1. Open `mockup.html`. Press **▶ Play**.
2. Toggle direction (Expand / Collapse) and re-play.
3. Toggle **Reduce motion: On** — verify cross-fade only, 120 ms, no transform.
4. Open `motion-spec.md` and confirm every value rendered in the mockup matches §1.

## What this task does NOT include

- No Compose or SwiftUI code shipped here. Both platforms absorb the spec in
  follow-up tasks (`YT-0027` iOS, `YT-0013` / retrofit Android).
- No edits to `docs/design-system.md` source — that single-line link gets
  added when this lands.

## Discoverability hook (suggested addition to `docs/design-system.md` § Motion)

> Now-playing entry: shared-element transition from `MiniPlayer` artwork to
> `NowPlayingScreen` artwork on both platforms. **See
> [`design-system/handoff/YT-0074/motion-spec.md`](../handoff/YT-0074/motion-spec.md)
> for canonical durations, easings, reduce-motion fallback, and per-platform
> mapping.**
