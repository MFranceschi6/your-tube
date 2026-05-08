package com.yourtube.feature.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.runtime.collectAsState
import com.yourtube.core.common.model.Playlist
import com.yourtube.core.ui.EmptyState
import com.yourtube.core.ui.ErrorState
import com.yourtube.core.ui.LoadingList
import com.yourtube.core.ui.LocalAppShellInsets
import com.yourtube.core.ui.LocalAppShellSlots
import com.yourtube.core.ui.PlaylistRow
import com.yourtube.core.ui.SkeletonPlaylistRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onOpenRecentlyPlayed: () -> Unit,
    onOpenPlaylistDetail: (playlistId: String) -> Unit,
    /**
     * YT-0156 — sink for playlist share intents fired from the row overflow menu.
     * Wired by `AppShell` to `PlaylistShareLauncher`; defaults to a no-op so screen
     * previews / tests don't have to thread it.
     */
    onShare: (PlaylistDetailViewModel.SharePayload) -> Unit = {},
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
            LibraryUiState.Loading -> {
                // C6 — skeleton ×4 below the Recently Played row. Header stays
                // visible above the skeletons so the destination doesn't flash
                // empty during initial load.
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                ) {
                    RecentlyPlayedRow(onClick = onOpenRecentlyPlayed)
                    LoadingList(
                        rowCount = 4,
                        rowFactory = { i -> SkeletonPlaylistRow(staggerIndex = i) },
                    )
                }
            }

            LibraryUiState.Empty -> {
                // C7 — Recently Played row at top + EmptyState with Create
                // playlist FAB-style CTA (default TONAL primary button per the
                // catalog's empty-state convention).
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                ) {
                    RecentlyPlayedRow(onClick = onOpenRecentlyPlayed)
                    EmptyState(
                        icon = { mod ->
                            Icon(Icons.Rounded.LibraryMusic, contentDescription = null, modifier = mod)
                        },
                        title = "No playlists yet",
                        body = "Create one to organize tracks for offline listening.",
                        actionLabel = "Create playlist",
                        onAction = { showCreateDialog = true },
                    )
                }
            }

            is LibraryUiState.Error -> {
                // C8 — full-screen error. Library is local-first, so an error
                // here is rare; assertive live region per catalog accessibility
                // rules for initial-load failures.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                ) {
                    ErrorState(
                        title = "Couldn't load your library",
                        body = "Check your connection and try again.",
                        onRetry = viewModel::retry,
                        assertive = true,
                    )
                }
            }

            is LibraryUiState.Content -> {
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
                        // YT-0153: dropdown lives inside the same Box as the IconButton
                        // (via PlaylistRow's `trailingContent` slot) so it anchors under
                        // the dot instead of at the row's leading edge.
                        PlaylistRow(
                            playlist = playlist,
                            onClick = { onOpenPlaylistDetail(playlist.id) },
                            onMoreClick = {},
                            trailingContent = {
                                Box {
                                    IconButton(
                                        onClick = { menuExpanded = true },
                                        modifier = Modifier.semantics {
                                            contentDescription = "More options for ${playlist.name}"
                                        },
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.MoreVert,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    DropdownMenu(
                                        expanded = menuExpanded,
                                        onDismissRequest = { menuExpanded = false },
                                    ) {
                                        // YT-0156 — Share is the first item, mirroring the
                                        // PlaylistDetail toolbar overflow order.
                                        DropdownMenuItem(
                                            text = { Text("Share") },
                                            modifier = Modifier.semantics { contentDescription = "Share playlist" },
                                            onClick = {
                                                menuExpanded = false
                                                viewModel.encodeForShare(playlist.id)?.let(onShare)
                                            },
                                        )
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
                            },
                        )
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

/**
 * Shared "Recently Played" row used by Loading and Empty branches. The
 * Content branch renders the same destination via the LazyColumn header so the
 * cell can be the first scroll item; this helper exists for the static
 * (non-list) layouts to avoid duplicating the ListItem wiring.
 */
@Composable
private fun RecentlyPlayedRow(onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text("Recently Played") },
        leadingContent = { Icon(Icons.Rounded.History, contentDescription = null) },
        trailingContent = { Icon(Icons.Rounded.ChevronRight, contentDescription = null) },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Open recently played", onClick = onClick),
    )
    HorizontalDivider()
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
