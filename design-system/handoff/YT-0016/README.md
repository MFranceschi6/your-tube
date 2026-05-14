# YT-0016 — Android Playlist Sharing · Handoff Package

> **Audience:** Claude Code, implementing playlist sharing on Android in Jetpack Compose + Material 3.
> **Status:** documents a **shipped** implementation. Two follow-up fixes (YT-0068, YT-0047) already closed undocumented gaps in this surface — this folder exists so the next implementer does not re-open them.
> **File format:** owned by [`docs/api-contracts.md`](../../../docs/api-contracts.md) (`.ytplaylist.json`, MIME `application/json`, integer `schemaVersion`). The UX wraps that format; it does not redefine it.
> **Companion iOS handoff:** [`design-system/handoff/YT-0030/`](../YT-0030/) — same 10 decisions, platform-adapted.

This handoff has **precedence** over every other reference for this surface. Where it disagrees with an HTML mockup or an inline icon reference, this folder wins.

## What's in this folder

| File | Purpose |
|---|---|
| `README.md` | This file. Read first. |
| `decision-log.md` | The 10 decisions with rationale + anti-pattern per entry. Mirrors `YT-0030/decision-log.md` decision-for-decision. |
| `compose-spec.md` | View hierarchy, ViewModel surface, `ActivityResultContracts.OpenDocument`, `FileProvider` wiring, state-machine, file paths. |
| `haptics-and-a11y.md` | TalkBack announcements, live-region rules, hit targets, Dynamic Type, haptic table for export/import op events. |
| `mockup.html` | Export bottom-sheet idle state on the Android-360 frame. Use as the visual anchor when reading the spec. |
| `mockup-states.html` | All 10 states (export idle / picker / op-loading / file-picker / import success / 4 error variants / Library overflow / context menu). |

## Existing system context — cite, do not redefine

- **State catalog cells** — `design-system/handoff/state-catalog/` C15 (op loading overlay) and C16 (op inline error). The sharing surface consumes these — no per-screen empty/loading/error variants live here.
- **Toast copy** — `design-system/handoff/toast-catalog/copy.md`. T05 ("Exported `"{{playlistName}}"`", short, no action), T06 ("Imported {{N}} tracks", short, no action), T13 ("Couldn't save", long, `Retry`) are the only confirmation surfaces; do NOT add a success dialog (Q9).
- **Iconography** — `design-system/handoff/symbol-map/symbol-map.md`. `share` → `Icons.Rounded.Share`; `delete`/destructive → `Icons.Rounded.Delete`. The export-from-file metaphor uses `Icons.Rounded.Upload` (per symbol-map "export (future)" — promoted to current by this ticket); import uses `Icons.Rounded.Download`.
- **Tokens** — `design-system/tokens/tokens.json`. Accent `#8B5CF6` (Material You dynamic on Android 12+); surfaces follow the `surface` / `surfaceContainer` / `surfaceContainerHigh` ladder.
- **File format** — `docs/api-contracts.md` § Playlist export file. Schema version is an integer; future-schema rejection is non-graceful (decision 5).
- **Fixtures** — `docs/fixtures/playlist-valid-v1.ytplaylist.json` and `docs/fixtures/playlist-future-schema.ytplaylist.json` are the canonical round-trip and reject test inputs.

## Shipped fixes — already closed, do NOT reopen

These two bugs landed before this handoff was written. The decisions below encode both fixes; future implementers must not undo them.

1. **YT-0068** — Android `ACTION_OPEN_DOCUMENT` MIME filter was originally `["*/*"]`, then briefly `["application/json", "application/octet-stream", "text/plain", "application/zip"]`. Narrowed to **`["application/json", "application/octet-stream"]`** because `text/plain` accidentally surfaced every `.txt` in the user's Downloads and `application/zip` matched some AirDroid bundles. The current array is documented in decision 1; do not re-broaden.
2. **YT-0047** — sibling iOS fix; not directly an Android concern but cross-referenced by the cross-platform parity tests. Android remains unaffected because `ACTION_OPEN_DOCUMENT` uses the system file picker, not `Intent.ACTION_VIEW`.

## Implementation order (do not skip)

1. **Format codec** — already shipped (`KotlinxPlaylistCodec`, `core/data/codec/`). Sanity-check the parity test (`CrossPlatformParityTest.kt`) still passes against the iOS-canonical fixture before touching UI.
2. **`ExportSheet`** (`ModalBottomSheet`) — playlist name, track count, size estimate, Share + Save buttons. Wire `FileProvider` BEFORE the `Intent.ACTION_SEND` button — sending a `file://` URI on API 24+ throws `FileUriExposedException`.
3. **Op loading overlay (C15)** — full-screen non-cancellable, `CircularProgressIndicator`. Cite the state-catalog cell; do not draw a custom spinner.
4. **`ImportLauncher`** — `rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument())` with the **two-element** MIME array (decision 1).
5. **Import success preview sheet** — name + N tracks + Done / View Playlist.
6. **Four error states** (decision 7) — distinct copy + icon per variant. Reuse C16 inline-error styling.
7. **Entry points** (decision 3) — Library top-app-bar overflow, per-playlist long-press context menu, Settings § Playlists rows.
8. **Toast confirmations** — wire `Imported "{name}" — {n} tracks` (T06) AFTER the success preview's Done/View Playlist navigation resolves, not before.

## Tokens — non-negotiable

- **Accent**: `MaterialTheme.colorScheme.primary` (dynamic on API 31+; brand fallback `#8B5CF6` / `#D0BCFF` in dark on API ≤30).
- **Export-sheet surface**: `colorScheme.surfaceContainerHigh` (M3 `ModalBottomSheet` default — do not override unless the design system changes).
- **Import success sheet surface**: same as above.
- **Op loading overlay**: scrim `colorScheme.scrim.copy(alpha = 0.6f)`, indicator `colorScheme.primary`.
- **Tap targets**: 48 dp minimum via `Modifier.minimumInteractiveComponentSize()`.
- **Corner style**: `MaterialTheme.shapes.large` for the sheet top corners (default 28 dp on M3).
- **Toast surface**: `colorScheme.inverseSurface` (M3 `SnackbarHost` default).

## Privacy — what the exported file contains and does NOT contain

The exported `.ytplaylist.json` contains, in this order: `schemaVersion`, `id`, `name`, `createdAt`, `updatedAt`, `tracks[]`. Each `Track` is `videoId`, `title`, `channel`, `durationSec`, `thumbnailUrl`. **Nothing else.** No auth tokens, no signing credentials, no `Cookie` header, no device identifiers, no install ID, no anonymous telemetry ID, no session state, no `androidId`, no `Settings.Secure.ANDROID_ID`, no `Build.SERIAL`. See decision 6 for the anti-pattern enforcement.

## Aesthetic decisions binding

When implementation has an open question and the spec is silent: **native M3 control > custom**, `ModalBottomSheet` > hand-rolled sheet, system file picker > in-app file browser, toast > dialog (Q9), C15/C16 catalog cells > one-off spinners and error views. Do not invent a custom share-target picker — `Intent.ACTION_SEND` already gives you the system one.
