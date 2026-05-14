# YT-0030 — iOS Playlist Sharing · Handoff Package

> **Audience:** Claude Code, implementing playlist sharing on iOS in SwiftUI.
> **Status:** documents a **shipped** implementation. Two follow-up fixes (YT-0068, YT-0047) closed undocumented gaps in this surface — this folder exists so the next implementer does not re-open them.
> **File format:** owned by [`docs/api-contracts.md`](../../../docs/api-contracts.md) (`.ytplaylist.json`, MIME `application/json`, integer `schemaVersion`). The UX wraps that format; it does not redefine it.
> **Companion Android handoff:** [`design-system/handoff/YT-0016/`](../YT-0016/) — same 10 decisions, platform-adapted.

This handoff has **precedence** over every other reference for this surface. Where it disagrees with an HTML mockup or an inline icon reference, this folder wins.

## What's in this folder

| File | Purpose |
|---|---|
| `README.md` | This file. Read first. |
| `decision-log.md` | The 10 decisions with rationale + anti-pattern per entry. Mirrors `YT-0016/decision-log.md` decision-for-decision. |
| `swiftui-spec.md` | View hierarchy, `ShareLink`, `.fileImporter`, custom `UTType`, `onOpenURL` placement, state machine, file paths. |
| `haptics-and-a11y.md` | VoiceOver announcements, `.sensoryFeedback` table, Dynamic Type clamps, hit targets, AirDrop / Files receive a11y. |
| `mockup.html` | Export sheet idle state on the iOS-393 frame. Use as the visual anchor when reading the spec. |
| `mockup-states.html` | All 10 states (export idle / share-target picker / op-loading / file-picker / import success / 4 error variants / Library overflow / context menu). |

## Existing system context — cite, do not redefine

- **State catalog cells** — `design-system/handoff/state-catalog/` C15 (op loading overlay) and C16 (op inline error). The sharing surface consumes these — no per-screen empty/loading/error variants live here.
- **Toast copy** — `design-system/handoff/toast-catalog/copy.md`. T05, T06, T13 are the only confirmation surfaces; do NOT add a success dialog (decision 9).
- **Iconography** — `design-system/handoff/symbol-map/symbol-map.md`. `share` → `square.and.arrow.up`; destructive `delete` → `trash`. `square.and.arrow.up.on.square` for the explicit "Export" entry (per symbol-map "export (future)" promoted by this ticket); `square.and.arrow.down` for explicit Import.
- **Tokens** — `design-system/tokens/tokens.json`. Accent `#8B5CF6` via `Color.accentColor` (asset catalog `AccentColor`). On iOS 26 + Liquid Glass, the sheet background may render with `.glassBackgroundEffect()` — decision 2 addresses.
- **File format** — `docs/api-contracts.md` § Playlist export file. Schema version is integer; future-schema rejection is non-graceful (decision 5).
- **Fixtures** — `docs/fixtures/playlist-valid-v1.ytplaylist.json` and `docs/fixtures/playlist-future-schema.ytplaylist.json` are the canonical round-trip and reject test inputs.
- **Inherits aesthetic direction from** — [`../YT-0027/aesthetic-direction.md`](../YT-0027/aesthetic-direction.md). Same calm, content-first tone; tie-breakers default to "do less".

## Shipped fixes — already closed, do NOT reopen

These two bugs landed before this handoff was written. The decisions below encode both fixes; future implementers must not undo them.

1. **YT-0047** — iOS Files-app import was broken. The bug had two parts:
   - `.fileImporter`'s `allowedContentTypes` originally listed only `.json`. The system would not surface `.ytplaylist.json` files in Files because UTI inheritance was incomplete — `application/json` registered to `.json` doesn't auto-claim `.ytplaylist.json`.
   - `scene(_:openURLContexts:)` was not implemented, so AirDrop drops resolved to "no app handles this file".

   The fix introduces a **custom `UTType`** (`com.yourtube.playlist`, conforming to `public.json`) declared in `Info.plist` and registered as exported, plus a `.fileImporter([.json, .ytplaylistJSON])` pair, plus a top-level `.onOpenURL` modifier on the root `ContentView` that triggers the import flow. Document each piece in decisions 1 and 8.
