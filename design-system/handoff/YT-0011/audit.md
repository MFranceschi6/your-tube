# YT-0011 — Android Shell · Retrospective Design Audit

**Auditor:** senior Android design consultant (M3 / Material You)
**Scope:** files shipped under YT-0011 — `MainActivity.kt`,
`navigation/AppNavigation.kt`, `ui/AppShell.kt`, `ui/theme/Theme.kt`,
`core/ui/{MiniPlayer,NowPlayingScreen,TrackRow,SearchBar,PlaylistRow,
EmptyState,ErrorState,Placeholder}.kt`.
**Lens:** what does the shipped shell foreclose, force, or leave ambiguous
for YT-0013 (Now Playing) and YT-0014 (Library), and what is the smallest
refactor today that prevents re-opening the shell after those handoffs land.

> **Reality check before the punch list.** A few things in the YT-0011
> brief don't match the code on disk. The brief says `MainActivity` passes
> a hardcoded demo `Track`; it doesn't — `AppShell` already collects a
> Hilt-injected `PlayerViewModel.playerState` and uses real
> `currentTrack`. The brief lists `ui/PlaceholderScreens.kt`; that file
> isn't here — `AppShell` already wires `SearchScreen`, `LibraryScreen`,
> `PlaylistDetailScreen`, `RecentlyPlayedScreen`, `SettingsScreen` from
> the feature modules. The brief says theme keeps brand purple #8B5CF6 as
> fallback; the code falls back to M3 stock `darkColorScheme()` — purple
> is **not in the theme at all**. These three deltas change which items
> below are urgent vs cosmetic. I've called them out on the relevant
> findings.

---

## TL;DR — punch list ordered by downstream impact

| # | Finding | File | Diff | Defer? | Beneficiary |
|---|---|---|---|---|---|
| **1** | `bottomBar` is a `Column { MiniPlayer; NavigationBar }` — no slot for an Extended FAB; YT-0014 will collide | `ui/AppShell.kt` | medium | **NO — absorb now** | YT-0014 |
| **2** | NowPlayingScreen rendered as in-tree `AnimatedVisibility` overlay forecloses `SharedTransitionLayout` artwork morph and predictive-back | `ui/AppShell.kt` + `core/ui/NowPlayingScreen.kt` | medium | **NO — absorb now** | YT-0013 |
| **3** | No `SharedTransitionLayout` host at AppShell level | `ui/AppShell.kt` | small | **NO — absorb now** | YT-0013 |
| **4** | Brand purple #8B5CF6 fallback is missing from theme entirely | `ui/theme/Theme.kt` | small | NO — absorb now | YT-0013, YT-0014 |
| **5** | No `LocalReduceMotion` (or equivalent) plumbed | `ui/theme/Theme.kt` | small | NO — absorb now | YT-0013, YT-0014 |
| **6** | MiniPlayer uses `primaryContainer` — wrong M3 surface token for a docked persistent player | `core/ui/MiniPlayer.kt` | small | NO — absorb now | YT-0013, YT-0014 |
| **7** | NowPlayingScreen `Box.background(background)` ignores Material You `surface` hierarchy | `core/ui/NowPlayingScreen.kt` | small | YT-0013 will rewrite — **defer** | YT-0013 |
| **8** | `TrackRow` lacks `onLongPress`, drag-handle slot, leading slot, EQ-indicator slot | `core/ui/TrackRow.kt` | small | NO — absorb now | YT-0014, YT-0026 |
| **9** | `MiniPlayer` lacks shared-element key + reduce-motion opt-in + drag-to-expand hook | `core/ui/MiniPlayer.kt` | small | NO — absorb now | YT-0013 |
| **10** | `EmptyState` / `ErrorState` lack icon-tint slot and image alternative — YT-0014 ContentUnavailable variant won't fit | `core/ui/EmptyState.kt`, `ErrorState.kt` | small | partial defer | YT-0014 |
| **11** | `PlaylistRow` cover is single-thumbnail only — YT-0014 picks one of: 4-up / dominant / HCT-hash gradient. Cover slot must be hoisted | `core/ui/PlaylistRow.kt` | small | NO — absorb now | YT-0014 |
| **12** | A11y labels still hardcoded in Kotlin (reviewer-deferred) | all `core/ui/*.kt` | medium | **DEFER until YT-0014 lands** | all |
| **13** | Demo Track risk — already resolved, no action | `MainActivity.kt` | — | — | — |
| **14** | `enableEdgeToEdge()` is in `MainActivity` but `Scaffold` doesn't pass `WindowInsets` recipe to children | `MainActivity.kt`, `ui/AppShell.kt` | medium | NO — absorb now | YT-0013, YT-0014 |
| **15** | Predictive-back not wired — NowPlaying overlay is `rememberSaveable<Boolean>`, not on the back stack | `ui/AppShell.kt` | medium | tied to #2 | YT-0013 |
| **16** | `NavigationBarItem` icon is a vector asset, not Material Symbols Rounded variable font | `navigation/AppNavigation.kt` | small | DEFER | cosmetic |

