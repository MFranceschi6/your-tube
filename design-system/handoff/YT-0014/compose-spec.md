# YT-0014 — Compose Implementation Spec (v2 / MVP)

> Companion to `decision-log.md`. Scoped to the Playlists-only MVP. Two screens: `LibraryScreen` and `PlaylistDetailScreen`.

## Module / file layout

```
android/feature/library/
  src/main/kotlin/com/yourtube/feature/library/
    LibraryScreen.kt                  ← top app bar + playlists list + Extended FAB
    LibraryViewModel.kt               ← playlistsFlow, createPlaylist, deletePlaylist
    PlaylistRow.kt                    ← ListItem with PlaylistCover leading
    PlaylistCover.kt                  ← 4-up grid + <4 fallbacks (Q2)
    NewPlaylistDialog.kt              ← AlertDialog with name field
    detail/
      PlaylistDetailScreen.kt         ← cover + header + tracks + edit mode
      PlaylistDetailViewModel.kt      ← tracksFlow, reorder, queueRemoval, undoRemoval
      TrackRow.kt                     ← read-only + edit slots (drag-handle, remove)
      LongPressDraggable.kt           ← hand-rolled list reorder (no third-party)
      RemovalSnackbar.kt              ← undo coalescing (Q3)
android/core/ui/
  src/main/kotlin/com/yourtube/core/ui/
    MiniPlayerInset.kt                ← LocalMiniPlayerState provider (Q4)
android/app/
  src/main/kotlin/com/yourtube/app/ui/
    AppShell.kt                       ← provides LocalMiniPlayerState
```

## View hierarchy — `LibraryScreen`

```
LibraryScreen
└─ Scaffold(
     containerColor = colorScheme.surface,
     topBar = { LargeTopAppBar(title = { Text("Library") }, actions = { HistoryAction(); SettingsAction() }) },
     snackbarHost = { SnackbarHost(snackbarHostState) },
     floatingActionButton = { NewPlaylistFab() },
     floatingActionButtonPosition = FabPosition.End,
   )
   ├─ if (playlists.isEmpty()) LibraryEmptyState()
   └─ else LazyColumn(
        contentPadding = PaddingValues(
          top = 8.dp,
          bottom = LocalMiniPlayerState.current.safeBottomInset,
        )
      ) {
        items(playlists, key = { it.id }) { p ->
          PlaylistRow(
            playlist = p,
            onClick = { navController.navigate("playlist/${p.id}") },
            onLongPress = { sheetTarget = p },
          )
        }
      }
   └─ if (sheetTarget != null) PlaylistContextSheet(sheetTarget!!, onDismiss = ...)
   └─ if (showNewPlaylistDialog) NewPlaylistDialog(onCreate = ..., onDismiss = ...)
```

### `NewPlaylistFab`

```kotlin
@Composable
fun NewPlaylistFab(onClick: () -> Unit) {
  val mp = LocalMiniPlayerState.current
  val NavBarHeight = 80.dp
  ExtendedFloatingActionButton(
    onClick = onClick,
    icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
    text = { Text("New playlist") },
    expanded = true,                   // Q5: do not collapse on scroll in MVP
    modifier = Modifier.padding(
      end = 16.dp,
      bottom = (mp.safeBottomInset - NavBarHeight + 16.dp).coerceAtLeast(16.dp),
    ),
  )
}
```

When MiniPlayer hidden: `safeBottomInset = 88.dp` → bottom = `88 - 80 + 16 = 24.dp` (i.e. 16 dp above NavBar with the standard 8 dp gap baked in).
When MiniPlayer visible: `safeBottomInset = 152.dp` → bottom = `152 - 80 + 16 = 88.dp` (i.e. 16 dp above MiniPlayer).

## `PlaylistCover` — 4-up + fallbacks (Q2)

