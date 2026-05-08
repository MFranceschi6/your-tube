package com.yourtube.core.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview

/**
 * Error state per the cross-platform state catalog
 * (`design-system/handoff/state-catalog/error.md`).
 *
 * Visual: same shape as [EmptyState]. Icon at 56dp tinted
 * `colorScheme.onSurfaceVariant` (the catalog explicitly forbids red — red is
 * reserved for destructive surfaces).
 *
 * Action label is always **"Try again"** (catalog binding) and re-runs the
 * exact request that produced the error. The optional secondary action exists
 * only for Search C5 ("offline") which surfaces "Go to Library".
 *
 * Accessibility: the container is wrapped in a polite live region so screen
 * readers auto-announce the error when it appears mid-screen. [assertive]
 * promotes that to assertive — used only for initial-load failures in a
 * foreground tab (catalog accessibility rules).
 */
@Composable
fun ErrorState(
    title: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    body: String? = null,
    icon: ImageVector = Icons.Rounded.ErrorOutline,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
    assertive: Boolean = false,
    scrollable: Boolean = false,
) {
    val regionMode = if (assertive) LiveRegionMode.Assertive else LiveRegionMode.Polite
    EmptyState(
        icon = { mod -> Icon(imageVector = icon, contentDescription = null, modifier = mod) },
        title = title,
        modifier = modifier
            .fillMaxWidth()
            .semantics { liveRegion = regionMode },
        body = body,
        actionLabel = "Try again",
        onAction = onRetry,
        iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
        secondaryActionLabel = secondaryActionLabel,
        onSecondaryAction = onSecondaryAction,
        primaryButtonStyle = EmptyStatePrimaryStyle.FILLED,
        scrollable = scrollable,
    )
}

// ── Backwards-compatible overload ─────────────────────────────────────────────
//
// Existing callers passed `message: String?`. The catalog renames the parameter
// to `body`, but keep the legacy overload until every caller has been migrated
// off the `message` keyword arg.
@Deprecated(
    message = "Use the ErrorState overload with a `body` parameter and an optional `icon`.",
    replaceWith = ReplaceWith("ErrorState(title = title, onRetry = onRetry, modifier = modifier, body = message)"),
)
@Composable
fun ErrorState(
    title: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    message: String?,
) {
    ErrorState(
        title = title,
        onRetry = onRetry,
        modifier = modifier,
        body = message,
    )
}

// ── Previews ──────────────────────────────────────────────────────────────────
//
// Catalog cells covered: C4 (Search generic), C5 (Search offline w/ "Go to
// Library"), C8 (Library), C11 (Playlist detail), C14 (History). Each preview
// provided light + dark.

@Preview(showBackground = true, name = "C4 Search generic error (light)")
@Preview(showBackground = true, name = "C4 Search generic error (dark)", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ErrorStateC4Preview() {
    MaterialTheme {
        ErrorState(
            title = "Couldn't search",
            body = "Something went wrong on our end. Try again in a moment.",
            onRetry = {},
        )
    }
}

@Preview(showBackground = true, name = "C5 Search offline (light)")
@Preview(showBackground = true, name = "C5 Search offline (dark)", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ErrorStateC5Preview() {
    MaterialTheme {
        ErrorState(
            title = "You're offline",
            body = "Connect to the internet to search. Your saved playlists are still available in Library.",
            icon = Icons.Rounded.WifiOff,
            onRetry = {},
            secondaryActionLabel = "Go to Library",
            onSecondaryAction = {},
        )
    }
}

@Preview(showBackground = true, name = "C8 Library error (light)")
@Preview(showBackground = true, name = "C8 Library error (dark)", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ErrorStateC8Preview() {
    MaterialTheme {
        ErrorState(
            title = "Couldn't load your library",
            body = "Check your connection and try again.",
            onRetry = {},
            assertive = true,
        )
    }
}

@Preview(showBackground = true, name = "C11 Playlist detail error (light)")
@Preview(showBackground = true, name = "C11 Playlist detail error (dark)", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ErrorStateC11Preview() {
    MaterialTheme {
        ErrorState(
            title = "Couldn't load this playlist",
            body = "Check your connection and try again.",
            onRetry = {},
        )
    }
}

@Preview(showBackground = true, name = "C14 History error (light)")
@Preview(showBackground = true, name = "C14 History error (dark)", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ErrorStateC14Preview() {
    MaterialTheme {
        ErrorState(
            title = "Couldn't load history",
            body = "Try again in a moment.",
            onRetry = {},
        )
    }
}
