# YT-0016 — Decision Log

Android playlist sharing for YourTube. Mirrors [`YT-0030/decision-log.md`](../YT-0030/decision-log.md) decision-for-decision, platform-adapted. Each entry is **Decision · Rationale · Anti-pattern**. The numbering is shared across both logs; treat them as one document with two implementations.

> **Original UI. Inspiration from Android Files-app share flows is behavioral only — do not reproduce any Google app's chrome.**

---

## 1. File extension, MIME type, and `ACTION_OPEN_DOCUMENT` filter

**Decision.** Exported file is `name.ytplaylist.json`. MIME `application/json`. The `ACTION_OPEN_DOCUMENT` MIME array for import is **exactly**:

```kotlin
private val IMPORT_MIME_TYPES = arrayOf(
    "application/json",
    "application/octet-stream",
)
```

`application/json` is the correct primary type. `application/octet-stream` is included because some Android share-receivers (AirDroid, certain mail clients, some MIUI File Manager builds) hand `.ytplaylist.json` over with the generic octet-stream MIME when they don't sniff the body. The fallback is necessary; the broader filter is not.

**Rationale.** YT-0068 closed a regression where the original `["*/*"]` filter surfaced every file in the user's Downloads tree, and the intermediate `["application/json", "application/octet-stream", "text/plain", "application/zip"]` filter surfaced every `.txt` shopping list. The narrowed two-element array is the load-bearing fix.

After the system picker returns the `Uri`, the import path narrows further: read the **file name** from the `Uri` and reject anything that doesn't end in `.ytplaylist.json` (decision 7, wrong-extension variant). The picker filter is necessary but not sufficient — the in-app name check is the second gate.

**Anti-pattern.**
- ❌ Broadening the MIME array back to `["*/*"]` "for compatibility". This is what YT-0068 fixed — do not regress.
- ❌ Adding `text/plain` "because some clients serve JSON as text". The cost (every `.txt` showing up in the picker) outweighs the recovery (those clients are rare and the user can long-press → "Open with" anyway).
- ❌ Reading the file body before checking the extension. Cheap checks first; expensive checks (parse) second.

---

## 2. Export sheet shape

**Decision.** `ModalBottomSheet` (`androidx.compose.material3.ModalBottomSheet`, `skipPartiallyExpanded = true`). Content, top-to-bottom:

- Sheet handle (`BottomSheetDefaults.DragHandle()`).
- Title — playlist name, `titleLarge`, two-line clamp with `basicMarquee` overflow.
- Metadata row — track count · file size estimate · `.ytplaylist.json`, `bodyMedium`, `onSurfaceVariant`.
- Primary button — **"Share"** (filled, full-width, `Icons.Rounded.Share` leading) → `Intent.ACTION_SEND` via `FileProvider`.
- Secondary button — **"Save to Files"** (outlined, full-width, `Icons.Rounded.Upload` leading) → `Intent.ACTION_CREATE_DOCUMENT`.
- Tertiary text button — **"Copy link"** (text, centered, `Icons.Rounded.Link` leading) — copies the **deep link** (`yourtube://playlist/<id>`), not the file path. The file path is a `content://` URI that expires when the sheet dismisses; deep links survive.

**Rationale.** `ModalBottomSheet` is the canonical M3 surface for confirmation-style flows that include a sticky primary action. The two-button hierarchy (Share / Save) maps to the two real intents users have: "send to someone" vs. "drop into Files for myself later". Most apps collapse these into a single Share button and force the user to scroll the chooser to "Save to Files" — that's an extra tap and one users miss often enough that we surface Save as its own affordance.

**Copy link** copies the deep link, NOT the file path, because:
1. The `content://` URI from `FileProvider` is scoped to the share-target Intent and expires when the sheet dismisses; pasting it into Slack would yield "permission denied".
2. The deep link is portable across YourTube installs and survives reinstall.
3. The user mental model for "Copy link" is "I can send this in chat" — only the deep link satisfies that.

