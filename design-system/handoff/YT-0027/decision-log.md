# YT-0027 — Decision Log

iOS Now Playing screen for YourTube. Each decision below has been validated against the design system tokens (`colors_and_type.css`) and the existing `MiniPlayer` + `NowPlaying.jsx` mockup components. The HTML mockup at `handoff/YT-0027/mockup.html` demonstrates each in context.

> **Original UI; behavioral inspiration only from Apple Music / Podcasts.** Don't recreate Apple's UI.

---

## 1. Presentation pattern — `.fullScreenCover` + interactive drag

`.fullScreenCover` with a custom `DragGesture` driving translation + a velocity-threshold dismiss. The artwork hero-transitions from the MiniPlayer via `matchedGeometryEffect`.

**Rationale.** This screen is a destination, not an inspector. A `.sheet(.large)` always shows ~10pt of background and a system grabber you can't fully control. A two-detent sheet implies the medium detent has a job — but the MiniPlayer already *is* the medium state. `.fullScreenCover` with the native swipe-down dismisses on threshold but doesn't track the finger; we want the artwork to move with the drag and rubber-band at the top.

Inspiration: Music, Podcasts.

---

## 2. Artwork treatment — square, rounded, scale on pause

Square artwork, `RoundedRectangle(cornerRadius: 12, style: .continuous)`, centered. Paused state scales to **0.85** with corner radius growing to **20pt**. Spring: `.spring(response: 0.45, dampingFraction: 0.78)`.

