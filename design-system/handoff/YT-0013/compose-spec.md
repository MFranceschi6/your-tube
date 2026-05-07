# YT-0013 — Compose Implementation Spec

> Companion to `decision-log.md`. This file is the technical contract — view hierarchy, animations, hit-targets, file layout. Open the legacy mockup side-by-side for layout reference.

## Module / file layout

```
android/feature/player/
  src/main/kotlin/com/yourtube/feature/player/
    PlayerViewModel.kt              ← play/pause/seek/shuffle/repeat ViewModel
    PlayerViewModelTest.kt          ← unit tests for the wrappers
android/core/ui/
  src/main/kotlin/com/yourtube/core/ui/
    NowPlayingScreen.kt             ← root composable
    NowPlayingArtwork.kt            ← square artwork, pause-scale Animatable
    NowPlayingScrubber.kt           ← Modifier.draggable + Canvas
    NowPlayingTransportRow.kt       ← shuffle / prev / play / next / repeat
    NowPlayingActionRow.kt          ← Cast / queue / share / playlist_add
    NowPlayingQueueSheet.kt         ← ModalBottomSheet + reorder + swipe-remove
    CastRouteButton.kt              ← AndroidView wrapping MediaRouteButton
    ReduceMotion.kt                 ← LocalReduceMotion CompositionLocal
android/core/player/
  src/main/kotlin/com/yourtube/core/player/
    PlaybackService.kt              ← MediaSession + setSessionActivity
    DefaultPlayerController.kt      ← shuffle/repeat surface (YT-0062a Q11)
    PlaybackTransport.kt            ← shuffle/repeat transport
android/app/
  src/main/kotlin/com/yourtube/app/
    MainActivity.kt                 ← onNewIntent reads OPEN_NOW_PLAYING
    ui/AppShell.kt                  ← SharedTransitionLayout host + NowPlaying nav destination
    ui/theme/Theme.kt               ← dynamicDarkColorScheme + LocalReduceMotion provider
```

## View hierarchy (top-down)

```
AppShell  (NavHost root, owns SharedTransitionLayout)
└─ NavHost
   ├─ composable("home") { … MiniPlayer with sharedBounds("art-$videoId") … }
   └─ composable(AppRoute.NowPlaying.route) {
        NowPlayingScreen(animatedVisibilityScope = this@composable)
      }

NowPlayingScreen
├─ Scaffold (containerColor = colorScheme.surface)
│  ├─ topBar: SmallTopAppBar
│  │   ├─ navigationIcon: dismiss (chevron_down, 24 sp glyph, 48 dp hit)
│  │   ├─ title: "FROM QUEUE · {source}" (labelMedium, onSurfaceVariant)
│  │   └─ actions: overflow Menu (add to playlist, share, etc)
│  └─ content: Column (16.dp horizontal padding)
│     ├─ NowPlayingArtwork (sharedBounds("art-$videoId"))
│     ├─ TitleBlock
│     │   ├─ Text(track.title, titleLarge, maxLines = 2, basicMarquee on overflow)
│     │   └─ Text(track.channel, bodyMedium, onSurfaceVariant)
│     ├─ NowPlayingScrubber
│     ├─ NowPlayingTransportRow
│     ├─ Spacer(8.dp)
│     ├─ VolumeRow (custom AudioManager-backed slider; system handles HUD)
│     └─ NowPlayingActionRow
└─ if (showQueue) NowPlayingQueueSheet(...)
```

## Presentation (Q1) — `SharedTransitionLayout` + nav destination

