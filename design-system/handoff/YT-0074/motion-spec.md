# MiniPlayer ↔ NowPlaying — canonical motion spec

> **Task:** YT-0074 · **Owner:** Design System · **Status:** Spec (no platform code shipped here)
> **Source of truth for:** `iOS/YT-0027`, `Android/YT-0013`, follow-ups absorbing this spec.
> **Linked from:** `docs/design-system.md` § Motion.

The shared-element transition between `MiniPlayer` and `NowPlayingScreen` was previously named
in `docs/design-system.md` but never defined. This document fixes that. Per-platform code is
out of scope; both platforms absorb in follow-up tasks.

---

## 1. Tokens (single source of truth)

All values below are referenced by name from per-platform mapping. Do **not** redefine them downstream.

| Token | Value | Notes |
|---|---|---|
| `motion.duration.expand` | **320 ms** | MiniPlayer → NowPlaying |
| `motion.duration.collapse` | **260 ms** | NowPlaying → MiniPlayer |
| `motion.duration.reduceMotion` | **120 ms** | both directions, opacity-only |
| `motion.easing.standard` | `cubic-bezier(0.2, 0.0, 0, 1.0)` | Material *emphasized-decelerate* / iOS *easeOut* equivalent |
| `motion.easing.exit` | `cubic-bezier(0.3, 0.0, 0.8, 0.15)` | accelerate; used on collapse for the artwork |
| `motion.delay.controls` | **200 ms** | transport-controls fade-in delay on expand |
| `motion.artwork.startRadius` | **4 px** | matches `--miniplayer-thumb-radius` (`--radius-xs`) |
| `motion.artwork.endRadius` | **12 px** | matches `--nowplaying-artwork-radius` (`--radius-md`) |
| `motion.artwork.startSize` | **48 dp / pt** | `--miniplayer-thumb-size` |
| `motion.artwork.endSize` | `screenWidth − 2 × 24 dp` | full-bleed minus `--space-lg` gutters |
| `motion.scrim.colorStart` | `#1C1C1E` (`--color-surface`) | MiniPlayer container fill |
| `motion.scrim.colorEnd` | `#0F0F0F` (`--color-bg`) | NowPlaying base background |

> **Band justification.** Standard band per `docs/design-system.md` is 200–300 ms. The expand
> at **320 ms** is intentionally 20 ms above the ceiling: the artwork morph + cross-fade overlap
> needs a tail beyond the cross-fade end (240 ms) so the artwork settles into its end radius
> without snapping. Collapse stays in-band at 260 ms because the user already knows the target.
> No other value is out-of-band.

---

## 2. Choreography — Expand (MiniPlayer tap → NowPlaying)

```
t (ms)  │ 0      80     160     240     320
────────┼─────────────────────────────────────
artwork │ ████████████████████████████████   morph: pos + size + radius
scrim   │ ████████████████████████           cross-fade surface → bg
mini UI │ ███                                opacity 1 → 0 (60 ms)
np chro │      ████████████████████████      headers/queue chip fade
trans.  │            ████████████████        controls fade (delay 200)
status  │ ████████████████████████████████   tab bar slides down (collapse-in-place)
```

### Artwork
- Anchor: top-left of the MiniPlayer thumbnail rect (in screen coordinates).
- Scale curve: `motion.easing.standard` over `motion.duration.expand`.
- Corner radius interpolates linearly from `4 px` → `12 px`.
- Position interpolates to centered top of NowPlaying artwork slot.
- Image content does **not** crossfade — it's the same bitmap in flight.

### Scrim / background
- The MiniPlayer container fill (`#1C1C1E`) cross-fades to the NowPlaying background (`#0F0F0F`) over **0–240 ms**.
- On iOS, an additional **`UIBlurEffect(.systemThickMaterialDark)`** layer fades in 0–200 ms behind the artwork; replaced by solid `--color-bg` after settle.

### MiniPlayer UI (title, play, close)
- Fades to opacity 0 over **0–60 ms**. Removed from hit-testing at 60 ms.

### NowPlaying chrome (header, queue button, scrubber rail)
- Fades in over **80–280 ms**.

### Transport controls (play / next / prev / shuffle / repeat)
- Delayed fade-in **200–320 ms**. Y-translate **+8 px → 0** on the same curve.
- Reason for delay: artwork lands first, then controls populate — feels intentional, not chaotic.

