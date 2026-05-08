package com.yourtube.feature.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.yourtube.core.ui.LocalAppShellInsets
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Outcome forwarded from the AppShell-scoped `SharingViewModel` into the
 * Settings destination so the C16 inline banner can react to import results
 * without the Settings module depending on the `:app` module's
 * `PlaylistImportResult` sealed type.
 *
 * - [Success] — the picked file was imported.
 * - [Failure] — the file was unreadable, malformed, or used a schema this app
 *               doesn't understand. All three map to the same banner copy in
 *               `design-system/handoff/state-catalog/copy.md` C16.import.
 */
enum class SettingsImportOutcome { Success, Failure }

/**
 * MIME types passed to the [ActivityResultContracts.OpenDocument] launcher used by
 * Settings → Import playlist.
 *
 * We accept:
 * - `application/json`: the canonical type for our exported `.ytplaylist.json` files.
 * - `application/octet-stream`: some content providers (notably files surfaced via
 *   "Recent" / Downloads on Pixel devices) classify untyped JSON exports as octet-stream
 *   when no extension-based mapping is available. Keeping it here ensures a freshly
 *   exported playlist is always selectable without forcing the user to flip the SAF
 *   filter to "All files".
 *
 * `text/plain` and the wildcard `* / *` (without spaces) are intentionally omitted:
 * they over-broaden the picker so SAF lists arbitrary system files
 * (PNGs, XML, screenshots) which the user must then scan.
 * The downstream import handler validates content shape, so we can keep this filter tight.
 */