```kotlin
// AppShell.kt
SharedTransitionLayout {
  val navController = rememberNavController()
  NavHost(navController, startDestination = AppRoute.Home.route) {
    composable(AppRoute.Home.route) {
      HomeScreen(
        sharedTransitionScope = this@SharedTransitionLayout,
        animatedVisibilityScope = this,
        onOpenNowPlaying = { navController.navigate(AppRoute.NowPlaying.route) }
      )
    }
    composable(AppRoute.NowPlaying.route) {
      NowPlayingScreen(
        sharedTransitionScope = this@SharedTransitionLayout,
        animatedVisibilityScope = this
      )
    }
  }
}

// MiniPlayer artwork:
Modifier
  .sharedBounds(
    rememberSharedContentState(key = "art-${track.videoId}"),
    animatedVisibilityScope = animatedVisibilityScope,
    boundsTransform = { _, _ ->
      if (LocalReduceMotion.current) snap()
      else tween(220, easing = FastOutSlowInEasing)
    }
  )

// NowPlaying artwork: same key, same animatedVisibilityScope.
```

Predictive-back is free with the nav destination on Android 13+.

## Artwork (Q2)

```kotlin
@Composable
fun NowPlayingArtwork(
  url: String?,
  isPlaying: Boolean,
  isScrubbing: Boolean,
  modifier: Modifier = Modifier,
) {
  val reduceMotion = LocalReduceMotion.current
  val target = when {
    isScrubbing -> 0.92f
    isPlaying -> 1f
    else -> 0.85f
  }
  val scale = remember { Animatable(target) }
  LaunchedEffect(target, reduceMotion) {
    if (reduceMotion) scale.snapTo(target)
    else scale.animateTo(target, spring(stiffness = 380f, dampingRatio = 0.78f))
  }
  val radiusDp = lerp(20.dp, 12.dp, ((scale.value - 0.85f) / 0.15f).coerceIn(0f, 1f))

  AsyncImage(
    model = url,
    contentDescription = null, // set on parent — see haptics-and-a11y.md
    modifier = modifier
      .aspectRatio(1f)
      .graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
        transformOrigin = TransformOrigin.Center
      }
      .clip(RoundedCornerShape(radiusDp))
      .background(colorScheme.surfaceVariant),
    contentScale = ContentScale.Crop,
  )
}
```

## Scrubber (Q4)

```kotlin
@Composable
fun NowPlayingScrubber(
  positionMs: Long,
  durationMs: Long,
  onSeek: (Long) -> Unit,
  modifier: Modifier = Modifier,
) {
  var isDragging by remember { mutableStateOf(false) }
  var previewMs by remember(positionMs, durationMs) { mutableLongStateOf(positionMs) }
  val haptics = LocalHapticFeedback.current
  val reduceMotion = LocalReduceMotion.current
  val tickKey = (previewMs / 10_000L).toInt()

  LaunchedEffect(tickKey, isDragging) {
    if (isDragging) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
  }

  Column(modifier) {
    BoxWithConstraints(Modifier.fillMaxWidth().height(28.dp)) {
      val widthPx = constraints.maxWidth.toFloat()
      val trackHeight by animateDpAsState(if (isDragging) 8.dp else 4.dp, tween(180), label = "track")
      val thumbSize by animateDpAsState(if (isDragging) 22.dp else 16.dp, tween(180), label = "thumb")

      Canvas(
        Modifier
          .fillMaxWidth()
          .height(28.dp)
          .draggable(
            orientation = Orientation.Horizontal,
            state = rememberDraggableState { delta ->
              val deltaMs = (delta / widthPx) * durationMs
              previewMs = (previewMs + deltaMs.toLong()).coerceIn(0L, durationMs)
            },
            onDragStarted = {
              isDragging = true
              previewMs = positionMs
            },
            onDragStopped = {
              onSeek(previewMs)             // ← seek fires HERE only
              haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
              isDragging = false
            },
          )
      ) {
        // draw track + filled portion + thumb at previewMs / durationMs
      }
    }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      Text(
        formatMs(previewMs),
        style = if (isDragging) typography.bodyMedium else typography.bodySmall,
        color = if (isDragging) colorScheme.onSurface else colorScheme.onSurfaceVariant,
        fontFeatureSettings = "tnum",
      )
      Text(
        "-${formatMs((durationMs - previewMs).coerceAtLeast(0L))}",
        style = if (isDragging) typography.bodyMedium else typography.bodySmall,
        color = if (isDragging) colorScheme.onSurface else colorScheme.onSurfaceVariant,
        fontFeatureSettings = "tnum",
      )
    }
  }
}
```

