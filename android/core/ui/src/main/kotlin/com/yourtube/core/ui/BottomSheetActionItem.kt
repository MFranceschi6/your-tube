package com.yourtube.core.ui

import androidx.compose.foundation.clickable
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * A single tappable row inside a Material 3 `ModalBottomSheet` action sheet.
 *
 * Used by Library (playlist long-press menu) and PlaylistDetail (track
 * long-press menu) so both context menus share identical visuals, padding, and
 * accessibility wiring. Pass [contentColor] to render destructive actions
 * (`MaterialTheme.colorScheme.error`).
 */
@Composable
fun BottomSheetActionItem(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    ListItem(
        headlineContent = { Text(text = label, color = contentColor) },
        modifier = modifier.clickable(onClickLabel = label, onClick = onClick),
    )
}
