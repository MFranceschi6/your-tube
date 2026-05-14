# YT-0030 — SwiftUI Implementation Spec

> Companion to [`decision-log.md`](decision-log.md). View hierarchy, `ShareLink`, `.fileImporter` / `.fileExporter`, custom `UTType`, `onOpenURL` placement, state machines, file layout. Open `mockup.html` / `mockup-states.html` side-by-side.

## File layout

```
ios/YourTube/Features/Sharing/
  ExportSheetView.swift              ← .sheet content — ShareLink + Save to Files + Copy link
  ExportSheetViewModel.swift         ← prepare exported URL, encode, temp-file lifecycle
  ImportRouter.swift                 ← single entry — onOpenURL / scene / picker all funnel here
  ImportViewModel.swift              ← read URL, parse, validate, route to success / error
  ImportSuccessSheetView.swift       ← preview + Done / View Playlist
  ImportErrorView.swift              ← 4 variants from decision 7
  OpLoadingOverlay.swift             ← C15 state-catalog overlay
  PlaylistFile.swift                 ← FileDocument wrapper for .fileExporter

ios/YourTube/Features/Library/
  LibraryView.swift                  ← +toolbar Menu "Import playlist…"
  PlaylistRow.swift                  ← +.contextMenu w/ "Share…"

ios/YourTube/Features/Settings/
  SettingsView.swift                 ← +Form § Playlists rows "Export Playlist…" / "Import Playlist…"

ios/YourTube/Core/Codec/
  PlaylistCodec.swift                ← (existing) encode/decode + schemaVersion rejection
  PlaylistCodecError.swift           ← (existing) unsupportedSchemaVersion / parseError / readError

ios/YourTube/Resources/
  Info.plist                         ← +UTExportedTypeDeclarations / +CFBundleDocumentTypes
ios/YourTube/AppRoot/
  YourTubeApp.swift                  ← .onOpenURL on root
  SceneDelegate.swift                ← scene(_:openURLContexts:) — or via UIApplicationDelegateAdaptor
```

## State machines

### Export

```
idle ──[user taps "Share…" from any entry point]──▶ preparing  (C15 overlay)
                                                     │
preparing ──[file written to tempDir]───────────────▶ readyToShare
                                                     │
readyToShare ──[user taps ShareLink → share sheet completes]──▶ idle + Toast(T05)
readyToShare ──[user taps "Save to Files" → fileExporter result OK]──▶ idle + Toast(T05)
readyToShare ──[user taps "Save to Files" → fileExporter result Cancel]──▶ readyToShare (no toast)
readyToShare ──[user taps "Copy link"]──────────────▶ readyToShare + Toast(T08 "Link copied")
readyToShare ──[user dismisses sheet]───────────────▶ idle (no toast)

preparing ──[encode fails]──────────────────────────▶ idle + Toast(T13 "Couldn't save", Retry)
```

### Import

```
idle ──[user taps "Import playlist…" / Settings row / onOpenURL fires]──▶ picking
                                                                          │
picking ──[fileImporter returns URL]─────────────────────────▶ checkingExtension
picking ──[fileImporter Cancel]──────────────────────────────▶ idle
onOpenURL ──[skips picking; goes straight to checkingExtension]

checkingExtension ──[lastPathComponent ends in .ytplaylist.json]──▶ reading  (C15 overlay)
checkingExtension ──[does NOT]───────────────────────────────────▶ error(.wrongExtension)

reading ──[startAccessing fails / Data(contentsOf:) throws]──▶ error(.readPermission)
reading ──[decode succeeds]──────────────────────────────────▶ persisting
reading ──[.parseError]──────────────────────────────────────▶ error(.parse)
reading ──[.unsupportedSchemaVersion]────────────────────────▶ error(.futureSchema)

persisting ──[SwiftData save ok]────────────────────────────▶ successPreview
persisting ──[SwiftData throws]──────────────────────────────▶ error(.parse)  // last-resort bucket

successPreview ──[Done]─────────────────────────────────────▶ idle + Toast(T06)
successPreview ──[View Playlist]────────────────────────────▶ navigate → PlaylistDetailView(id) → Toast(T06)

error(*) ──[Try Again]──────────────────────────────────────▶ picking  // except .futureSchema → "Open App Store"
```

## Custom `UTType` — decision 1

