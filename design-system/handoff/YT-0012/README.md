# YT-0012 — Android Search · Handoff Package

> **Audience:** Claude Code, implementing the Android Search screen in Jetpack Compose + Material 3.
> **Source mockups:** `mockup.html` (idle + results + active row) and `mockup-states.html` (all nine states).
> **Design system:** `docs/design-system.md` (product-level) and `design-system/tokens/tokens.json` (canonical tokens).
> **Cross-platform sibling:** `design-system/handoff/YT-0026/` (iOS Search).
> **State catalog:** `design-system/handoff/state-catalog/` — Search consumes cells **C1 / C2 / C3 / C4 / C5** verbatim. **Do not redefine** state copy or layout here; cite the catalog.
> **Symbol map:** `design-system/handoff/symbol-map/symbol-map.md` is the canonical icon source. Do not invent glyphs.
> **Not a binding visual spec** — match the *behavior* and the M3 *tokens*. Use Compose idioms.

This handoff **documents the shipped behavior** of the Search workflow and locks the post-implementation decisions. It is not a greenfield proposal. Where this folder and the legacy combined mockup at `design-system/mockups/android/YT-0011-0012-0013-shell-search-player.html` disagree, this folder wins.

## What's in this folder

| File | Purpose |
|---|---|
| `README.md` | This file. Read first. |
| `decision-log.md` | D1–D10 decisions with rationale + anti-patterns. M3 tokens, debounce, EQ indicator, state-catalog citations, voice-search deferral. |
| `compose-spec.md` | View hierarchy, `SearchUiState` shape, `SearchBar` state machine, `TrackRow` reuse, EQ overlay, file layout. |
| `haptics-and-a11y.md` | `LocalHapticFeedback` table, TalkBack labels, live-region announcements, focus-on-tab-activation contract. |
| `mockup.html` | Static reference render: idle (chips), results list, active EQ row. Android-360 frame, dark theme. |
| `mockup-states.html` | All nine states laid out side-by-side: idle, focused, loading (C1), results, no-results (C3), error (C4), offline (C5), chip-selected, long-press context menu. |

## Cross-reference matrix

| Surface concern | Lives in |
|---|---|
| State copy / layout (loading, no-results, error, offline) | `state-catalog/copy.md` + `state-catalog/loading.md` + `state-catalog/error.md` + `state-catalog/empty.md` (cells C1–C5) |
| Icon vocabulary (search, search_off, wifi_off, error) | `symbol-map/symbol-map.md` |
| Toast / snackbar copy (e.g. "Cleared search history") | `toast-catalog/copy.md` |
| Now Playing transition target (tap-to-play) | `YT-0013/decision-log.md` Q12 + `YT-0074/motion-spec.md` |
| Search history clearing UI (full clear) | `YT-0017` Settings § Data (this folder governs only the per-chip long-press remove) |

## Implementation order (do not skip)

1. `SearchScreen` composable + `SearchUiState` sealed shape + `SearchViewModel` (debounce + IME-action fast-path).
2. `SearchBar` (M3 `SearchBar` on `LargeTopAppBar` slot) + clear / cancel affordances. Focus-on-tab-activation is **40% of the win** — don't shortcut it.
3. Idle state — `LazyRow` of suggestion chips (recent + curated), tap-fills, long-press removes.
4. Loading (C1) → results (`LazyColumn` of `TrackRow`) wiring.
5. Active-row EQ indicator overlay on `TrackRow` — `tween(600ms, infinite)` keyed to `result.videoId == playerState.currentTrack?.videoId AND playerState.isPlaying`.
6. State-catalog cell integration: C3 (no-results), C4 (error), C5 (offline). Reuse the catalog's `EmptyState` / `ErrorState` primitives. **Do not redefine.**
7. Long-press context menu on result row (add to playlist, share).
8. Voice search — deferred. Add `// TODO(post-MVP): voice search` placeholder; ship nothing visible.

Ship 1–6 before worrying about 7. A perfect Search screen with broken offline copy is worse than a plain Search screen with the catalog's offline cell wired correctly.

## Tokens — non-negotiable

- Accent: `MaterialTheme.colorScheme.primary` — Material You on Android 12+; brand fallback `#8B5CF6` (`#D0BCFF` in dark) per YT-0013 §Q10. Set at `MaterialTheme(...)` root.
- Search-bar fill: `MaterialTheme.colorScheme.surfaceVariant` (matches token `background.surface-variant.dark = #2C2C2E`).
- Active-row tint: `colorScheme.primaryContainer.copy(alpha = 0.16f)` — **same value as the NowPlaying active TrackRow** (YT-0013 §Q12 web-isms table). Single source of truth across the app.
- EQ indicator bars: `colorScheme.primary` at `alpha = 1f` while playing; `alpha = 0.6f` and frozen on pause.
- Chip surface: `colorScheme.surfaceVariant`; chip selected: `colorScheme.secondaryContainer`.
- Skeleton: `--skeleton-bg = #2C2C2E` per state-catalog (Compose: `colorScheme.surfaceVariant`).
- Tap targets: 48 dp minimum via `Modifier.minimumInteractiveComponentSize()`; chip hit target is 36 dp visible / 48 dp gesture.
- Debounce: **400 ms**. IME `Search` action fires immediately, bypassing debounce.

## Mockup web-isms — do NOT translate literally

| Mockup CSS / pattern | Compose / M3 equivalent |
|---|---|
| `backdrop-filter: blur(20px)` on chip strip | Drop. Use `surfaceVariant` solid fill instead. |
| `box-shadow` under SearchBar | Drop. M3 uses tonal elevation; `SearchBar` provides it. |
| `border-radius: 28px` (pill SearchBar) | M3 `SearchBar` shape is built-in; do not override `shape =`. |
| `rgba(139,92,246,0.16)` active-row tint | `colorScheme.primaryContainer.copy(alpha = 0.16f)`. |
| `@keyframes eq` 600ms infinite | `rememberInfiniteTransition(label = "eq")` + `animateFloat(... infiniteRepeatable(tween(600), reverseMode = Reverse))`. |
| Chip `padding: 0 14px; height: 36px` | M3 `FilterChip` / `SuggestionChip` — accept default sizing; do not override. |
| `transition: opacity 200ms` chip-selected | `Crossfade(animationSpec = tween(200))` on chip selection state. |
| Hand-drawn `<svg>` magnifier | `Icons.Rounded.Search` (per `symbol-map.md`). |

See `compose-spec.md` for the full mapping including `SearchBar` state machine and `TrackRow` reuse.

## Aesthetic decisions binding

When implementation has an open question and the spec is silent: **do less, not more**. Default tie-breakers: native M3 control > custom; Material Symbols (rounded) > hand-drawn glyph; size hierarchy > chrome; tonal elevation > drop shadows; `LocalReduceMotion` honored everywhere (EQ bars freeze, skeleton shimmer pauses, chip-selection crossfade snaps).
