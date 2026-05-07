package com.yourtube.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.runtime.collectAsState
import com.yourtube.core.common.model.Track
import com.yourtube.core.ui.EmptyState
import com.yourtube.core.ui.LocalAppShellInsets
import com.yourtube.core.ui.TrackRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailScreen(
    onBackClick: () -> Unit,
    onPlaylistDeleted: () -> Unit,
    onShare: (PlaylistDetailViewModel.SharePayload) -> Unit,
    onPlayPlaylist: (List<Track>) -> Unit = {},
    onShufflePlaylist: (List<Track>) -> Unit = {},
    currentTrackVideoId: String? = null,
    modifier: Modifier = Modifier,
    viewModel: PlaylistDetailViewModel = hiltViewModel(),
) {
    val playlist by viewModel.playlist.collectAsState()
    var menuExpanded by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    // YT-0063a Q12: consume the AppShell-supplied bottom inset so the last track
    // never sits under the MiniPlayer / FAB / nav bar. AppShell exposes
    // `LocalAppShellInsets` as bottom-only `PaddingValues`, mirroring the LibraryScreen
    // consumption pattern.
    val shellInsets = LocalAppShellInsets.current

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(playlist?.name ?: "") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Rounded.MoreVert, contentDescription = "More options")
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Share") },
                                enabled = playlist != null,
                                onClick = {
                                    menuExpanded = false
                                    viewModel.encodeForShare()?.let(onShare)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Rename") },
                                onClick = { menuExpanded = false; showRenameDialog = true },
                            )
                            DropdownMenuItem(
                                text = { Text("Delete playlist") },
                                onClick = { menuExpanded = false; showDeleteDialog = true },
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        val currentPlaylist = playlist
        if (currentPlaylist == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else if (currentPlaylist.tracks.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                EmptyState(
                    icon = { mod -> Icon(Icons.Rounded.LibraryMusic, contentDescription = null, modifier = mod) },
                    title = "No tracks yet",
                    body = "Add tracks from Search to populate this playlist.",
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                // YT-0063a Q4 — Play (filled, weight 2) + Shuffle (tonal, weight 1) action
                // row directly under the toolbar. Share stays in the toolbar overflow per
                // the AC. Hidden when the playlist has no tracks (handled by the outer
                // branch above).
                PlaylistActions(
                    onPlay = { onPlayPlaylist(currentPlaylist.tracks) },
                    onShuffle = { onShufflePlaylist(currentPlaylist.tracks) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                )
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    // YT-0063a Q12: bottom inset from AppShell ensures the last track
                    // clears the MiniPlayer + nav bar without per-screen inset math.
                    contentPadding = PaddingValues(
                        bottom = shellInsets.calculateBottomPadding(),
                    ),
                ) {
                    itemsIndexed(currentPlaylist.tracks, key = { _, t -> t.videoId }) { index, track ->
                        val dismissState = rememberSwipeToDismissBoxState(
                            confirmValueChange = { value ->
                                if (value == SwipeToDismissBoxValue.EndToStart) {
                                    viewModel.removeTrack(index)
                                    true
                                } else false
                            },
                        )
                        SwipeToDismissBox(
                            state = dismissState,
                            backgroundContent = {},
                        ) {
                            TrackRow(
                                track = track,
                                isPlaying = currentTrackVideoId == track.videoId,
                                onClick = {},
                                onMoreClick = {},
                            )
                        }
                    }
                }
            }
        }
    }

    if (showRenameDialog) {
        val currentName = playlist?.name ?: ""
        var name by remember(currentName) { mutableStateOf(currentName) }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Rename playlist") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.renamePlaylist(name)
                        showRenameDialog = false
                    },
                    enabled = name.isNotBlank(),
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) { Text("Cancel") }
            },
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete this playlist?") },
            text = { Text("The tracks will stay in your library. This can't be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deletePlaylist()
                        showDeleteDialog = false
                        onPlaylistDeleted()
                    },
                ) {
                    Text("Delete Playlist", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
            },
        )
    }
}

/**
 * YT-0063a Q4 — Play (filled, leading `play_arrow`, weight 2) + Shuffle (tonal, leading
 * `shuffle`, weight 1) action row. 12 dp gap. 48 dp height. Per the YT-0014 decision-log §4
 * the row sits directly under the cover (Q3 CollapsingHeader, not yet implemented) — until
 * that lands the row sits between the toolbar and the LazyColumn.
 */
@Composable
private fun PlaylistActions(
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(
            onClick = onPlay,
            modifier = Modifier
                .weight(2f)
                .height(48.dp)
                .semantics { contentDescription = "Play playlist" },
        ) {
            Icon(
                imageVector = Icons.Rounded.PlayArrow,
                contentDescription = null,
            )
            Text(
                text = "Play",
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        FilledTonalButton(
            onClick = onShuffle,
            modifier = Modifier
                .weight(1f)
                .height(48.dp)
                .semantics { contentDescription = "Shuffle playlist" },
        ) {
            Icon(
                imageVector = Icons.Rounded.Shuffle,
                contentDescription = null,
            )
            Text(
                text = "Shuffle",
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}