```swift
// Sharing/PlaylistFile.swift
import UniformTypeIdentifiers

extension UTType {
    /// `com.yourtube.playlist` — declared as exported in Info.plist (see decision 1).
    /// Conforms to `public.json` so cloud providers preview the file as text and inherit JSON open-with handlers.
    static let ytplaylistJSON = UTType(
        exportedAs: "com.yourtube.playlist",
        conformingTo: .json,
    )
}
```

`Info.plist` companion (already in repo per YT-0047):

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
            <array><string>ytplaylist.json</string></array>
            <key>public.mime-type</key>
            <array><string>application/json</string></array>
        </dict>
    </dict>
</array>
<key>CFBundleDocumentTypes</key>
<array>
    <dict>
        <key>CFBundleTypeName</key>
        <string>YourTube Playlist</string>
        <key>LSHandlerRank</key>
        <string>Owner</string>
        <key>LSItemContentTypes</key>
        <array><string>com.yourtube.playlist</string></array>
    </dict>
</array>
```

## `PlaylistFile` — `FileDocument` for `.fileExporter`

```swift
struct PlaylistFile: FileDocument {
    static var readableContentTypes: [UTType] = [.ytplaylistJSON, .json]
    static var writableContentTypes: [UTType] = [.ytplaylistJSON]

    let playlist: PlaylistPayload   // canonical record from core/codec

    init(playlist: PlaylistPayload) { self.playlist = playlist }
    init(configuration: ReadConfiguration) throws {
        guard let data = configuration.file.regularFileContents else {
            throw CocoaError(.fileReadCorruptFile)
        }
        self.playlist = try PlaylistCodec.decode(data: data)
    }
    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        let data = try PlaylistCodec.encode(playlist: playlist)
        return FileWrapper(regularFileWithContents: data)
    }
}
```

## Export flow — `ExportSheetView`

```swift
struct ExportSheetView: View {
    let playlistID: PlaylistID
    @StateObject private var vm = ExportSheetViewModel()
    @State private var saveToFilesPresented = false
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(spacing: 0) {
            switch vm.state {
            case .preparing:
                ExportSheetSkeleton()
            case .ready(let info):
                ScrollView {
                    VStack(alignment: .leading, spacing: 16) {
                        Text(info.playlistName)
                            .font(.title2.weight(.semibold))
                            .lineLimit(2)

                        Text("\(info.trackCount) tracks · \(info.fileSizeFormatted) · .ytplaylist.json")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)

                        VStack(spacing: 12) {
                            ShareLink(
                                item: info.fileURL,
                                preview: SharePreview(info.playlistName, image: Image("PlaylistGlyph")),
                            ) {
                                Label("Share", systemImage: "square.and.arrow.up")
                                    .frame(maxWidth: .infinity, minHeight: 50)
                            }
                            .buttonStyle(.borderedProminent)
                            .controlSize(.large)
                            .sensoryFeedback(.impact(weight: .medium), trigger: vm.shareTapCount)

                            Button {
                                saveToFilesPresented = true
                            } label: {
                                Label("Save to Files", systemImage: "square.and.arrow.up.on.square")
                                    .frame(maxWidth: .infinity, minHeight: 50)
                            }
                            .buttonStyle(.bordered)
                            .controlSize(.large)

                            Button {
                                UIPasteboard.general.string = "yourtube://playlist/\(info.playlistID)"
                                vm.onLinkCopied()
                            } label: {
                                Label("Copy link", systemImage: "link")
                                    .frame(maxWidth: .infinity, minHeight: 44)
                            }
                            .buttonStyle(.borderless)
                        }
                        .padding(.top, 12)
                    }
                    .padding(.horizontal, 24)
                    .padding(.top, 12)
                    .padding(.bottom, 24)
                }
            }
        }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Share playlist")
        .task(id: playlistID) { await vm.prepare(playlistID: playlistID) }
        .fileExporter(
            isPresented: $saveToFilesPresented,
            document: vm.state.fileDocument,
            contentType: .ytplaylistJSON,
            defaultFilename: vm.state.defaultFilename,
        ) { result in
            switch result {
            case .success: ToastHost.shared.post(.exported(name: vm.state.playlistName))
            case .failure: break  // user cancel or error — no toast on cancel
            }
        }
        .overlay { OpLoadingOverlay(visible: vm.state.isLoading) }
    }
}
```

### `ExportSheetViewModel`

```swift
@MainActor
final class ExportSheetViewModel: ObservableObject {
    @Published private(set) var state: ExportSheetState = .preparing
    @Published private(set) var shareTapCount: Int = 0

