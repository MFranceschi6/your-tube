package com.yourtube.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * Generic empty state: icon, title, optional body text, and optional action buttons.
 * Used on list-driven screens when there is no content to display.
 *
 * The [icon] slot receives a `Modifier` constrained to the legacy 64dp icon
 * size; callers should apply it to their `Icon`/illustration so the rendered
 * size stays consistent across screens. [iconTint] flows through
 * [LocalContentColor] so `Icon { ... }` calls inside the slot pick it up
 * automatically; callers that need a custom tint can override inside the slot.
 *
 * [secondaryActionLabel] / [onSecondaryAction] enable two-button empty states
 * (rendered as an outlined button next to the primary filled button).
 */
@Composable
fun EmptyState(
    icon: @Composable (Modifier) -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    iconTint: Color = LocalContentColor.current.copy(alpha = 0.6f),
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 48.dp)
            .semantics(mergeDescendants = true) {},
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides iconTint) {
            icon(Modifier.size(64.dp))
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        if (body != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        val resolvedPrimaryAction: (() -> Unit)? = onAction.takeIf { actionLabel != null }
        val resolvedPrimaryLabel: String? = actionLabel.takeIf { onAction != null }
        val resolvedSecondaryAction: (() -> Unit)? = onSecondaryAction.takeIf { secondaryActionLabel != null }
        val resolvedSecondaryLabel: String? = secondaryActionLabel.takeIf { onSecondaryAction != null }
        val hasPrimary = resolvedPrimaryAction != null && resolvedPrimaryLabel != null
        val hasSecondary = resolvedSecondaryAction != null && resolvedSecondaryLabel != null
        if (hasPrimary || hasSecondary) {
            Spacer(modifier = Modifier.height(24.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (resolvedPrimaryAction != null && resolvedPrimaryLabel != null) {
                    Button(onClick = resolvedPrimaryAction) {
                        Text(text = resolvedPrimaryLabel)
                    }
                }
                if (hasPrimary && hasSecondary) {
                    Spacer(modifier = Modifier.width(12.dp))
                }
                if (resolvedSecondaryAction != null && resolvedSecondaryLabel != null) {
                    OutlinedButton(onClick = resolvedSecondaryAction) {
                        Text(text = resolvedSecondaryLabel)
                    }
                }
            }
        }
    }
}

// ── Previews ──────────────────────────────────────────────────────────────────

@Preview(showBackground = true, backgroundColor = 0xFF141218)
@Composable
private fun EmptyStatePreview() {
    MaterialTheme {
        EmptyState(
            icon = { mod -> Icon(imageVector = Icons.Rounded.SearchOff, contentDescription = null, modifier = mod) },
            title = "Search for something to start listening",
            body = "Type an artist, song, or album name above.",
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF141218)
@Composable
private fun EmptyStateWithActionPreview() {
    MaterialTheme {
        EmptyState(
            icon = { mod -> Icon(imageVector = Icons.Rounded.SearchOff, contentDescription = null, modifier = mod) },
            title = "No playlists yet",
            body = "Create a playlist to organise your music.",
            actionLabel = "Create playlist",
            onAction = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF141218)
@Composable
private fun EmptyStateTwoButtonPreview() {
    MaterialTheme {
        EmptyState(
            icon = { mod -> Icon(imageVector = Icons.Rounded.SearchOff, contentDescription = null, modifier = mod) },
            title = "Nothing here yet",
            body = "Browse the catalog or import an existing playlist to get started.",
            actionLabel = "Browse",
            onAction = {},
            secondaryActionLabel = "Import",
            onSecondaryAction = {},
        )
    }
}
