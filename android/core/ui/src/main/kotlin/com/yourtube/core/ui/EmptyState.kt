package com.yourtube.core.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * Primary-button visual style for [EmptyState].
 *
 * Per the cross-platform state catalog (`design-system/handoff/state-catalog/empty.md`
 * + `error.md`), empty-state CTAs use a `FilledTonalButton` and error-state retries
 * use a filled `Button`. The component uses [TONAL] by default; [ErrorState]
 * explicitly opts into [FILLED].
 */
enum class EmptyStatePrimaryStyle { FILLED, TONAL }

/**
 * Generic empty / error scaffold per the cross-platform state catalog.
 *
 * Layout (catalog `empty.md` + `error.md`):
 * - 56dp icon, color `colorScheme.onSurfaceVariant` (catalog `--color-fg-tertiary`).
 *   The icon is decorative — the title carries the announcement.
 * - 16dp gap → title (`titleMedium`, `heading()` semantics).
 * - 8dp gap → body (`bodyMedium`, max-width 280dp).
 * - 24dp gap → primary action (52dp height, ≥ 48dp hit target).
 * - 12dp gap → optional secondary action (text button).
 *
 * The whole layout scrolls (`verticalScroll`) so 200% font-scale never clips.
 */
@Composable
fun EmptyState(
    icon: @Composable (Modifier) -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
    primaryButtonStyle: EmptyStatePrimaryStyle = EmptyStatePrimaryStyle.TONAL,
    // Internal `verticalScroll` would crash with `IllegalStateException: infinity
    // maximum height constraints` whenever this composable lives inside a parent
    // that already supplies unbounded vertical constraints (a `LazyColumn item`,
    // or a `SharedTransitionLayout` Lookahead pass). Opt-in via this flag only on
    // screens where this is the sole body and the parent constraints are bounded.
    scrollable: Boolean = false,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .let { m -> if (scrollable) m.verticalScroll(rememberScrollState()) else m }
            .padding(horizontal = 32.dp, vertical = 48.dp)
            .semantics(mergeDescendants = true) {},
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides iconTint) {
            icon(Modifier.size(56.dp))
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .widthIn(max = 280.dp)
                .semantics { heading() },
        )
        if (body != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 280.dp),
            )
        }
        val resolvedPrimaryAction: (() -> Unit)? = onAction.takeIf { actionLabel != null }
        val resolvedPrimaryLabel: String? = actionLabel.takeIf { onAction != null }
        val resolvedSecondaryAction: (() -> Unit)? = onSecondaryAction.takeIf { secondaryActionLabel != null }
        val resolvedSecondaryLabel: String? = secondaryActionLabel.takeIf { onSecondaryAction != null }
        val hasPrimary = resolvedPrimaryAction != null && resolvedPrimaryLabel != null
        val hasSecondary = resolvedSecondaryAction != null && resolvedSecondaryLabel != null
        if (hasPrimary && resolvedPrimaryAction != null && resolvedPrimaryLabel != null) {
            Spacer(modifier = Modifier.height(24.dp))
            val buttonModifier = Modifier
                .height(52.dp)
                .defaultMinSize(minHeight = 48.dp)
            when (primaryButtonStyle) {
                EmptyStatePrimaryStyle.FILLED -> Button(
                    onClick = resolvedPrimaryAction,
                    modifier = buttonModifier,
                ) { Text(text = resolvedPrimaryLabel) }
                EmptyStatePrimaryStyle.TONAL -> FilledTonalButton(
                    onClick = resolvedPrimaryAction,
                    modifier = buttonModifier,
                ) { Text(text = resolvedPrimaryLabel) }
            }
        }
        if (hasSecondary && resolvedSecondaryAction != null && resolvedSecondaryLabel != null) {
            Spacer(modifier = Modifier.height(12.dp))
            TextButton(
                onClick = resolvedSecondaryAction,
                modifier = Modifier.defaultMinSize(minHeight = 48.dp),
            ) { Text(text = resolvedSecondaryLabel) }
        }
    }
}

// ── Previews ──────────────────────────────────────────────────────────────────
//
// Catalog cells covered: C2 (Search idle, no action), C3 (Search no results,
// "Clear search"), C7 (Library "Create playlist"), C10 (PlaylistDetail "Find
// tracks"), C13 (History "Browse search"). Each preview provided light + dark.

@Preview(showBackground = true, name = "C2 Search idle (light)")
@Preview(showBackground = true, name = "C2 Search idle (dark)", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun EmptyStateC2Preview() {
    MaterialTheme {
        EmptyState(
            icon = { mod -> Icon(Icons.Rounded.Search, contentDescription = null, modifier = mod) },
            title = "Search YourTube",
            body = "Find tracks, channels, and topics from your subscriptions.",
        )
    }
}

@Preview(showBackground = true, name = "C3 Search no results (light)")
@Preview(showBackground = true, name = "C3 Search no results (dark)", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun EmptyStateC3Preview() {
    MaterialTheme {
        EmptyState(
            icon = { mod -> Icon(Icons.Rounded.SearchOff, contentDescription = null, modifier = mod) },
            title = "No results for \"lofi xyz\"",
            body = "Check your spelling or try a different search.",
            actionLabel = "Clear search",
            onAction = {},
        )
    }
}

@Preview(showBackground = true, name = "C7 Library empty (light)")
@Preview(showBackground = true, name = "C7 Library empty (dark)", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun EmptyStateC7Preview() {
    MaterialTheme {
        EmptyState(
            icon = { mod -> Icon(Icons.Rounded.LibraryMusic, contentDescription = null, modifier = mod) },
            title = "No playlists yet",
            body = "Create one to organize tracks for offline listening.",
            actionLabel = "Create playlist",
            onAction = {},
        )
    }
}

@Preview(showBackground = true, name = "C10 Playlist detail empty (light)")
@Preview(showBackground = true, name = "C10 Playlist detail empty (dark)", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun EmptyStateC10Preview() {
    MaterialTheme {
        EmptyState(
            icon = { mod -> Icon(Icons.Rounded.MusicNote, contentDescription = null, modifier = mod) },
            title = "This playlist is empty",
            body = "Add tracks from search or your history.",
            actionLabel = "Find tracks",
            onAction = {},
        )
    }
}

@Preview(showBackground = true, name = "C13 History empty (light)")
@Preview(showBackground = true, name = "C13 History empty (dark)", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun EmptyStateC13Preview() {
    MaterialTheme {
        EmptyState(
            icon = { mod -> Icon(Icons.Rounded.History, contentDescription = null, modifier = mod) },
            title = "Nothing played yet",
            body = "Tracks you play will show up here.",
            actionLabel = "Browse search",
            onAction = {},
        )
    }
}
