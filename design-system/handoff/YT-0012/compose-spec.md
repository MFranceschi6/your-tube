# YT-0012 — Compose Implementation Spec

> Companion to `decision-log.md`. This file is the technical contract — view hierarchy, state shape, animation specs, hit-targets, file layout. Open `mockup.html` and `mockup-states.html` side-by-side.

## Module / file layout

```
android/feature/search/
  src/main/kotlin/com/yourtube/feature/search/
    SearchScreen.kt                ← root composable, wires VM + SearchBar + body switch
    SearchViewModel.kt             ← state holder, debounce, history MRU
    SearchUiState.kt               ← data class + sealed substates
    SearchFilters.kt               ← (already shipped) filter logic — out of scope for this handoff
    SuggestionChipRow.kt           ← LazyRow of recent + curated chips
    SearchResultRow.kt             ← TrackRow reuse + EQ indicator overlay
    SearchEqIndicator.kt           ← 3-bar infinite-transition composable
    SearchHistoryDao.kt            ← Room DAO for the 20-entry MRU
android/core/ui/
  src/main/kotlin/com/yourtube/core/ui/
    TrackRow.kt                    ← reused from NowPlaying / Library (existing)
    EmptyState.kt                  ← state-catalog C2 / C3 / C7 / C10 / C13 host
    ErrorState.kt                  ← state-catalog C4 / C5 / C8 / C11 / C14 host
    SkeletonTrackRow.kt            ← state-catalog C1 / C6 / C12 host
android/core/data/
  src/main/kotlin/com/yourtube/core/data/search/
    SearchRepository.kt            ← debounce-cancelable suggest + results, 30s in-memory cache
    NetworkMonitor.kt              ← isOnline StateFlow consumed by SearchViewModel
```

## State shape

```kotlin
data class SearchUiState(
  val query: String = "",
  val recentSuggestions: List<String> = emptyList(),     // up to 6, MRU; from SearchHistoryDao
  val curatedFallback: List<String> = listOf("lofi", "focus", "ambient", "podcasts"),
  val body: Body = Body.Idle,
) {
  sealed interface Body {
    data object Idle : Body                              // C2 — chips visible
    data object Loading : Body                           // C1 — skeleton ×6
    data class Results(val items: List<SearchResult>) : Body
    data object NoResults : Body                         // C3
    data class Error(val cause: Cause) : Body            // C4 (cause = Generic) or C5 (cause = Offline)
  }
  enum class Cause { Generic, Offline }

  /** Chips shown above the body — recent first, then curated to fill up to 6. */
  val visibleSuggestions: List<Chip> by lazy {
    val recents = recentSuggestions.take(6).map { Chip(it, isRecent = true) }
    val needed = 6 - recents.size
    val curated = if (needed > 0) curatedFallback.take(needed).map { Chip(it, isRecent = false) } else emptyList()
    recents + curated
  }
  data class Chip(val text: String, val isRecent: Boolean)
}
```

## State machine — SearchBar / body

```
                                 (query.isBlank() && body == Idle)
                                              ↑
   tab activated  ──►  Idle ───── focus, chips visible ─────►  Idle (focused, keyboard open)
                          │
                          │  user types ≥ 1 char
                          ▼
                       Loading  ─── results arrive ──►  Results
                          │                                 │
                          │                                 │ user clears (D9)
                          │                                 ▼
                          │                              Idle
                          ├── 0 matches ──►  NoResults  ── chip tap (D10) / clear ──► Idle / Loading
                          ├── HTTP fail + online ──►  Error(Generic)
                          └── HTTP fail + offline ──►  Error(Offline)

                       Any error / no-results → tap "Try again" → Loading (re-run the same query)
```

The `SearchBar` itself has a smaller, focus-driven state machine:

```
unfocused (blank)  ── tap ──►  focused (blank, keyboard open, chips visible)
focused (blank)    ── type ──►  focused (non-blank, clear glyph visible)
focused (non-blank) ── tap clear (D9) ──►  focused (blank)  [keyboard stays open]
focused (any)      ── system back ──►  unfocused (query preserved iff results visible)
```

## View hierarchy (top-down)

