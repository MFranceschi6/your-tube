# YT-0015 — Compose Implementation Spec

> Companion to `decision-log.md`. Scoped to the Recently Played screen.

## Module / file layout

```
android/feature/history/
  src/main/kotlin/com/yourtube/feature/history/
    RecentlyPlayedScreen.kt          ← top app bar + day-grouped LazyColumn + dialog
    RecentlyPlayedViewModel.kt       ← entriesFlow (dedup'd), clearAll, removeEntry
    RecentlyPlayedUiState.kt         ← sealed; Loading / Empty / Content / Error
    HistoryRowSheet.kt               ← ModalBottomSheet with 5 actions (Q5)
    ClearHistoryDialog.kt            ← AlertDialog with confirm copy (Q6)
    HistoryDayGrouping.kt            ← pure: List<HistoryEntry> → List<Pair<DayKey, List<HistoryEntry>>>
    HistoryTimestampFormatter.kt     ← pure: formatHistoryTimestamp(now, playedAt)
    nav/HistoryNavigation.kt         ← navGraph route "history"; called from LibraryScreen
android/core/ui/
  src/main/kotlin/com/yourtube/core/ui/
    TrackRow.kt                      ← shared row (already exists; reused, NOT forked)
    EmptyState.kt / ErrorState.kt    ← shared (state-catalog)
    SkeletonTrackRow.kt              ← shared skeleton row (C12)
android/feature/library/
  src/main/kotlin/com/yourtube/feature/library/
    LibraryScreen.kt                 ← top-app-bar `history` action wired to navigate("history")  (per YT-0014 Q1)
```

`HistoryRepository` and `HistoryEntryEntity` already live under `core/data`; the dedup pipeline (Q3) runs at the repository boundary (per YT-0048).

## State — `RecentlyPlayedUiState`

```kotlin
sealed interface RecentlyPlayedUiState {
  data object Loading : RecentlyPlayedUiState
  data object Empty : RecentlyPlayedUiState
  data class Content(val entries: List<HistoryEntry>) : RecentlyPlayedUiState
  data class Error(val cause: Throwable) : RecentlyPlayedUiState
}
```

State-machine contract (state-catalog `README.md` § Status contract): exactly one of `Loading`, `Empty`, `Content`, `Error` at any time. The view model collapses the data flow to this sealed type — the screen never juggles `isLoading` flags against a populated list.

## View model

```kotlin
@HiltViewModel
class RecentlyPlayedViewModel @Inject constructor(
  private val repo: HistoryRepository,
  private val player: PlayerCoordinator,        // injected for context-menu actions
  private val clock: Clock,                     // for relative timestamp computation
) : ViewModel() {

  val uiState: StateFlow<RecentlyPlayedUiState> =
    repo.entriesFlow                           // already dedup'd by videoId (Q3)
      .map<List<HistoryEntry>, RecentlyPlayedUiState> { list ->
        if (list.isEmpty()) RecentlyPlayedUiState.Empty
        else RecentlyPlayedUiState.Content(list)
      }
      .catch { emit(RecentlyPlayedUiState.Error(it)) }
      .onStart { emit(RecentlyPlayedUiState.Loading) }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecentlyPlayedUiState.Loading)

  fun playFromHere(entry: HistoryEntry) { player.playNow(entry.track) }
  fun playNext(entry: HistoryEntry)      { player.enqueueNext(entry.track) }
  fun addToQueue(entry: HistoryEntry)    { player.enqueueLast(entry.track) }
  fun removeEntry(entry: HistoryEntry)   { viewModelScope.launch { repo.remove(entry.id) } }
  fun clearAll()                         { viewModelScope.launch { repo.clearAll() } }
  fun retry()                            { viewModelScope.launch { repo.refresh() } }
}
```

`repo.entriesFlow` already applies `distinctBy { it.videoId }` per YT-0048 (sorted by `playedAt` desc). The view model does NOT re-dedup. If it did, that would be a Q3 anti-pattern — the dedup pipeline lives at the repository boundary, single source.

## View hierarchy — `RecentlyPlayedScreen`

