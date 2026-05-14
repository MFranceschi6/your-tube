# YT-0030 — Decision Log

iOS playlist sharing for YourTube. Mirrors [`YT-0016/decision-log.md`](../YT-0016/decision-log.md) decision-for-decision, platform-adapted. Each entry is **Decision · Rationale · Anti-pattern**. The numbering is shared across both logs; treat them as one document with two implementations.

> **Original UI. Behavioral inspiration only from Apple Music's `ShareLink` patterns and the Files app — do not reproduce Apple's chrome.**

---

## 1. File extension, MIME type, and `.fileImporter` content types

**Decision.** Exported file is `name.ytplaylist.json`. MIME `application/json`. The `.fileImporter`'s `allowedContentTypes` is **exactly**:

```swift
.fileImporter(
    isPresented: $importPresented,
    allowedContentTypes: [.json, .ytplaylistJSON],   // <- two types
    allowsMultipleSelection: false,
) { result in ... }
```

with the custom `UTType`:

```swift
extension UTType {
    static let ytplaylistJSON = UTType(
        exportedAs: "com.yourtube.playlist",
        conformingTo: .json,
    )
}
```

and **`Info.plist`**:

```xml
<key>UTExportedTypeDeclarations</key>
<array>
    <dict>
        <key>UTTypeIdentifier</key>
        <string>com.yourtube.playlist</string>
        <key>UTTypeDescription</key>
        <string>YourTube Playlist</string>
        <key>UTTypeConformsTo</key>
        <array>
            <string>public.json</string>
            <string>public.data</string>
        </array>
        <key>UTTypeTagSpecification</key>
        <dict>
            <key>public.filename-extension</key>
            <array>
                <string>ytplaylist.json</string>
            </array>
            <key>public.mime-type</key>
            <array>
                <string>application/json</string>
            </array>
        </dict>
    </dict>
</array>
```

The **broader** type (`.json`) is included because Files-app browsing through some cloud providers (Dropbox, Google Drive) historically presents `.ytplaylist.json` as plain `public.json` until the custom UTI propagates through the system's UTI cache. The narrowing — the second-gate file-name check — happens in `ImportViewModel.importFile(_:)`: read the URL's `lastPathComponent`, reject if it does not match `^.+\.ytplaylist\.json$` (case-insensitive). This is the file-name extension check from decision 7 variant 3.

**Rationale.** Filing this part of YT-0047, the iOS Files-app receive path was broken because (a) the custom UTI was not declared in `Info.plist`, so Files did not know YourTube could open these files, and (b) `.fileImporter([.json])` would surface every `.json` file but did not even surface `.ytplaylist.json` files reliably because the system did not infer the conformance backward. Declaring the exported UTI plus enumerating BOTH types in the importer's allowed list fixed both arms.

Why `application/json` and not a vendor-specific MIME (e.g. `application/vnd.yourtube.playlist+json`)? Email clients and chat apps don't preserve vendor MIME hints; the file arrives as `application/json` regardless. The MIME field is informational only — the file-name extension carries the routing.

**Anti-pattern.**
- ❌ Only `.json` in `allowedContentTypes`. Will not surface `.ytplaylist.json` files from cloud providers that haven't synced the custom UTI yet.
- ❌ Only `.ytplaylistJSON`. Will hide files received from Mail (where the attachment may register as `.json` until renamed).
- ❌ Removing the in-app file-name extension check after the picker returns. The picker is the first gate; the file-name check is the second. Both are required (the wrong-extension variant in decision 7 catches files renamed to `.json` after export).
- ❌ Declaring the UTI as `conformingTo: .data` only (not `.json`). The Files app uses JSON-conformance to render the file's preview as text; declaring data-only renders it as a binary blob with no preview.

---

## 2. Export sheet shape

**Decision.** SwiftUI `.sheet` modifier hosting a custom export view. The primary action uses `ShareLink`:

```swift
ShareLink(
    item: exportedFileURL,
    preview: SharePreview(playlist.name, image: playlistCoverImage),
) {
    Label("Share", systemImage: "square.and.arrow.up")
        .frame(maxWidth: .infinity, minHeight: 50)
}
.buttonStyle(.borderedProminent)
.controlSize(.large)
```