---

## File-by-file findings

### `ui/AppShell.kt` — root scaffold

#### Finding 1 — `bottomBar` slot can't accommodate the YT-0014 Extended FAB. **HIGH.**

**(a) Today.** `Scaffold(bottomBar = { AppBottomBar(...) })` and
`AppBottomBar` is a `Column { MiniPlayer(...); NavigationBar { ... } }`.
The MiniPlayer is _inside_ `bottomBar`, stacked above the `NavigationBar`
in a `Column`. There is no `floatingActionButton` slot used and no other
docking point.

**(b) Constraint.** YT-0014's brief locks an **Extended FAB "New
playlist" docked bottom-end above MiniPlayer + NavigationBar**. The
`Scaffold`'s `floatingActionButton` slot draws above content but stacks
**below** the `bottomBar` by default (with `floatingActionButtonPosition
= End`). That positioning was designed for a `BottomAppBar`, not a
`NavigationBar` + custom MiniPlayer column. As-is, dropping a FAB into
`Scaffold(floatingActionButton = …)` will land it _above_ both the
NavigationBar and the MiniPlayer (correct) but the FAB will overlap the
MiniPlayer's track title at typical 16 dp `padding` because the
MiniPlayer fills the full width of the `bottomBar` slot and the FAB is
just floating in front of it. There's no inset reservation.

**(c) Recommended refactor.** Extract the MiniPlayer **out** of the
`bottomBar` slot into a `Box` overlay above content but below
NowPlaying, anchored to the `Scaffold`'s bottom inner padding. Make
`bottomBar` only hold `NavigationBar`. Then expose two destination-
addressable slots from `AppShell`:

```kotlin
// pseudo-API, no code requested by user — but for the refactor
CompositionLocal LocalAppShellSlots = compositionLocalOf {
  AppShellSlots(
    fabSlot: SnapshotStateRef<@Composable (() -> Unit)?>,
    miniPlayerHeight: () -> Dp,
    navBarHeight: () -> Dp,
  )
}
```

Library destination calls `LocalAppShellSlots.current.fabSlot.value =
{ ExtendedFloatingActionButton(...) }` in a `DisposableEffect`. AppShell
renders the slot inside its `Box` above the MiniPlayer with bottom-end
alignment and a `windowInsetsPadding(navigationBars)` that also adds
`miniPlayerHeight + 16.dp`. This is the standard "destination injects
FAB" pattern (Now in Android does the same).

Alternative, simpler: hoist a `var fab by remember { mutableStateOf<(@Composable () -> Unit)?>(null) }`
in `AppShell`, pass `setFab` via a `CompositionLocal` or a typed
parameter, and have `LibraryScreen` drive it via `LaunchedEffect(Unit)`.

**(d) Impact if NOT done.** YT-0014 implementation will either (i) push
the FAB inside `LibraryScreen` content, breaking the dock contract and
making it scroll away with the list, or (ii) add a parallel
`floatingActionButton =` slot on `Scaffold`, producing a FAB that
overlaps the MiniPlayer. Either way YT-0014 cannot land cleanly without
re-opening AppShell. **Refactor now, ~40 lines.**

---

#### Finding 2 — NowPlaying as in-tree `AnimatedVisibility` overlay forecloses two of three YT-0013 presentation choices. **HIGH.**

