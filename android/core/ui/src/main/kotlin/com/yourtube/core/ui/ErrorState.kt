package com.yourtube.core.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview

/**
 * Error state with the same shape as [EmptyState].
 * Always shows a retry button. Message is optional.
 *
 * Reimplemented in YT-0060 as a thin wrapper around [EmptyState] with
 * `iconTint = colorScheme.error` so visual structure stays in one place.
 */
@Composable
fun ErrorState(
    title: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    message: String? = null,
) {
    EmptyState(
        icon = { mod -> Icon(imageVector = Icons.Rounded.ErrorOutline, contentDescription = null, modifier = mod) },
        title = title,
        modifier = modifier,
        body = message,
        actionLabel = "Retry",
        onAction = onRetry,
        iconTint = MaterialTheme.colorScheme.error,
    )
}

// ── Previews ──────────────────────────────────────────────────────────────────

@Preview(showBackground = true, backgroundColor = 0xFF141218)
@Composable
private fun ErrorStatePreview() {
    MaterialTheme {
        ErrorState(
            title = "Something went wrong",
            message = "Check your internet connection and try again.",
            onRetry = {},
        )
    }
}
