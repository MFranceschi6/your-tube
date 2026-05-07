# YT-0013 — Decision Log

Android Now Playing screen for YourTube. Each decision below is validated against `docs/design-system.md` and the post-implementation audit (`design-system/handoff/YT-0011/audit.md`). The legacy combined mockup at `design-system/mockups/android/YT-0011-0012-0013-shell-search-player.html` is layout reference only; this log overrides it where they disagree.

> **Original UI; behavioral inspiration only from YouTube Music / Spotify on Android.** Don't recreate either app's surface.

---

## Q1 — Presentation pattern

**Decision: NowPlaying as a top-level Navigation destination inside a `SharedTransitionLayout` host**, not a `ModalBottomSheet`, not an inline expanding view. Predictive-back (Android 13+) drives dismissal; older releases use the framework slide.

The MiniPlayer artwork hero-morphs into the NowPlaying artwork via `Modifier.sharedBounds(...)` keyed by `track.videoId`. Owning host: `AppShell` (YT-0061).

**Rationale.** The MiniPlayer already plays the role of the medium-detent sheet — adding a sheet on top is a duplicated state machine. A nav destination gives free predictive-back, free back-stack, and a stable parent for `MediaSession`-driven re-entry (Q11 PendingIntent).

**Anti-patterns.**
- ❌ `ModalBottomSheet(skipPartiallyExpanded = true)` as the player — duplicates the MiniPlayer's role; loses predictive-back.
- ❌ Hand-rolled `AnimatedVisibility` slide — re-implements what nav already does and breaks shared-element timing.
- ❌ Pulling NowPlaying into `MainActivity` directly — couples the player to the activity lifecycle; bad for `MediaController` re-bind.

---

## Q2 — Artwork treatment — square, rounded, scale on pause

Square artwork, `RoundedCornerShape(12.dp)` while playing, lerping to `RoundedCornerShape(20.dp)` while paused. Pause scales to **0.85** with the radius transition tied to scale progress. Spring: `spring(stiffness = 380f, dampingRatio = 0.78f)` driven by a single `Animatable<Float, AnimationVector1D>` keyed to `isPlaying`. Bind `transformOrigin = TransformOrigin.Center` via `graphicsLayer`.