```
RecentlyPlayedScreen
└─ Scaffold(
     containerColor = colorScheme.surface,
     topBar = {
       LargeTopAppBar(
         title = { Text("Recently Played") },
         navigationIcon = { IconButton({ navController.popBackStack() }) {
           Icon(Icons.Rounded.ArrowBack, "Back")
         }},
         actions = {
           if (uiState is Content && uiState.entries.isNotEmpty()) {
             OverflowMenu(onClearAll = { showClearDialog = true })   // Q6
           }
         },
       )
     },
   )
   └─ Box(Modifier.padding(scaffoldPadding)) {
       when (uiState) {
         Loading       -> HistorySkeleton()                          // C12 — Q10
         Empty         -> EmptyState(cell = C13, onAction = ::goToSearch)   // Q7
         is Error      -> ErrorState(cell = C14, onRetry = vm::retry)       // Q10
         is Content    -> HistoryList(
           entries        = uiState.entries,
           clock          = clock,
           onTap          = vm::playFromHere,
           onLongPress    = { sheetTarget = it },
           onSwipeRemove  = vm::removeEntry,
         )
       }
     }
   └─ if (sheetTarget != null) HistoryRowSheet(
        entry            = sheetTarget!!,
        onPlayNext       = { vm.playNext(it); sheetTarget = null },
        onAddToQueue     = { vm.addToQueue(it); sheetTarget = null },
        onAddToPlaylist  = { navController.navigate("add-to-playlist/${it.videoId}"); sheetTarget = null },
        onRemove         = { vm.removeEntry(it); sheetTarget = null },
        onShare          = { sharer.share(it.track.shareUrl); sheetTarget = null },
        onDismiss        = { sheetTarget = null },
      )
   └─ if (showClearDialog) ClearHistoryDialog(
        onConfirm = { vm.clearAll(); showClearDialog = false },
        onDismiss = { showClearDialog = false },
      )
```

The Scaffold has no `snackbarHost` — per Q4 and Q6, this screen does not use snackbars.

## `HistoryList` — day-grouped LazyColumn (Q1)

```kotlin
@Composable
fun HistoryList(
  entries: List<HistoryEntry>,
  clock: Clock,
  onTap: (HistoryEntry) -> Unit,
  onLongPress: (HistoryEntry) -> Unit,
  onSwipeRemove: (HistoryEntry) -> Unit,
) {
  val mp = LocalMiniPlayerState.current
  val grouped: List<Pair<DayKey, List<HistoryEntry>>> = remember(entries) {
    groupByDay(entries, zone = ZoneId.systemDefault(), now = clock.instant())
  }

  LazyColumn(
    contentPadding = PaddingValues(bottom = mp.safeBottomInset),
    modifier = Modifier.fillMaxSize(),
  ) {
    grouped.forEach { (dayKey, dayEntries) ->
      stickyHeader(key = "header-$dayKey") {
        DayHeader(dayKey)
      }
      items(dayEntries, key = { it.id }) { entry ->
        SwipeableHistoryRow(
          entry         = entry,
          clock         = clock,
          onTap         = { onTap(entry) },
          onLongPress   = { onLongPress(entry) },
          onSwipeRemove = { onSwipeRemove(entry) },
        )
      }
    }
  }
}

@Composable
private fun DayHeader(key: DayKey) {
  Box(
    Modifier
      .fillMaxWidth()
      .background(colorScheme.surface)         // opaque — see Q1 anti-patterns
      .padding(horizontal = 16.dp, vertical = 8.dp)
      .semantics { heading() }                  // see haptics-and-a11y.md
  ) {
    Text(
      text  = key.label,
      style = typography.titleSmall,
      color = colorScheme.onSurfaceVariant,
    )
  }
}
```

`DayKey` is the sealed type returned by `groupByDay`:

```kotlin
sealed interface DayKey {
  val label: String
  data object Today : DayKey { override val label = "Today" }
  data object Yesterday : DayKey { override val label = "Yesterday" }
  data class Date(val date: LocalDate) : DayKey {
    override val label: String = date.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault()))
  }
}
```

`groupByDay` is pure, unit-tested in `feature/history/src/test/.../HistoryDayGroupingTest.kt`.