Content, top-to-bottom:

- Drag handle (native sheet grabber on `.medium` detent).
- Title — playlist name, `.title2.bold()`, two-line clamp.
- Metadata row — track count · file size estimate · `.ytplaylist.json`, `.subheadline / .secondary`.
- Primary action — **`ShareLink`** wrapping a "Share" label → system share sheet.
- Secondary action — **"Save to Files"** (`.bordered` button) → wraps `UIActivityViewController` with the activity type filtered to `UIActivity.ActivityType.markupAsPDF`'s sibling `addToReadingList` → actually a `UIDocumentPickerViewController(forExporting:)` in `.fileImporter`'s sibling `.fileExporter` modifier. Use **`.fileExporter`** in SwiftUI:

  ```swift
  .fileExporter(
      isPresented: $saveToFilesPresented,
      document: PlaylistFile(playlist: playlist),
      contentType: .ytplaylistJSON,
      defaultFilename: "\(playlist.name).ytplaylist",
  ) { result in ... }
  ```
- Tertiary text button — **"Copy link"** (`.borderless` button) → copies the **deep link** (`yourtube://playlist/<id>`), not the file URL.

On **iOS 26 + Liquid Glass**, the sheet's background may render with `.glassBackgroundEffect()` automatically. Do not override — let the system pick. On iOS 17–25, the sheet uses the default `.regularMaterial`.

**Rationale.** `ShareLink` is the system-blessed share affordance — it integrates with the share sheet, system Quick Look preview, and VoiceOver's share semantics. The secondary "Save to Files" action exists because `ShareLink` routes through the share sheet, where "Save to Files" is one item among 30 and easy to miss; surfacing it as a dedicated row matches the Android behavior (decision 2) and reduces the tap count to 1.

**Copy link** copies the deep link, NOT the file URL, because file URLs are temporary (the exported file lives in `FileManager.default.temporaryDirectory` and is deleted on app suspend) and the user's mental model for "Copy link" is "I can paste this in chat" — only the deep link satisfies that.

**Anti-pattern.**
- ❌ Custom-built `UIActivityViewController` wrapper instead of `ShareLink`. Loses the SwiftUI integration with the navigation context, share-preview rendering, and VoiceOver.
- ❌ Overriding the sheet background to a custom dark color. Breaks Liquid Glass on iOS 26 and removes the system's adaptive backgrounding.
- ❌ "Copy file URL" as the copy action. Expires immediately; pasted result yields "file not found".
- ❌ `.alert` instead of `.sheet`. Alerts are for blocking confirmations of destructive actions; export is a non-destructive composition surface.

---

## 3. Entry points

**Decision.** Three iOS entry points, all required:

1. **Library toolbar `Menu`** — the trailing toolbar item is a `Menu` (`ellipsis.circle`). Inside: a single `Button("Import playlist…", systemImage: "square.and.arrow.down") { importPresented = true }`. The trailing ellipsis in the title indicates "opens a picker".
2. **Per-playlist `.contextMenu`** — applied to `PlaylistRow`. Items, in order: `Play next`, `Add to queue`, `Share…`, `Rename…`, `Delete…` (`role: .destructive`). The "Share…" item opens the export sheet (decision 2). On iOS 16+, `.contextMenu` is triggered by long-press or by the row's trailing ellipsis-button — both invocations must work.
3. **Settings `Form` § Playlists** — two `NavigationLink`-styled rows (`Label` + chevron, via `Button` with a custom row style): **"Export Playlist…"** (opens a playlist `Picker` sheet → export sheet) and **"Import Playlist…"** (sets `importPresented = true`). Cite YT-0017 Settings handoff when available; until then, follow the existing Settings § Storage rows in `Features/Settings/SettingsView.swift` for visual parity.

**Rationale.** Same three task starting points as Android (Library, per-playlist, Settings). Removing any of the three has been measured (YT-0034 MVP validation) to lose users. On iOS, the `Menu` + `.contextMenu` + `Form` pattern is the native vocabulary; pushing each entry through SwiftUI primitives makes the surface free of custom chrome.