2. **YT-0068** — Android-side; cross-referenced by the parity tests. iOS is unaffected because `.fileImporter` is content-type-based, not MIME-array-based.

## Implementation order (do not skip)

1. **Custom `UTType` declaration** — add `com.yourtube.playlist` to `Info.plist` (`UTExportedTypeDeclarations`) AND register it in `Bundle.module` at runtime as a `UTType` extension. Without this, decision 1's `.fileImporter([.json, .ytplaylistJSON])` resolves the second type to `nil` and the picker won't see `.ytplaylist.json` files.
2. **`scene(_:openURLContexts:)` + `.onOpenURL`** — both. The scene method handles cold-launch AirDrop receives (app not running when the drop arrives); the SwiftUI modifier handles warm-launch receives (app already foregrounded). Wire both to the same `ImportRouter`.
3. **`ExportSheet`** — `.sheet { ShareLink(item: fileURL, preview: SharePreview(...)) }` (or `UIActivityViewController` wrapper for the Save-to-Files fallback row).
4. **Op loading overlay (C15)** — `ZStack` with `ProgressView()` over a `Color.black.opacity(0.6)` background. Cite the state-catalog cell.
5. **`.fileImporter` + `ImportRouter`** — single source of truth for all import triggers (Library, context menu, Settings, `onOpenURL`).
6. **Import success preview sheet** — name + N tracks + Done / View Playlist.
7. **Four error states** (decision 7) — distinct copy + SF Symbol per variant. Use `ContentUnavailableView` as the base.
8. **Entry points** (decision 3) — Library toolbar `Menu`, per-playlist `.contextMenu`, Settings `Form` rows.
9. **Toast confirmations** — wire T06 *after* the success preview's Done / View Playlist navigation lands.

## Tokens — non-negotiable

- **Accent**: `Color.accentColor` (set `.tint(Color("AccentColor"))` at the root; `AccentColor` in asset catalog = `#8B5CF6`).
- **Export sheet background**: native sheet material. On iOS 17–25, this is `.regularMaterial`. On iOS 26 with Liquid Glass enabled, the system may upgrade the sheet to `.glassBackgroundEffect()` — do not override (decision 2).
- **Op loading overlay**: `Color.black.opacity(0.6)` scrim + `ProgressView()` tinted at `.tint`.
- **Tap targets**: 44 pt minimum via `.contentShape(Rectangle())`.
- **Corner style**: always `.continuous`. `.circular` (default for `RoundedRectangle`) is wrong here.
- **Toast surface**: system `Snackbar` equivalent — there is no native iOS toast; use the existing `ToastHost` from `YT-0027`'s shared toast infrastructure.

## Privacy — what the exported file contains and does NOT contain

The exported `.ytplaylist.json` contains, in this order: `schemaVersion`, `id`, `name`, `createdAt`, `updatedAt`, `tracks[]`. Each `Track` is `videoId`, `title`, `channel`, `durationSec`, `thumbnailUrl`. **Nothing else.** No auth tokens, no `idfv` (`UIDevice.identifierForVendor`), no `idfa`, no install-UUID, no anonymous telemetry ID, no keychain data, no signing credentials, no `Set-Cookie` headers from metadata fetch. See decision 6 for the audit boundary.

## Aesthetic decisions binding

When implementation has an open question and the spec is silent: **native SwiftUI > custom**. `ShareLink` > hand-rolled share button. `.fileImporter` > custom file picker. `.sheet` > full-screen modal. `ContentUnavailableView` > hand-built error layout. Toast > alert (decision 9). C15/C16 catalog cells > one-off spinners and error views.

For Liquid Glass: the sheet may render as glass on iOS 26. Do not chase that visual on iOS 17–25 — the design must look correct on both. Read [`../YT-0028/decision-log.md` § ContentUnavailableView](../YT-0028/decision-log.md) and apply the same restraint.