**(a) Today.** `nowPlayingOpen` is a `rememberSaveable<Boolean>` hoisted
in `AppShell`. NowPlaying is rendered as a sibling of `Scaffold` inside
the outer `Box`, gated by `AnimatedVisibility` with vertical slide. It
is **not** a Compose Navigation destination and **not** a
`ModalBottomSheet`.

**(b) Constraint.** YT-0013 will pick one of:
- `ModalBottomSheet` full-detent
- Full-screen Compose destination via Navigation
- `SharedTransitionLayout` + `sharedElement` morph from MiniPlayer artwork

**Today's overlay supports option 3 in spirit (sibling overlay can host
sharedElement) but blocks options 1 and 2 cleanly.** It also blocks
predictive-back: `rememberSaveable<Boolean>` is not on the navigation
back stack, so Android 13+'s predictive-back gesture animates
`MainActivity` away under the user's finger instead of closing the
overlay. The current `AnimatedVisibility` slide has no
`PredictiveBackHandler` wired.

**(c) Recommended refactor.** Make the NowPlaying open-state a function
of navigation, not a boolean. Add a route:

```
composable("now-playing") { NowPlayingScreen(...) }
```

…with `enterTransition` / `exitTransition` set. Replace `nowPlayingOpen
= true` with `navController.navigate("now-playing")` and `onDismiss`
with `navController.popBackStack()`. Wrap the `NavHost` content in a
`SharedTransitionLayout { AnimatedContent(navBackStackEntry) { … } }` so
that artwork shared-element keys cross the transition. This unblocks all
three YT-0013 options simultaneously: a Navigation destination is the
default, you can wrap it in `ModalBottomSheet` if YT-0013 picks that,
and `SharedTransitionLayout` is hosted at the right level for shared-
element keys to resolve.

Alternatively, if the team really wants the boolean overlay to stay
(less invasive): wrap with `BackHandler(enabled = nowPlayingOpen) {
nowPlayingOpen = false }` AND wrap with `PredictiveBackHandler` so the
slide animation tracks finger progress. This still blocks the bottom-
sheet option in YT-0013 and is not recommended.

**(d) Impact if NOT done.** If YT-0013 chooses (1) or (2), the AppShell
overlay needs to be unwound — that's a re-open. If it picks (3), the
shell still needs `SharedTransitionLayout` lifted up (Finding 3). Either
way, doing the navigation-destination refactor now satisfies all three
choices. **Refactor now, ~25 lines.**

---

#### Finding 3 — No `SharedTransitionLayout` host at AppShell level. **HIGH.**

**(a) Today.** The `Box` wrapping `Scaffold` and the NowPlaying overlay
is plain `Box`. `androidx.compose.animation.SharedTransitionLayout`
appears nowhere in the shell.

**(b) Constraint.** Shared-element transitions require a
`SharedTransitionLayout` ancestor that contains **both** the source
(MiniPlayer artwork) and destination (NowPlaying artwork). The two must
be in the same `SharedTransitionScope`. That scope **must** live above
`NavHost` and above the NowPlaying overlay so a `Modifier.sharedElement(
key = "artwork-${track.videoId}", scope, animatedVisibilityScope)` on
each end resolves the same scope.

**(c) Recommended refactor.** Wrap the outer `Box` in `AppShell` with
`SharedTransitionLayout` and provide its `SharedTransitionScope` via a
`CompositionLocal` (e.g. `LocalSharedTransitionScope`). MiniPlayer's
artwork `Box` and NowPlayingScreen's artwork `Box` both consume it and
attach `Modifier.sharedElement(..., key = "artwork-${track.videoId}")`.
Pair with the `AnimatedContent` per-route so each side gets an
`AnimatedVisibilityScope`.

The smallest version that keeps YT-0013's hands free: lift the scope and
expose it via `CompositionLocal`, leave attachment to YT-0013.

**(d) Impact if NOT done.** YT-0013 cannot wire shared-element artwork
without re-opening AppShell. **Lift now, ~10 lines.**

---

#### Finding 14 — Edge-to-edge insets not propagated to all destinations. **MEDIUM.**