**Anti-pattern.**
- ❌ A floating action button at the bottom of Library. iOS does not have FABs in the system vocabulary; adding one is a fingerprint of an Android port.
- ❌ "Share" button in the navigation bar of `PlaylistDetailView`. Navigation bar real estate is reserved for primary playlist actions (Play, Shuffle moved to overflow in YT-0028) — Share lives in the ellipsis menu, not the title bar.
- ❌ Deep-link-only import ("paste `yourtube://`"). Files have URLs but not deep links; the import surface must accept a file.

---

## 4. Import sheet shape

**Decision.** After `.fileImporter` returns a valid `URL` and parsing succeeds, present a `.sheet` ("ImportSuccessSheet") containing, top-to-bottom:

- Drag handle.
- Title — `Imported "{name}" — {N} tracks`, `.title2.bold()`, two-line clamp.
- Track preview — `List` (max 5 visible) of `TrackRow` (thumb 40 pt, title `.body`, channel `.subheadline / .secondary`). If `N > 5`, append a row `+ {N - 5} more tracks`, `.subheadline / .secondary`, no chevron, non-selectable (`.disabled(true)`).
- Primary button — **"Done"** (`.borderedProminent`, full-width). Dismiss sheet → fire toast T06.
- Secondary text button — **"View Playlist"** (`.borderless`, full-width). Dismiss sheet → push `PlaylistDetailView(id)` → fire T06 *after* the navigation lands.

Parse / read / future-schema / wrong-extension errors do NOT use this sheet — they use decision 7 variants.

**Rationale.** A toast alone isn't enough — the user just performed a multi-step file-picker journey and earned a confirmation surface that includes the actionable next step. The preview rows answer "did I import the right one?" without forcing navigation. "Done" / "View Playlist" is the standard two-button confirmation pattern.

**Anti-pattern.**
- ❌ Auto-navigating to the imported playlist with no confirmation step. Removes the user's chance to abort.
- ❌ `.alert` instead of `.sheet`. Alerts can't host a track preview list cleanly.
- ❌ Firing the toast (T06) before the success sheet dismisses. Overlapping the toast with the sheet's dismiss animation reads as a bug. Always: dismiss → toast.

---

## 5. Schema-future rejection — non-negotiable

**Decision.** When `ImportViewModel.importFile(url:)` decodes the file body and discovers `schemaVersion > PlaylistCodec.supportedSchemaVersion` (currently `1`), it MUST emit a full-screen error state with the copy:

> **Update YourTube to import this file.**
>
> This playlist was exported from a newer version of YourTube. Update the app to import it.

Primary action: **"Open App Store"** (`UIApplication.shared.open(URL(string: "itms-apps://apps.apple.com/app/idXXXX")!)`). No "Import anyway", no "Try partial import", no fallback.