```kotlin
@Composable
fun PlaylistCover(
  trackThumbs: List<String?>,    // already loaded; first 4 only
  playlistName: String,
  modifier: Modifier = Modifier,
) {
  val n = trackThumbs.size.coerceIn(0, 4)
  val cd = if (n == 0) "$playlistName, no tracks yet"
           else "$playlistName, cover from first $n tracks"

  Box(
    modifier
      .clip(RoundedCornerShape(12.dp))
      .background(colorScheme.surfaceContainerHighest)
      .semantics { contentDescription = cd }
  ) {
    when (n) {
      0 -> Icon(
        Icons.Rounded.QueueMusic,
        contentDescription = null,
        modifier = Modifier.size(32.dp).align(Alignment.Center),
        tint = colorScheme.onSurfaceVariant,
      )
      1 -> Tile(trackThumbs[0], Modifier.fillMaxSize())
      2 -> Row(Modifier.fillMaxSize()) {
        Tile(trackThumbs[0], Modifier.weight(1f).fillMaxHeight())
        Spacer(Modifier.width(2.dp).fillMaxHeight().background(colorScheme.surface))
        Tile(trackThumbs[1], Modifier.weight(1f).fillMaxHeight())
      }
      3 -> Column(Modifier.fillMaxSize()) {
        Tile(trackThumbs[0], Modifier.weight(1f).fillMaxWidth())
        Spacer(Modifier.height(2.dp).fillMaxWidth().background(colorScheme.surface))
        Row(Modifier.weight(1f)) {
          Tile(trackThumbs[1], Modifier.weight(1f).fillMaxHeight())
          Spacer(Modifier.width(2.dp).fillMaxHeight().background(colorScheme.surface))
          Tile(trackThumbs[2], Modifier.weight(1f).fillMaxHeight())
        }
      }
      else -> Column(Modifier.fillMaxSize()) {
        Row(Modifier.weight(1f)) {
          Tile(trackThumbs[0], Modifier.weight(1f).fillMaxHeight())
          Spacer(Modifier.width(2.dp).fillMaxHeight().background(colorScheme.surface))
          Tile(trackThumbs[1], Modifier.weight(1f).fillMaxHeight())
        }
        Spacer(Modifier.height(2.dp).fillMaxWidth().background(colorScheme.surface))
        Row(Modifier.weight(1f)) {
          Tile(trackThumbs[2], Modifier.weight(1f).fillMaxHeight())
          Spacer(Modifier.width(2.dp).fillMaxHeight().background(colorScheme.surface))
          Tile(trackThumbs[3], Modifier.weight(1f).fillMaxHeight())
        }
      }
    }
  }
}

@Composable
private fun Tile(url: String?, modifier: Modifier) {
  AsyncImage(
    model = url,
    contentDescription = null,
    contentScale = ContentScale.Crop,
    placeholder = ColorPainter(colorScheme.surfaceVariant),
    modifier = modifier,
  )
}
```

`PlaylistRow` consumes `PlaylistCover` at 56 dp:

```kotlin
ListItem(
  leadingContent = { PlaylistCover(p.firstFourThumbs, p.name, Modifier.size(56.dp)) },
  headlineContent = { Text(p.name, style = typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis) },
  supportingContent = { Text("${p.trackCount} tracks", style = typography.bodyMedium, color = colorScheme.onSurfaceVariant) },
  trailingContent = { IconButton(onClick = onOverflow) { Icon(Icons.Rounded.MoreVert, "More") } },
  modifier = Modifier.pointerInput(p.id) {
    detectTapGestures(onTap = { onClick() }, onLongPress = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onLongPress() })
  },
)
```

## View hierarchy — `PlaylistDetailScreen`