**(a) Today.** `MainActivity` calls `enableEdgeToEdge()`. `Scaffold`
provides `innerPadding` to `NavHost`. `NowPlayingScreen` adds its own
`statusBarsPadding()` + `navigationBarsPadding()`. `MiniPlayer` does
not consume `WindowInsets.navigationBars` (it relies on being inside
`bottomBar`, which the Scaffold pads, but only if the MiniPlayer stays
in `bottomBar` — see Finding 1).

**(b) Constraint.** Once Finding 1 is applied (MiniPlayer leaves
`bottomBar`), it must consume `navigationBars` insets itself. YT-0014
content under MiniPlayer + NavigationBar + FAB needs a single canonical
inset value. YT-0013 NowPlaying needs `WindowInsets.systemBars` (status
+ navigation) regardless of presentation pattern.

**(c) Recommended refactor.** Define a single
`LocalAppShellInsets`/`LocalContentBottomInset` that yields:
`navigationBars + miniPlayerHeight (if visible)`. Library / Search /
Settings destinations consume this for their `LazyColumn`'s
`contentPadding`. Bake it once in AppShell and stop relying on every
screen to re-derive it.

**(d) Impact if NOT done.** Each destination will re-invent its own
inset math; YT-0014's FAB-aware inset will diverge from
YT-0013's queue-sheet inset. **Centralize now, ~15 lines.**

---

#### Finding 15 — Predictive-back not wired. **MEDIUM (tied to Finding 2).**

Resolved as part of Finding 2 — once NowPlaying is a Navigation
destination, predictive-back works for free via `NavHost` +
`enableEdgeToEdge`. If Finding 2 is deferred, add `PredictiveBackHandler`
to AppShell.

---

### `ui/theme/Theme.kt` — Material 3 theme

#### Finding 4 — Brand purple #8B5CF6 fallback is missing entirely. **HIGH.**

**(a) Today.**
```kotlin
private val LightColors = lightColorScheme()  // M3 default — not brand
private val DarkColors = darkColorScheme()    // M3 default — not brand
```
`dynamicColor && SDK_INT >= S` wins on every device this app ships to
(minSdk 31), so the fallback path is theoretically dead. But: users can
disable Material You system-wide on Pixel ("Wallpaper colors" off) — when
that happens, the user is supposed to see brand purple, not Material 3's
generic stock palette.

**(b) Constraint.** Project tokens specify primary fallback `#8B5CF6`.
YT-0013 transport-control filled play button uses `colorScheme.primary`;
YT-0014 FAB uses `colorScheme.primary`. With the current theme, both
will be the M3 stock primary purple-blue, not brand purple, when dynamic
color is off.

**(c) Recommended refactor.** Define a `BrandDarkColors` and
`BrandLightColors` `ColorScheme` with `primary = Color(0xFF8B5CF6)` and
matching `onPrimary`, `primaryContainer`, `onPrimaryContainer`,
`secondary`, `tertiary`, plus surfaces from the project token doc
(`background = #0F0F0F`, `surface = #1C1C1E`, `surfaceVariant = #2C2C2E`,
`error = #FF453A`). Replace `LightColors` / `DarkColors` with
`BrandLightColors` / `BrandDarkColors`. Order in the `when`: dynamic
wins on Android 12+, brand fallback on disabled-Material-You.

Also: add an explicit `dynamicColor: Boolean = true` parameter exit hatch
already exists — keep it. The parameter lets the YT-0014 mockup tooling
force the fallback path.

**(d) Impact if NOT done.** Any user with Material You disabled sees
M3-default colors instead of brand. **Land now — small, ~30 lines incl.
the two `ColorScheme`s.**

---

#### Finding 5 — No `LocalReduceMotion`. **HIGH.**

**(a) Today.** `Theme.kt` is a 32-line file that wraps
`MaterialTheme(colorScheme, content)`. There is no
`CompositionLocalProvider`, no read of
`Settings.Global.TRANSITION_ANIMATION_SCALE`, no opt-in primitive.

