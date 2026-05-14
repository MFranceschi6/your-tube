# YT-0026 — iOS Search · Handoff Package

> **Audience:** Claude Code, implementing the iOS Search screen in SwiftUI on iOS 17+ (with iOS 26 Liquid Glass affordances noted).
> **Source mockups:** `mockup.html` (idle + results + active row) and `mockup-states.html` (all nine states).
> **Design system:** `docs/design-system.md` (product-level) and `design-system/tokens/tokens.json` (canonical tokens).
> **Cross-platform sibling:** `design-system/handoff/YT-0012/` (Android Search).
> **State catalog:** `design-system/handoff/state-catalog/` — Search consumes cells **C1 / C2 / C3 / C4 / C5** verbatim. **Do not redefine** state copy or layout here; cite the catalog.
> **Symbol map:** `design-system/handoff/symbol-map/symbol-map.md` is the canonical icon source.
> **Not a binding visual spec** — match the *behavior* and *tokens*. Use SwiftUI idioms.

This handoff **documents the shipped behavior** of the Search workflow on iOS and locks the post-implementation decisions. Where this folder and the legacy mockup at `design-system/mockups/ios/YT-0025-0026-0027-shell-search-player.html` disagree, this folder wins.

## What's in this folder

| File | Purpose |
|---|---|
| `README.md` | This file. Read first. |
| `decision-log.md` | D1–D10 decisions with rationale + anti-patterns. Native `.searchable(text:)` vs. custom TextField, debounce, EQ indicator (`TimelineView(.animation)`), state-catalog citations, voice-search deferral, Liquid Glass surface call. |
| `swiftui-spec.md` | View hierarchy, `@Observable SearchViewModel`, `.searchable(text:)` wiring, `TimelineView`-driven EQ bars, state-catalog cell integration. |
| `haptics-and-a11y.md` | `.sensoryFeedback` table, VoiceOver labels, `UIAccessibility.post(.announcement)` rules, focus-on-tab-activation contract, Dynamic Type behavior. |
| `mockup.html` | Static reference render: idle (chips), results list, active EQ row. iOS-393 frame, dark theme. |
| `mockup-states.html` | All nine states laid out side-by-side: idle, focused, loading (C1), results, no-results (C3), error (C4), offline (C5), chip-selected, row-context-menu. |

## Cross-reference matrix

| Surface concern | Lives in |
|---|---|
| State copy / layout (loading, no-results, error, offline) | `state-catalog/copy.md` + `state-catalog/loading.md` + `state-catalog/error.md` + `state-catalog/empty.md` (cells C1–C5) |
| Icon vocabulary (`magnifyingglass`, `wifi.slash`, `exclamationmark.triangle`) | `symbol-map/symbol-map.md` |
| Toast / banner copy (e.g. "Removed from recent searches") | `toast-catalog/copy.md` |
| Now Playing presentation target (tap-to-play) | `YT-0027/decision-log.md` §10 + `YT-0074/motion-spec.md` |
| Search history clearing UI (full clear) | `YT-0031` Settings § Data (this folder governs only the per-chip context-menu remove) |

## Implementation order (do not skip)

1. `SearchScreen` + `@Observable SearchViewModel` + `SearchUiState` enum + `Combine`-style `.debounce` on the query stream.
2. `.searchable(text:)` wiring on the `NavigationStack` root, with `.searchPresentationToolbarBehavior(.avoidHidingContent)` (iOS 17+). Native focus-on-tab-activation is **40% of the win** — don't shortcut it.
3. Idle state — chip grid (`LazyVGrid` 1-row layout works; the chip strip is logically a horizontal `LazyHStack` — see D3 for the call).
4. Loading (C1) → results (`List` of `TrackRow`) wiring with `.listStyle(.plain)` and `.scrollDismissesKeyboard(.immediately)`.
5. Active-row EQ indicator overlay on `TrackRow` — `TimelineView(.animation)` driving three `Capsule()`s with phase-offset sine waves.
6. State-catalog cell integration: C3 (no-results), C4 (error), C5 (offline). Use `ContentUnavailableView` for C2/C3 and the catalog's `ErrorState` host for C4/C5.
7. Result-row context menu (`.contextMenu` — Play now / Play next / Add to queue / Add to playlist / Share).
8. Voice search — deferred. Add `// TODO(post-MVP): voice search` placeholder; ship nothing visible.