```
PlaylistDetailScreen
└─ Scaffold(
     topBar = {
       LargeTopAppBar(
         title = { Text(playlist.name) },
         navigationIcon = { IconButton({ navController.popBackStack() }) { Icon(Icons.Rounded.ArrowBack, "Back") } },
         actions = {
           if (isEditing) TextButton({ isEditing = false }) { Text("Done") }
           else TextButton({ isEditing = true }) { Text("Edit") }
           OverflowMenu(...)
         },
       )
     },
     snackbarHost = { SnackbarHost(snackbarHostState) },
   )
   └─ LazyColumn(
        contentPadding = PaddingValues(bottom = LocalMiniPlayerState.current.safeBottomInset)
      ) {
        item("cover") { PlaylistCover(p.firstFourThumbs, p.name, Modifier.size(240.dp).align(...)) }
        item("header") { PlaylistHeader(playlist) }
        item("actions") { if (!isEditing) PlayShuffleRow(...) }
        items(tracks, key = { it.queueId }) { track ->
          TrackRow(
            track = track,
            isEditing = isEditing,
            onClick = { vm.playFromHere(track) },
            onRemove = { vm.queueRemoval(track) },
            reorderState = reorderState,
          )
        }
      }
```

`reorderState` is a hand-rolled `LongPressDraggable` host (`LongPressDraggable.kt`). When `isEditing == false`, the host is a no-op pass-through; when `true`, it captures long-press on each row and drives the y-offset animation.

## `TrackRow` — read-only + edit slots

```kotlin
@Composable
fun TrackRow(
  track: TrackItem,
  isEditing: Boolean,
  onClick: () -> Unit,
  onRemove: () -> Unit,
  reorderState: ReorderState,
) {
  val haptics = LocalHapticFeedback.current
  val rowOffset = reorderState.offsetFor(track.queueId)

  ListItem(
    modifier = Modifier
      .graphicsLayer { translationY = rowOffset.value }
      .pointerInput(track.queueId, isEditing) {
        if (isEditing) detectDragGesturesAfterLongPress(
          onDragStart = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); reorderState.start(track.queueId) },
          onDrag = { _, drag -> reorderState.onDrag(drag.y) },
          onDragEnd = { haptics.performHapticFeedback(HapticFeedbackType.GestureEnd); reorderState.commit() },
          onDragCancel = { reorderState.cancel() },
        ) else detectTapGestures(onTap = { onClick() })
      },
    leadingContent = {
      if (isEditing) Icon(
        Icons.Rounded.DragHandle,
        contentDescription = "Move ${track.title}, double-tap to enter reorder",
        modifier = Modifier.size(48.dp).padding(12.dp),    // 24 dp glyph in 48 dp hit area
        tint = colorScheme.onSurfaceVariant,
      ) else AsyncImage(model = track.thumbnailUrl, /* … 56 dp square */)
    },
    headlineContent = { Text(track.title, style = typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis) },
    supportingContent = { Text("${track.channel} · ${formatDuration(track.durationMs)}", style = typography.bodyMedium, color = colorScheme.onSurfaceVariant) },
    trailingContent = if (isEditing) ({
      IconButton(onClick = onRemove, modifier = Modifier.size(48.dp)) {
        Icon(Icons.Rounded.RemoveCircle, contentDescription = "Remove ${track.title}", tint = colorScheme.error)
      }
    }) else null,
  )
}
```

## Removal flow — toast with undo (Q3)

```kotlin
// In PlaylistDetailViewModel:
private val pendingRemovals = mutableListOf<Removal>()    // {trackId, oldPosition}

fun queueRemoval(track: TrackItem) {
  val oldPos = tracks.indexOf(track)
  pendingRemovals += Removal(track.id, oldPos, track)
  _tracks.update { it.filterNot { t -> t.id == track.id } }
  _removalEvents.tryEmit(RemovalEvent(pendingRemovals.size))
}

fun undoLastBatch() {
  pendingRemovals.sortedBy { it.oldPosition }.forEach { r ->
    _tracks.update { current -> current.toMutableList().apply { add(r.oldPosition.coerceAtMost(size), r.track) } }
  }
  pendingRemovals.clear()
}

fun commitPendingRemovals() {
  viewModelScope.launch { repo.removeTracks(playlistId, pendingRemovals.map { it.trackId }) }
  pendingRemovals.clear()
}

// In PlaylistDetailScreen:
LaunchedEffect(Unit) {
  vm.removalEvents.collect { event ->
    val msg = if (event.batchSize == 1) "Removed track" else "Removed ${event.batchSize} tracks"
    val result = snackbarHostState.showSnackbar(message = msg, actionLabel = "Undo", duration = SnackbarDuration.Long)
    when (result) {
      SnackbarResult.ActionPerformed -> vm.undoLastBatch()
      SnackbarResult.Dismissed -> vm.commitPendingRemovals()
    }
  }
}
```