The artwork shrinks during scrub via the parent passing `isScrubbing` into `NowPlayingArtwork`.

## Transport row (Q5)

```kotlin
Row(
  Modifier.fillMaxWidth().padding(horizontal = 8.dp),
  horizontalArrangement = Arrangement.SpaceBetween,
  verticalAlignment = Alignment.CenterVertically,
) {
  TransportIcon(
    icon = Icons.Rounded.Shuffle, contentDescription = "Shuffle",
    isOn = shuffleOn,
    onClick = { vm.setShuffleMode(!shuffleOn) },
    glyph = 24.sp, hit = 48.dp,
  )
  TransportIcon(Icons.Rounded.SkipPrevious, "Previous track", glyph = 32.sp, hit = 56.dp, onClick = vm::skipPrevious)

  FilledIconButton(
    onClick = { if (isPlaying) vm.pause() else vm.resume() },
    modifier = Modifier.size(72.dp),
    shape = CircleShape,
    colors = IconButtonDefaults.filledIconButtonColors(
      containerColor = colorScheme.onSurface,
      contentColor = colorScheme.surface,
    ),
  ) {
    Crossfade(targetState = isPlaying, animationSpec = tween(140), label = "play-pause") { playing ->
      Icon(
        if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
        contentDescription = if (playing) "Pause" else "Play",
        modifier = Modifier.size(36.dp),
      )
    }
  }

  TransportIcon(Icons.Rounded.SkipNext, "Next track", glyph = 32.sp, hit = 56.dp, onClick = vm::skipNext)
  TransportIcon(
    icon = Icons.Rounded.Repeat, contentDescription = "Repeat",
    isOn = repeatMode != Player.REPEAT_MODE_OFF,
    onClick = { vm.setRepeatMode(nextRepeatMode(repeatMode)) },
    glyph = 24.sp, hit = 48.dp,
  )
}
```

`TransportIcon` is a thin wrapper around `IconButton` that decouples glyph from hit area:

```kotlin
@Composable
fun TransportIcon(
  icon: ImageVector, contentDescription: String,
  glyph: TextUnit, hit: Dp,
  isOn: Boolean = false,
  onClick: () -> Unit,
) {
  IconButton(onClick = onClick, modifier = Modifier.size(hit)) {
    Icon(icon, contentDescription, modifier = Modifier.size(with(LocalDensity.current) { glyph.toDp() }),
      tint = if (isOn) colorScheme.primary else colorScheme.onSurfaceVariant)
  }
}
```

## Queue sheet (Q6)

```kotlin
val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

if (showQueue) {
  ModalBottomSheet(
    onDismissRequest = { showQueue = false },
    sheetState = sheetState,
    containerColor = colorScheme.surfaceContainer,
    dragHandle = { BottomSheetDefaults.DragHandle() },
  ) {
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 600.dp)) {
      items(queue, key = { it.queueId }) { item ->
        SwipeToDismissBox(
          state = rememberSwipeToDismissBoxState(
            confirmValueChange = { value ->
              if (value == SwipeToDismissBoxValue.EndToStart) {
                vm.removeFromQueue(item.queueId)
                haptics.performHapticFeedback(HapticFeedbackType.Reject)
                true
              } else false
            }
          ),
          enableDismissFromStartToEnd = false,
          enableDismissFromEndToStart = true,
          backgroundContent = { DeleteBackground() },
        ) {
          QueueRow(
            track = item.track,
            modifier = Modifier
              .pointerInput(item.queueId) {
                detectDragGesturesAfterLongPress(
                  onDragStart = { /* haptic LongPress, bind dragged key */ },
                  onDrag = { /* update offset */ },
                  onDragEnd = { /* commit reorder, haptic GestureEnd */ },
                  onDragCancel = { /* reset */ },
                )
              }
          )
        }
      }
    }
  }
}
```

