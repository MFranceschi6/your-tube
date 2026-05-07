# YT-0013 — Haptics & Accessibility

## Haptics — `LocalHapticFeedback` + `performHapticFeedback`

Use `LocalHapticFeedback.current` from Compose for tap events; drop down to `View.performHapticFeedback(HapticFeedbackConstants.*)` only where the SDK constant has no Compose equivalent (rare).

### Full table

| UI event | Compose call | API floor / fallback |
|---|---|---|
| Play tap | `haptics.performHapticFeedback(HapticFeedbackType.Confirm)` | API 34+; fallback `LongPress` |
| Pause tap | `haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)` | API 30+ |
| Skip forward | `haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)` | API 35+; fallback `TextHandleMove` |
| Skip backward | `haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)` | API 35+; fallback `TextHandleMove` |
| Scrubber drag tick (every 10 s) | `SegmentTick` keyed on `Int(previewMs / 10_000)` derivative | 35+ / `TextHandleMove` |
| Scrubber release | `HapticFeedbackType.GestureEnd` | API 30+; fallback `LongPress` |
| Queue open | `LongPress` | always |
| Queue swipe-cross-threshold | `Reject` | API 34+; fallback `LongPress` |
| Queue reorder pickup | `LongPress` | always |
| Queue reorder drop | `GestureEnd` | API 30+ |
| Shuffle ON | `Confirm` | API 34+; fallback `LongPress` |
| Shuffle OFF | `SegmentTick` / `TextHandleMove` | always |
| Repeat ON / OFF cycle | `SegmentTick` / `TextHandleMove` | always |

### Volume slider — explicitly NO haptics

Android owns the volume HUD haptic. Adding our own causes a double-tap feel. The volume row is `AudioManager.adjustStreamVolume(...)`-backed — system handles it.

### Single-fire pattern (avoids spurious haptics)

For asymmetric haptics (different feedback for on vs off), key the `LaunchedEffect` on the *transition*, not the value:

```kotlin
var lastShuffleOn by remember { mutableStateOf(shuffleOn) }
LaunchedEffect(shuffleOn) {
  if (shuffleOn && !lastShuffleOn) haptics.performHapticFeedback(HapticFeedbackType.Confirm)
  if (!shuffleOn && lastShuffleOn) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
  lastShuffleOn = shuffleOn
}
```

Or use `snapshotFlow { shuffleOn }.distinctUntilChanged().collect { … }` inside a `LaunchedEffect(Unit)`.

---

## System text scaling (`fontScale`)

All text uses M3 `Typography` roles (`displayLarge`, `titleLarge`, `bodyMedium`, `labelMedium`, etc) — they scale with `Configuration.fontScale` automatically.

| UI element | Role | Behavior at `fontScale = 2.0f` |
|---|---|---|
| Title (track) | `titleLarge` | `maxLines = 2` + `basicMarquee()` on overflow; do not clamp the role |
| Channel | `bodyMedium` | scales freely |
| Time labels (scrubber) | `labelMedium` with `fontFeatureSettings = "tnum"` | clamp via `Modifier.fontScalingClamp(max = 1.4f)` — tabular layout breaks otherwise; document the helper in `core/ui/FontScaling.kt` |
| Section labels (e.g. "Up Next") | `labelMedium` `weight = SemiBold` | scales freely |
| Transport icon "labels" (TalkBack only) | n/a — `contentDescription` reads aloud | the visible glyph doesn't scale; the description does |

**Why clamp some.** The transport row + scrubber must remain visible without scrolling at `fontScale = 2.0`. If everything scales freely, the screen overflows. Clamp safety-critical labels (time) and tabular layouts. For everything else, layouts must adapt — verify in the Validation step at `Settings → Accessibility → Display size and text` set to maximum.

---

## Reduce-motion contract

Read `LocalReduceMotion.current` (defined in `core/ui/ReduceMotion.kt`; provided at the theme root from `Settings.Global.TRANSITION_ANIMATION_SCALE == 0f` and on API 33+ also `AccessibilityManager.isReducedAnimationsEnabled`).

| Animation | Default | Reduce Motion |
|---|---|---|
| Artwork pause-scale spring | `spring(stiffness = 380, dampingRatio = 0.78)` | `snapTo(target)` — instant |
| Scrubber thumb grow on drag | `tween(180)` | instant (`snap()`) |
| Scrubber track expand | `tween(180)` | instant |
| Play↔Pause icon swap | `Crossfade(tween(140))` | `Crossfade(snap())` (still cross-fade, just instant) |
| Queue sheet entry | M3 default slide | M3 reduced-motion variant (cross-fade) |
| Queue row reorder spring-back | `spring(...)` on `Animatable<Float>` | `snapTo` |
| MiniPlayer↔NowPlaying `sharedBounds` morph | `tween(220, FastOutSlowInEasing)` | `snap()` |
| Predictive-back / drag-to-dismiss | tracks finger | **stays** — user-driven motion isn't vestibular-triggering |
| Progress bar fill advance | continuous | **stays** — that's information, not motion |
| Color/state changes (active/inactive icon) | `tween(150)` | **stays** — not motion |