    func prepare(playlistID: PlaylistID) async {
        state = .preparing
        do {
            let playlist = try await playlistRepo.load(id: playlistID)
            let data = try PlaylistCodec.encode(playlist: playlist.toPayload())
            let tempURL = FileManager.default.temporaryDirectory
                .appendingPathComponent("\(playlist.name.sanitizedFilename).ytplaylist.json")
            try data.write(to: tempURL, options: .atomic)
            state = .ready(.init(
                playlistID: playlistID,
                playlistName: playlist.name,
                trackCount: playlist.tracks.count,
                fileSizeFormatted: ByteCountFormatter.string(fromByteCount: Int64(data.count), countStyle: .file),
                fileURL: tempURL,
                fileDocument: PlaylistFile(playlist: playlist.toPayload()),
                defaultFilename: "\(playlist.name).ytplaylist",
            ))
        } catch {
            ToastHost.shared.post(.couldntSave)
        }
    }

    func onLinkCopied() {
        ToastHost.shared.post(.linkCopied)
    }
}
```

## Import flow — `ImportRouter` + `.fileImporter`

```swift
// AppRoot/YourTubeApp.swift
@main
struct YourTubeApp: App {
    @StateObject private var importRouter = ImportRouter()
    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(importRouter)
                .onOpenURL { url in importRouter.handle(url: url) }
        }
    }
}

// Cold-launch path — UIKit lifecycle handler
final class SceneDelegate: NSObject, UIWindowSceneDelegate {
    func scene(_ scene: UIScene, openURLContexts URLContexts: Set<UIOpenURLContext>) {
        for ctx in URLContexts {
            ImportRouter.shared.handle(url: ctx.url)
        }
    }
}
```

```swift
// Sharing/ImportRouter.swift
@MainActor
final class ImportRouter: ObservableObject {
    static let shared = ImportRouter()
    @Published var pickerPresented = false
    @Published var pendingURL: URL? = nil

    func presentPicker() { pickerPresented = true }

    func handle(url: URL) {
        // Cold/warm AirDrop, Files-app share-to, Mail attachment open-with.
        pendingURL = url
    }
}
```

```swift
// Library/LibraryView.swift — toolbar Menu (decision 3.1)
.toolbar {
    ToolbarItem(placement: .topBarTrailing) {
        Menu {
            Button {
                importRouter.presentPicker()
            } label: {
                Label("Import playlist…", systemImage: "square.and.arrow.down")
            }
        } label: {
            Image(systemName: "ellipsis.circle")
        }
    }
}
.fileImporter(
    isPresented: $importRouter.pickerPresented,
    allowedContentTypes: [.json, .ytplaylistJSON],
    allowsMultipleSelection: false,
) { result in
    switch result {
    case .success(let urls):
        if let url = urls.first { importRouter.handle(url: url) }
    case .failure: break
    }
}
.sheet(item: $importRouter.pendingURL) { url in
    ImportFlowView(url: url)
}
```

> **Note:** `URL` does not conform to `Identifiable` out of the box. Wrap it: `extension URL: @retroactive Identifiable { public var id: URL { self } }` in the Sharing module so `.sheet(item:)` works directly.

### `ImportViewModel.importFile(url:)`

```swift
@MainActor
final class ImportViewModel: ObservableObject {
    @Published private(set) var state: ImportState = .idle

    func importFile(url: URL) async {
        state = .reading

        let displayName = url.lastPathComponent
        guard displayName.lowercased().hasSuffix(".ytplaylist.json") else {
            state = .error(.wrongExtension); return
        }

        let didStart = url.startAccessingSecurityScopedResource()
        defer { if didStart { url.stopAccessingSecurityScopedResource() } }

        let data: Data
        do {
            data = try Data(contentsOf: url)
        } catch {
            state = .error(.readPermission); return
        }

        let payload: PlaylistPayload
        do {
            payload = try PlaylistCodec.decode(data: data)
        } catch PlaylistCodecError.unsupportedSchemaVersion {
            state = .error(.futureSchema); return
        } catch {
            state = .error(.parse); return
        }

        do {
            let saved = try await playlistRepo.upsert(payload)
            state = .successPreview(saved)
        } catch {
            state = .error(.parse)
        }
    }
}
```

`PlaylistCodec.decode(data:)` is the same codec the parity tests exercise (`CrossPlatformParityTests.swift`). Do not bypass it for "performance" — the decoder is the contract surface.

## Entry point wiring

### Per-playlist `.contextMenu`

```swift
// Library/PlaylistRow.swift
PlaylistRowContent(playlist: playlist)
    .contextMenu {
        Button { /* play next */ } label: { Label("Play next", systemImage: "text.line.first.and.arrowtriangle.forward") }
        Button { /* add to queue */ } label: { Label("Add to queue", systemImage: "text.badge.plus") }
        Button {
            exportSheetPresented = true
        } label: {
            Label("Share…", systemImage: "square.and.arrow.up")
        }
        Button { /* rename */ } label: { Label("Rename…", systemImage: "pencil") }
        Button(role: .destructive) { /* delete */ } label: { Label("Delete…", systemImage: "trash") }
    }
    .sheet(isPresented: $exportSheetPresented) {
        ExportSheetView(playlistID: playlist.id)
    }
