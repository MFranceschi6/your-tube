package com.yourtube.feature.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.runtime.collectAsState
import com.yourtube.core.ui.LocalAppShellInsets

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
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    var showQualityDialog by remember { mutableStateOf(false) }
    val shellInsets = LocalAppShellInsets.current

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) onImportFromUri(uri) }

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
                ListItem(
                    headlineContent = { Text("Import playlist") },
                    supportingContent = { Text("Open a .ytplaylist.json file") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            onClickLabel = "Import playlist",
                            onClick = {
                                importLauncher.launch(IMPORT_PLAYLIST_MIME_TYPES)
                            },
                        ),
                )
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