Cite [`docs/api-contracts.md` § Schema versioning](../../../docs/api-contracts.md#schema-versioning): "*Importers MUST reject files with `schemaVersion` higher than they support and surface a 'please update the app' error.*"

iOS parity test: `ios/YourTubeTests/CrossPlatformParityTests.swift` already asserts `playlist-future-schema.ytplaylist.json` decodes as `PlaylistCodecError.unsupportedSchemaVersion(999)`. Do not change this behavior to anything other than the rejection.

**Rationale.** Same as YT-0016 decision 5 — schema-future files contain fields the current build does not know how to interpret. Best-effort import silently drops fields; the playlist round-trips back with fields stripped; data loss disguised as a feature. The hard rejection is the only safe path. The future-schema fixture and the parity test are the canaries.

**Anti-pattern.**
- ❌ Surfacing the error as a `.alert(...)`. Alerts can be dismissed and forgotten — the user re-attempts the import. A full-screen state forces engagement with the next action ("Open App Store").
- ❌ "Some fields couldn't be read" toast + partial import. Silent data loss.
- ❌ Dropping unknown fields and proceeding. Same problem.
- ❌ Decoding as `schemaVersion = supportedSchemaVersion`. Disrespects the format contract.

---

## 6. Privacy — what does and does not go in the exported file

**Decision.** The exported `.ytplaylist.json` contains, in order: `schemaVersion`, `id`, `name`, `createdAt`, `updatedAt`, `tracks[]`. Each `Track`: `videoId`, `title`, `channel`, `durationSec`, `thumbnailUrl`. **Nothing else.**

Explicitly excluded:
- No auth tokens, OAuth grants, refresh tokens, or session cookies.
- No `UIDevice.identifierForVendor`, no IDFA, no install-UUID, no anonymous telemetry ID, no `ASIdentifierManager` value.
- No keychain entries or values derived from keychain.
- No `User-Agent`, `Cookie`, or `Authorization` header that was used to fetch any track metadata.
- No playback history (`lastPlayedAt`), preference flags, or per-device sort overrides.
- No path information from the device filesystem.
- No `addedAt` per-track timestamps in MVP. May be added in `schemaVersion = 2`; bump the version when adding.

`JSONEncoder` is configured with `[.prettyPrinted, .sortedKeys]` (per `docs/api-contracts.md` § Cross-platform parity fixtures) so the byte output is deterministic and the parity test is meaningful.

**Rationale.** Same as YT-0016 decision 6. Files leave the device; the recipient sees what we encoded; sensitive fields become discoverable post-hoc by third parties.

**Anti-pattern.**
- ❌ Including `idfv` for "cross-device matching". The deep link already handles cross-device matching.
- ❌ Adding fields to the JSON without bumping `schemaVersion`. Breaks `CrossPlatformParityTests`.
- ❌ Encoding with `JSONEncoder.OutputFormatting(rawValue: 0)` (no formatting). The pretty-printed + sorted output is the parity-fixture contract; non-deterministic output causes the parity test to fail on Apple Silicon where dictionary iteration order differs from Intel.

---

## 7. Error variants — 4 distinct states, each with its own copy and SF Symbol

**Decision.** Four mutually exclusive error states, each rendered with `ContentUnavailableView` as the base. Primary action **"Try Again"** re-presents the file picker — except variant 2 (future-schema), where the primary action is **"Open App Store"**.

| # | Trigger | Copy | Body | SF Symbol | Primary action |
|---|---|---|---|---|---|
| 1 | **Parse error** — file is JSON but fails `PlaylistCodec` decode | `Couldn't import playlist — file is invalid.` | This .ytplaylist.json doesn't match the expected format. Make sure you're importing a file that was exported by YourTube. | `exclamationmark.triangle` (`.foregroundStyle(.tertiary)`) | Try Again |
| 2 | **Future-schema** — `schemaVersion > 1` | `Update YourTube to import this file.` | This playlist was exported from a newer version of YourTube. Update the app to import it. | `arrow.up.circle` (`.foregroundStyle(.tertiary)`) | Open App Store |
| 3 | **Wrong extension** — `URL.lastPathComponent` doesn't end in `.ytplaylist.json` | `Choose a .ytplaylist.json file to import.` | Select a YourTube playlist file. The file name should end with .ytplaylist.json. | `doc.questionmark` (`.foregroundStyle(.tertiary)`) | Try Again |
| 4 | **Read permission** — security-scoped URL fails `startAccessingSecurityScopedResource()` or `Data(contentsOf:)` throws | `Couldn't read the file. Try sharing it directly from Files.` | The file couldn't be opened. Try sharing it from the Files app instead of selecting it through the picker. | `lock.open` (`.foregroundStyle(.tertiary)`) | Try Again |

All four use `ContentUnavailableView` (iOS 17+) — symbol + title + description + action button. The tint stays at `.tertiary` for the icon — error red is reserved for destructive confirmation (cite state-catalog README "Error icon is NOT red").

**Rationale.** Distinguishing the four causes lets the user take a different action per variant. The error copy is calibrated to the next-step the user must take, not to the internal error type. `ContentUnavailableView` is the native iOS 17+ surface for "this state has no content, here's why and how to fix it" — using it gets Dynamic Type, VoiceOver, and the standard icon-title-body-action layout for free.

**Anti-pattern.**
- ❌ One generic `.alert("Import failed")`. Forces a dismiss tap and leaves the user in an ambiguous state.
- ❌ Red SF Symbol. Cite state-catalog README — red is reserved for destructive operations.
- ❌ Hand-rolled "empty state" instead of `ContentUnavailableView`. Cite YT-0028 decision-log for the same rule on Library.
- ❌ Auto-retry on permission error. The fix requires the user to take action (re-share from Files); we cannot resolve from inside our process.

---

## 8. iOS Files + AirDrop — `onOpenURL` placement

**Decision.** Both `scene(_:openURLContexts:)` (in `SceneDelegate` or the `App`'s `WindowGroup.handlesExternalEvents` companion) AND `.onOpenURL { url in ... }` (on the root `ContentView`) MUST be implemented. They route to the same `ImportRouter`.

```swift
// AppRoot.swift
@main
struct YourTubeApp: App {
    @StateObject private var importRouter = ImportRouter()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(importRouter)
                .onOpenURL { url in
                    importRouter.handle(url: url)
                }
        }
    }
}
```

For the scene path (cold-launch AirDrop receive):

```swift
// SceneDelegate.swift (UIKit lifecycle) — or via UIApplicationDelegateAdaptor
func scene(_ scene: UIScene, openURLContexts URLContexts: Set<UIOpenURLContext>) {
    for context in URLContexts {
        ImportRouter.shared.handle(url: context.url)
    }
}
```

`ImportRouter.handle(url:)` does the following:
1. Calls `url.startAccessingSecurityScopedResource()` if applicable.
2. Routes the URL to `ImportViewModel.importFile(url:)`, which runs the state machine in decision 4 / 7 / 10.
3. Calls `url.stopAccessingSecurityScopedResource()` in `defer`.

**Document fields required in `Info.plist`** so the system routes incoming `.ytplaylist.json` files to YourTube:

```xml
<key>CFBundleDocumentTypes</key>
<array>
    <dict>
        <key>CFBundleTypeName</key>
        <string>YourTube Playlist</string>
        <key>LSHandlerRank</key>
        <string>Owner</string>
        <key>LSItemContentTypes</key>
        <array>
            <string>com.yourtube.playlist</string>
        </array>
    </dict>
</array>
```

(`UTExportedTypeDeclarations` from decision 1 is the partner key.)

**AirDrop receive flow** (documented for future implementers): user A drops `.ytplaylist.json` to user B → iOS shows the share sheet on B's device asking "Open in YourTube?" → on Accept, iOS routes the file through `scene(_:openURLContexts:)` (cold launch) or `.onOpenURL` (warm) → `ImportRouter` triggers the same import sheet as the in-app picker. No special UI for "this came from AirDrop"; the user is already aware they accepted the drop.

**No share extension in MVP.** A share extension would let YourTube appear as a target *inside* other apps' share sheets (Messages, Files), but it requires App Group entitlements (the extension and main app run in separate processes; sharing the SwiftData store requires the App Group container). Deferred — track as a post-MVP task.

**Rationale.** YT-0047 closed a gap where AirDrop receives silently failed because `scene(_:openURLContexts:)` wasn't implemented. The SwiftUI `.onOpenURL` alone catches warm-launch receives but not cold-launch receives — on a cold launch, the scene method fires first and the SwiftUI app isn't fully attached yet. Both are required.

**Anti-pattern.**
- ❌ Only `.onOpenURL`. Misses cold-launch AirDrop receives — drops appear to silently fail.
- ❌ Only `scene(_:openURLContexts:)`. Misses warm-launch (e.g. user receives an AirDrop while YourTube is in the foreground).
- ❌ Adding a share extension in MVP. Out of scope — needs App Group entitlements and a parallel SwiftData store.
- ❌ Calling `Data(contentsOf: url)` without `startAccessingSecurityScopedResource()`. Throws on sandbox boundaries; the user-visible symptom is variant 4 of decision 7, even though the file is actually readable.

---

## 9. Success confirmation — toast, NOT alert

**Decision.** After the user taps Done or View Playlist on the import success sheet (decision 4), fire toast **T06** from `toast-catalog/copy.md`:

> **Imported `"{playlistName}"` — {N} tracks**
>
> Duration: Short (4 s). No action label.

Navigation rule:
- **Done** → dismiss sheet → toast fires on Library screen.
- **View Playlist** → dismiss sheet → push `PlaylistDetailView(id)` via the navigation path → toast fires *after* the navigation lands (use a `task(id: navigationPath.last)` gate, or `DispatchQueue.main.asyncAfter(...0.4)` matching the sheet's dismiss animation).

Export success uses **T05** (`Exported "{{playlistName}}"`), fired after `ShareLink` completes (i.e., the user has finished interacting with the system share sheet — there is no return callback from `ShareLink`, so we fire optimistically when the share sheet dismisses).

**Rationale.** Same as YT-0016 decision 9. An alert interrupts; a toast confirms without interrupting. State-catalog (C15/C16) reserves blocking surfaces for in-flight operations and errors; success is not blocking.

**Anti-pattern.**
- ❌ `.alert("Import complete")`. Forces a confirm tap on a success state. Slop pattern.
- ❌ Long-duration toast. Long is reserved for destructive-undo (T01/T02/T03).
- ❌ Firing the toast *before* the sheet dismisses. Overlap reads as a bug.

---

## 10. Op loading overlay — state-catalog C15

**Decision.** During (a) export file generation (encoding via `PlaylistCodec` and writing to `FileManager.default.temporaryDirectory`) and (b) import parsing (reading via security-scoped URL and decoding), render state-catalog cell **C15** — a full-screen, non-cancellable overlay with a centered `ProgressView()`.

```swift
struct OpLoadingOverlay: View {
    let visible: Bool
    var body: some View {
        if visible {
            ZStack {
                Color.black.opacity(0.6).ignoresSafeArea()
                ProgressView()
                    .controlSize(.large)
                    .tint(.white)
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("Loading")
            .accessibilityAddTraits(.isModal)
            .allowsHitTesting(true)
            .transition(.opacity)
            .animation(reduceMotion ? nil : .easeInOut(duration: 0.12), value: visible)
        }
    }
}
```

The overlay swallows taps (`allowsHitTesting(true)` + no buttons inside). MVP does **not** offer cancel for long operations; track as a post-MVP addendum.

**Rationale.** Same as YT-0016 decision 10. Operations are fast for typical playlists (< 200 tracks; parity tests run sub-100 ms in `CrossPlatformParityTests.swift`). The exceptional case (2000-track playlist on an old device) is rare enough that MVP can ship without cancel. Adding cancel correctly requires `Task` cancellation propagation through `PlaylistCodec` and a rollback path for the partial write — out of MVP scope.

**Anti-pattern.**
- ❌ Inline `ProgressView` inside the sheet header. Reads as background activity; the user can still tap Share and trigger a second concurrent encode.
- ❌ Tap-outside-to-dismiss on the overlay. Destroys mid-operation; partial files end up in the temporary directory.
- ❌ Skipping the overlay because "the operation is fast". Fast for *normal* playlists. The overlay is the safety net for the long-tail case.

---

## Mockup web-isms — translate to SwiftUI

| Mockup CSS / pattern | SwiftUI equivalent |
|---|---|
| `backdrop-filter: blur(20px)` on the sheet | `.regularMaterial` (iOS 17–25) or system Liquid Glass (iOS 26) — do not pick by radius |
| `box-shadow: 0 8px 32px rgba(0,0,0,0.6)` on the sheet | Drop. Sheets ship system shadow |
| `border-radius: 28px 28px 0 0` for the sheet top | Native sheet shape — do not override |
| `rgba(255,255,255,0.6)` body text | `.foregroundStyle(.secondary)` |
| Inline SVG icons | SF Symbols via `Image(systemName:)` — per `symbol-map.md` |
| `cubic-bezier(...)` on button hover | Native button state — do not animate manually |
| Hardcoded `#8B5CF6` primary | `Color.accentColor` (set `.tint(...)` at root) |
| `font-family: 'Geist'` | System SF Pro — do not import Geist on iOS |
| Centered "Imported 12 tracks" big text | `Text(...).font(.title2.weight(.semibold)).multilineTextAlignment(.center)` + `.lineLimit(2)` |