### Tab bar
- Slides down + fades over **0–200 ms** (translateY `0 → +56 dp`, opacity `1 → 0`). Hit-testing disabled at 60 ms.

---

## 3. Choreography — Collapse (NowPlaying drag-down or back → MiniPlayer)

```
t (ms)  │ 0      65      130     195     260
────────┼─────────────────────────────────────
artwork │ ████████████████████████████████   reverse morph (exit easing)
scrim   │ ████████████████████████           bg → surface
np chro │ ████                               headers fade fast (0–60)
trans.  │ ████████                           controls fade (0–120)
mini UI │             ████████████████████   re-fade-in 130–260
tab bar │      ████████████████████████      slide back 65–260
```

- Curve flips to `motion.easing.exit` for the artwork only. Everything else uses `motion.easing.standard`.
- **Drag-driven collapse**: while the finger is down, treat translation as a 1:1 follow with no
  curve. Release: if drag ≥ 30 % of screen height OR velocity ≥ 800 dp/s downward, run
  `motion.duration.collapse` from current state to MiniPlayer; otherwise spring back over 200 ms.
- Back-button / swipe-back collapse on iOS uses the same 260 ms curve (no drag follow).

---

## 4. Reduce Motion (contract, not polish)

When the platform reports reduce-motion ON:

- **No transform animations.** The artwork does not move, scale, or morph. It is rendered at
  its destination position and size from frame 0 of the new screen.
- **Cross-fade only.** The outgoing screen and incoming screen cross-fade over
  `motion.duration.reduceMotion` (**120 ms**) using `motion.easing.standard`.
- **Same duration both directions.** Expand and collapse are symmetric under reduce-motion.
- **No delayed control fades.** Transport controls render at full opacity from frame 0.
- **No drag-follow.** Back-gesture / swipe-down still works but completes as a 120 ms cross-fade
  the moment intent is recognized.

### Detection

| Platform | API |
|---|---|
| Android | `Settings.Global.getFloat(resolver, Settings.Global.TRANSITION_ANIMATION_SCALE, 1f) == 0f` (also gate on `Settings.Global.ANIMATOR_DURATION_SCALE` for safety) |
| iOS | `UIAccessibility.isReduceMotionEnabled` (observe `UIAccessibility.reduceMotionStatusDidChangeNotification`) |

Treat any implementation that ignores either signal as a **regression**, not a polish nit.

---

## 5. Per-platform mapping

### Android (Compose)

- Use `SharedTransitionLayout` + `AnimatedContent` (Compose 1.7+).
- Artwork: `Modifier.sharedElement(rememberSharedContentState(key = "np-artwork"), this)`.
- Curve: `tween(durationMillis = 320, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f))`.
- Corner radius: animate via `RoundedCornerShape` with `lerp(4.dp, 12.dp, fraction)` inside a `derivedStateOf`.
- Scrim: `AnimatedContent` content transform with `fadeIn(tween(240)) togetherWith fadeOut(tween(240))`.
- Transport controls: wrap in `AnimatedVisibility` with `enter = fadeIn(tween(120, delayMillis = 200)) + slideInVertically { it / 8 }`.
- Reduce motion: read `LocalAccessibilityManager` + `Settings.Global.TRANSITION_ANIMATION_SCALE`; swap to `Crossfade(targetState, animationSpec = tween(120))`.

### iOS (SwiftUI)

- `matchedGeometryEffect(id: "np-artwork", in: namespace)` between MiniPlayer thumb and NowPlaying artwork view.
- Curve: `.animation(.timingCurve(0.2, 0.0, 0.0, 1.0, duration: 0.32), value: isExpanded)`.
- Corner radius: animate via `.clipShape(RoundedRectangle(cornerRadius: lerp(4, 12, progress)))`; SwiftUI interpolates if the radius is a state-tracked value.
- Scrim: `ZStack` of two `Color` layers driven by `.opacity(progress)` cross-fade.
- Transport controls: `.transition(.opacity.animation(.easeOut(duration: 0.12).delay(0.20)).combined(with: .offset(y: 8)))`.
- Reduce motion: `@Environment(\.accessibilityReduceMotion) var reduce` — when true, replace
  the matched-geometry container with a plain `if/else` switch wrapped in
  `.transition(.opacity.animation(.linear(duration: 0.12)))`.