**Anti-pattern.**
- ❌ Custom-built sheet with hand-rolled drag handle. `ModalBottomSheet` ships predictive-back, accessibility scrim semantics, and density-aware insets for free.
- ❌ Surfacing the share-target picker inline inside our sheet. The system `Intent.ACTION_SEND` chooser is the picker; nesting another picker is the slop tell.
- ❌ "Copy file path" as the copy action. Expires immediately; user gets a broken paste.

---

## 3. Entry points

**Decision.** Three Android entry points, all required:

1. **Library top-app-bar overflow** — `Icons.Rounded.MoreVert` opens a `DropdownMenu` containing a single item, "Import playlist…" (Material trailing-ellipsis convention for "opens a picker"). On tap, launches the `ActivityResultContracts.OpenDocument()` contract (decision 1).
2. **Per-playlist long-press context menu** — on a `PlaylistRow`, long-press opens a `DropdownMenu` (or M3 `Menu`) anchored to the row. Items, in order: `Play next`, `Add to queue`, `Share…`, `Rename…`, `Delete…`. The "Share…" item opens the export sheet (decision 2). The trailing ellipsis indicates "opens a sheet, not an immediate action".
3. **Settings § Playlists** — two chevron-disclosure rows under a "Playlists" section: **"Export Playlist…"** (opens a playlist picker → export sheet) and **"Import Playlist…"** (launches the document picker). Cite YT-0017 Settings handoff when available; until then, follow the existing Settings § Cache convention from `feature/settings/SettingsScreen.kt`.

**Rationale.** The three entry points correspond to three task starting points: "I'm in Library and want to bring something in" (overflow), "I'm looking at a specific playlist and want to send it" (context menu), "I'm in Settings and want a clean export/import row" (Settings § Playlists). Removing any of the three has been measured (YT-0034 MVP validation) to lose users — Library-overflow users are first-time importers, context-menu users are repeat sharers, Settings users are power users doing batch maintenance.

**Anti-pattern.**
- ❌ Single floating action button for "Share/Import". One button can't host both directions; users tap it expecting one and get the other.
- ❌ "Share" button in the top-app-bar of PlaylistDetail. Top-app-bar real estate is reserved for navigation and primary playlist actions (Play, Shuffle); Share is overflow.
- ❌ Importing through a deep-link URL only ("paste a `yourtube://` link to import"). Files don't have deep links; the import surface must accept a file.

---

## 4. Import sheet shape

**Decision.** After the system file picker returns a valid `Uri` and parsing succeeds, present a `ModalBottomSheet` ("ImportSuccessSheet") containing, top-to-bottom:

- Sheet handle.
- Title — `Imported "{name}" — {N} tracks`, `titleLarge`, two-line clamp.
- Track preview — `LazyColumn` (max 5 visible) of `TrackRow` (thumb 40 dp, title `bodyLarge`, channel `bodyMedium / onSurfaceVariant`). If `N > 5`, append a row `+ {N - 5} more tracks`, `bodyMedium / onSurfaceVariant`, no chevron, not tappable.
- Primary button — **"Done"** (filled, full-width). On tap: dismiss sheet, stay on Library, fire toast T06.
- Secondary text button — **"View Playlist"** (text, centered). On tap: navigate to the newly-imported PlaylistDetail (`AppRoute.PlaylistDetail(id)`), dismiss sheet, fire toast T06 *after* navigation lands.

Parse / read / validate errors do NOT use this sheet — they use the error variants in decision 7.

**Rationale.** A toast alone isn't enough — the user just performed a multi-step file-picker journey and earned a confirmation surface that includes the actionable next step ("now go look at it"). The preview rows answer "did I import the right one?" without forcing navigation. The "Done" / "View Playlist" pair is the standard two-button confirmation pattern.

**Anti-pattern.**
- ❌ Auto-navigating to the imported playlist with no confirmation step. Removes the user's chance to abort if they imported the wrong file.
- ❌ Dialog (`AlertDialog`) instead of bottom sheet. Dialogs are for blocking confirmations of destructive actions; this is a success surface.
- ❌ Firing the toast (T06) before the sheet dismisses. The toast is the **persistent** confirmation after the sheet goes away — overlapping the two reads as a bug.

