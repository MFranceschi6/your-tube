package com.yourtube.core.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.yourtube.core.common.model.Track

/**
 * A single track row: thumbnail, title, channel, duration, and overflow menu.
 * Stateless — callers own all interaction.
 *
 * Slot-based parameters (added in YT-0060) keep the component back-compatible
 * while giving downstream features (queue edit mode, multi-select, downloads)
 * room to customise without forking:
 *  - [isSelected] flips the row into selection-mode visuals (no behavior change yet,
 *    but the semantics flag is set so screen readers see the selection state).
 *  - [showEqIndicator] decouples the equaliser indicator from playback state so
 *    a paused-but-active queue row can still show the active marker; defaults to
 *    [isPlaying] for back-compat.
 *  - [onLongClick] enables long-press context menus.
 *  - [leadingContent] / [trailingContent] override the default thumbnail-leading
 *    and overflow-menu-trailing slots respectively. When [trailingContent] is
 *    non-null it fully replaces the default `MoreVert` button.
 *  - When both [trailingContent] is null and [onMoreClick] is null, no trailing
 *    icon is rendered.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackRow(
    track: Track,
    isPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
    showEqIndicator: Boolean = isPlaying,
    onLongClick: (() -> Unit)? = null,
    onMoreClick: (() -> Unit)? = null,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    val rowDescription = if (showEqIndicator) {
        "Now playing: ${track.title} by ${track.channel}"
    } else {
        "${track.title} by ${track.channel}"
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                // Active-row tint is intentionally unchanged in YT-0060; the
                // MiniPlayer surface-token swap is a separate concern.
                if (isPlaying) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.13f)
                else Color.Transparent,
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                onClickLabel = "Play ${track.title}",
                onLongClickLabel = if (onLongClick != null) "More options for ${track.title}" else null,
            )
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics {
                contentDescription = rowDescription
                selected = isSelected
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingContent != null) {
            leadingContent()
            Spacer(modifier = Modifier.width(16.dp))
        }

        // Thumbnail — 56dp square, 4dp corner radius per mockup
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (track.thumbnailUrl.isNotEmpty()) {
                AsyncImage(
                    model = track.thumbnailUrl,
                    contentDescription = null, // decorative; row carries full contentDescription
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize(),
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
            }
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (showEqIndicator) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = buildTrackMeta(track),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Trailing slot precedence: explicit slot > default overflow icon.
        // When neither is provided, render nothing — keeps callers that
        // do not need a trailing affordance free of dead space.
        when {
            trailingContent != null -> trailingContent()
            onMoreClick != null -> {
                IconButton(
                    onClick = onMoreClick,
                    modifier = Modifier.semantics {
                        contentDescription = "More options for ${track.title}"
                    },
                ) {
                    Icon(
                        imageVector = Icons.Rounded.MoreVert,
                        contentDescription = null, // described by semantics above
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

internal fun buildTrackMeta(track: Track): String {
    if (track.durationSec <= 0) return track.channel
    val m = track.durationSec / 60
    val s = track.durationSec % 60
    return "${track.channel} · $m:${s.toString().padStart(2, '0')}"
}

// ── Previews ──────────────────────────────────────────────────────────────────

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun TrackRowIdlePreview() {
    MaterialTheme {
        TrackRow(
            track = Track("1", "lofi hip hop radio – beats to relax/study to", "Lofi Girl", 3612, ""),
            isPlaying = false,
            onClick = {},
            onMoreClick = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun TrackRowPlayingPreview() {
    MaterialTheme {
        TrackRow(
            track = Track("2", "Chill Lofi Mix – Deep Focus", "ChillHop Music", 3612, ""),
            isPlaying = true,
            onClick = {},
            onMoreClick = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun TrackRowEditModePreview() {
    MaterialTheme {
        TrackRow(
            track = Track("3", "Selected Track in Edit Mode", "Some Channel", 245, ""),
            isPlaying = false,
            onClick = {},
            isSelected = true,
            leadingContent = {
                Icon(
                    imageVector = Icons.Rounded.DragHandle,
                    contentDescription = "Drag to reorder",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            trailingContent = {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
            },
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun TrackRowNoTrailingPreview() {
    MaterialTheme {
        TrackRow(
            track = Track("4", "Track Without Trailing Icon", "Channel", 200, ""),
            isPlaying = false,
            onClick = {},
            // onMoreClick = null and trailingContent = null → no trailing icon
        )
    }
}
