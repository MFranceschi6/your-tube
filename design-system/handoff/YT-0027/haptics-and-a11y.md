# YT-0027 — Haptics & Accessibility

## Haptics — `.sensoryFeedback` (iOS 17+)

All haptics use SwiftUI's `.sensoryFeedback` modifier. Do **not** drop down to `UIFeedbackGenerator` unless you need pre-warming (you don't — the system handles it).

### Full table

| UI event | Modifier | Trigger value |
|---|---|---|
| Play | `.sensoryFeedback(.impact(weight: .medium), trigger: isPlaying)` | fires when isPlaying flips false → true |
| Pause | `.sensoryFeedback(.impact(weight: .light), trigger: isPlaying)` | fires when isPlaying flips true → false |
| Skip forward | `.sensoryFeedback(.impact(weight: .light, intensity: 0.7), trigger: skipFwdCount)` | increment counter on each tap |
| Skip backward | `.sensoryFeedback(.impact(weight: .light, intensity: 0.7), trigger: skipBackCount)` | increment counter on each tap |
| Scrubber drag tick | `.sensoryFeedback(.selection, trigger: Int(scrubPosition / 10))` | fires every 10s of scrub distance |
| Scrubber release | `.sensoryFeedback(.impact(weight: .medium), trigger: isDragging) { old, new in old && !new }` | fires only on drag end |
| Queue open | `.sensoryFeedback(.impact(weight: .light), trigger: showQueue)` | fires when showQueue flips false → true |
| Shuffle ON | `.sensoryFeedback(.success, trigger: shuffleOn) { old, new in !old && new }` | only on enable |
| Shuffle OFF | `.sensoryFeedback(.selection, trigger: shuffleOn) { old, new in old && !new }` | only on disable |
| Repeat ON | `.sensoryFeedback(.success, trigger: repeatMode) { old, new in old == .off && new != .off }` | only on enable |
| Repeat OFF | `.sensoryFeedback(.selection, trigger: repeatMode) { old, new in old != .off && new == .off }` | only on disable |

### Volume slider — explicitly NO haptics

iOS owns the volume HUD haptic. Adding our own causes a double-tap feel. The volume slider is `MPVolumeView` wrapped in `UIViewRepresentable`, not a custom slider — system handles it.

### Single trigger pattern (avoids spurious fires)

The `.sensoryFeedback(_:trigger:)` modifier with a closure form `{ old, new in ... }` lets you fire on a *specific* transition. Use it whenever the haptic is asymmetric (different feedback for on vs off).

---

## Dynamic Type

All text uses semantic font roles:

| UI element | Font | Behavior at AX5 |
|---|---|---|
| Title (track) | `.system(size: 22, weight: .semibold)` + `.dynamicTypeSize(...DynamicTypeSize.xxxLarge)` | clamp at xxxLarge; `.lineLimit(2)` + `.minimumScaleFactor(0.7)` |
| Channel | `.system(size: 15)` + `.dynamicTypeSize(...DynamicTypeSize.accessibility2)` | scales |
| Time labels (scrubber) | `.system(size: 12, weight: .medium).monospacedDigit()` + clamp `.dynamicTypeSize(...DynamicTypeSize.xxLarge)` | clamp — tabular layout breaks otherwise |
| Section labels (e.g. "Up Next") | `.font(.caption.weight(.semibold))` | scales freely |

**Why clamp some.** The transport row + scrubber must remain visible without scrolling at AX5. If everything scales freely, the screen overflows. Clamp safety-critical labels (time) and high-density layout text (title at AX5 needs 2 lines max).

---

## Reduce Motion contract

Read `@Environment(\.accessibilityReduceMotion) var reduceMotion`.

| Animation | Default | Reduce Motion |
|---|---|---|
| Artwork pause-scale spring | `.spring(response: 0.45, dampingFraction: 0.78)` | `.linear(duration: 0)` (instant) — *or* swap scale for `.opacity(isPlaying ? 1 : 0.9)` |
| Sheet entry / cover | slide up | cross-fade |
| Up Next preview appearance | slide up | cross-fade |
| Queue push (NavigationStack) | slide horizontal | cross-fade |
| Scrubber thumb grow on drag | `.easeInOut(0.18)` | instant (set duration to 0) |
| Scrubber track expand | `.easeInOut(0.18)` | instant |
| Drag-to-dismiss | tracks finger | **stays** — user-driven motion isn't vestibular-triggering |
| Progress bar fill advance | continuous | **stays** — that's information, not motion |
| Color/state changes (active/inactive icon) | `.easeInOut(0.15)` | **stays** — not motion |

Don't conditional-out the whole animation — provide a reduced variant. Pattern:

```swift
.animation(reduceMotion ? .linear(duration: 0) : .spring(response: 0.45, dampingFraction: 0.78), value: scale)
```

---

## VoiceOver

Per the README: "Accessibility labels are state-aware. 'Pause' not 'Toggle playback'. 'Play, Bohemian Rhapsody by Queen' not 'Play button'."

| Element | Label | Hint |
|---|---|---|
| Play button (paused) | `"Play \(track.title) by \(track.channel)"` | `"Double-tap to play"` |
| Pause button (playing) | `"Pause"` | omitted |
| Skip forward | `"Next track"` | `"Skip to \(nextTrack.title)"` if known |
| Skip backward | `"Previous track or restart"` | omitted (behavior changes based on elapsed) |
| Scrubber | `"\(elapsed) of \(duration)"` | `"Swipe up or down to scrub"` (rotor adjustable) |
| Shuffle | `"Shuffle, \(isOn ? "on" : "off")"` | `"Double-tap to toggle"` |
| Repeat | `"Repeat \(repeatMode.label)"` (off / one / all) | omitted |
| Add to playlist | `"Add to playlist"` | omitted |
| AirPlay | system-provided (don't override `AVRoutePickerView`) | system |
| Dismiss | `"Close player"` | `"Swipe down to dismiss"` |

Make the **scrubber adjustable** — `accessibilityAdjustableAction` so VoiceOver users can scrub via swipe-up/down:

```swift
.accessibilityAdjustableAction { direction in
    let step = duration * 0.05  // 5% steps
    switch direction {
    case .increment: dragValue = min(duration, dragValue + step); commit()
    case .decrement: dragValue = max(0, dragValue - step); commit()
    @unknown default: break
    }
}
```

---

## Tap targets

Minimum **44pt × 44pt** for every interactive element. Visible glyph size is decoupled from hit size:

```swift
Image(systemName: "shuffle")
    .font(.system(size: 20))           // visible glyph: 20pt
    .frame(width: 44, height: 44)      // hit area: 44pt
    .contentShape(Rectangle())          // makes whole frame tappable
```

The 72pt play-pause is both visible and hit area — it's already comfortable.

---

## Color contrast

- Title (white) on `#1C1C1E`: ~16:1 — passes AAA easily.
- `.secondary` channel name on `#1C1C1E`: ~7:1 — passes AA.
- `.tertiary` (used for inactive shuffle/repeat): ~4:1 — passes AA *for non-text*. Don't use `.tertiary` for content text; only for icon glyphs in the off state, where the meaning is duplicated by the on/off color contrast.
- Accent `#8B5CF6` on `#1C1C1E`: ~5.4:1 — passes AA for non-text and large text. For body text on accent, use white (`--color-accent-on`).