internal val IMPORT_PLAYLIST_MIME_TYPES: Array<String> = arrayOf(
    "application/json",
    "application/octet-stream",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onImportFromUri: (Uri) -> Unit,
    modifier: Modifier = Modifier,
    importEvents: Flow<SettingsImportOutcome> = emptyFlow(),
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    var showQualityDialog by remember { mutableStateOf(false) }
    val shellInsets = LocalAppShellInsets.current

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            // YT-0164 C15: signal in-progress while the SAF document is read
            // by the downstream importer. The terminal state is now driven by
            // [importEvents] (YT-0187) — Success flips back to Idle; any
            // failure variant flips to Failed and renders the C16 banner.
            viewModel.onImportStarted()
            onImportFromUri(uri)
        }
    }

    // YT-0187: drive the terminal row state from real importer outcomes
    // rather than the previous 600 ms auto-clear. The shell maps
    // SharingViewModel.PlaylistImportResult into SettingsImportOutcome so this
    // module stays free of an :app dependency.
    LaunchedEffect(importEvents, viewModel) {
        importEvents.collect { outcome ->
            when (outcome) {
                SettingsImportOutcome.Success -> viewModel.onImportSucceeded()
                SettingsImportOutcome.Failure -> viewModel.onImportFailed()
            }
        }
    }

    // YT-0164 C16: surface retry requests from the inline banner by re-
    // launching the SAF picker. The banner's "Try again" calls
    // viewModel.retryImport() which emits on retryImportRequests.
    LaunchedEffect(viewModel) {
        viewModel.retryImportRequests.collect {
            importLauncher.launch(IMPORT_PLAYLIST_MIME_TYPES)
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(title = { Text("Settings") })
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            // YT-0061: bottom inset from AppShell ensures content clears MiniPlayer + nav bar.
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                bottom = shellInsets.calculateBottomPadding(),
            ),
        ) {
            item {
                SectionHeader("Playback")
            }
            item {
                ListItem(
                    headlineContent = { Text("Audio Quality") },
                    supportingContent = { Text(uiState.audioQuality.label) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            onClickLabel = "Change audio quality",
                            onClick = { showQualityDialog = true },
                        ),
                )
                HorizontalDivider()
            }
            item {
                SectionHeader("Playlists")
            }
            item {
                val importing = uiState.importRow == ImportRowState.InProgress
                ListItem(
                    headlineContent = { Text("Import playlist") },
                    // C15 status text: replace the row subtitle with the
                    // operation-scoped progress copy while in flight.
                    supportingContent = {
                        Text(
                            if (importing) "Reading file…"
                            else "Open a .ytplaylist.json file",
                        )
                    },
                    // C15 indicator: 24 dp spinner replaces the row chevron
                    // while the operation runs. Settings page itself stays
                    // interactive — only this row's trailing area changes.
                    trailingContent = if (importing) {
                        {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    } else null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            enabled = !importing,
                            onClickLabel = "Import playlist",
                            onClick = {
                                importLauncher.launch(IMPORT_PLAYLIST_MIME_TYPES)
                            },
                        ),
                )
                // YT-0187 C16: inline banner attaches *below* the failed row,
                // full-width within the row's section group. Renders only when
                // the most recent import attempt failed; the spinner row above
                // stays untouched.
                if (uiState.importRow == ImportRowState.Failed) {
                    ImportErrorBanner(
                        onRetry = { viewModel.retryImport() },
                        onDismiss = { viewModel.dismissImportError() },
                    )
                }
                HorizontalDivider()
            }
            item {
                SectionHeader("About")
            }
            item {
                ListItem(
                    headlineContent = { Text("YourTube") },
                    supportingContent = { Text("Personal-use YouTube audio client") },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("Version") },
                    supportingContent = { Text("0.1.0") },
                )
                HorizontalDivider()
            }
        }
    }

    if (showQualityDialog) {
        AudioQualityDialog(
            currentQuality = uiState.audioQuality,
            onSelect = { quality ->
                viewModel.setAudioQuality(quality)
                showQualityDialog = false
            },
            onDismiss = { showQualityDialog = false },
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

/**
 * YT-0187 C16 inline banner — Settings · operation failed.
 *
 * Form factor and copy follow `design-system/handoff/state-catalog/error.md`
 * C16 and `copy.md` C16.import verbatim:
 *
 *  - Background: `Color(0xFFFF453A).copy(alpha = 0.12f)`
 *    (`--color-error-surface` from the catalog).
 *  - 3 dp left accent strip in `Color(0xFFFF453A)` — the only place red
 *    appears in this catalog, and only as a strip (icon and text stay
 *    `onSurfaceVariant`).
 *  - 12 dp internal padding.
 *  - 20 dp `Icons.Rounded.ErrorOutline` icon, tinted `onSurfaceVariant`.
 *  - Two right-aligned `TextButton`s — "Try again" and "Dismiss".
 *  - `liveRegion = Assertive` — the only assertive announcement in the
 *    catalog. Out-of-band failures justify the interrupt.
 *
 * The icon's `contentDescription` is null (decorative); the live region
 * announces the title + body together when the banner enters composition.
 */
@Composable
private fun ImportErrorBanner(
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val errorRed = Color(0xFFFF453A)
    // 3 dp left accent strip drawn via `drawBehind` so the strip is the full
    // banner height regardless of the parent's vertical constraints. A leading
    // `fillMaxHeight()` Box renders at 0 dp inside a `LazyColumn` item because
    // the incoming `maxHeight` is `Constraints.Infinity`.
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Assertive }
            .drawBehind {
                drawRect(
                    color = errorRed,
                    size = Size(width = 3.dp.toPx(), height = size.height),
                )
            },
        color = errorRed.copy(alpha = 0.12f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 3.dp)
                .padding(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = Icons.Rounded.ErrorOutline,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Couldn't import that file.",
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "This file may be corrupted or made with a newer version.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = onRetry) { Text("Try again") }
                TextButton(onClick = onDismiss) { Text("Dismiss") }
            }
        }
    }
}

@Composable
private fun AudioQualityDialog(
    currentQuality: AudioQuality,
    onSelect: (AudioQuality) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Audio Quality") },
        text = {
            Column {
                AudioQuality.entries.forEach { quality ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(quality) },
                    ) {
                        RadioButton(
                            selected = quality == currentQuality,
                            onClick = { onSelect(quality) },
                        )
                        Text(quality.label)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
