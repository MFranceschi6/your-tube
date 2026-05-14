package com.yourtube.feature.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.yourtube.core.common.model.Track

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryRowSheet(
    track: Track,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onRemove: () -> Unit,
    onShare: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        ListItem(
            headlineContent = {
                Text(track.title, style = MaterialTheme.typography.titleMedium)
            },
            supportingContent = { Text(track.channel) },
        )
        HorizontalDivider()
        HistorySheetAction(Icons.Rounded.PlaylistPlay, "Play next", onPlayNext)
        HistorySheetAction(Icons.Rounded.QueueMusic, "Add to queue", onAddToQueue)
        HistorySheetAction(Icons.Rounded.PlaylistAdd, "Add to playlist", onAddToPlaylist)
        HistorySheetAction(Icons.Rounded.Share, "Share", onShare)
        HorizontalDivider()
        ListItem(
            leadingContent = {
                Icon(
                    Icons.Rounded.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            headlineContent = {
                Text(
                    "Remove from history",
                    color = MaterialTheme.colorScheme.error,
                )
            },
            modifier = Modifier.clickable { onRemove(); onDismiss() },
        )
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun HistorySheetAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    ListItem(
        leadingContent = { Icon(icon, contentDescription = null) },
        headlineContent = { Text(label) },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    )
}