Ship 1–6 before worrying about 7. A perfect Search screen with broken offline copy is worse than a plain Search screen with the catalog's offline cell wired correctly.

## Tokens — non-negotiable

- Accent: `Color.accent` from the asset catalog (asset value: `#8B5CF6` dark / `#7C3AED` light per `tokens.json` § `color.brand.accent`). Set `.tint(.accent)` at `RootTabView`; do **not** hardcode the hex anywhere downstream.
- Search field fill: `Color(.tertiarySystemFill)` (matches the native `.searchable` field appearance — do not override unless taking the custom-field branch in D1).
- Active-row tint: `Color.accent.opacity(0.16)` — **same value as the NowPlaying active TrackRow** (YT-0027 §Q11 web-isms). Single source of truth across the app.
- EQ bar color: `Color.accent` (full alpha while playing; `.opacity(0.6)` when paused).
- Chip surface: `.tint(Color(.tertiarySystemFill))` for unselected (`.buttonStyle(.bordered)`); selected chip uses `.tint(.accent)` with `.buttonStyle(.borderedProminent)`.
- Skeleton: state-catalog `--skeleton-bg = #2C2C2E` — already wrapped in the catalog's `SkeletonTrackRow`. Do not re-author.
- Hit targets: 44 pt minimum via `.contentShape(Rectangle())` decoupling visible glyph from gesture area.
- Corner style: **always** `.continuous`. `.circular` (the implicit default on some shapes) is wrong here.
- Debounce: **400 ms**. Return key / submit fires immediately, bypassing debounce.

## Liquid Glass on iOS 26+

**Decision: the SearchBar surface does NOT render in a Liquid Glass container in MVP. See D1 for the call.**

When `#available(iOS 26, *)` is true, the system `.searchable` field already participates in Liquid Glass when docked on a `.toolbar(...) { ToolbarItem(.search) { ... } }` slot (new iOS 26 placement). Our app uses the **navigation-root `.searchable(text:)`** placement (D1), which renders the field below the large nav title in a translucent material — that is iOS 26's default and we accept it as-is. No `.glassEffect(...)` overrides, no custom `Material` backgrounds.

The action-row buttons on Now Playing (YT-0027 §Q7) and the tab bar do render in Liquid Glass on iOS 26 per the system. Search has no per-screen Liquid Glass surface of its own — the only glass surface visible in Search comes from system chrome (tab bar bottom, toolbar top).

## Mockup web-isms — do NOT translate literally

| Mockup CSS | SwiftUI equivalent |
|---|---|
| `backdrop-filter: blur(20px)` on tab bar | System tab bar already uses `.regularMaterial` — do not redraw. |
| `box-shadow` under SearchBar | Drop. Native `.searchable` provides the appearance. |
| `border-radius: 18px` rounded chip | `.buttonBorderShape(.capsule)` or `Capsule()` |
| `rgba(139,92,246,0.16)` active-row tint | `Color.accent.opacity(0.16)` |
| `rgba(139,92,246,0.30)` chip selected | `.tint(.accent)` + `.buttonStyle(.borderedProminent)` (the system handles tint folding) |
| `@keyframes eq` 600ms | `TimelineView(.animation(minimumInterval: 1.0 / 30.0))` + `sin(...)` per-bar phase offset |
| `transition: width 200ms` chip-selection swap | `.animation(.easeInOut(duration: 0.2), value: selection)` |
| `font-feature-settings: "tnum"` | `.monospacedDigit()` |
| Hand-drawn magnifier SVG | `Image(systemName: "magnifyingglass")` |
| Hand-drawn search-off SVG | `Image(systemName: "magnifyingglass").symbolVariant(.slash)` (per `symbol-map.md` no-results note) |
| Hand-drawn wifi-off SVG | `Image(systemName: "wifi.slash")` |
| Hand-drawn close SVG | `Image(systemName: "xmark.circle.fill")` |

See `swiftui-spec.md` for the full mapping including `.searchable` state machine and `TrackRow` reuse.

## Aesthetic decisions binding

When implementation has an open question and the spec is silent: **do less, not more**. Default tie-breakers: native control > custom; SF Symbols > hand-drawn; size hierarchy > chrome; `.continuous` corners > `.circular`; `accessibilityReduceMotion` honored everywhere (EQ bars freeze, skeleton shimmer pauses, chip-selection cross-fade snaps).