**(b) Constraint.** Both YT-0013 (artwork pause-scale animation, queue-
sheet enter/exit, scrubber thumb scale) and YT-0014 (TopAppBar
exitUntilCollapsed parallax, swipe-to-dismiss spring, FAB hide-on-scroll)
must opt out of motion when
`Settings.Global.TRANSITION_ANIMATION_SCALE == 0f`. Reading the setting
once at theme level and exposing via `CompositionLocal` is the
canonical shape.

**(c) Recommended refactor.**

```kotlin
// pseudo
val LocalReduceMotion = staticCompositionLocalOf { false }

@Composable
fun YourTubeTheme(...) {
  val context = LocalContext.current
  val reduce = remember {
    Settings.Global.getFloat(context.contentResolver,
      Settings.Global.TRANSITION_ANIMATION_SCALE, 1f) == 0f
  }
  CompositionLocalProvider(LocalReduceMotion provides reduce) {
    MaterialTheme(colorScheme = ..., content = content)
  }
}
```

Animations gate themselves with
`val spec = if (LocalReduceMotion.current) snap() else tween(300)`.

Stretch: add a second local `LocalAccessibilityManager` reader for
`isHighTextContrastEnabled` and `isTouchExplorationEnabled` so YT-0014
can disable swipe-to-dismiss when TalkBack is on (long-press +
context-menu fallback).

**(d) Impact if NOT done.** YT-0013 and YT-0014 will each invent their
own one-shot reads, diverge, and probably forget. Reduce-motion
contract is policy, not per-screen. **Land now, ~15 lines.**

---

### `core/ui/MiniPlayer.kt`

#### Finding 6 — Wrong M3 surface token. **HIGH.**

**(a) Today.** `Box(... .background(MaterialTheme.colorScheme.primaryContainer))`.
Text uses `onPrimaryContainer`. Progress bar uses
`onPrimaryContainer.copy(alpha = 0.2f)`.

**(b) Constraint.** M3 elevation hierarchy for surface containers:
- `surface` (level 0, base) — main canvas
- `surfaceContainerLow` (level 1) — cards on canvas
- `surfaceContainer` (level 2) — bottom sheets, navigation drawers
- `surfaceContainerHigh` (level 3) — sticky elements, search bars, **persistent
  player**
- `surfaceContainerHighest` (level 4) — text fields, snackbars

A docked persistent MiniPlayer above NavigationBar is M3 "level 3" — the
right token is **`surfaceContainerHigh`**, not `primaryContainer`.
`primaryContainer` is for tonal _emphasis_ surfaces (filled tonal buttons,
selected-state chips), not for chrome. Using it makes the MiniPlayer the
loudest thing on screen and conflicts with the FAB / play button
(YT-0013) when those use `primary`.

**(c) Recommended refactor.** Change to
`MaterialTheme.colorScheme.surfaceContainerHigh`, text to `onSurface`,
secondary text to `onSurfaceVariant`, progress track to
`onSurface.copy(alpha = 0.12f)`, progress fill to `primary` (Material You
will tint this dynamically; brand purple in fallback).