`SnackbarDuration.Long` ≈ 10 s — covers the 6-second target on devices with longer accessibility timeouts. M3's `SnackbarHost` handles coalescing of consecutive snackbars (newer replaces older); the message format collapses the batch size automatically.

## Whole-playlist delete (Q8)

```kotlin
if (showDeleteConfirm) AlertDialog(
  onDismissRequest = { showDeleteConfirm = false },
  title = { Text("Delete playlist?") },
  text = { Text("This permanently removes \"${playlist.name}\". The tracks themselves stay in your library.") },
  confirmButton = {
    TextButton(onClick = { vm.deletePlaylist(); showDeleteConfirm = false; navController.popBackStack() }) {
      Text("Delete", color = colorScheme.error)
    }
  },
  dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") } },
)
```

## `LocalMiniPlayerState` (Q4)

```kotlin
// core/ui/MiniPlayerInset.kt
data class MiniPlayerState(val isVisible: Boolean, val safeBottomInset: Dp)

val LocalMiniPlayerState = compositionLocalOf<MiniPlayerState> {
  error("LocalMiniPlayerState not provided. Wrap your tree in AppShell.")
}

@Composable
fun ProvideMiniPlayerState(currentTrack: TrackItem?, content: @Composable () -> Unit) {
  val state = remember(currentTrack) {
    val visible = currentTrack != null
    val inset = if (visible) 152.dp else 88.dp
    MiniPlayerState(visible, inset)
  }
  CompositionLocalProvider(LocalMiniPlayerState provides state, content = content)
}

// AppShell.kt:
ProvideMiniPlayerState(currentTrack = playerState.currentTrack) {
  NavHost(...)
}
```

## Token mapping (mockup CSS → Compose)

| Mockup `pal.*` / CSS | Compose / M3 |
|---|---|
| `pal.primary` | `colorScheme.primary` |
| `pal.primaryContainer` | `colorScheme.primaryContainer` (FAB container) |
| `pal.surface` | `colorScheme.surface` (body) |
| `pal.surfaceVariant` | `colorScheme.surfaceVariant` (placeholder tiles) |
| `pal.surfaceContainerHighest` | same in Compose (0-track cover background) |
| `pal.error` | `colorScheme.error` (remove icon, delete confirm button) |
| `--radius-md: 12px` | `MaterialTheme.shapes.medium = RoundedCornerShape(12.dp)` |
| `--radius-sm: 8px` | `RoundedCornerShape(8.dp)` |
| 4-photo collage CSS grid | `PlaylistCover` composable (Q2) |
| FAB pill | `ExtendedFloatingActionButton` |
| Drag-handle CSS `cursor: grab` | n/a — use haptic + visual offset |
| Snackbar with action | `SnackbarHost` + `SnackbarHostState.showSnackbar(actionLabel = "Undo")` |

## What the mockup gets wrong (don't port)

1. **Tabs:** any `SecondaryTabRow` in the mockup is post-MVP — drop it (decision Q1).
2. **Empty playlist cover:** if mockup shows single-color tile + glyph for non-empty playlists, that's the v1 reversal — production uses 4-up (Q2). Single-color tile is 0-track only.
3. **No Edit affordance:** mockup likely lacks the Edit toggle and drag-handle/remove slots — production requires them in `PlaylistDetailScreen` (Q3).
4. **No FAB or wrong FAB:** mockup may have plain FAB or no FAB — production requires `ExtendedFloatingActionButton` with the "New playlist" label (Q5).
5. **Bottom padding:** any hardcoded `padding-bottom: 64px` style hack must be replaced by `safeBottomInset` from `LocalMiniPlayerState` (Q4).
6. **Inline destructive on tap:** any mockup that removes a track without undo is wrong — production toast-with-undo (Q3) or AlertDialog for whole-playlist (Q8).
