# YT-0012 — Haptics & Accessibility

> Companion to `decision-log.md` and `compose-spec.md`. Covers haptic table, TalkBack labels, focus-on-tab-activation contract, live-region announcements, font-scale behavior, and reduce-motion contract for the Search screen.

## Haptics — `LocalHapticFeedback`

Use `LocalHapticFeedback.current` from Compose for tap events. Drop to `View.performHapticFeedback(HapticFeedbackConstants.*)` only where the SDK constant has no Compose equivalent (none in this surface).

### Full table

| UI event | Compose call | API floor / fallback |
|---|---|---|
| Suggestion chip — single tap | `haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)` | API 35+; fallback `TextHandleMove` |
| Suggestion chip — long-press (recent) | `haptics.performHapticFeedback(HapticFeedbackType.Reject)` | API 34+; fallback `LongPress` |
| Suggestion chip — long-press (curated) | *(no haptic — long-press is ignored on curated chips per D3)* | n/a |
| Clear button (`Icons.Rounded.Close`) tap | `haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)` | API 35+; fallback `TextHandleMove` |
| Submit query (IME Search action) | `haptics.performHapticFeedback(HapticFeedbackType.Confirm)` | API 34+; fallback `LongPress` |
| Result row tap → starts playback | `haptics.performHapticFeedback(HapticFeedbackType.Confirm)` | API 34+; fallback `LongPress` |
| Result row long-press → context menu opens | `haptics.performHapticFeedback(HapticFeedbackType.LongPress)` | always |
| Retry button (state-catalog C4/C5) | `haptics.performHapticFeedback(HapticFeedbackType.Confirm)` | API 34+; fallback `LongPress` |
| Offline "Go to Library" secondary CTA (C5) | `haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)` | API 35+; fallback `TextHandleMove` |

### Explicitly NO haptics

- Every keystroke in the SearchBar — system IME already haptics keys via the user's keyboard settings.
- Debounced auto-submission (D2) — only the IME-fast-path submit fires `Confirm`; the silent debounced fire is mute.
- EQ indicator transitions — decorative; haptics on every state-flip would feel buzzy.

### Single-fire pattern

For events that depend on a transition (chip becoming selected), key the `LaunchedEffect` on the transition, not the value:

```kotlin
var lastSelectedChip by remember { mutableStateOf<String?>(null) }
LaunchedEffect(uiState.query) {
  val nowSelected = chips.firstOrNull { it.text == uiState.query }?.text
  if (nowSelected != null && nowSelected != lastSelectedChip) {
    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
  }
  lastSelectedChip = nowSelected
}
```

---

## TalkBack labels — every interactive element

All `contentDescription` strings come from `strings.xml` (`feature/search/src/main/res/values/strings.xml`). No hardcoded literals in composables.

| Element | TalkBack label (state-aware) | Hint / role |
|---|---|---|
| SearchBar field | `"Search YourTube"` | role: `EditField` (M3 default); hint: `"Find tracks, channels, and topics"` |
| Clear button (trailing) | `"Clear"` | `clickAction("Clear search")`; role: `Button`; hidden when `query.isBlank()` |
| Suggestion chip — recent | `"Recent search: {query}, double-tap to search, hold to remove"` | role: `Button`; custom action: `"Remove from recent searches"` (per D3 long-press) |
| Suggestion chip — curated | `"Suggested search: {query}, double-tap to search"` | role: `Button` |
| Suggestion chip — selected | `"Recent search: {query}, currently selected"` | role: `Button`; `selected = true` flag via `Modifier.semantics { selected = true }` |
| Skeleton row (C1) | (not focusable — `Modifier.semantics { invisibleToUser() }`) | list container value: `"Loading"` |
| Result row | inherited from `TrackRow` — `"{title} by {channel}, {duration}"` | role: `Button`; custom actions: `"Add to playlist"`, `"Share"` |
| Result row — active (EQ playing) | `"{title} by {channel}, {duration}, now playing"` | trailing "now playing" status appended |
| Result row — active (paused) | `"{title} by {channel}, {duration}, paused"` | |
| EQ indicator bars | `accessibilityHidden` — decorative (`Modifier.clearAndSetSemantics { }`) | the row label carries the "now playing" status |
| C2 empty state | `"Search YourTube. heading."` (per state-catalog) | inherited from `EmptyState` |
| C3 no-results | `"No results for {query}. heading."` (per state-catalog) | inherited; **announces via polite live region** |
| C4 generic error | `"Couldn't search. heading."` | inherited; **announces via polite live region** |
| C5 offline | `"You're offline. heading."` | inherited; **announces via polite live region** |
| Retry button | `"Try again"` | `clickAction("Try again")` |
| Offline secondary CTA | `"Go to Library"` | `clickAction("Open Library tab")` |