Keep the 16 dp `RoundedCornerShape` — that matches "cards 12 / FAB 16
extended" tier well. Optionally swap to M3 `Card(elevation =
CardDefaults.cardElevation(2.dp))` for the tonal lift.

**(d) Impact if NOT done.** Visual hierarchy remains wrong; YT-0013's
FAB-style 72 dp play button will compete with the MiniPlayer for primary
emphasis. **Token swap, ~6 lines.**

---

#### Finding 9 — MiniPlayer parameter gaps. **MEDIUM.**

**(a) Today.** Stateless, `track`, `isPlaying`, `progressFraction`, three
callbacks. No shared-element key, no buffering / loading state, no
swipe-down dismiss, no swipe-up to expand.

**(b) Constraint.** YT-0013 will:
- attach a `Modifier.sharedElement(...)` to the artwork
- show a buffering indicator over the play button when
  `Player.STATE_BUFFERING`
- respond to vertical swipe-up to open NowPlaying (parity with Spotify /
  YT Music)
- respond to horizontal swipe to skip (optional)

**(c) Recommended parameter list:**
```kotlin
fun MiniPlayer(
  track: Track?,
  isPlaying: Boolean,
  isBuffering: Boolean = false,         // NEW — YT-0013
  progressFraction: Float,
  onPlayPauseClick: () -> Unit,
  onSkipNextClick: () -> Unit,
  onExpandClick: () -> Unit,
  onSwipeDismiss: (() -> Unit)? = null, // NEW — YT-0013 (optional)
  artworkModifier: Modifier = Modifier, // NEW — for sharedElement
  modifier: Modifier = Modifier,
)
```

`artworkModifier` is the cleanest seam — YT-0013 attaches `sharedElement`
to it without MiniPlayer needing to know the `SharedTransitionScope`.
`isBuffering` swaps the play-arrow for a 20 dp `CircularProgressIndicator`
when true (also useful before YT-0013 — Media3 stub state will buffer on
network start).

**(d) Impact if NOT done.** YT-0013 will fork the MiniPlayer into
`MiniPlayerV2`, carrying the breakage forward. **Add params now, ~4
lines.**

---

### `core/ui/NowPlayingScreen.kt`

#### Finding 7 — Background uses `colorScheme.background`, not surface tier. **LOW.**

**(a) Today.** `Box(... .background(MaterialTheme.colorScheme.background))`.

**(b) Constraint.** M3 NowPlaying surfaces are typically `surface`
(level 0) with the artwork providing the visual anchor. `background` is
for the absolute bottom layer (window backdrop) and should not be used
for content surfaces in M3.

**(c) Recommended refactor.** Switch to `colorScheme.surface`. YT-0013
may then layer a Material You-derived tint extracted from artwork (per
the brief's mockup-states palette) on top.

**(d) Impact if NOT done.** YT-0013 will rewrite this whole file
anyway — **defer**. Note in the YT-0013 handoff so the implementer
fixes it in passing.

---

### `core/ui/TrackRow.kt`

#### Finding 8 — Missing parameter slots for YT-0014 and YT-0026. **HIGH.**

**(a) Today.** `track`, `isPlaying`, `onClick`, `onMoreClick`. Renders
the equaliser-bar concept by recoloring title to `primary` and tinting
the row background. No EQ animation, no long-press, no drag handle slot,
no removal slot, no leading-content slot.

**(b) Constraint.** YT-0014 needs:
- Edit mode: leading red remove glyph + trailing drag handle (mockup
  may be wrong, but at minimum a slot)
- Long-press → context menu (rename / play next / add to playlist)
- Selection mode (multi-select for batch remove)

YT-0013 needs (in its queue panel):
- Animated equaliser-bar indicator on the now-playing row (3-bar
  Material Symbol or Lottie-style)
- Drag handle for queue reorder

YT-0026 (downloads) likely needs:
- Trailing download-state badge (queued / downloading / done / failed)

**(c) Recommended parameter list:**
```kotlin
fun TrackRow(
  track: Track,
  isPlaying: Boolean = false,
  isSelected: Boolean = false,           // NEW — selection mode
  showEqIndicator: Boolean = isPlaying,  // NEW — decouple from isPlaying
  onClick: () -> Unit,
  onLongClick: (() -> Unit)? = null,     // NEW — context menu
  onMoreClick: (() -> Unit)? = null,     // becomes optional
  leadingContent: (@Composable () -> Unit)? = null, // NEW — drag handle / remove glyph
  trailingContent: (@Composable () -> Unit)? = null, // NEW — overrides MoreVert
  modifier: Modifier = Modifier,
)
```

`showEqIndicator` decouples animation from playback state so the queue
panel can show the indicator on the active row even if the user has
paused. `leadingContent` and `trailingContent` slots let YT-0014's edit
mode and YT-0013's queue mode customise without forking the component.

The hardcoded `Icons.Rounded.MoreVert` becomes the default
`trailingContent`.

**(d) Impact if NOT done.** YT-0014 will fork into `TrackRowEditable`,
TrackRow-in-Queue, and so on. **Add params now, ~12 lines, default-
compatible.**

---

### `core/ui/PlaylistRow.kt`

#### Finding 11 — Cover is single-thumbnail; YT-0014 needs slot. **HIGH.**

**(a) Today.** Cover is a hard-coded `Box` showing
`playlist.tracks.firstOrNull()?.thumbnailUrl` or a `QueueMusic` icon.
4-up grid not supported. HCT-hash gradient not supported.

**(b) Constraint.** YT-0014's cover-composition decision will pick one
of: 4-up grid, single dominant, deterministic HCT gradient, or a hybrid
(0 tracks → gradient + glyph; 1–3 → single; 4+ → 4-up). Whichever wins,
the cover composition must be a **slot**, not hardcoded.

**(c) Recommended refactor.** Hoist the cover into a slot:
```kotlin
fun PlaylistRow(
  playlist: Playlist,
  onClick: () -> Unit,
  onMoreClick: () -> Unit,
  cover: @Composable (size: Dp) -> Unit = { size -> DefaultPlaylistCover(playlist, size) },
  modifier: Modifier = Modifier,
)
```

`DefaultPlaylistCover` keeps today's behavior; YT-0014 swaps in the
4-up / gradient implementation without touching `PlaylistRow`.

**(d) Impact if NOT done.** YT-0014 will rewrite `PlaylistRow` rather
than adding a single composable. **Hoist now, ~8 lines.**

---

### `core/ui/EmptyState.kt`, `core/ui/ErrorState.kt`

#### Finding 10 — No icon-tint slot, no image alternative, no body slot. **MEDIUM.**

**(a) Today.** `EmptyState(icon: ImageVector, title, body?, actionLabel?,
onAction?)`. Hardcoded tint
`onSurfaceVariant.copy(alpha = 0.6f)`, hardcoded 64 dp icon, hardcoded
`titleMedium`. `ErrorState` similar but tint hardcoded to `error`.

**(b) Constraint.** YT-0014 calls for a "ContentUnavailableView-
equivalent" — an empty state that may host an illustration (vector
asset, not Material Symbol), a richer tonal background card, or a
secondary outlined button alongside the primary button.

**(c) Recommended refactor.**
- Add `iconTint: Color = LocalContentColor.current.copy(alpha = 0.6f)`
- Add `iconSize: Dp = 64.dp`
- Replace `icon: ImageVector` with `icon: @Composable () -> Unit` (slot)
  so callers can pass a Material Symbol or a Coil-loaded illustration
- Add optional `secondaryActionLabel: String?` + `onSecondaryAction:
  (() -> Unit)?` for two-button empty states

`ErrorState` should compose `EmptyState` internally with `iconTint =
error`, not duplicate the column.

**(d) Impact if NOT done.** YT-0014 will fork
`PlaylistEmptyState` and `RecentlyPlayedEmptyState`. **Partial defer
acceptable** — landing the icon-slot change now is enough; the
secondary button can wait. ~6 lines.

---

### `navigation/AppNavigation.kt`

#### Finding 16 — Icons are `androidx.compose.material.icons.rounded.*`, not Material Symbols Rounded variable font. **LOW.**

**(a) Today.** `Icons.Rounded.Search`, `Icons.Rounded.LibraryMusic`,
`Icons.Rounded.Settings`. These are the Compose-bundled Material Icons,
which are the **fixed-weight** rounded variant.

**(b) Constraint.** Project tokens specify "Material Symbols **Rounded**"
which strictly means the variable-weight Symbols font (`fill`, `wght`,
`grad`, `opsz` axes) — not the legacy Icons collection. The two share a
visual family but the Symbols font supports filled/outlined toggle on
selected nav state, which both YT-0013 and YT-0014 use.

**(c) Recommended refactor.** Bundle Material Symbols Rounded as a Font
asset and provide an `IconKey` enum + `Icon(icon: IconKey, filled:
Boolean)` composable that draws the codepoint. Switch
`NavigationBarItem`'s icon to use `Icon(IconKey.Search, filled = selected)`
so selected tabs show the filled glyph (M3 NavigationBar standard
behavior).

**(d) Impact if NOT done.** Aesthetic only — the legacy Icons render
correctly; selected-tab fill toggle isn't a hard YT-0013/YT-0014
requirement. **Defer** unless a separate icon-system task picks it up.

---

### `MainActivity.kt`

#### Finding 13 — Demo Track risk: already resolved. **NONE.**

The brief says `MainActivity` passes a hardcoded demo `Track`. The code
on disk doesn't — `MainActivity` only calls `setContent { YourTubeTheme
{ AppShell(...) } }` and `AppShell` reads `playerState.currentTrack`
from `PlayerViewModel`. The state-shape mismatch concern is already
mitigated. No action.

`Track`'s shape (`videoId, title, channel, durationSec, thumbnailUrl`)
is minimal and Media3-friendly — `videoId` maps to `MediaItem.mediaId`,
the rest to `MediaMetadata`. YT-0013 will likely add `artistArtUrl` or
`isLocal: Boolean`; safe to defer.

---

### `core/ui/SearchBar.kt`

No refactor needed. Already stateless, slot-based (`content`), uses
M3 `SearchBar` + `SearchBarDefaults`. YT-0013 / YT-0014 don't touch it.

---

### `core/ui/Placeholder.kt`

Not read in this audit (placeholder file). No action.

---

## A11y migration — Finding 12 — **DEFER, but with a stub commitment**

**(a) Today.** Every `core/ui` composable hardcodes English strings:
`"Skip to next track"`, `"Expand player"`, `"More options for ${...}"`,
`"Now playing: ... by ..."`, etc.

**(b) Constraint.** Reviewer flagged this; YT-0013 and YT-0014 will add
~30 more such strings.

**(c) Recommended approach — defer with discipline.** Migrating now is
a 200-string churn before the new strings are even written. Migrate
**after** YT-0014 lands (so all the strings exist) but commit now to:

1. Stub the resource keys in `app/src/main/res/values/strings.xml` for
   the strings you _will_ need — even before they're used. Naming
   convention: `cd_<component>_<action>` (e.g. `cd_mini_player_expand`,
   `cd_track_row_more`).
2. Add a `lint.xml` rule (or comment in `Placeholder.kt`) that
   discourages new hardcoded strings in `core/ui`.
3. Schedule the migration as YT-0011-followup, not part of YT-0011 close.

**(d) Impact if NOT done in 0011.** None on the design surface. Pure
i18n debt. **Defer.**

---

## What the shell can safely defer

- **Finding 7** (NowPlaying background token) — YT-0013 rewrites the
  whole file; let them fix it in passing.
- **Finding 16** (Material Symbols variable font) — aesthetic; not
  blocking.
- **Finding 12** (a11y → strings.xml) — pure i18n debt; do after YT-0014.
- The secondary-button slot on `EmptyState` (Finding 10 stretch).

## What the shell MUST absorb now

- **Finding 1** — `bottomBar` extraction so YT-0014 FAB doesn't collide.
- **Finding 2** — NowPlaying as Navigation destination so YT-0013 can
  pick any of three presentation patterns.
- **Finding 3** — `SharedTransitionLayout` host at AppShell level.
- **Finding 4** — Brand purple `ColorScheme` fallback wired in.
- **Finding 5** — `LocalReduceMotion` plumbed through theme.
- **Finding 6** — MiniPlayer surface token: `surfaceContainerHigh`.
- **Finding 8** — `TrackRow` parameter slots (leading / trailing /
  long-press / showEqIndicator).
- **Finding 9** — `MiniPlayer` parameters (`isBuffering`,
  `artworkModifier`).
- **Finding 11** — `PlaylistRow` cover slot.
- **Finding 14** — `LocalAppShellInsets` centralized.
- **Finding 15** — folded into Finding 2.

Total absorb-now diff estimate: **~150 lines net**, spread across 5
files. Two of these (Findings 1+2) are coupled — do them together.

---

## Recommended absorb order

1. Theme: brand `ColorScheme` + `LocalReduceMotion` (Findings 4, 5) —
   no dependencies.
2. `core/ui` parameter additions (Findings 6, 8, 9, 10, 11) — back-
   compatible defaults; no callers break.
3. AppShell refactor (Findings 1, 2, 3, 14, 15) — biggest change, do
   last so the smaller component changes are already proven.

After that the YT-0013 and YT-0014 implementations should land without
re-opening YT-0011 files.
