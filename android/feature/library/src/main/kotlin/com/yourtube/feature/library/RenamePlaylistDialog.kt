package com.yourtube.feature.library

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.yourtube.core.ui.R as CoreUiR

/**
 * YT-0063a N4 — shared rename-playlist dialog.
 *
 * Used by both [LibraryScreen] (row overflow → Rename) and [PlaylistDetailScreen]
 * (toolbar overflow → Rename) so the copy and field validation stay in lock-step.
 */
@Composable
internal fun RenamePlaylistDialog(
    currentName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(currentName) { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(CoreUiR.string.lbl_dialog_rename_playlist_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(CoreUiR.string.lbl_dialog_name_label)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onConfirm(name) },
                enabled = name.isNotBlank(),
            ) { Text(stringResource(CoreUiR.string.lbl_dialog_btn_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CoreUiR.string.lbl_dialog_btn_cancel)) }
        },
    )
}