No `Reorderable` library. Hand-rolled `detectDragGesturesAfterLongPress` per `decision-log.md` §6.

## Action row (Q7)

```kotlin
Row(
  Modifier.fillMaxWidth().padding(horizontal = 24.dp),
  horizontalArrangement = Arrangement.SpaceAround,
) {
  CastRouteButton(modifier = Modifier.size(48.dp))
  IconButton(onClick = { showQueue = true }) {
    Icon(Icons.Rounded.QueueMusic, contentDescription = "Queue")
  }
  IconButton(onClick = vm::shareCurrent) {
    Icon(Icons.Rounded.Share, contentDescription = "Share")
  }
  IconButton(onClick = { showAddToPlaylist = true }) {
    Icon(Icons.Rounded.PlaylistAdd, contentDescription = "Add to playlist")
  }
}

@Composable
fun CastRouteButton(modifier: Modifier = Modifier) {
  AndroidView(
    factory = { ctx ->
      MediaRouteButton(ctx).also { CastButtonFactory.setUpMediaRouteButton(ctx, it) }
    },
    modifier = modifier,
  )
}
```

## Token mapping (mockup CSS → Compose)

| `colors_and_type.css` / mockup `pal.*` | Compose / M3 |
|---|---|
| `pal.primary` | `MaterialTheme.colorScheme.primary` (dynamic on API 31+; `#D0BCFF` brand fallback) |
| `pal.primaryContainer` | `colorScheme.primaryContainer` (MiniPlayer bg only) |
| `pal.surface` | `colorScheme.surface` (NowPlaying body) |
| `pal.bg` | `colorScheme.background` — **avoid** for the player surface; audit caught `surface` is the correct choice (Q3) |
| `pal.surfaceVariant` | `colorScheme.surfaceVariant` (skeleton rows, artwork placeholder) |
| `pal.onSurface` | `colorScheme.onSurface` |
| `pal.onSurfaceVariant` | `colorScheme.onSurfaceVariant` |
| `--radius-md: 12px` | `RoundedCornerShape(12.dp)` = `MaterialTheme.shapes.medium` |
| `--font-sans` (Roboto) | M3 default Roboto via `Typography` — don't import Geist on Android |
| `font-variant-numeric: tabular-nums` | `Text(..., fontFeatureSettings = "tnum")` |

## What the existing mockup gets wrong (on purpose, for layout reference only)

The combined HTML mockup at `design-system/mockups/android/YT-0011-0012-0013-shell-search-player.html` is the *legacy* artifact. The production Now Playing screen differs:

1. **Background:** mockup uses `pal.bg` (`#141218` purple) — production uses `colorScheme.surface` (Q3).
2. **Scrubber thumb:** mockup uses `pal.primary` purple — production uses **white** for v1.1 wallpaper-tint contrast (Q4).
3. **Play button:** mockup uses 64 dp `pal.primary` circle — production uses **72 dp `FilledIconButton` with `onSurface` container / `surface` content** (Q5).
4. **Queue:** mockup is collapsible inline panel — production is `ModalBottomSheet` with hand-rolled reorder (Q6).
5. **Cast:** missing in mockup — production puts `MediaRouteButton` as the leftmost icon in the action row (Q7).
6. **MiniPlayer ↔ NowPlaying:** mockup has no transition — production uses `SharedTransitionLayout` + `sharedBounds` keyed by `videoId` (Q12).

Don't port the mockup styling to Compose 1:1. Use it for layout reference only. The Q decisions above are the new source of truth.
