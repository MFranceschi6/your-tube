# YT-0016 — Compose Implementation Spec

> Companion to [`decision-log.md`](decision-log.md). This file is the technical contract — view hierarchy, ViewModels, `ActivityResultContracts`, file layout, state machine. Open `mockup.html` / `mockup-states.html` side-by-side.

## Module / file layout

```
android/feature/sharing/
  src/main/kotlin/com/yourtube/feature/sharing/
    ExportSheet.kt                  ← ModalBottomSheet for export
    ExportSheetViewModel.kt         ← prepare file, FileProvider URI, intents
    ImportLauncher.kt               ← rememberLauncherForActivityResult + MIME filter
    ImportViewModel.kt              ← parse, validate, route to success / error
    ImportSuccessSheet.kt           ← preview + Done / View Playlist
    ImportErrorScreen.kt            ← 4 variants from decision 7
    OpLoadingOverlay.kt             ← C15 state-catalog overlay
    SharingNavigation.kt            ← AppRoute additions for sharing routes
  src/main/AndroidManifest.xml      ← <provider android:name=".sharing.PlaylistFileProvider">
  src/main/res/xml/playlist_file_paths.xml ← FileProvider config (cache-path "exports")

android/feature/library/
  src/main/kotlin/com/yourtube/feature/library/
    LibraryScreen.kt                ← +TopAppBar overflow "Import playlist…"
    PlaylistRow.kt                  ← +long-press anchored DropdownMenu w/ "Share…"

android/feature/settings/
  src/main/kotlin/com/yourtube/feature/settings/
    SettingsScreen.kt               ← +§ Playlists rows "Export Playlist…" / "Import Playlist…"

android/core/data/
  src/main/kotlin/com/yourtube/core/data/codec/
    KotlinxPlaylistCodec.kt         ← (existing) encode/decode + schemaVersion rejection
    PlaylistCodecError.kt           ← (existing) sealed error type — UnsupportedSchemaVersion,
                                       ParseError, ReadError
```

## State machines

### Export

```
idle ──[user taps "Share…" from any entry point]──▶ preparing  (C15 overlay)
                                                     │
preparing ──[file written to FileProvider cache]────▶ readyToShare
                                                     │
readyToShare ──[user taps "Share"]──────────────────▶ shareChooserOpen
readyToShare ──[user taps "Save to Files"]──────────▶ saveChooserOpen
readyToShare ──[user taps "Copy link"]──────────────▶ idle + Snackbar(T08 "Link copied")
readyToShare ──[user dismisses sheet]───────────────▶ idle

shareChooserOpen ──[onActivityResult any]──────────▶ idle + Snackbar(T05 "Exported \"name\"")
saveChooserOpen  ──[onActivityResult OK]───────────▶ idle + Snackbar(T05 "Exported \"name\"")
saveChooserOpen  ──[onActivityResult CANCELED]─────▶ idle (no toast)

preparing ──[encode/write fails]────────────────────▶ idle + Snackbar(T13 "Couldn't save", Retry)
```

### Import

```
idle ──[user taps "Import playlist…" or Settings row]──▶ picking
                                                          │
picking ──[user picks file]─────────────────────────────▶ checkingExtension
picking ──[user cancels]────────────────────────────────▶ idle

checkingExtension ──[name ends in .ytplaylist.json]─────▶ reading  (C15 overlay)
checkingExtension ──[name does NOT end in .ytplaylist.json]──▶ error(WrongExtension)

reading ──[openInputStream throws Security/FileNotFound]──▶ error(ReadPermission)
reading ──[decode succeeds]──────────────────────────────▶ persisting
reading ──[ParseError]───────────────────────────────────▶ error(Parse)
reading ──[UnsupportedSchemaVersion]─────────────────────▶ error(FutureSchema)

persisting ──[Room upsert ok]──────────────────────────▶ successPreview
persisting ──[Room throws]──────────────────────────────▶ error(Parse)  // last-resort bucket

successPreview ──[user taps Done]──────────────────────▶ idle + Snackbar(T06)
successPreview ──[user taps View Playlist]─────────────▶ navigate → PlaylistDetail(id) → Snackbar(T06)

error(*) ──[user taps Try Again]──────────────────────▶ picking  // (or "Open Play Store" for FutureSchema)
```

## Export flow — `ExportSheet`

```kotlin
@Composable
fun ExportSheet(
    playlistId: String,
    onDismiss: () -> Unit,
    vm: ExportSheetViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(playlistId) { vm.prepare(playlistId) }
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val clipboard = LocalClipboardManager.current

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) vm.writeTo(uri) else vm.cancelSave()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = colorScheme.surfaceContainerHigh,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        when (val s = state) {
            is ExportState.Preparing -> {
                // overlay C15 owns this — sheet shows skeleton placeholders only
                ExportSheetSkeleton()
            }
            is ExportState.Ready -> {
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
                    Text(s.playlistName, style = typography.titleLarge, maxLines = 2)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${s.trackCount} tracks · ${formatSize(s.fileSizeBytes)} · .ytplaylist.json",
                        style = typography.bodyMedium,
                        color = colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(24.dp))

                    Button(
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "application/json"
                                putExtra(Intent.EXTRA_STREAM, s.fileUri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(send, null))
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        contentPadding = PaddingValues(horizontal = 24.dp),
                    ) {
                        Icon(Icons.Rounded.Share, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Share")
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = { saveLauncher.launch("${s.playlistName}.ytplaylist.json") },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    ) {
                        Icon(Icons.Rounded.Upload, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Save to Files")
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        onClick = {
                            clipboard.setText(AnnotatedString("yourtube://playlist/${s.playlistId}"))
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            vm.onLinkCopied()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Rounded.Link, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Copy link")
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }

    if (state is ExportState.Preparing) OpLoadingOverlay(visible = true)
}
```