## `SwipeableHistoryRow` — Q4

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeableHistoryRow(
  entry: HistoryEntry,
  clock: Clock,
  onTap: () -> Unit,
  onLongPress: () -> Unit,
  onSwipeRemove: () -> Unit,
) {
  val haptics = LocalHapticFeedback.current
  val dismissState = rememberSwipeToDismissBoxState(
    confirmValueChange = { value ->
      if (value == SwipeToDismissBoxValue.EndToStart) {
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        onSwipeRemove()
        true
      } else false
    },
    positionalThreshold = { totalDistance -> totalDistance * 0.5f },   // ≥50% width
  )

  SwipeToDismissBox(
    state                          = dismissState,
    enableDismissFromStartToEnd    = false,                            // Q4: trailing only
    enableDismissFromEndToStart    = true,
    backgroundContent              = { SwipeBackground(dismissState) },
  ) {
    TrackRow(
      track             = entry.track,
      supportingExtra   = formatHistoryTimestamp(now = clock.instant(), playedAt = entry.playedAt),
      onClick           = onTap,
      onLongClick       = {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        onLongPress()
      },
    )
  }
}

@Composable
private fun SwipeBackground(state: SwipeToDismissBoxState) {
  val progress = state.progress
  Box(
    Modifier
      .fillMaxSize()
      .background(colorScheme.errorContainer),
    contentAlignment = Alignment.CenterEnd,
  ) {
    Icon(
      imageVector        = Icons.Rounded.Delete,
      contentDescription = null,                      // a11y handled by SwipeToDismissBox semantics
      tint               = colorScheme.onErrorContainer,
      modifier           = Modifier
        .padding(end = 24.dp)
        .scale(0.6f + 0.4f * progress.coerceIn(0f, 1f)),
    )
  }
}
```

`TrackRow` exposes a `supportingExtra: String?` slot that renders below the existing supportingContent line. The Library and Playlist Detail surfaces pass `null` (no third line); History passes the formatted timestamp.

## `HistoryRowSheet` — Q5

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryRowSheet(
  entry: HistoryEntry,
  onPlayNext: (HistoryEntry) -> Unit,
  onAddToQueue: (HistoryEntry) -> Unit,
  onAddToPlaylist: (HistoryEntry) -> Unit,
  onRemove: (HistoryEntry) -> Unit,
  onShare: (HistoryEntry) -> Unit,
  onDismiss: () -> Unit,
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
    Column {
      // Header: thumbnail + title + channel (no actions)
      ListItem(
        leadingContent  = { AsyncImage(entry.track.thumbnailUrl, /* 56 dp */) },
        headlineContent = { Text(entry.track.title) },
        supportingContent = { Text("${entry.track.channel} · ${formatDuration(entry.track.durationMs)}") },
      )
      HorizontalDivider()
      SheetRow(Icons.Rounded.PlaylistPlay,  "Play next")        { onPlayNext(entry) }
      SheetRow(Icons.Rounded.QueueMusic,    "Add to queue")     { onAddToQueue(entry) }
      SheetRow(Icons.Rounded.PlaylistAdd,   "Add to playlist")  { onAddToPlaylist(entry) }
      HorizontalDivider()
      SheetRow(Icons.Rounded.Delete,        "Remove from history",
        tint = colorScheme.error) { onRemove(entry) }
      HorizontalDivider()
      SheetRow(Icons.Rounded.Share,         "Share")            { onShare(entry) }
      Spacer(Modifier.height(8.dp))
    }
  }
}
```

## `ClearHistoryDialog` — Q6

```kotlin
@Composable
fun ClearHistoryDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title  = { Text("Clear watch history?") },
    text   = { Text("This permanently removes all entries from your history. Tracks themselves stay in your library.") },
    confirmButton = {
      TextButton(onClick = onConfirm) {
        Text("Clear", color = colorScheme.error)
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text("Cancel") }
    },
  )
}
```

## `HistorySkeleton` — Q10 / C12

```kotlin
@Composable
fun HistorySkeleton() {
  Column(Modifier.fillMaxSize()) {
    repeat(8) {
      SkeletonTrackRow(modifier = Modifier.padding(horizontal = 0.dp))
    }
  }
}
```

`SkeletonTrackRow` (shared `core/ui`) renders the row outline: 56 dp thumb placeholder + two text placeholders. Shimmer cycle 1400ms linear infinite. **No day headers in skeleton** — the date scale is unknown until data arrives.

## Empty (C13) / Error (C14) — Q7 / Q10

```kotlin
// In RecentlyPlayedScreen.kt's body when (uiState):
Empty -> EmptyState(
  icon  = Icons.Rounded.History,
  title = "Nothing played yet",
  body  = "Tracks you play will show up here.",
  primaryAction = ActionSpec("Browse search", onClick = { onSwitchTab(Tab.Search) }),
)
is Error -> ErrorState(
  icon  = Icons.Rounded.Error,
  title = "Couldn't load history",
  body  = "Try again in a moment.",
  primaryAction = ActionSpec("Try again", onClick = vm::retry),
)
```