---

## 5. Schema-future rejection — non-negotiable

**Decision.** When `ImportViewModel.importFile(uri)` decodes the file body and discovers `schemaVersion > KotlinxPlaylistCodec.SUPPORTED_SCHEMA_VERSION` (currently `1`), it MUST emit a full-screen error state with the copy:

> **Update YourTube to import this file.**
>
> This playlist was exported from a newer version of YourTube. Update the app to import it.

Primary action: **"Open Play Store"** (deep link `market://details?id=com.yourtube`). No "Import anyway", no "Try partial import", no fallback. This is a hard rejection.

Cite [`docs/api-contracts.md` § Schema versioning](../../../docs/api-contracts.md#schema-versioning): "*Importers MUST reject files with `schemaVersion` higher than they support and surface a 'please update the app' error.*"

**Rationale.** Schema-future files contain fields the current build does not know how to interpret. A "best-effort" import would silently drop those fields, the user would tap "Done", the imported playlist would round-trip BACK to the next user with the fields stripped, and we'd have data-loss disguised as a feature. The hard rejection is the only safe path. The future-schema fixture (`docs/fixtures/playlist-future-schema.ytplaylist.json`) and the parity test (`CrossPlatformParityTest.kt`) exist to catch any regression.

**Anti-pattern.**
- ❌ "Some fields couldn't be read" toast + partial import. Silent data loss.
- ❌ Drop unknown fields and proceed. Same problem.
- ❌ Surface the error as a transient toast. Hard errors need a non-dismissable surface — full-screen error state (decision 7, variant 2).
- ❌ Trying to import as `schemaVersion = SUPPORTED_SCHEMA_VERSION` anyway "in case it's compatible". It's not. Fail closed.

---

## 6. Privacy — what does and does not go in the exported file

**Decision.** The exported `.ytplaylist.json` contains, in order: `schemaVersion`, `id`, `name`, `createdAt`, `updatedAt`, `tracks[]`. Each track: `videoId`, `title`, `channel`, `durationSec`, `thumbnailUrl`. **Nothing else.**

Explicitly excluded:
- No auth tokens, OAuth grants, refresh tokens, or session cookies.
- No device identifiers (`Settings.Secure.ANDROID_ID`, `Build.SERIAL`, install UUID, anonymous telemetry ID).
- No `User-Agent`, `Cookie`, or `Authorization` header that was used to fetch any track metadata.
- No playback history (`lastPlayedAt`), preference flags, or local-only sort overrides.
- No path information from the device filesystem.
- No `addedAt` per-track timestamps in MVP. (May be added in `schemaVersion = 2`; bump the version when adding.)

**Rationale.** Sharing files is a transitive operation: the file leaves the device, the user can't recall it, and the system shows no preview of contents. Any sensitive field included is a privacy bug discovered by a third party, not us. The exclude-list above is the audit boundary — when adding a new field to `Track` or `Playlist`, file a parallel review against this list.

**Anti-pattern.**
- ❌ "We'll just include `addedAt` per-track, it's harmless." It isn't harmless once a malicious recipient can correlate it with timezone fingerprints.
- ❌ Embedding the user's `androidId` for "cross-device matching". The deep link (decision 2) already handles cross-device matching.
- ❌ Adding fields to the JSON without bumping `schemaVersion`. Breaks cross-platform parity (`CrossPlatformParityTest.kt`).

---

## 7. Error variants — 4 distinct states, each with its own copy and icon

**Decision.** Four mutually exclusive error states, rendered as full-screen `ErrorState` (state-catalog C16 inline-error styling, scaled up to fill the import flow's slot). Each has a single primary action **"Try Again"** which re-launches the document picker — except variant 2 (future-schema), where the primary action is **"Open Play Store"**.

| # | Trigger | Copy | Body | Icon | Primary action |
|---|---|---|---|---|---|
| 1 | **Parse error** — file is JSON but fails `KotlinxPlaylistCodec` schema validation | `Couldn't import playlist — file is invalid.` | This `.ytplaylist.json` doesn't match the expected format. Make sure you're importing a file that was exported by YourTube. | `Icons.Rounded.Error` (outline), tint `onSurfaceVariant` | Try Again |
| 2 | **Future-schema** — `schemaVersion > 1` | `Update YourTube to import this file.` | This playlist was exported from a newer version of YourTube. Update the app to import it. | `Icons.Rounded.SystemUpdate`, tint `onSurfaceVariant` | Open Play Store |
| 3 | **Wrong extension** — picker returned a file whose display name doesn't end in `.ytplaylist.json` | `Choose a .ytplaylist.json file to import.` | Select a YourTube playlist file. The file name should end with `.ytplaylist.json`. | `Icons.Rounded.FilePresent`, tint `onSurfaceVariant` | Try Again |
| 4 | **Read permission** — `ContentResolver.openInputStream(uri)` throws `SecurityException` or `FileNotFoundException` | `Couldn't read the file. Try sharing it directly from Files.` | The file couldn't be opened. Try sharing it from the Files app instead of selecting it through the picker. | `Icons.Rounded.LockOpen` (lock-related variant), tint `onSurfaceVariant` | Try Again |

All four use C16 catalog styling for the typography and spacing; only the icon and copy vary. The tint stays at `onSurfaceVariant` for all four — error red is reserved for destructive confirmation (cite state-catalog README "Error icon is NOT red").

**Rationale.** Distinguishing the four causes lets the user take a different action per variant. Collapsing them to one "Couldn't import" surface forces the user to guess whether they need to update the app, pick a different file, or grant a permission. The error copy is calibrated to the next-step the user must take, not to the internal exception type.

**Anti-pattern.**
- ❌ One generic "Import failed" with a stack trace in a `Snackbar`. No actionable next step, and the stack trace is a privacy leak on shared screens.
- ❌ Red error icon. Cite state-catalog README — red is reserved for destructive operations.
- ❌ Auto-retry on permission error. The fix requires the user to switch apps; we cannot retry from inside our process.

---

## 8. iOS Files + AirDrop — Android cross-reference only

**Decision.** This decision is iOS-owned (see [`YT-0030/decision-log.md` § 8](../YT-0030/decision-log.md)). Documenting on Android for parity:

- Android does not have an exact AirDrop equivalent in MVP. **Nearby Share** (`Intent.ACTION_SEND` to the system chooser, which surfaces Nearby Share when available) is the closest analog; YourTube does not implement a custom receiver.
- The Android import path is uniform: every import — whether the user got the file via Gmail attachment, Telegram, Nearby Share, or USB-MTP file copy — flows through `ACTION_OPEN_DOCUMENT` (decision 1). There is no app-direct receive path.
- **No `ACTION_VIEW` handler for `application/json`** in MVP. Registering it would surface YourTube as a candidate "Open with" target for every `.json` file on the device — too broad. Post-MVP: register `ACTION_VIEW` with a custom MIME (`application/vnd.yourtube.playlist+json`) once we can convince third-party share-receivers to emit it.

**Rationale.** The "import from anywhere" UX on Android is "save the file, then open YourTube and tap Import". Trying to be cleverer than that — registering broad `ACTION_VIEW` handlers, app-direct receive — wins users 0.5% of the time and breaks the OS conventions 100% of the time.

**Anti-pattern.**
- ❌ `IntentFilter` matching `*.json` to make YourTube show up as a system "Open with" target. Surfaces YourTube for every config file on the device.
- ❌ Custom Nearby Share receiver. Out of MVP scope; the system chooser already handles it.

---

## 9. Success confirmation — toast, NOT dialog

**Decision.** After the user taps Done or View Playlist on the import success sheet (decision 4), fire toast **T06** from `toast-catalog/copy.md`:

> **Imported `"{playlistName}"` — {N} tracks**
>
> Duration: Short (4 s). No action label.

Navigation rule:
- **Done** → dismiss sheet → toast fires on Library screen.
- **View Playlist** → dismiss sheet → push `AppRoute.PlaylistDetail(id)` → toast fires *after* the navigation lands (use `NavController.currentBackStackEntryFlow.first { it.destination.route == AppRoute.PlaylistDetail.route }` to gate).

Export success uses **T05** (`Exported "{{playlistName}}"`), fired after `Intent.ACTION_SEND` returns control to YourTube (i.e., the user has finished interacting with the system share chooser — `ActivityResultLauncher.onActivityResult`).

**Rationale.** A success dialog interrupts; a toast confirms without interrupting. The state-catalog (C15/C16) reserves blocking surfaces for operations in flight and errors; success is not blocking.

**Anti-pattern.**
- ❌ `AlertDialog` "Import complete". Forces a confirm tap on a success state. Slop pattern.
- ❌ Long-duration toast for success. Long is reserved for destructive-undo (T01/T02/T03). Success is short.
- ❌ Firing the toast *before* the success preview sheet dismisses. Overlaps the toast with the sheet's animation; reads as a bug. Always: dismiss → toast.

---

## 10. Op loading overlay — state-catalog C15

**Decision.** During (a) export file generation (encoding the playlist via `KotlinxPlaylistCodec` and writing through `FileProvider`) and (b) import parsing (reading the `Uri` and decoding), render state-catalog cell **C15** — a full-screen, non-cancellable overlay with a centered `CircularProgressIndicator`.

```kotlin
@Composable
fun OpLoadingOverlay(visible: Boolean) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(120)),
        exit = fadeOut(tween(120)),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(colorScheme.scrim.copy(alpha = 0.6f))
                .pointerInput(Unit) { detectTapGestures { /* swallow */ } }
                .semantics { liveRegion = LiveRegionMode.Polite; contentDescription = "Loading" },
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(color = colorScheme.primary)
        }
    }
}
```

The overlay swallows taps and back-presses (consume `BackHandler(enabled = visible) {}`). MVP does **not** offer cancel for long operations; tracked as a post-MVP addendum (see YT-0182 stub task once filed).

**Rationale.** The export/import operations are short for typical playlists (< 200 tracks) — the parity tests in `core/data/codec/` run sub-100ms. The exceptional case (a 2000-track playlist on an old device) is rare enough that MVP can ship without cancel. Adding cancel correctly requires a `CoroutineScope` lifecycle tied to the overlay's `composable` scope, deferred parsing of the JSON stream, and a rollback path for the partial write — out of MVP scope.

**Anti-pattern.**
- ❌ Inline progress bar above the sheet header. Reads as background activity; the user can still tap Share and trigger a second concurrent encode.
- ❌ Dismissible overlay (tap-outside-to-close). Destroys the operation half-way through; partial files end up in `FileProvider`'s exposed directory.
- ❌ Skipping the overlay because "the operation is fast". Fast for *normal* playlists. The overlay is the safety net for the long-tail case.

---

## Mockup web-isms — translate to Compose / M3

| Mockup CSS / pattern | Compose / M3 equivalent |
|---|---|
| `backdrop-filter: blur(20px)` on the sheet | Drop. M3 sheets use `surfaceContainerHigh` tonal elevation; no backdrop blur. |
| `box-shadow: 0 8px 32px rgba(0,0,0,0.6)` on the sheet | Drop. `ModalBottomSheet` ships M3 elevation/shadow tokens. |
| `border-radius: 28px 28px 0 0` for the sheet top | `BottomSheetDefaults` — the shape is already correct; do not override. |
| `rgba(255,255,255,0.6)` body text | `colorScheme.onSurfaceVariant`. |
| Inline SVG icons in the mockup | `Icons.Rounded.*` (Material Symbols rounded) — per `symbol-map.md`. |
| `cubic-bezier(...)` on button hover | M3 `IconButton` / `Button` ship state-layer ripple; do not animate fill. |
| Hardcoded `#8B5CF6` primary | `colorScheme.primary` — dynamic on API 31+, brand fallback below. |
| `font-family: 'Geist'` | Roboto via M3 `Typography`. Geist is web-mockup-only. |
| Centered "Imported 12 tracks" big text | `titleLarge` + `Modifier.padding(horizontal = 24.dp)` + `text-wrap: pretty` is `TextOverflow.Visible` + `maxLines = 2`. |
