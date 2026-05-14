# YT-0029 · iOS Recently Played — Handoff Package

Personal iOS audio player. Recently Played destination, reached from the Library top-app-bar `history` action.

> **Audience:** Claude Code, implementing the iOS Recently Played screen in SwiftUI on iOS 26 with Liquid Glass.
> **Sibling Android handoff:** `design-system/handoff/YT-0015/` — same shipped behavior, idiomatic per platform.
> **Source mockup:** `design-system/mockups/ios/YT-0028-0029-library-history.html` (layout reference only).
> **Status: documents shipped behavior.** `RecentlyPlayedScreen.swift`, `HistoryViewModel.swift`, `HistoryStore.swift`, and the Library `NavigationLink` already ship. This package locks them in — no behavior changes, no overrides.

## Files

| File | Purpose |
|---|---|
| `README.md` | This file. Read first. |
| `decision-log.md` | Q1–Q10 decisions with HIG rationale, Apple-app references, anti-patterns. Mirrors the Android sibling. |
| `swiftui-spec.md` | View hierarchy, `@Observable RecentlyPlayedViewModel`, `Section` + `swipeActions` + `.toolbar`, Liquid Glass note, file paths. |
| `haptics-and-a11y.md` | `.sensoryFeedback` table, VoiceOver row reads, swipe-action announcements, rotor headings, Dynamic Type, Reduce Motion. |
| `mockup.html` | Populated state — iOS 393×852 frame, dark, Liquid Glass tab bar + MiniPlayer. |
| `mockup-states.html` | All 8 states: populated · single-row swiped · "Clear all" confirm · empty (C13) · error (C14) · loading (C12) · context menu · single-row edge case. |

## Aesthetic direction

Inherits from YT-0027 / YT-0028. See [`../YT-0027/aesthetic-direction.md`](../YT-0027/aesthetic-direction.md) — same binding tone (calm, content-first, Podcasts-inspired). When in doubt: **do less**.

## Build order

1. `RecentlyPlayedViewModel` (`@Observable`) — exposes `entries: [HistoryEntry]`, `isLoading`, `error`. Reads from `HistoryStore` (already wired per YT-0048).
2. `RecentlyPlayedScreen` shell — `NavigationStack` reached as the destination of the Library `NavigationLink`. Title "Recently Played" (large, collapses to inline on scroll).
3. Body branches off the view model: skeleton (C12) → empty (C13) → error (C14) → content.
4. Content: `List` with `Section(header:)` per day group + `ForEach` of `TrackRow` + `swipeActions(edge: .trailing) { Button(role: .destructive) }`.
5. `.contextMenu` per row — Play next · Add to queue · Add to playlist · Remove from history · Share.
6. `.toolbar { ToolbarItem(.topBarTrailing) { Menu { Button("Clear All", role: .destructive) }}}` opening an `.alert` (see Q6).
7. MiniPlayer coexistence via `.safeAreaInset(edge: .bottom)`.

## Liquid Glass (iOS 26+)

The screen does NOT apply a glass material as a list background — see Q3 in the decision log. The persistent system chrome (tab bar + navigation bar) already carries the Liquid Glass treatment; the list body stays on `Color(.systemBackground)` so the day-section headers don't ride a translucent ground that interferes with sticky-header legibility.

## Key overrides from mockup

- ❌ Custom row "✕" or trash glyph at rest → ✅ Native `.swipeActions(edge: .trailing) { Button(role: .destructive) }` only.
- ❌ Toolbar standalone "Clear" text button → ✅ Trailing toolbar `Menu` (ellipsis) opening an `.alert` (Q6).
- ❌ Inlining a "history paused" link in the empty state body → ✅ Empty state copy-only, deferred (Q8).
- ❌ Flat newest-first list → ✅ Sectioned by day (Today / Yesterday / `d MMM yyyy`) with the native sticky-header treatment (Q1).

## Aesthetic decisions binding

When implementation has an open question and the spec is silent: **do less, not more**. Default tie-breakers:

1. Native control > custom (`swipeActions`, `Menu`, `.alert`, `ContentUnavailableView`).
2. System style > hand-drawn glyph (SF Symbols only).
3. Size hierarchy > chrome (large nav title; the page IS the date scale; no chrome cards).
4. Sectioned `List` > grouped sections in a `LazyVStack`.

See `decision-log.md` for full rationale.
