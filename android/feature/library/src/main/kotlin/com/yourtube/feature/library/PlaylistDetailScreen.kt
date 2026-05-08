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
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import com.yourtube.core.ui.ErrorState
import com.yourtube.core.ui.LoadingList
import com.yourtube.core.ui.LocalAppShellInsets
import com.yourtube.core.ui.SkeletonPlaylistHeader
import com.yourtube.core.ui.SkeletonRow
import com.yourtube.core.ui.TrackRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailScreen(
    onBackClick: () -> Unit,
    onPlaylistDeleted: () -> Unit,
    onShare: (PlaylistDetailViewModel.SharePayload) -> Unit,
    onPlayPlaylist: (List<Track>) -> Unit = {},
    onShufflePlaylist: (List<Track>) -> Unit = {},
    /**
     * YT-0155: tap-on-row plays the full playlist (natural order) starting at the
     * tapped index. Falls back to [onPlayPlaylist] semantics for index 0 so callers
     * that only wire the header buttons keep working.
     */
    onPlayPlaylistAt: (List<Track>, Int) -> Unit = { tracks, _ -> onPlayPlaylist(tracks) },
    /** YT-0164 C10: navigate to the Search tab from the empty state's "Find tracks" CTA. */
    onGoToSearch: () -> Unit = {},
    currentTrackVideoId: String? = null,
    modifier: Modifier = Modifier,
    viewModel: PlaylistDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    var menuExpanded by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    // YT-0063a Q12: consume the AppShell-supplied bottom inset so the last track
    // never sits under the MiniPlayer / FAB / nav bar. AppShell exposes
    // `LocalAppShellInsets` as bottom-only `PaddingValues`, mirroring the LibraryScreen
    // consumption pattern.
    val shellInsets = LocalAppShellInsets.current

    // YT-0164 catalog rule: the toolbar title hides while loading and during
    // an Error state — only render the playlist name once we have it.
    val toolbarTitle: String = when (val s = state) {
        is PlaylistDetailUiState.Empty -> s.playlist.name
        is PlaylistDetailUiState.Content -> s.playlist.name
        else -> ""
    }
    val canShowOverflow: Boolean = state is PlaylistDetailUiState.Empty ||
        state is PlaylistDetailUiState.Content

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(toolbarTitle) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (canShowOverflow) {
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
                    }
                },
            )
        },
    ) { innerPadding ->
        when (val current = state) {
            PlaylistDetailUiState.Loading -> {
                // C9 — skeleton header + 6 skeleton rows.
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                ) {
                    SkeletonPlaylistHeader()
                    LoadingList(
                        rowCount = 6,
                        rowFactory = { i -> SkeletonRow(staggerIndex = i) },
                    )
                }
            }

            is PlaylistDetailUiState.Empty -> {
                // C10 — the toolbar still shows the playlist name (above), but
                // we hide the Play/Shuffle action row since there are no tracks
                // to play. The CTA punts the user to Search.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                ) {
                    EmptyState(
                        icon = { mod ->
                            Icon(Icons.Rounded.MusicNote, contentDescription = null, modifier = mod)
                        },
                        title = "This playlist is empty",
                        body = "Add tracks from search or your history.",
                        actionLabel = "Find tracks",
                        onAction = onGoToSearch,
                        scrollable = true,
                    )
                }
            }

            is PlaylistDetailUiState.Content -> {
                val playlist = current.playlist
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                ) {
                    // YT-0063a Q4 — Play (filled, weight 2) + Shuffle (tonal, weight 1) action
                    // row directly under the toolbar.
                    PlaylistActions(
                        onPlay = { onPlayPlaylist(playlist.tracks) },
                        onShuffle = { onShufflePlaylist(playlist.tracks) },
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
                        itemsIndexed(playlist.tracks, key = { _, t -> t.videoId }) { index, track ->
                            // YT-0184: dismiss-by-videoId so two simultaneous swipes don't
                            // race on a captured index. The repository serializes mutations
                            // and no-ops when the track is already gone.
                            val dismissState = rememberSwipeToDismissBoxState(
                                confirmValueChange = { value ->
                                    if (value == SwipeToDismissBoxValue.EndToStart) {
                                        viewModel.removeTrack(track)
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
                                    // YT-0155: tap a row → playlist becomes the queue, starting here.
                                    onClick = { onPlayPlaylistAt(playlist.tracks, index) },
                                    onMoreClick = {},
                                )
                            }
                        }
                    }
                }
            }

            PlaylistDetailUiState.Error -> {
                // C11 — full error state. Catalog: header does NOT render in
                // this state (we hide the toolbar title and overflow above).
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                ) {
                    ErrorState(
                        title = "Couldn't load this playlist",
                        body = "Check your connection and try again.",
                        onRetry = viewModel::retry,
                        scrollable = true,
                    )
                }
            }
        }
    }

    if (showRenameDialog) {
        val currentName: String = when (val s = state) {
            is PlaylistDetailUiState.Empty -> s.playlist.name
            is PlaylistDetailUiState.Content -> s.playlist.name
            else -> ""
        }
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