### Idiomatic substitutions (where 1:1 isn't possible)

- **Drag-follow on iOS** uses `DragGesture` translation against a `@State` progress `0…1`;
  on Android use `Modifier.draggable` + `AnchoredDraggableState`. The 30 % / 800 dp/s thresholds
  are the same on both.
- **Blur layer** (`UIBlurEffect.systemThickMaterialDark`) has no exact Compose equivalent;
  Android uses `Modifier.blur(24.dp)` + a surface-tint overlay only on API 31+; on API < 31
  fall back to solid `--color-surface`. This is a known parity gap, documented, not a defect.

---

## 6. Edge cases

| Case | Behavior |
|---|---|
| **Track switch during animation** | Animation completes for the OUTGOING track's artwork. The incoming track's artwork cross-fades into the artwork view over 200 ms after settle. Do not abort the geometry transition mid-flight — it looks broken. |
| **App backgrounded mid-transition** | Cancel the in-flight animation, jump to destination state, no recovery animation on resume. Resume reflects whichever screen the user was heading to. |
| **MiniPlayer not yet rendered (cold open into NowPlaying)** | No shared element. NowPlaying enters with a plain fade + 4 dp upward translate, 240 ms, `motion.easing.standard`. The artwork itself fades in over 0–200 ms. |
| **Artwork load failure** | Use the placeholder (solid `--color-surface-variant` + centered music-note glyph at 40 % `--color-fg-tertiary`). Animate the placeholder, not a broken-image flash. If the artwork resolves mid-animation, cross-fade the bitmap into the placeholder over 160 ms after settle. |
| **Rapid double-tap on MiniPlayer** | Debounce: while expand is in-flight, ignore further taps on the MiniPlayer. After settle, taps activate the NowPlaying scrim (no-op) until collapse. |
| **NowPlaying opened via deep link** | Same as cold open. No collapse animation if user backs out and there's no MiniPlayer to collapse to — fade out to the previous screen over 200 ms. |

---

## 7. Accessibility — focus & announcements

### Focus target on entry

- **iOS (VoiceOver):** post `UIAccessibility.Notification.screenChanged` with the **artwork view** as the argument, after the transition settles (**+50 ms** after `motion.duration.expand`). VoiceOver then reads the artwork's accessibility label: *"Now playing: \{title\}, by \{channel\}. Double-tap for player controls."*
- **Android (TalkBack):** call `View.sendAccessibilityEvent(TYPE_WINDOW_STATE_CHANGED)` on the NowPlaying root after settle. Set `accessibilityPaneTitle = "Now playing"` on the root so TalkBack announces the pane. Initial focus target: the **collapse button** (top-left), so back-out is one swipe away.

### Focus target on exit

- **iOS:** post `screenChanged` with the **MiniPlayer thumbnail** as the argument.
- **Android:** focus returns to whichever element was focused before NowPlaying opened (default Android stack behavior). If the prior focus is no longer in the tree, focus the MiniPlayer container.

### Announcements suppressed under reduce-motion?

No. Announcements are independent of motion. Reduce-motion only changes the visual transition;
focus and announcement contracts are identical.

### Hit-testing during transition

All interactive elements are **non-hittable** while a transition is running, in either direction.
Re-enable on settle.

---

## 8. Validation

A reviewer reading only this document MUST be able to answer:

- [ ] What's the artwork curve? — `cubic-bezier(0.2, 0.0, 0, 1.0)` over 320 ms (expand) / 260 ms (collapse).
- [ ] What's the total duration? — 320 ms expand, 260 ms collapse, 120 ms reduce-motion.
- [ ] What happens under reduce-motion? — Cross-fade only, 120 ms, no transform, no delays.
- [ ] What happens if the artwork fails to load mid-flight? — Animate the placeholder; cross-fade into the bitmap when it resolves.
- [ ] What's the focus target on entry? — Artwork (iOS) / collapse button (Android), 50 ms after settle.

Mockup at `mockup.html` plays this timing back; measure within ±20 ms of the values above.

---

## 9. Changelog

- **2026-05-06** · v1 · YT-0074 — initial canonical spec.
