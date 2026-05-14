# YT-0025 — iOS App Shell · Handoff Package

> **Audience:** Claude Code and iOS engineers implementing or reviewing the iOS app shell.
> **Primary mockup:** `design-system/mockups/ios/YT-0025-0026-0027-shell-search-player.html`
> **Source of truth for this surface.** Where this folder and the HTML mockup disagree, this folder wins.

---

## What this package covers

The app shell is the outermost host of the iOS app. It owns:

- Tab structure (`TabView`, three tabs: Search / Library / Settings).
- The persistent `MiniPlayer` docked above the tab bar via `safeAreaInset`.
- The `@Namespace` for the NowPlaying artwork hero transition.
- The `fullScreenCover` presentation of `NowPlayingView`.
- The tab bar hide/show animation driven by `AppShellViewModel.isNowPlayingOpen`.
- Shell-level `.sensoryFeedback` for track taps and error banners.
- `.onOpenURL` routing for playlist file imports (YT-0030).

This package does **not** cover:

- NowPlaying screen internals — see `design-system/handoff/YT-0027/`.
- Library / Playlist screens — see `design-system/handoff/YT-0028/`.
- MiniPlayer ↔ NowPlaying motion detail — see `design-system/handoff/YT-0074/motion-spec.md`.
- Empty / loading / error states per screen — see `design-system/handoff/state-catalog/`.

---

## Files in this folder

| File | Purpose |
|---|---|
| `README.md` | This file. Scope, index, cross-references. |
| `decision-log.md` | Design decisions — tab order, MiniPlayer strategy, namespace location, presentation mode, materials, gestures, provider hierarchy, Dynamic Type. |
| `swiftui-spec.md` | Concrete view hierarchy, exact file paths, modifier placement, `@Namespace` contract, `safeAreaInset` signature. |
| `haptics-and-a11y.md` | VoiceOver focus contract, MiniPlayer a11y group, `UIAccessibility.post` calls, haptics table, reduce-motion fallback. |

Reading order: README → decision-log → swiftui-spec → haptics-and-a11y.

---

## Relationship to other handoffs

| Handoff | What it adds |
|---|---|
| `YT-0027/` | NowPlaying screen internals — artwork, scrubber, transport, queue, action row. |
| `YT-0028/` | Library tab internals — playlist CRUD, edit mode, add-to-playlist sheet. |
| `YT-0074/motion-spec.md` | Canonical timing tokens (320 ms expand, 260 ms collapse, 120 ms reduce-motion). Per-platform SwiftUI mapping. |
| `state-catalog/` (YT-0073) | Empty / loading / error states for each list-driven screen hosted inside these tabs. |

The shell is the outermost boundary: it injects the `@Namespace` and `AppShellViewModel` that `YT-0027` and the MiniPlayer depend on. Read YT-0025 before YT-0027 if you are new to the codebase.

---

## Implementation status

As of 2026-05-09, the shell is **substantially implemented**. Decision log and swiftui-spec entries are marked `[Implemented]` or `[Proposed]` to indicate what is live in the codebase vs. what is a design decision not yet landed.