```
SearchScreen(navController, vm: SearchViewModel = hiltViewModel())
└─ Scaffold(containerColor = colorScheme.background)
   ├─ topBar = LargeTopAppBar
   │  └─ title = SearchBar(
   │       query = uiState.query,
   │       onQueryChange = vm::setQuery,
   │       onSearch = vm::submit,                       // IME action bypasses debounce (D2)
   │       active = isFocused,
   │       onActiveChange = { isFocused = it },
   │       placeholder = { Text("Search YourTube") },   // state-catalog C2 copy
   │       leadingIcon = { Icon(Icons.Rounded.Search, ...) },
   │       trailingIcon = {
   │         if (uiState.query.isNotBlank()) IconButton(onClick = vm::clearQuery) {
   │           Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.search_clear_cd))
   │         }
   │       },
   │       colors = SearchBarDefaults.colors(containerColor = colorScheme.surfaceVariant),
   │     )
   ├─ content: Column(Modifier.fillMaxSize().padding(scaffoldPadding))
   │  ├─ // Chip strip: rendered iff Body.Idle OR Body.NoResults (D3 — never on top of skeleton)
   │  │  AnimatedVisibility(visible = uiState.body == Idle || uiState.body == NoResults) {
   │  │    SuggestionChipRow(
   │  │      chips = uiState.visibleSuggestions,
   │  │      selectedQuery = uiState.query,
   │  │      onTap = vm::submitChip,
   │  │      onLongPress = vm::removeRecent,
   │  │    )
   │  │  }
   │  └─ // Body switch — exactly one branch at a time (D5)
   │     when (val b = uiState.body) {
   │       Idle           -> EmptyState(cell = StateCatalog.C2)                      // chips above already
   │       Loading        -> LazyColumn { items(6) { SkeletonTrackRow(index = it) } } // C1
   │       is Results     -> LazyColumn { items(b.items, key = { it.videoId }) {
   │                          SearchResultRow(result = it, playerState = playerState, onTap = vm::onResultTap)
   │                        } }
   │       NoResults      -> EmptyState(cell = StateCatalog.C3, query = uiState.query) // C3 with interpolated query
   │       is Error       -> ErrorState(
   │                          cell = if (b.cause == Offline) StateCatalog.C5 else StateCatalog.C4,
   │                          onRetry = { vm.submit(uiState.query) },
   │                          onSecondary = if (b.cause == Offline) { { navController.navigate(AppRoute.Library.route) } } else null,
   │                        )
   │     }
   └─ (no FAB — Search has no floating action)
```

## SuggestionChipRow (D3)

```kotlin
@Composable
fun SuggestionChipRow(
  chips: List<SearchUiState.Chip>,
  selectedQuery: String,
  onTap: (String) -> Unit,
  onLongPress: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  val haptics = LocalHapticFeedback.current
  LazyRow(
    modifier = modifier.fillMaxWidth().padding(vertical = 8.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    contentPadding = PaddingValues(horizontal = 16.dp),
  ) {
    items(chips, key = { it.text }) { chip ->
      val isSelected = chip.text == selectedQuery
      FilterChip(
        selected = isSelected,
        onClick = { onTap(chip.text) },                                     // D10 — fires search immediately
        label = { Text(chip.text) },
        modifier = Modifier.combinedClickable(
          onClick = { onTap(chip.text) },
          onLongClick = if (chip.isRecent) {
            {
              haptics.performHapticFeedback(HapticFeedbackType.Reject)      // D3
              onLongPress(chip.text)
            }
          } else null,
        ),
        colors = FilterChipDefaults.filterChipColors(
          containerColor = colorScheme.surfaceVariant,
          selectedContainerColor = colorScheme.secondaryContainer,
        ),
      )
    }
  }
}
```

Note: M3 `FilterChip` already provides hit target ≥ 48 dp via `Modifier.minimumInteractiveComponentSize()`. Don't add another `.size(...)`.

## SearchResultRow + EQ indicator (D4)

```kotlin
@Composable
fun SearchResultRow(
  result: SearchResult,
  playerState: PlayerState,
  onTap: (SearchResult) -> Unit,
  modifier: Modifier = Modifier,
) {
  val isActive = result.videoId == playerState.currentTrack?.videoId
  val isPlaying = isActive && playerState.isPlaying

  val rowTint = if (isActive) colorScheme.primaryContainer.copy(alpha = 0.16f) else Color.Transparent
  TrackRow(
    track = result.toTrack(),
    onTap = { onTap(result) },
    modifier = modifier.background(rowTint),                                // single shared tint token
    leadingOverlay = if (isActive) {
      { SearchEqIndicator(isPlaying = isPlaying) }                          // overlays the duration badge
    } else null,
  )
}
```

`TrackRow` is the existing `core/ui/TrackRow.kt` composable; the `leadingOverlay` param was added in YT-0011 §Q7 to keep the EQ indicator out of TrackRow's own logic. If your TrackRow doesn't yet expose that slot, add it as `leadingOverlay: (@Composable () -> Unit)? = null` — minimal API surface, opt-in for callers.