### `FileProvider` wiring (do not forget)

```xml
<!-- AndroidManifest.xml -->
<provider
    android:name="androidx.core.content.FileProvider"
    android:authorities="${applicationId}.sharing.fileprovider"
    android:exported="false"
    android:grantUriPermissions="true">
    <meta-data
        android:name="android.support.FILE_PROVIDER_PATHS"
        android:resource="@xml/playlist_file_paths"/>
</provider>
```

```xml
<!-- res/xml/playlist_file_paths.xml -->
<paths>
    <cache-path name="exports" path="exports/"/>
</paths>
```

```kotlin
// in ExportSheetViewModel.prepare(...)
val outDir = File(context.cacheDir, "exports").apply { mkdirs() }
val file = File(outDir, "${playlistName.sanitize()}.ytplaylist.json")
file.writeText(KotlinxPlaylistCodec.encode(playlist))
val uri: Uri = FileProvider.getUriForFile(
    context, "${context.packageName}.sharing.fileprovider", file
)
```

The cache directory is cleared on app uninstall and by `cacheDir` LRU eviction — the file is short-lived by design.

## Import flow — `ImportLauncher`

```kotlin
private val IMPORT_MIME_TYPES = arrayOf(
    "application/json",
    "application/octet-stream",
)

@Composable
fun rememberImportLauncher(
    onResult: (Uri?) -> Unit,
): ManagedActivityResultLauncher<Array<String>, Uri?> =
    rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = onResult,
    )

@Composable
fun ImportEntryPoint(
    vm: ImportViewModel = hiltViewModel(),
) {
    val launcher = rememberImportLauncher { uri ->
        if (uri != null) vm.importFile(uri)
    }
    // Caller wires its trigger (overflow item, settings row, etc) to launcher.launch(IMPORT_MIME_TYPES)
}
```

### `ImportViewModel.importFile`

```kotlin
fun importFile(uri: Uri) = viewModelScope.launch {
    _state.value = ImportState.Reading

    val displayName = resolveDisplayName(uri)
    if (!displayName.endsWith(".ytplaylist.json", ignoreCase = true)) {
        _state.value = ImportState.Error(ImportError.WrongExtension); return@launch
    }

    val bytes: ByteArray = try {
        contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: run { _state.value = ImportState.Error(ImportError.ReadPermission); return@launch }
    } catch (e: SecurityException) {
        _state.value = ImportState.Error(ImportError.ReadPermission); return@launch
    } catch (e: FileNotFoundException) {
        _state.value = ImportState.Error(ImportError.ReadPermission); return@launch
    }

    val decoded = try {
        KotlinxPlaylistCodec.decode(bytes.decodeToString())
    } catch (e: PlaylistCodecError.UnsupportedSchemaVersion) {
        _state.value = ImportState.Error(ImportError.FutureSchema); return@launch
    } catch (e: PlaylistCodecError.ParseError) {
        _state.value = ImportState.Error(ImportError.Parse); return@launch
    }

    val saved = try {
        playlistRepo.upsert(decoded)   // last-write-wins by id (api-contracts.md § Conflict resolution)
    } catch (e: Exception) {
        _state.value = ImportState.Error(ImportError.Parse); return@launch
    }

    _state.value = ImportState.SuccessPreview(saved)
}
```

### Display-name resolver — the load-bearing extension check

```kotlin
private fun resolveDisplayName(uri: Uri): String =
    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else uri.lastPathSegment.orEmpty()
    } ?: uri.lastPathSegment.orEmpty()
```

`OpenableColumns.DISPLAY_NAME` is the authoritative name for `content://` URIs. Falling back to `uri.lastPathSegment` is OK for `file://` URIs (rare on API 24+) but the picker returns `content://` 99% of the time.

## Entry point wiring

### Library top-app-bar overflow

```kotlin
// LibraryScreen.kt — inside TopAppBar(actions = { ... })
var menuExpanded by remember { mutableStateOf(false) }
IconButton(onClick = { menuExpanded = true }) {
    Icon(Icons.Rounded.MoreVert, contentDescription = "More options")
}
DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
    DropdownMenuItem(
        text = { Text("Import playlist…") },
        onClick = {
            menuExpanded = false
            importLauncher.launch(IMPORT_MIME_TYPES)
        },
        leadingIcon = { Icon(Icons.Rounded.Download, contentDescription = null) },
    )
}
```

