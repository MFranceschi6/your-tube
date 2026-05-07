# YT-0014 — Android Library · Handoff Package (v2 / MVP)

> **Audience:** Claude Code, implementing the Android Library tab in Jetpack Compose + Material 3.
> **Source mockup:** `design-system/mockups/android/YT-0014-0015-0016-0017-library-history-settings-sharing.html` — combined library/history/settings/sharing canvas (layout reference only).
> **Design system:** `docs/design-system.md` and `design-system/colors_and_type.css`.
> **Scope:** **Playlists tab only.** History is YT-0015 (separate screen). Downloads / Liked are post-MVP.

This handoff has **precedence** over the HTML mockup. Where they disagree, the handoff wins.

> **v2 redo notice.** This package was rewritten under YT-0075 to close four review blockers from the first run (missing Extended FAB, missing 4-up cover, missing in-place edit mode, missing MiniPlayer-aware bottom padding) and trim the spec back to MVP scope (Playlists only, no `SecondaryTabRow`/pager). The original 4-tab work is preserved in `v1x-tabs-addendum.md` for post-MVP reference.

## What's in this folder

| File | Purpose |
|---|---|
| `README.md` | This file. Read first. |
| `decision-log.md` | Q1–Q10 decisions for the Playlists-only MVP: Extended FAB, 4-up cover (with <4 fallbacks), in-place edit mode, MiniPlayer-aware bottom padding, destructive-undo pattern, empty state copy. |
| `compose-spec.md` | `LibraryScreen` (Playlists list + FAB) and `PlaylistDetailScreen` (cover + header + list + edit mode) view hierarchies, Modifier chains, file layout, mockup→Compose token map. |
| `haptics-and-a11y.md` | Haptics table, fontScale clamp, reduce-motion, TalkBack — including drag-handle accessibility actions and MiniPlayer-overlap focus order. |
| `media3-parity.md` | Trimmed for the Playlists-only scope. References to Downloads/History/Liked tabs removed. |
| `aesthetic-direction.md` | Tone, density, type rhythm, motion language. Preserved from v1. |
| `mockup.html` | Pointer + drift list to the combined HTML mockup. |
| `mockup-states.html` | Empty / populated / edit-mode / remove-confirm states for visual reference. |
| `v1x-tabs-addendum.md` | The original 4-tab spec (Playlists · Downloads · History · Liked) — for post-MVP, not the MVP build target. |
| `colors_and_type.css` / `android-frame.jsx` | Carry-overs from v1. Untouched. |

## Implementation order

1. `LibraryScreen` — top-app-bar + playlists `LazyColumn` + Extended FAB. Uses `safeBottomInset` from `LocalMiniPlayerState` (decision Q4).
2. `PlaylistRow` with **4-up cover** (decision Q2) and `<4` fallbacks.
3. `PlaylistDetailScreen` — header (cover + title + meta) + tracks list. Read-only first.
4. **Edit mode** (decision Q3) — top-app-bar "Edit" toggles `isEditing`; rows render drag-handle + remove-button; commit-on-release reorder; **toast-with-undo** on remove.
5. `LocalMiniPlayerState` provider in `AppShell` so every list and the FAB lift consistently (decision Q4).
6. Empty state for "No playlists yet" with CTA to the FAB (decision Q9).

Steps 1–4 are MVP. Step 5 is shared with YT-0013 / YT-0011; consume the existing provider if it lands first.

## Tokens — non-negotiable

- Surface: `colorScheme.surface` (body), `surfaceContainer` (bottom sheets, dialogs, snackbars).
- FAB: `ExtendedFloatingActionButton` defaults — `containerColor = colorScheme.primaryContainer`, `contentColor = colorScheme.onPrimaryContainer`. Do **not** override.
- Cover radius: `MaterialTheme.shapes.medium = 12.dp`.
- Edit-mode remove icon tint: `colorScheme.error`.
- Edit-mode drag handle tint: `colorScheme.onSurfaceVariant`.
- Snackbar (undo): default M3 `Snackbar` colors; action label `colorScheme.inversePrimary`.
- Tap targets: 56 dp default for `ListItem`; 48 dp for icon-only actions.
- `safeBottomInset` math (Q4): NavBar 80 dp + MiniPlayer 64 dp + gap 8 dp = **152 dp** when MiniPlayer visible; **88 dp** otherwise.

## Aesthetic decisions binding

- **Extended FAB is the only "create playlist" entry point in MVP.** The empty state CTA points at it; the top-app-bar overflow does not duplicate it.
- **No tabs in MVP.** History lives on its own screen (YT-0015) reachable from a top-app-bar action; Downloads / Liked do not exist yet.
- **4-up cover is the brief.** Single-color tile + glyph is the *0-track* fallback only.
- **Toast-with-undo for destructive removes**, not a confirm dialog. 6 s window via `SnackbarHostState.showSnackbar(...)`. Confirm-dialogs are reserved for whole-playlist deletion (Q8).

## Override convention

When an override of YT-0072's original ask is proposed, the override entry in `decision-log.md` must:
1. Cite the original ask verbatim.
2. Name the alternative and where it lives.
3. Tag the decision **`override-pending-reviewer-approval`** until the reviewer signs off.

This package does **not** override the four blockers from YT-0075 — they are now satisfied as specified.

See `decision-log.md` for full rationale.