Skip the vinyl circle (it's a costume — and YourTube audio comes from video sources, rarely "albums"). Skip the full-bleed-as-artwork move. `.bouncy` is too playful at 0.85 — feels rubbery. The custom spring lands cleanly with one tiny visible settle. Match the same spring on resume so asymmetry doesn't read as a glitch.

Anchor: `.center` (default). Don't anchor `.bottom` — that's an Apple Music tic that looks unsettled in our context.

Inspiration: Music (their ratio is ~0.82; 0.85 is more conservative for a non-album context).

---

## 3. Background atmosphere — solid `#1C1C1E` for v1

Ship the mockup's solid `#1C1C1E`. Stage extracted-color behind a feature flag for v1.1.

**Rationale.** Extracted-color backgrounds are the premium move done right and the canonical AI-slop tell done wrong. To do it right requires (a) palette extraction with vibrancy/contrast filtering so muddy thumbnails don't produce muddy backgrounds, (b) a luminance clamp so text contrast never breaks WCAG AA, (c) cross-fade tied to the artwork transition, not independent. That's a week of polish, not a checkbox.

If "solid feels flat" feedback comes, ship the **subtle radial** option (toggleable in the mockup): `radial-gradient(120% 80% at 50% 0%, #2A2A2D 0%, #1C1C1E 60%)`. Avoid linear two-stop top-to-bottom gradients — that's the slop tell.

Inspiration: Podcasts (solid).

---

## 4. Scrubber — custom view, `.onEnded`-only commit

Custom `DragGesture` on a `GeometryReader`, not native `Slider`. `Slider` doesn't give you the expand-on-drag track or the time-label behavior, and `UIViewRepresentable` on `UISlider` is more code than the gesture.

**Specs.**
- Track: `4pt` rest → `8pt` while dragging, animate width with `.easeInOut(duration: 0.18)`.
- Thumb: `12pt` rest → `18pt` dragging, accent or white fill (white reads better on dark).
- Time labels: `.monospacedDigit()` always. `12pt` secondary at rest → `14pt` primary while dragging.
- **Artwork shrinks** to `0.92` while dragging. Less than the pause shrink — pause is the bigger event.
- **Haptic ticks every 10 seconds** of scrub distance (key the trigger to `Int(scrubPos / 10)`).
- **Commit on release only.** AVPlayer `seek(to:)` fires in `.onEnded`. Drag is *seek preview*. This protects against scrub-spam and matches Apple Music.

Inspiration: Music scrubber (track expansion + label grow are theirs).

---

## 5. Transport row — size hierarchy, no chrome

Single row, free-floating SF Symbols, no capsule, no glass. Layout: `shuffle · backward.fill · play.circle.fill (72pt) · forward.fill · repeat`.

The play-pause is the center of gravity through **size and weight contrast**, not a container. Sizes:
- Shuffle / Repeat: `20pt` glyph, `44pt` hit target, `tertiary` color when off / `accent` when on.
- Skip: `26pt` glyph, `56pt` hit target.
- Play-pause: `72pt` filled circle. White fill on dark BG; on extracted-color BG (v1.1), use `.tint`.

No "capsule glass row" — that's a Liquid Glass move and you've ruled iOS 26 out.

**Hit targets.** The visible glyphs are 20/26/72pt; the hit areas are `44/44/72pt` minimum via `.contentShape(Rectangle())` padding. Don't let the visible glyph dictate the hit target.

Inspiration: Music, Podcasts.

---

## 6. Queue access — Up Next preview + pushed destination

Inline **Up Next** preview (next 3 tracks) on the Now Playing screen, with a "See all" affordance that pushes a full `NavigationStack` destination — *not* a collapsible panel.

**Rationale.** A panel falls apart for long queues, edit mode, or swipe-delete — a panel can't host a `List` with edit affordances cleanly. A separate sheet works but loses the back-stack metaphor. A pushed destination gives full-height list, native `EditMode`, swipe actions, and the MiniPlayer stays visible above the tab bar so audio focus is preserved.

**Update the existing mockup component (`ui_kits/app/NowPlaying.jsx`)** — currently it has a collapsible panel; the production screen ships the inline-preview + pushed-destination pattern.

Inspiration: Podcasts (Up Next is its own screen).

---

## 7. Action row — icon-only, AirPlay first

Bottom row, four icons evenly spaced, icon-only. Order left → right: `AirPlay · Queue · Share · Add to playlist`.

**AirPlay placement.** On iOS, AirPlay is special — `AVRoutePickerView` (UIKit-bridged), shows the system route sheet, users expect it in a *consistent* place. Music puts it bottom-left of transport. We put it bottom-left of the action row. Don't move AirPlay to the top toolbar — users hunt for it during a session, not at session start.

**Don't label the icons.** Labeled icons make the row feel like a settings screen, not a player. SF Symbols (`airplayaudio`, `list.bullet`, `square.and.arrow.up`, `text.badge.plus`) are universally legible.

---

## 8. Haptics — `.sensoryFeedback` (iOS 17+)

| Event | Variant | Why |
|---|---|---|
| Play tap | `.impact(weight: .medium)` | Confident |
| Pause tap | `.impact(weight: .light)` | Settling |
| Skip fwd / back | `.impact(weight: .light, intensity: 0.7)` | Quick, dry, symmetric |
| Scrubber drag tick (every 10s) | `.selection` | Like volume HUD ticks |
| Scrubber release | `.impact(weight: .medium)` | Match play weight — commits seek |
| Queue open | `.impact(weight: .light)` | Navigation feel |
| Shuffle / Repeat ON | `.success` | Distinct from transport |
| Shuffle / Repeat OFF | `.selection` | Undo is less momentous than do |

**Do NOT** add haptics to volume slider drags — iOS owns that via the system volume HUD; doubling up feels broken.

Detail: `haptics-and-a11y.md`.

---

## 9. Reduce motion — degrade gracefully

Gate via `@Environment(\.accessibilityReduceMotion)`.

**Degrades to instant or cross-fade:** artwork pause-scale spring · queue panel slide · sheet entry · scrubber thumb grow.
**Stays:** color/state changes · progress bar advance (information, not motion) · drag-to-dismiss (user-driven motion isn't vestibular-triggering).

Don't conditional-out the whole animation — provide a reduced variant.

---

## 10. System parity — what mirrors to lock screen / Dynamic Island

**`MPNowPlayingInfoCenter` (must mirror):**
- Title, channel/artist, artwork (highest-res — `MPMediaItemArtwork(boundsSize:requestHandler:)` closure, not fixed `UIImage`).
- Playback rate, current time, duration — for lock-screen scrubber tracking.
- Shuffle / repeat *state* — `MPRemoteCommandCenter.shared().changeShuffleModeCommand` / `changeRepeatModeCommand`. Most apps skip these. Doing them is the differentiator.

**Live Activity / Dynamic Island:**
- Compact leading: artwork thumbnail.
- Compact trailing: 2-bar audio waveform that animates only while playing, **pauses on pause**. This is the Apple-tier touch.
- Expanded: title, channel, scrubber, play/pause + skip transport.
- **Crucial:** artwork in the Dynamic Island uses the same proportional corner radius (~16% of side length) as the on-screen artwork. Consistency across surfaces is the award-bait.

Detail: `system-parity.md`.