### Live-region announcements

| Trigger | Region | Politeness |
|---|---|---|
| Results populate after submit | the LazyColumn container | **Polite** — emits `"{N} results for {query}"` via `Modifier.semantics { liveRegion = LiveRegionMode.Polite; contentDescription = announcement }` on a small invisible spacer above the list |
| No-results cell appears | the C3 cell title | **Polite** — `state-catalog/README.md` mandates this for C3 |
| Error cell appears (C4 / C5) | the cell title | **Polite** (per state-catalog § Live announcements) |
| Loading state | (no announcement) | the container carries `accessibilityValue("Loading")`; no live region |
| Chip removed via long-press | the snackbar | inherited from toast-catalog — snackbar already announces |

Implementation for the results count announce:

```kotlin
val count = (uiState.body as? SearchUiState.Body.Results)?.items?.size
val query = uiState.query
val announcement = remember(count, query) {
  if (count != null) "$count results for $query" else null
}
LaunchedEffect(announcement) { /* the Modifier below picks it up */ }

Spacer(
  Modifier
    .size(1.dp)
    .semantics {
      liveRegion = LiveRegionMode.Polite
      announcement?.let { contentDescription = it }
    }
)
```

---

## Focus-on-tab-activation contract (D7)

Per `decision-log.md` §D7 and the cross-platform a11y rule: when the Search tab activates and `query.isBlank()`, the SearchBar receives focus and **TalkBack announces the field label**.

| Scenario | Focus | TalkBack announces |
|---|---|---|
| Cold launch → Search tab | SearchBar | `"Search YourTube. edit field. Find tracks, channels, and topics."` |
| Switch from Library tab → Search tab (blank query) | SearchBar | same as above |
| Switch from another tab → Search tab (query preserved from saved state) | last focused element OR the results list (whichever was active) | does NOT re-announce |
| Process death → restore | results list (if non-blank query) | does NOT re-announce |
| Back from NowPlaying → Search (results visible) | results list (preserved scroll position) | does NOT re-announce |

The `LaunchedEffect(Unit) { focusRequester.requestFocus() }` pattern in `compose-spec.md` produces this behavior automatically because Compose's `LaunchedEffect(Unit)` re-runs only on composition entry, and the `if (initialQuery.isBlank())` guard skips it on restored entry.

---

## System text scaling (`fontScale`)

All text uses M3 `Typography` roles — they scale with `Configuration.fontScale` automatically.

| UI element | Role | Behavior at `fontScale = 2.0f` |
|---|---|---|
| SearchBar placeholder + input | `bodyLarge` | scales freely; SearchBar height grows |
| Suggestion chip label | `labelLarge` | scales freely; chip height grows; LazyRow becomes taller but stays horizontal |
| Result row title | `titleMedium` | scales freely; `maxLines = 1` + `basicMarquee()` on overflow (inherited from `TrackRow`) |
| Result row subtitle (channel · duration) | `bodyMedium` | scales freely |
| Duration badge | `labelSmall` with `fontFeatureSettings = "tnum"` | clamp via `Modifier.fontScalingClamp(max = 1.4f)` — tabular layout otherwise breaks; helper in `core/ui/FontScaling.kt` |
| State-catalog cell title (C2/C3/C4/C5) | inherited from state-catalog — `headlineSmall` | scales freely (state-catalog `README.md` § Dynamic Type / Font Scaling) |
| State-catalog body copy | inherited — `bodyMedium` | scales freely |
| Action button label (Try again / Go to Library / Clear search) | inherited — `labelLarge`, `weight = SemiBold` | scales freely |

At `fontScale = 2.0f`, the chip strip remains a `LazyRow` (no wrap) so the layout doesn't reflow into a multi-line stack — that would push the body off-screen on small phones.