Skip the vinyl-circle costume; YourTube audio comes from video sources, not "albums". `transformOrigin.Center` (default is sometimes `(0.5, 0.5)` already, but the audit caught a regression where it wasn't — set it explicitly).

**Anti-patterns.**
- ❌ Two parallel `animateFloatAsState` for scale and corner — they desync by a frame on Pixel 8 at 120 Hz; use one `Animatable` and lerp the radius from its live value.
- ❌ `Modifier.scale(...)` — clips the shared-element bounds wrong on the `sharedBounds` morph; use `Modifier.graphicsLayer { scaleX = …; scaleY = … }`.
- ❌ Anchoring `transformOrigin` to `BottomCenter` — the artwork looks "settled" but reads as drifting away from the shared-element anchor.

---

## Q3 — Background atmosphere — `colorScheme.surface` for v1

Ship `MaterialTheme.colorScheme.surface` (the audit's flag was `colorScheme.background` — corrected here). Stage wallpaper-color-derived tint behind a feature flag for v1.1 (Material You harmonization via `MaterialColors.harmonize` over a 22-tone HCT seed extracted from the active artwork).

**Rationale.** Wallpaper-derived backgrounds are the premium move done right and the canonical AI-slop tell done wrong. Right requires (a) HCT extraction with chroma clamp, (b) tone clamp so contrast never breaks 4.5:1, (c) cross-fade tied to the artwork morph not independent. That's a week of polish on top of a screen that has to ship in MVP — feature-flag it.

**Anti-patterns.**
- ❌ `colorScheme.background` — too dark on Material You purple wallpaper; the audit caught text contrast dropping below 4.5:1 on certain dynamic schemes. `surface` is the correct token.
- ❌ Linear two-stop top-to-bottom gradient — that's the slop tell.
- ❌ `Brush.radialGradient` without HCT clamp — produces muddy backgrounds on muddy artwork.

---

## Q4 — Scrubber — hand-rolled `Modifier.draggable` on `Canvas`, commit on release

Reject `Slider`. M3 `Slider` doesn't expose a clean expand-on-drag track or the time-label scaling, and `RangeSlider`'s thumb hit-target is too small at rest.

**Specs.**
- Track: `4.dp` rest → `8.dp` while dragging, `tween(durationMillis = 180, easing = FastOutSlowInEasing)`.
- Thumb: `16.dp` rest → `22.dp` dragging, **white** fill (the legacy mockup used `pal.primary` purple — overridden; white reads better on dark and survives the v1.1 wallpaper-tint background).
- Time labels: `fontFeatureSettings = "tnum"` always (tabular numbers via Compose `Text(... fontFeatureSettings)`). `12.sp` secondary at rest → `14.sp` primary while dragging.
- **Artwork shrinks** to `0.92` while dragging (extends the Q2 `Animatable`'s low end). Less than pause — pause is the bigger event.
- **Haptic ticks every 10s of scrub distance** — key on `Int(previewMs / 10_000)` derivative. API 35+ uses `HapticFeedbackConstants.SEGMENT_TICK`; 31–34 falls back to `HapticFeedbackConstants.TEXT_HANDLE_MOVE`.
- **Commit on release only.** `playerController.seekTo(...)` fires in `onDragStopped`. Drag is *seek preview*. This protects against scrub-spam and is the only sane path for `MediaController` (where every seek round-trips through the service).

**Anti-patterns.**
- ❌ `Slider(onValueChange = { vm.seekTo(it) })` — every frame seeks the underlying ExoPlayer; UI feels syrupy and `MediaSession` floods the lock-screen.
- ❌ `BasicTextField`-style time labels without `tnum` — labels jitter horizontally on every digit change.
- ❌ Thumb in `colorScheme.primary` — fights the v1.1 wallpaper tint.

---

## Q5 — Transport row — size hierarchy, no chrome

Single row, free-floating Material Symbols (rounded), no capsule, no glass. Layout: `shuffle · skip_previous · play_pause (72 dp filled circle) · skip_next · repeat`.

The play-pause is the center of gravity through **size and weight contrast**, not a container. Use `FilledIconButton` 72 dp with `containerColor = colorScheme.onSurface`, `contentColor = colorScheme.surface` for the white-on-dark inversion. Do not use `colorScheme.primary` here — that fights v1.1 wallpaper tint.

Sizes:
- Shuffle / Repeat: `24.sp` glyph, `48.dp` hit target, `colorScheme.onSurfaceVariant` when off / `colorScheme.primary` when on.
- Skip prev / next: `32.sp` glyph, `56.dp` hit target.
- Play-pause: `72.dp` filled circle. `Crossfade(tween(140))` on play↔pause icon swap.

**Hit targets.** Visible glyph 24/32 sp; hit area 48/56 dp via `Modifier.size(...)` decoupled from glyph. Don't let the visible glyph dictate the hit target.

**Material Symbols variable font** (rounded): if the variable TTF is bundled in `core/designsystem/src/main/res/font/` (YT-0065), animate the `wght` axis 400 → 600 on press. If not bundled, ship the static `Icons.Rounded.*` set and document the deferral.

**Anti-patterns.**
- ❌ Hand-rolled `Box` for the play-pause container — loses the M3 ripple and state-layer semantics. Use `FilledIconButton`.
- ❌ Capsule "glass" row — that's iOS Liquid Glass; we're on M3.
- ❌ `Icons.Default.PlayArrow` (sharp) — design system specifies rounded.

---

## Q6 — Queue access — `ModalBottomSheet` + hand-rolled reorder

Queue lives in a `ModalBottomSheet` triggered from the action row (Q7), not inline below the transport. The legacy mockup's collapsible inline panel was retired in audit — it falls apart for long queues, edit mode, and swipe-delete.

**Sheet contents.**
- `rememberModalBottomSheetState(skipPartiallyExpanded = false)` — partial detent shows ~3 rows; full detent shows the rest.
- Long-press on a row triggers `detectDragGesturesAfterLongPress` to start reorder; per-row `Animatable` y-offset; on drop, `viewModel.reorderQueue(from, to)` with a single Room UPDATE (gap-based positions).
- Swipe trailing-to-leading uses `SwipeToDismissBox(enableDismissFromStartToEnd = false, enableDismissFromEndToStart = true, backgroundContent = { DeleteBackground() })` — removes from the queue.
- **No third-party reorder library.** `sh.calvin.reorderable` is rejected — hand-roll it.

**Anti-patterns.**
- ❌ Inline collapsible panel (legacy mockup) — can't host edit mode; gesture conflicts with scroll.
- ❌ `Reorderable` third-party — the audit and `compose-spec.md` both reject it.
- ❌ `swipeToDismiss` for *both* directions — leading-to-trailing has no defined action; only trailing-to-leading destructive.

---

## Q7 — Action row — icon-only, Cast first

Bottom row, four icons evenly spaced, icon-only, `Arrangement.SpaceAround`. Order left → right: `cast · queue · share · playlist_add`.

**Cast placement.** On Android, Cast is special — `MediaRouteButton` from `androidx.mediarouter`, shows the system Cast picker, users expect it in a *consistent* place. We put it bottom-left of the action row to mirror the iOS AirPlay placement (cross-platform muscle memory). Don't move Cast to the top app bar — users hunt for it during a session, not at session start.

`CastButton` is `AndroidView { MediaRouteButton(ctx).also { CastButtonFactory.setUpMediaRouteButton(ctx, it) } }`, sized 48 dp. **Cast is dependency-gated:** only enable if `play-services-cast-framework` is on the dependency graph. Otherwise the slot stays disabled and a follow-up task adds the dep — do not introduce silently.

Share fires `Intent.ACTION_SEND` system share sheet (text + `share-preview` style for the YouTube link). `playlist_add` reuses the existing `AddToPlaylistSheet` route.

**Don't label the icons.** Labeled icons make the row feel like a settings screen, not a player. Material Symbols (`cast`, `queue_music`, `share`, `playlist_add`) are universally legible.

---

## Q8 — Haptics — `HapticFeedback` + `performHapticFeedback`

Use `LocalHapticFeedback.current` for tap-events; drop down to `View.performHapticFeedback(HapticFeedbackConstants.*)` only where the SDK constant matches (scrubber tick — `SEGMENT_TICK` API 35+, `TEXT_HANDLE_MOVE` 31–34).

| Event | API |
|---|---|
| Play tap | `HapticFeedbackType.LongPress` (the only "confident" type pre-API 34; on API 34+ use `HapticFeedbackType.Confirm`) |
| Pause tap | `HapticFeedbackType.TextHandleMove` (light) |
| Skip fwd / back | `HapticFeedbackType.SegmentTick` (API 35+) / `TextHandleMove` |
| Scrubber drag tick (every 10s) | `HapticFeedbackType.SegmentTick` / `TextHandleMove` |
| Scrubber release | `HapticFeedbackType.GestureEnd` (API 30+) / `LongPress` |
| Queue open | `HapticFeedbackType.LongPress` |
| Shuffle / Repeat ON | `HapticFeedbackType.Confirm` (API 34+) / `LongPress` |
| Shuffle / Repeat OFF | `HapticFeedbackType.SegmentTick` / `TextHandleMove` |
| Reorder pickup | `HapticFeedbackType.LongPress` (drag start) |
| Reorder drop | `HapticFeedbackType.GestureEnd` |
| Swipe-cross-threshold (queue remove) | `HapticFeedbackType.Reject` (API 34+) / `LongPress` |

**Do NOT** add haptics to volume slider drags — Android owns that via the system volume HUD. Detail: `haptics-and-a11y.md`.

---

## Q9 — Reduce motion — degrade gracefully

Gate via `LocalReduceMotion.current` (defined in `core/ui/ReduceMotion.kt` per YT-0062a Q3 commit; sourced from `Settings.Global.TRANSITION_ANIMATION_SCALE == 0f` and on API 33+ also `AccessibilityManager.isReducedAnimationsEnabled`).

**Degrades to instant or cross-fade:**
- Artwork pause-scale spring → `snapTo(target)`.
- Scrubber thumb / track expansion → instant.
- Bottom-sheet entry → cross-fade.
- Queue row reorder spring-back → snap.
- Shared-element bounds morph → cross-fade.

**Stays:**
- Color/state changes (active/inactive icon).
- Progress bar fill advance — that's information, not motion.
- Drag-to-dismiss / predictive-back — user-driven motion isn't vestibular-triggering.

Pattern:

```kotlin
val reduceMotion = LocalReduceMotion.current
LaunchedEffect(isPlaying, reduceMotion) {
  val target = if (isPlaying) 1f else 0.85f
  if (reduceMotion) artworkScale.snapTo(target)
  else artworkScale.animateTo(target, spring(stiffness = 380f, dampingRatio = 0.78f))
}
```

---

## Q10 — Dynamic color vs. brand fallback

**Material You on Android 12+** via `dynamicDarkColorScheme(LocalContext.current)` (or `dynamicLightColorScheme` for light theme). On Android 11 and below, fall back to the brand palette (`#8B5CF6` as `primary`, `#D0BCFF` as `primary` in dark — see legacy mockup PALETTES).

Decision happens once at `MaterialTheme(...)` root in `Theme.kt`. Override toggle (Settings → Appearance → "Use wallpaper colors") writes to `DataStore` and re-themes.

**Anti-patterns.**
- ❌ Reading `Build.VERSION.SDK_INT` inside a composable repeatedly — pick the scheme once at theme construction.
- ❌ Mixing `colorScheme.primary` (dynamic) with hardcoded `#8B5CF6` (fallback) inside a single screen — defeats the dynamic-color contract.

---

## Q11 — Media3 / lock-screen / notification parity

**`MediaSession` (must mirror — see `media3-parity.md`):**
- Title, channel/artist, artwork — `MediaMetadata.artworkUri = https://i.ytimg.com/vi/$videoId/maxresdefault.jpg` (with `hqdefault.jpg` fallback as a follow-up; YT-0062a Q11 ships maxres-only).
- Playback rate, current position, duration — for lock-screen scrubber.
- Shuffle / repeat state — `Player.shuffleModeEnabled` / `Player.repeatMode`. `MediaSession.Callback.onSetShuffleMode` / `onSetRepeatMode` route through the `Player` automatically; don't override unless you need transformation.
- `setSessionActivity(nowPlayingPendingIntent())` — PendingIntent into `MainActivity` with action `OPEN_NOW_PLAYING`; `MainActivity.onNewIntent` reads it and signals `AppShell` to navigate.

**`MediaNotificationProvider`:**
- `DefaultMediaNotificationProvider.Builder(this).build()`.
- Compact: `[skip_prev, play_pause, skip_next]`.
- Expanded: adds `[shuffle, repeat]`. (Layout customization is partly `@UnstableApi` in current Media3 — YT-0062a Q11 ships the default layout and tracks this as a follow-up.)

Detail: `media3-parity.md`.

---

## Q12 — MiniPlayer↔NowPlaying transition

`SharedTransitionLayout` host at `AppShell` level (YT-0061). MiniPlayer artwork has `Modifier.sharedBounds(rememberSharedContentState(key = "art-${videoId}"), animatedVisibilityScope = …, boundsTransform = { _, _ -> tween(220, easing = FastOutSlowInEasing) })`. NowPlaying artwork mirrors with the matching key.

Title and channel use `sharedElement` (not bounds) so they fade through their own enter/exit instead of stretching.

**Reduce-motion path:** `boundsTransform = { _, _ -> snap() }` and `sizeTransform = SizeTransform(clip = false) { _, _ -> snap() }`.

---

## Mockup web-isms — translate to Compose

| Mockup CSS / pattern | Compose / M3 |
|---|---|
| `pal.primaryContainer` MiniPlayer bg | `MaterialTheme.colorScheme.primaryContainer` *only on the MiniPlayer*; NowPlaying body uses `surface`. |
| `pal.primaryContainer + '22'` row tint for active TrackRow | `colorScheme.primaryContainer.copy(alpha = 0.16f)`. |
| `box-shadow: 0 2px 8px rgba(0,0,0,0.3)` on MiniPlayer | Drop. M3 uses tonal elevation; raise via `Surface(tonalElevation = 3.dp)`. |
| `border-radius: 16` MiniPlayer | `MaterialTheme.shapes.medium` (12 dp) per design system, **not** 16 — the mockup had drift. |
| Hand-drawn play/pause SVG in MiniPlayer | `Icons.Rounded.PlayArrow` / `Icons.Rounded.Pause` — Material Symbols rounded. |
| `gap: 12px` Play/Shuffle | `Arrangement.spacedBy(12.dp)` on the parent `Row`. |
| `transition: width 1s linear` MiniPlayer progress | `LinearProgressIndicator(progress = { … })` (Compose 1.6+ lambda overload). |
| `transform: translateX(-72px)` swipe-peek | Native `SwipeToDismissBox` — don't simulate metrics in CSS-derived dp. |
| Cubic-bezier ease | `tween(durationMillis, easing = FastOutSlowInEasing)` for non-physical; `spring` for physical. |