### Per-playlist long-press context menu

```kotlin
// PlaylistRow.kt
var menuAnchor by remember { mutableStateOf<Offset?>(null) }
Box(
    Modifier.combinedClickable(
        onClick = { onOpenDetail(playlist.id) },
        onLongClick = { menuAnchor = it; haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
    )
) {
    // …row content
    DropdownMenu(
        expanded = menuAnchor != null,
        onDismissRequest = { menuAnchor = null },
        offset = menuAnchor?.let { DpOffset(it.x.toDp(), it.y.toDp()) } ?: DpOffset.Zero,
    ) {
        DropdownMenuItem(text = { Text("Play next") }, onClick = { /*…*/ })
        DropdownMenuItem(text = { Text("Add to queue") }, onClick = { /*…*/ })
        DropdownMenuItem(
            text = { Text("Share…") },
            leadingIcon = { Icon(Icons.Rounded.Share, contentDescription = null) },
            onClick = { menuAnchor = null; onOpenExportSheet(playlist.id) },
        )
        DropdownMenuItem(text = { Text("Rename…") }, onClick = { /*…*/ })
        DropdownMenuItem(
            text = { Text("Delete…") },
            leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
            onClick = { /*…*/ },
        )
    }
}
```

### Settings § Playlists rows

```kotlin
// SettingsScreen.kt — inside the existing list
SectionHeader("Playlists")
SettingsRow(
    title = "Export Playlist…",
    leadingIcon = Icons.Rounded.Upload,
    onClick = { showPlaylistPickerForExport = true },
    trailingChevron = true,
)
SettingsRow(
    title = "Import Playlist…",
    leadingIcon = Icons.Rounded.Download,
    onClick = { importLauncher.launch(IMPORT_MIME_TYPES) },
    trailingChevron = true,
)
```

Cite [YT-0017 Settings handoff](../YT-0017/) for the `SettingsRow` / `SectionHeader` definitions when that handoff lands. Until then, follow the existing `Settings § Cache` rows for visual parity (chevron disclosure, 48dp row height, leading icon at `onSurfaceVariant`).

## Token mapping (mockup CSS → Compose)

| Mockup CSS / `colors_and_type.css` | Compose / M3 |
|---|---|
| `--color-surface` (sheet bg) | `colorScheme.surfaceContainerHigh` (M3 `ModalBottomSheet` default) |
| `--color-accent` (#8B5CF6) | `colorScheme.primary` (dynamic on API 31+; brand fallback in `Theme.kt`) |
| `--color-fg-primary` (#FFF) | `colorScheme.onSurface` |
| `--color-fg-secondary` | `colorScheme.onSurfaceVariant` |
| `--radius-lg` (16px on the sheet top corners) | M3 sheet shape default (28 dp) — DO NOT override |
| `--shadow-lg` under the sheet | M3 tonal elevation — DO NOT add a shadow |
| Mockup `Share` button bg accent | `Button` filled — `colorScheme.primary` is implicit |
| Mockup `Save to Files` outline | `OutlinedButton` — `colorScheme.outline` is implicit |
| `--color-overlay-sheet` (scrim) | `colorScheme.scrim.copy(alpha = 0.6f)` |

## Mockup web-isms — do NOT translate literally

| Mockup | Compose |
|---|---|
| Hand-drawn cloud / arrow SVGs in the share buttons | `Icons.Rounded.Share`, `Icons.Rounded.Upload`, `Icons.Rounded.Link`, `Icons.Rounded.Download` |
| `transition: transform 200ms cubic-bezier(...)` on button hover | M3 `IconButton` / `Button` state-layer ripple — do not animate `transform` |
| Centered modal `display: flex; justify-content: center;` | `ModalBottomSheet` (full-width, bottom-anchored, drag-handle) |
| `position: absolute; bottom: 0` for the sheet | `ModalBottomSheet` handles insets automatically |
| Custom share-target picker grid in mockup-state 2 | `Intent.createChooser(send, null)` — the system chooser is the picker; never reimplement |
| `font-family: Geist Mono` for `.ytplaylist.json` filename | M3 default Roboto; do not import Geist on Android |

## Tests

The codec tests already cover the format. New tests required:

```
android/feature/sharing/src/test/kotlin/com/yourtube/feature/sharing/
  ImportViewModelTest.kt
    - rejects file with display name "playlist.json" (no .ytplaylist prefix) → WrongExtension
    - rejects file with display name "PLAYLIST.YTPLAYLIST.JSON" → success (case-insensitive)
    - SecurityException on openInputStream → ReadPermission
    - FileNotFoundException on openInputStream → ReadPermission
    - decode of playlist-future-schema.ytplaylist.json → FutureSchema
    - decode of playlist-valid-v1.ytplaylist.json → SuccessPreview, upsert called with the decoded record

  ExportSheetViewModelTest.kt
    - prepare writes file to cacheDir/exports/ with sanitized name
    - prepare returns FileProvider URI matching the configured authority
    - writeTo (Save to Files) copies cache file bytes to the chosen destination Uri
```