**Verify in QA**: Settings → Accessibility → Display size and text → text size at maximum. The Search screen must:
1. SearchBar visible without horizontal scroll.
2. Chip strip horizontally scrollable to reveal all 6 chips.
3. Result rows scrollable.
4. State-catalog cell readable end-to-end without scrolling within the cell (the cell itself wraps in a `verticalScroll` per state-catalog rule).

---

## Reduce-motion contract

Read `LocalReduceMotion.current` (defined in `core/ui/ReduceMotion.kt` per YT-0013 §Q9; sourced from `Settings.Global.TRANSITION_ANIMATION_SCALE == 0f` and on API 33+ also `AccessibilityManager.isReducedAnimationsEnabled`).

| Animation | Default | Reduce Motion |
|---|---|---|
| EQ indicator bars (D4) | `rememberInfiniteTransition` 600 ms reverse | **Static** — render the three bars at a fixed mid-pattern (`[12, 10, 11]` dp), same color, frozen (per `compose-spec.md` `SearchEqIndicator`) |
| Skeleton shimmer (C1) | 1400 ms shimmer sweep | **Static** — solid `#2C2C2E` blocks, no shimmer (per state-catalog § Shimmer rules) |
| Chip selection cross-fade | `Crossfade(tween(200))` between unselected and selected colors | `Crossfade(snap())` — instant color swap (still cross-fade primitive, just zero duration) |
| Result list enter animation (post-loading) | M3 default `fadeIn() + slideInVertically(initialOffsetY = { 16.dp.toPx() })` | `fadeIn()` only — no slide |
| State-catalog cell enter (C3/C4/C5) | inherited from `EmptyState`/`ErrorState` | inherited |
| Color/state changes (active-row tint flicker on play/pause) | `tween(150)` | **stays** — not motion, it's information |

Don't conditional-out the whole animation — provide a reduced variant. Pattern (already in `SearchEqIndicator`):

```kotlin
if (!isPlaying || reduceMotion) {
  StaticThreeBar(color = color, modifier = modifier)
  return
}
// ... rememberInfiniteTransition ...
```

---

## Tap targets

Minimum **48 dp × 48 dp** for every interactive element (`docs/design-system.md`).

| Element | Visible | Hit area |
|---|---|---|
| SearchBar field | full-width × 56 dp | full-width × 56 dp (`SearchBar` default) |
| Clear (trailing icon) | 24 dp glyph | 48 dp (M3 `IconButton`) |
| Suggestion chip | ~36 dp tall visible | 48 dp (M3 `FilterChip` via `minimumInteractiveComponentSize`) |
| Result row | ~72 dp tall | full row tap; long-press surface inherits |
| EQ indicator | 24 × 24 dp | not interactive |
| State-catalog action buttons (Try again / Go to Library / Clear search) | 52 dp height | 52 dp (per state-catalog `empty.md` § Action button) |

---

## Color contrast

| Pair | Ratio | Pass |
|---|---|---|
| SearchBar input text (`onSurface` white) on `surfaceVariant` `#2C2C2E` | ~14:1 | AAA |
| Placeholder (`onSurfaceVariant`) on `surfaceVariant` `#2C2C2E` | ~6:1 | AA body |
| Chip label (`onSurfaceVariant`) on unselected chip (`surfaceVariant`) | ~6:1 | AA |
| Selected chip label (`onSecondaryContainer`) on `secondaryContainer` | ≥7:1 (M3 HCT-guaranteed) | AA body |
| Result row active tint (`primaryContainer.copy(alpha = 0.16f)` over `background`) | non-text, indicator only | ≥3:1 not required (decorative — the EQ bars at full alpha carry meaning) |
| EQ bar `primary` on active-row tint background | ~4.5:1 (Material You HCT-guaranteed) | AA non-text |
| Skeleton block `#2C2C2E` on `background #0F0F0F` | ~2:1 | non-text — passes by design |
| `colorScheme.primary` on `background` (Try again button surface) | ~4.6:1 (brand fallback) | AA — just over the 4.5:1 floor; do NOT lighten |

When v1.1 Material You wallpaper-tint background ships (per YT-0013 §Q3), re-validate every text/icon pair against the harmonized scheme. The active-row tint may need a chroma clamp — the harmonization helper covers it.