```

### Settings § Playlists rows

```swift
// Settings/SettingsView.swift
Section("Playlists") {
    Button {
        showPlaylistPickerForExport = true
    } label: {
        HStack {
            Label("Export Playlist…", systemImage: "square.and.arrow.up.on.square")
            Spacer()
            Image(systemName: "chevron.right").foregroundStyle(.tertiary)
        }
    }
    .foregroundStyle(.primary)

    Button {
        importRouter.presentPicker()
    } label: {
        HStack {
            Label("Import Playlist…", systemImage: "square.and.arrow.down")
            Spacer()
            Image(systemName: "chevron.right").foregroundStyle(.tertiary)
        }
    }
    .foregroundStyle(.primary)
}
```

Cite [YT-0017 Settings handoff](../YT-0017/) when it lands. Until then, follow the existing § Storage section style.

## Liquid Glass (iOS 26)

On iOS 26, sheets with `.presentationBackground(.thinMaterial)` may render as `.glassBackgroundEffect()` if the device supports the Liquid Glass system. Do **not** explicitly opt in — let the system choose. Confirmed: the default `.sheet` already adapts. Confirmed: setting `.presentationBackground(Color.black)` overrides Liquid Glass and is incorrect.

On iOS 17–25, the sheet uses the standard `.regularMaterial` background. The design must look correct on both — verify by toggling the simulator's OS version during review.

The op loading overlay (decision 10) does NOT use glass — it's an explicit opaque scrim (`Color.black.opacity(0.6)`) because the user must understand they cannot interact with the underlying surface. Glass implies "see what's behind"; a hard scrim implies "blocked".

## Token mapping (mockup CSS → SwiftUI)

| Mockup CSS / `colors_and_type.css` | SwiftUI |
|---|---|
| `--color-accent` (#8B5CF6) | `Color.accentColor` (set `.tint(...)` at root; `AccentColor` asset = #8B5CF6) |
| `--color-bg` (#0F0F0F) | `Color(.systemBackground)` (auto-adapts) |
| `--color-surface` | `Color(.secondarySystemBackground)` |
| `--color-fg-secondary` | `.foregroundStyle(.secondary)` |
| `--color-fg-tertiary` | `.foregroundStyle(.tertiary)` |
| `--radius-md: 12px` | `RoundedRectangle(cornerRadius: 12, style: .continuous)` |
| `--color-overlay-sheet` scrim | `Color.black.opacity(0.6)` (op-overlay only) |
| `--font-sans` | System SF Pro — do not import Geist on iOS |
| Mockup "Share" button bg | `.buttonStyle(.borderedProminent).controlSize(.large)` |
| Mockup "Save to Files" outline | `.buttonStyle(.bordered).controlSize(.large)` |

## Tests

```
ios/YourTubeTests/Sharing/
  ImportViewModelTests.swift
    - wrongExtension when URL.lastPathComponent does not end in .ytplaylist.json
    - readPermission when Data(contentsOf:) throws
    - decode of playlist-future-schema.ytplaylist.json → .futureSchema
    - decode of playlist-valid-v1.ytplaylist.json → .successPreview with decoded payload
    - case-insensitive extension check accepts "PLAYLIST.YTPLAYLIST.JSON"

  ExportSheetViewModelTests.swift
    - prepare writes file to temporaryDirectory with sanitized name
    - prepare returns URL that survives until the sheet's lifetime ends
    - sanitizedFilename strips path separators and reserved characters

  AirDropReceiveTests.swift (integration; UI test)
    - scene(_:openURLContexts:) routes a fixture URL into the import sheet
    - .onOpenURL routes a fixture URL into the import sheet when warm-started
```

The existing `CrossPlatformParityTests.swift` already covers codec parity and future-schema rejection — do not duplicate.