## SearchEqIndicator (D4 detail)

```kotlin
@Composable
fun SearchEqIndicator(
  isPlaying: Boolean,
  modifier: Modifier = Modifier,
) {
  val reduceMotion = LocalReduceMotion.current
  val baseColor = colorScheme.primary
  val color = if (isPlaying) baseColor else baseColor.copy(alpha = 0.6f)

  if (!isPlaying || reduceMotion) {
    // Frozen state — three bars at a fixed mid-height pattern; respects D4 "bars freeze on pause".
    StaticThreeBar(color = color, modifier = modifier)
    return
  }

  val transition = rememberInfiniteTransition(label = "eq")
  val bar1 = transition.animateFloat(
    initialValue = 6f, targetValue = 18f,
    animationSpec = infiniteRepeatable(
      animation = tween(600, easing = FastOutSlowInEasing),
      repeatMode = RepeatMode.Reverse,
    ),
    label = "bar1",
  )
  val bar2 = transition.animateFloat(
    initialValue = 4f, targetValue = 16f,
    animationSpec = infiniteRepeatable(
      animation = tween(600, easing = FastOutSlowInEasing, delayMillis = 120),
      repeatMode = RepeatMode.Reverse,
    ),
    label = "bar2",
  )
  val bar3 = transition.animateFloat(
    initialValue = 8f, targetValue = 14f,
    animationSpec = infiniteRepeatable(
      animation = tween(600, easing = FastOutSlowInEasing, delayMillis = 240),
      repeatMode = RepeatMode.Reverse,
    ),
    label = "bar3",
  )

  Row(
    modifier = modifier
      .size(24.dp)
      .clearAndSetSemantics { }                                              // decorative — see a11y
      .padding(vertical = 4.dp),
    verticalAlignment = Alignment.Bottom,
    horizontalArrangement = Arrangement.spacedBy(2.dp),
  ) {
    Bar(heightDp = bar1.value.dp, color = color)
    Bar(heightDp = bar2.value.dp, color = color)
    Bar(heightDp = bar3.value.dp, color = color)
  }
}

@Composable
private fun Bar(heightDp: Dp, color: Color) {
  Box(
    Modifier
      .width(3.dp)
      .height(heightDp)
      .background(color, RoundedCornerShape(1.5.dp))
  )
}
```

Per-bar `delayMillis` of 0 / 120 / 240 produces the visible stagger called out in D4 anti-patterns.

## SearchViewModel — debounce + history wiring

```kotlin
@HiltViewModel
class SearchViewModel @Inject constructor(
  private val repo: SearchRepository,
  private val history: SearchHistoryDao,
  private val net: NetworkMonitor,
  savedState: SavedStateHandle,
) : ViewModel() {

  private val _query = savedState.getStateFlow("search.query", "")
  private val _body = MutableStateFlow<SearchUiState.Body>(SearchUiState.Body.Idle)

  val uiState: StateFlow<SearchUiState> = combine(
    _query, _body, history.recents(limit = 20),
  ) { q, b, recents ->
    SearchUiState(query = q, recentSuggestions = recents.map { it.query }, body = b)
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUiState())

  init {
    // D2 — debounce text-change submissions; IME action calls submit() directly.
    viewModelScope.launch {
      _query
        .debounce(400.milliseconds)
        .distinctUntilChanged()
        .collectLatest { q ->
          if (q.isBlank()) { _body.value = SearchUiState.Body.Idle }
          else { runSearch(q) }
        }
    }
  }

  fun setQuery(q: String) { savedState["search.query"] = q }
  fun clearQuery() { savedState["search.query"] = "" }                       // D9

  fun submit(q: String = _query.value) {                                     // IME action / chip tap / retry
    if (q.isBlank()) return
    viewModelScope.launch { runSearch(q) }
  }
  fun submitChip(text: String) {                                             // D10
    setQuery(text)
    submit(text)
  }
  fun removeRecent(query: String) {                                          // D3 long-press
    viewModelScope.launch { history.delete(query) }
  }

  private suspend fun runSearch(q: String) {
    _body.value = SearchUiState.Body.Loading
    val result = repo.search(q)
    _body.value = when {
      result.isSuccess && result.getOrThrow().isEmpty() -> SearchUiState.Body.NoResults
      result.isSuccess -> SearchUiState.Body.Results(result.getOrThrow())
      !net.isOnline.value -> SearchUiState.Body.Error(SearchUiState.Cause.Offline)
      else -> SearchUiState.Body.Error(SearchUiState.Cause.Generic)
    }
    if (result.isSuccess && result.getOrThrow().isNotEmpty()) {
      history.upsert(q)                                                      // D6
    }
  }

  fun onResultTap(result: SearchResult) {
    // Defer to PlayerController; NowPlaying transition handled by AppShell (YT-0013 §Q12)
    playerController.play(result.toMediaItem())
  }
}
```

