# YT-0027 — Aesthetic Direction (Q11)

> **Binding tone.** When implementation has an open question and the spec is silent, this file decides. The default answer is always: **do less, not more.**

---

## The one sentence

**Calm, focused, content-first — like Podcasts, not Music.**

YourTube is a personal listening tool, not a discovery storefront. The "wow" is the system integration (Dynamic Island waveform, lock-screen scrubber parity), **not the chrome**. The artwork is the only color on screen. Purple is a single accent, never multiple. Motion is restrained, surfaces are quiet.

Apple-app reference: **Podcasts**. Not Music (too storefront-y), not Apple TV (too cinematic), not Journal (too writerly).

---

## What this means concretely

### Surfaces
- Solid `#1C1C1E` background, not extracted color (v1).
- No glass capsules around transports.
- No decorative gradients anywhere on chrome.
- No drop shadows on dark surfaces — they don't read and they add visual noise.
- Borders only where they separate content (Up Next divider lines), never decorative.

### Color
- Purple `#8B5CF6` is the *only* accent. Never combine with a second hue.
- Active states use accent. Inactive states use `.tertiary`. There is no third tier.
- The artwork is the only multi-color element on the screen. Let it be.

### Motion
- Spring on artwork pause-scale (information-bearing).
- Linear ease on scrubber thumb/track expansion (utility).
- Everything else: instant or cross-fade.
- No bounce, no overshoot, no decorative entrance animations.

### Type
- One typeface (SF Pro). One italic exception: never.
- Two weights: 400 (body) and 600 (titles, active states). Skip 500.
- Tabular numerals on every time/duration. Never on body copy.

### Iconography
- SF Symbols only. One stroke weight per context.
- No emoji, no custom glyphs.
- Active state = filled variant + accent color. Inactive = outline + `.tertiary`.

### Copy
- Lowercase sentence-case for all body and action copy.
- "Up Next" is the *one* exception (treated as a label, not body).
- State-aware accessibility labels. "Pause", not "Toggle playback".

---

## Refuse list (literal)

When tempted, refuse:

- ❌ Extracted-color background in v1.
- ❌ Glass / material capsule around the transport row.
- ❌ Linear top-to-bottom gradient anywhere on chrome.
- ❌ Vinyl-circle artwork mode.
- ❌ A second accent color (red, blue, green for state).
- ❌ A "premium" badge, "now playing" hero text, or any marketing chrome.
- ❌ Decorative motion (rotating disc, pulsing bars *on the main screen*).
- ❌ A "share via" custom sheet — use `ShareLink`.
- ❌ A custom AirPlay button — use `AVRoutePickerView`.
- ❌ Custom volume slider — wrap `MPVolumeView`.

---

## Where the polish goes

If chrome is austere, the *system integration* must be impeccable. Spend the polish budget on:

1. **`MPNowPlayingInfoCenter` accuracy.** Elapsed/rate update on every state change, not on a polling timer. Shuffle/repeat mode keys present.
2. **Dynamic Island waveform.** 2 bars, animate only on play, freeze flat on pause. Same accent color as in-app.
3. **Lock-screen artwork.** Closure-resolved at the size the system requests, not pre-rendered.
4. **MiniPlayer ↔ NowPlaying matchedGeometry.** The artwork *flies* between the two — no jump, no fade overlap.
5. **AirPlay route changes.** Reflect immediately in the action row, mirror to lock screen.
6. **Reduce-motion variants** that still feel intentional — cross-fades that match the system's own.

A user can't articulate why one music app feels premium and another doesn't. The reason is almost always: the system surfaces (lock screen, Dynamic Island, control center) match the in-app surface frame-for-frame. That's where the budget goes.

---

## Tie-breaker rules

| Open question | Default answer |
|---|---|
| Add this nice flourish? | No. |
| Add a second accent for this state? | No, use opacity. |
| Add a label to this icon? | No, the icon is enough. |
| Add a divider here? | Only if it separates two distinct content groups. |
| Add a shadow here? | Not on dark surfaces. |
| Animate this change? | Cross-fade or nothing. |
| Make this bigger to draw attention? | Yes — size > chrome. |
| Replace a custom control with a system one? | Always yes. |

---

## Compared to v1 (Music-style)

| Aspect | v1 (Music-inspired, rejected) | This direction (Podcasts-inspired) |
|---|---|---|
| Background | Extracted-color blur from artwork | Solid `#1C1C1E` |
| Transport row chrome | Material capsule | None — free-floating glyphs |
| Play button | Accent purple filled | White filled (lets the accent live in *toggle* states only) |
| Scrubber thumb | Accent purple | White |
| Up Next | Modal sheet from queue button | Inline preview + pushed destination |
| Tone | Vibrant, discovery | Quiet, focused |

The v1 direction isn't *wrong* — it's right for a streaming service. It's wrong for a personal indie audio app. Commit to this direction and the screen reads as deliberate; mix the two and it reads as half-finished Apple Music.
