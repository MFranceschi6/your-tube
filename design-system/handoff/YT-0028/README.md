# YT-0028 · Library & Playlist Management — Handoff

Personal iOS audio player. Library tab + playlist CRUD + track management.

## Files

| File | Purpose |
|---|---|
| `decision-log.md` | Q1–Q10 decisions with HIG rationale, Apple-app references, anti-patterns. |
| `swiftui-spec.md` | View hierarchy, SwiftData models, native code patterns. |
| `mockup.html` | Interactive HTML mockup — Library + PlaylistDetail (at-rest). |
| `mockup-states.html` | Static states the interactive mockup doesn't show: empty Library, edit mode, Add-to-Playlist sheet. |

## Aesthetic direction

Inherits from YT-0027. See [`../YT-0027/aesthetic-direction.md`](../YT-0027/aesthetic-direction.md) — same binding tone (calm, content-first, Podcasts-inspired).

## Build order

1. `LibraryScreen` shell — `.insetGrouped` List + Recently Played row + empty state.
2. `PlaylistRow` with hybrid cover (1 / 2-3 / 4+).
3. Create flow (sheet → save → push detail).
4. `PlaylistDetailScreen` — parallax cover + Play/Shuffle pills + TrackRow list.
5. Native edit mode (`.onMove`, `.onDelete`, `.swipeActions`, `EditButton`).
6. Add-to-playlist sheet.
7. Rename `.alert` + Delete `.confirmationDialog` + Undo snackbar.
8. Empty state + a11y pass.

## Key overrides from mockup

- ❌ Custom `✕` + drag handle outside edit mode → ✅ Native `EditButton` + system glyphs in `.editMode` only.
- ❌ Three pill buttons (Play / Shuffle / Share) → ✅ Two pills (Play / Shuffle); Share to ellipsis menu.
- ❌ Bottom sheet for rename → ✅ `.alert` + `TextField` (iOS 16+).
- ❌ Custom illustration empty state → ✅ `ContentUnavailableView` + SF Symbol.

## Aesthetic decisions binding

When implementation has an open question and the spec is silent: **do less, not more**. See `../YT-0027/aesthetic-direction.md`. Default tie-breakers apply: native control > custom; system style > hand-drawn glyph; size hierarchy > chrome.
