package com.yourtube.feature.library

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import com.yourtube.core.ui.R as CoreUiR

/**
 * YT-0063a v2 Q8 — destructive-confirm dialog for whole-playlist delete.
 *
 * Shared between [LibraryScreen] (row long-press → Delete) and
 * [PlaylistDetailScreen] (toolbar overflow → Delete) so the copy and colour
 * split stay in lock-step with the v2 decision log.
 */
@Composable
internal fun DeletePlaylistDialog(
    playlistName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(CoreUiR.string.lbl_dialog_delete_playlist_title)) },
        text = {
            Text(stringResource(CoreUiR.string.lbl_dialog_delete_playlist_body, playlistName))
        },
        confirmButton = {
            TextButton(onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.Reject)
                onConfirm()
            }) {
                Text(
                    stringResource(CoreUiR.string.lbl_dialog_btn_delete),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    stringResource(CoreUiR.string.lbl_dialog_btn_cancel),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        },
    )
}
