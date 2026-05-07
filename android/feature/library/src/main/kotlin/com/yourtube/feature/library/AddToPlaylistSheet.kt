package com.yourtube.feature.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.yourtube.core.common.model.Playlist
import com.yourtube.core.common.model.Track
import com.yourtube.core.ui.EmptyState

/**
 * Modal bottom sheet that lets the user add [track] to one of their existing playlists or create
 * a brand-new playlist seeded with [track]. Pulls the playlist list from
 * [AddToPlaylistViewModel] (Hilt-scoped to the current NavBackStackEntry).
 *
 * The sheet dismisses itself after a successful action; callers only need to clear their
 * "target track" state inside [onDismiss].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToPlaylistSheet(
    track: Track,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AddToPlaylistViewModel = hiltViewModel(),
) {
    val playlists by viewModel.playlists.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    AddToPlaylistSheetContent(
        track = track,
        playlists = playlists,
        sheetState = sheetState,
        onAddToExisting = { playlistId ->
            viewModel.addTrack(playlistId, track)
            onDismiss()
        },
        onCreateAndAdd = { name ->
            viewModel.createAndAdd(name, track)
            onDismiss()
        },
        onDismiss = onDismiss,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddToPlaylistSheetContent(
    track: Track,
    playlists: List<Playlist>,
    sheetState: SheetState,
    onAddToExisting: (playlistId: String) -> Unit,
    onCreateAndAdd: (name: String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var creatingNew by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
        ) {
            Text(
                text = "Add to playlist",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
            )
            Text(
                text = track.title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            HorizontalDivider()

            if (creatingNew) {
                NewPlaylistInline(
                    name = newName,
                    onNameChange = { newName = it },
                    onConfirm = {
                        if (newName.isNotBlank()) onCreateAndAdd(newName)
                    },
                    onCancel = {
                        creatingNew = false
                        newName = ""
                    },
                )
            } else {
                CreateNewRow(
                    onClick = { creatingNew = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Create new playlist with this track" },
                )
                HorizontalDivider()
            }

            if (playlists.isEmpty() && !creatingNew) {
                EmptyState(
                    icon = { mod -> Icon(Icons.Rounded.QueueMusic, contentDescription = null, modifier = mod) },
                    title = "No playlists yet",
                    body = "Create one above to add this track.",
                )
            } else if (!creatingNew) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 480.dp),
                ) {
                    items(playlists, key = { it.id }) { playlist ->
                        PlaylistPickerRow(
                            playlist = playlist,
                            onClick = { onAddToExisting(playlist.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CreateNewRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ListItem(
        headlineContent = { Text("Create new playlist") },
        leadingContent = {
            Icon(Icons.Rounded.Add, contentDescription = null)
        },
        modifier = modifier.clickable(onClickLabel = "Create new playlist", onClick = onClick),
    )
}

@Composable
private fun PlaylistPickerRow(
    playlist: Playlist,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(playlist.name, maxLines = 1) },
        supportingContent = {
            val count = playlist.tracks.size
            Text("$count track${if (count != 1) "s" else ""}")
        },
        leadingContent = {
            Icon(Icons.Rounded.QueueMusic, contentDescription = null)
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Add to ${playlist.name}", onClick = onClick)
            .semantics {
                contentDescription = "Add to playlist ${playlist.name}, " +
                    "${playlist.tracks.size} tracks"
            },
    )
}

@Composable
private fun NewPlaylistInline(
    name: String,
    onNameChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            label = { Text("Playlist name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onCancel) { Text("Cancel") }
            Spacer(modifier = Modifier.width(8.dp))
            TextButton(onClick = onConfirm, enabled = name.isNotBlank()) {
                Text("Create & add")
            }
        }
    }
}