Don't conditional-out the whole animation — provide a reduced variant. Pattern:

```kotlin
val reduceMotion = LocalReduceMotion.current
val artworkScale = remember { Animatable(if (isPlaying) 1f else 0.85f) }
LaunchedEffect(isPlaying, reduceMotion) {
  val target = if (isPlaying) 1f else 0.85f
  if (reduceMotion) artworkScale.snapTo(target)
  else artworkScale.animateTo(target, spring(stiffness = 380f, dampingRatio = 0.78f))
}
```

---

## TalkBack labels

Per `docs/design-system.md`: "Player controls expose state in label: 'Pause' not 'Toggle'."

All `contentDescription` strings must come from `strings.xml` (YT-0064) — no hardcoded literals in composables.

| Element | Label (state-aware) | Hint / context |
|---|---|---|
| Play button (paused) | `"Play ${track.title} by ${track.channel}"` | `clickAction("Play")` |
| Pause button (playing) | `"Pause"` | `clickAction("Pause")` |
| Skip forward | `"Next track"` | hint: `"Skip to ${nextTrack.title}"` if known |
| Skip backward | `"Previous track or restart"` | omitted (behavior depends on elapsed) |
| Scrubber | `"${formatMs(positionMs)} of ${formatMs(durationMs)}"` | also expose as a `CustomAccessibilityAction` "seek forward 10 seconds" / "seek backward 10 seconds" |
| Shuffle | `"Shuffle, ${if (shuffleOn) "on" else "off"}"` | `clickAction("Toggle shuffle")` |
| Repeat | `"Repeat ${repeatMode.label()}"` (off / one / all) | `clickAction("Cycle repeat mode")` |
| Add to playlist | `"Add to playlist"` | omitted |
| Cast | `MediaRouteButton` provides its own; do not override | system |
| Dismiss | `"Close player"` | `clickAction("Close")` |
| Queue row | `"${track.title} by ${track.channel}, position ${index + 1} of ${queue.size}"` | `customActions("Remove from queue", "Move up", "Move down")` |

Make the **scrubber adjustable** via `CustomAccessibilityAction`s so TalkBack users can scrub without dragging:

```kotlin
Modifier.semantics {
  customActions = listOf(
    CustomAccessibilityAction("Seek forward 10 seconds") {
      onSeek((positionMs + 10_000L).coerceAtMost(durationMs)); true
    },
    CustomAccessibilityAction("Seek backward 10 seconds") {
      onSeek((positionMs - 10_000L).coerceAtLeast(0L)); true
    },
  )
}
```

### Content descriptions for state changes

When `isPlaying` flips, also announce via `LiveRegionMode.Polite`:

```kotlin
val announcement = if (isPlaying) "Playing" else "Paused"
Modifier.semantics { liveRegion = LiveRegionMode.Polite; contentDescription = announcement }
```

Apply this to a small invisible Spacer near the transport row, not to the button itself (TalkBack would re-read the button's own label otherwise).

---

## Tap targets

Minimum **48 dp × 48 dp** for every interactive element (`docs/design-system.md`). Visible glyph size is decoupled from hit size:

```kotlin
IconButton(onClick = { … }, modifier = Modifier.size(48.dp)) {
  Icon(
    Icons.Rounded.Shuffle,
    contentDescription = stringResource(R.string.np_shuffle_cd, shuffleOn.label()),
    modifier = Modifier.size(24.dp),                  // visible glyph
  )
}
```

The 72 dp play-pause is both visible and hit area — it's already comfortable.

For the scrubber, the visible track is 4–8 dp tall but the gesture surface is `Modifier.height(28.dp)` so dragging is easy at the edges.

---

## Color contrast

- Title (`onSurface` white-ish) on `surface` `#1C1B1F`: ~16:1 — passes AAA.
- `onSurfaceVariant` channel name on `surface`: ~7:1 — passes AA body.
- `onSurfaceVariant` for inactive shuffle/repeat icon glyphs: ~4:1 — passes AA *for non-text*. Don't use it for body text; only for icon glyphs in the off state, where the meaning is duplicated by the on/off color contrast.
- `colorScheme.primary` (Material You) on `surface`: variable. The HCT-derived M3 schemes guarantee ≥3:1 for non-text on `surface` — do not assume body-text contrast on accent. For body text on accent surfaces, use `onPrimaryContainer`.

When v1.1 wallpaper-tint background lands (Q3), re-validate every text/icon pair against the harmonized scheme — that's the whole point of the tone clamp in the harmonization helper.