## Focus on tab activation (D7)

`SearchScreen` owns a `FocusRequester` and calls `requestFocus()` exactly once on tab activation when the query is blank:

```kotlin
val focusRequester = remember { FocusRequester() }
val initialQuery = uiState.query                                             // captured once

LaunchedEffect(Unit) {
  if (initialQuery.isBlank()) {
    focusRequester.requestFocus()                                            // also opens IME via SearchBar
  }
}

SearchBar(
  modifier = Modifier.focusRequester(focusRequester),
  ...
)
```

Returning from NowPlaying (back-stack pop) does NOT re-trigger this `LaunchedEffect` because the screen is restored from saved state — `initialQuery` will be non-blank.

## State-catalog cell integration points

| Cell | Used in | Component | Reusable? |
|---|---|---|---|
| **C1** loading skeleton ×6 | `Body.Loading` branch | `SkeletonTrackRow` (`core/ui`) | shared with C6 / C12 |
| **C2** idle empty | `Body.Idle` branch (chips already above) | `EmptyState(cell = C2)` (`core/ui`) | shared with all idle empty cells |
| **C3** no results | `Body.NoResults` branch | `EmptyState(cell = C3, query = ...)` | shared |
| **C4** generic error | `Body.Error(Generic)` | `ErrorState(cell = C4, onRetry = ...)` | shared |
| **C5** offline | `Body.Error(Offline)` | `ErrorState(cell = C5, onRetry = ..., onSecondary = navToLibrary)` | shared — only place secondary CTA fires |

`EmptyState` / `ErrorState` are documented in state-catalog `README.md` § Per-platform mapping. They consume copy from `state-catalog/copy.md` and icon names from `symbol-map/symbol-map.md`. **Do not** hardcode strings here.

## Token mapping (mockup CSS → Compose)

| Mockup / `pal.*` | Compose / M3 |
|---|---|
| `--color-surface-variant: #2C2C2E` | `colorScheme.surfaceVariant` |
| `--color-bg: #0F0F0F` | `colorScheme.background` |
| `--color-accent: #8B5CF6` | `colorScheme.primary` |
| `rgba(139,92,246,0.16)` active row | `colorScheme.primaryContainer.copy(alpha = 0.16f)` |
| `rgba(139,92,246,0.30)` chip selected | `colorScheme.secondaryContainer` (re-themed in `Theme.kt` if needed) |
| `--radius-md: 12px` | `RoundedCornerShape(12.dp)` = `MaterialTheme.shapes.medium` |
| `--font-sans` Roboto | M3 `Typography` default — don't import Geist on Android |
| `font-feature-settings: "tnum"` | `Text(..., fontFeatureSettings = "tnum")` |
| `@keyframes eq` 600ms | `rememberInfiniteTransition(label = "eq") + animateFloat(infiniteRepeatable(tween(600), Reverse))` |
| Hand-drawn `<svg>` magnifier | `Icons.Rounded.Search` |
| Hand-drawn `<svg>` close | `Icons.Rounded.Close` |
| Skeleton block `#2C2C2E` | Inherit from `SkeletonTrackRow` — do not redefine |

## What the existing combined mockup gets wrong (do not port)

The legacy combined mockup at `design-system/mockups/android/YT-0011-0012-0013-shell-search-player.html` differs from production:

1. **Chip strip:** mockup uses `FlowRow`-style wrap — production is a horizontal `LazyRow` (D3).
2. **Chip max count:** mockup shows 8+ — production caps at 6 (D3).
3. **Active row tint:** mockup uses solid `#3B2A5C` — production uses `primaryContainer.copy(alpha = 0.16f)` (D4, same as NowPlaying active row).
4. **EQ indicator:** mockup is a static SVG — production is `rememberInfiniteTransition` with 3 staggered bars (D4).
5. **No-results copy:** mockup has bespoke copy — production consumes state-catalog C3 verbatim (D5).
6. **History clearing:** mockup shows a "Clear all" toolbar action — production defers full clear to Settings § Data (D6).
7. **Voice search:** mockup shows a mic icon — production hides voice for MVP (D8).

Don't port the mockup styling to Compose 1:1. Use it for layout reference only. This folder is the source of truth.
