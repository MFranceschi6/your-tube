package com.yourtube.feature.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
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
import com.yourtube.core.common.model.Playlist
import com.yourtube.core.ui.EmptyState
import com.yourtube.core.ui.LocalAppShellInsets
import com.yourtube.core.ui.LocalAppShellSlots
import com.yourtube.core.ui.PlaylistRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onOpenRecentlyPlayed: () -> Unit,
    onOpenPlaylistDetail: (playlistId: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    var showCreateDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<Playlist?>(null) }
    var deleteTarget by remember { mutableStateOf<Playlist?>(null) }

    // YT-0063a Q5: hoist the list scroll state so the registered Extended FAB can collapse to
    // its icon-only form while the user is actively scrolling. Hoisting (vs. local
    // `rememberLazyListState()` inside the LazyColumn) is required because the FAB closure runs
    // inside `DisposableEffect(slots)` above and would otherwise close over a state that does
    // not exist yet on the first frame.
    val listState = rememberLazyListState()
    val fabExpanded by remember { derivedStateOf { !listState.isScrollInProgress } }

    // YT-0061 FAB seam: register the "New playlist" Extended FAB with the AppShell while
    // this destination is composed; clear on disposal so other tabs don't inherit it.
    // YT-0063a Q5: the FAB collapses on scroll via `expanded = !listState.isScrollInProgress`.
    // Re-register on every change to `fabExpanded` so the rendered FAB picks up the new state
    // (the slot stores a single composable lambda; without re-registering the closed-over value
    // would never update).
    val slots = LocalAppShellSlots.current
    DisposableEffect(slots, fabExpanded) {
        slots.setFab {
            ExtendedFloatingActionButton(
                onClick = { showCreateDialog = true },
                icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                text = { Text("New playlist") },
                expanded = fabExpanded,
                modifier = Modifier.semantics { contentDescription = "New playlist" },
            )
        }
        onDispose { slots.setFab(null) }
    }

    val shellInsets = LocalAppShellInsets.current

    Scaffold(
        modifier = modifier,
        topBar = {
            // YT-0063a Q5: the toolbar `+` IconButton is removed — decision-log §5 explicitly
            // marks it redundant when the screen owns an Extended FAB. The FAB is the single
            // entry point for "new playlist" creation.
            TopAppBar(
                title = { Text("Library") },
            )
        },
    ) { innerPadding ->
        when (val state = uiState) {
            LibraryUiState.Loading -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }

            is LibraryUiState.Error -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                Text(state.message, color = MaterialTheme.colorScheme.error)
            }

            is LibraryUiState.Success -> {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    // YT-0061: bottom inset from AppShell ensures the last item clears the
                    // MiniPlayer + FAB + nav bar without per-screen inset math.
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        bottom = shellInsets.calculateBottomPadding(),
                    ),
                ) {
                    item {
                        ListItem(
                            headlineContent = { Text("Recently Played") },
                            leadingContent = {
                                Icon(Icons.Rounded.History, contentDescription = null)
                            },
                            trailingContent = {
                                Icon(Icons.Rounded.ChevronRight, contentDescription = null)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(
                                    onClickLabel = "Open recently played",
                                    onClick = onOpenRecentlyPlayed,
                                ),
                        )
                        HorizontalDivider()
                    }

                    if (state.playlists.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 48.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                EmptyState(
                                    icon = { mod -> Icon(Icons.Rounded.LibraryMusic, contentDescription = null, modifier = mod) },
                                    title = "No playlists yet",
                                    body = "Create one to organize tracks for offline listening.",
                                    actionLabel = "New playlist",
                                    onAction = { showCreateDialog = true },
                                )
                            }
                        }
                    } else {
                        item {
                            Text(
                                text = "Playlists",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                        items(state.playlists, key = { it.id }) { playlist ->
                            var menuExpanded by remember { mutableStateOf(false) }
                            Box {
                                PlaylistRow(
                                    playlist = playlist,
                                    onClick = { onOpenPlaylistDetail(playlist.id) },
                                    onMoreClick = { menuExpanded = true },
                                )
                                DropdownMenu(
                                    expanded = menuExpanded,
                                    onDismissRequest = { menuExpanded = false },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Rename") },
                                        onClick = {
                                            menuExpanded = false
                                            renameTarget = playlist
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Delete") },
                                        onClick = {
                                            menuExpanded = false
                                            deleteTarget = playlist
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        CreatePlaylistDialog(
            onConfirm = { name ->
                viewModel.createPlaylist(name)
                showCreateDialog = false
            },
            onDismiss = { showCreateDialog = false },
        )
    }

    renameTarget?.let { playlist ->
        RenamePlaylistDialog(
            currentName = playlist.name,
            onConfirm = { newName ->
                viewModel.renamePlaylist(playlist.id, newName)
                renameTarget = null
            },
            onDismiss = { renameTarget = null },
        )
    }

    deleteTarget?.let { playlist ->
        DeletePlaylistDialog(
            playlistName = playlist.name,
            onConfirm = {
                viewModel.deletePlaylist(playlist.id)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
        )
    }
}

@Composable
private fun CreatePlaylistDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Playlist") },
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
                onClick = { if (name.isNotBlank()) onConfirm(name) },
                enabled = name.isNotBlank(),
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun RenamePlaylistDialog(
    currentName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(currentName) { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onDismiss,
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
                onClick = { if (name.isNotBlank()) onConfirm(name) },
                enabled = name.isNotBlank(),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun DeletePlaylistDialog(
    playlistName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete \"$playlistName\"?") },
        text = { Text("The tracks will stay in your library. This can't be undone.") },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