`EmptyState` and `ErrorState` are the shared composables from `core/ui` per the state-catalog. Do **not** instantiate per-screen.

## Navigation — Q9

```kotlin
// nav/HistoryNavigation.kt
const val HistoryRoute = "history"

fun NavGraphBuilder.historyDestination(
  navController: NavController,
  onSwitchTab: (Tab) -> Unit,
) {
  composable(HistoryRoute) {
    RecentlyPlayedScreen(navController = navController, onSwitchTab = onSwitchTab)
  }
}

// In LibraryScreen.kt's TopAppBar(actions):
IconButton(onClick = {
  navController.navigate(HistoryRoute) {
    launchSingleTop = true       // Q9 single-instance
    restoreState = true
  }
}) {
  Icon(Icons.Rounded.History, contentDescription = "Recently played")
}
```

## MiniPlayer overlap — carry from YT-0014 Q4

```kotlin
val mp = LocalMiniPlayerState.current
LazyColumn(contentPadding = PaddingValues(bottom = mp.safeBottomInset)) { ... }
```

Do NOT apply `Modifier.padding(bottom = inset)` to the parent `Scaffold` content — that would shift any subsequent host (none here, but invariant kept for parity with Library) off-screen.

## `HistoryEntry` — already in `core/data`

```kotlin
data class HistoryEntry(
  val id: Long,
  val videoId: String,
  val track: Track,
  val playedAt: Instant,
)
```

The dedup-by-videoId rule (Q3) lives in `HistoryRepository.entriesFlow`. Pseudo-code:

```kotlin
override val entriesFlow: Flow<List<HistoryEntry>> =
  historyDao.observeAllDescending()
    .map { rows -> rows.distinctBy { it.videoId }.map { it.toHistoryEntry() } }
```

Do not move this dedup elsewhere. Three previous fixes (YT-0048 / YT-0154 / YT-0158) landed it here intentionally.

## Token mapping (mockup CSS → Compose)

| Mockup `pal.*` / CSS | Compose / M3 |
|---|---|
| `pal.primary` / `--primary` | `colorScheme.primary` |
| `pal.surface` / `--surface` | `colorScheme.surface` (body, sticky day header bg) |
| `pal.surfaceVariant` | `colorScheme.surfaceVariant` (skeleton blocks) |
| `pal.onSurfaceVariant` | `colorScheme.onSurfaceVariant` (timestamp text, day header label, empty/error icon tint) |
| `pal.error` | `colorScheme.error` (Clear button label, Remove sheet item) |
| `pal.errorContainer` | `colorScheme.errorContainer` (swipe-action background) |
| `--radius-md: 12px` | `MaterialTheme.shapes.medium = RoundedCornerShape(12.dp)` |
| `--radius-sm: 8px` | `RoundedCornerShape(8.dp)` (thumbnail) |
| Sticky header `position: sticky; top: 0` | `LazyColumn { stickyHeader { ... } }` |
| Skeleton shimmer `@keyframes` | shared `SkeletonTrackRow` composable from `core/ui` |
| Swipe peek `transform: translateX(-Xpx)` | `SwipeToDismissBox` (do NOT simulate metrics manually) |
| Confirm dialog modal | `AlertDialog` |
| Bottom-sheet contextual menu | `ModalBottomSheet` |

## What the mockup gets wrong (don't port)

1. **Flat list:** if the mockup shows a flat newest-first list, override with day-grouped sticky headers (Q1).
2. **No swipe affordance:** if rows render with no swipe peek demonstration, that's mockup omission — production uses `SwipeToDismissBox` end-to-start (Q4).
3. **"Clear all" inline text button:** if the mockup uses a top-bar `TextButton("Clear")`, replace with overflow `IconButton(MoreVert)` + `DropdownMenu` (Q6).
4. **Empty-state copy override:** if mockup shows custom empty copy, replace with state-catalog C13 verbatim (Q7).
5. **Snackbar on row swipe:** if mockup shows a "Removed · Undo" snackbar, drop it — Q4 explicitly rejects toast-undo.
6. **History as a 4th tab:** if mockup shows History in the NavBar, drop it — Q9 says destination only.
