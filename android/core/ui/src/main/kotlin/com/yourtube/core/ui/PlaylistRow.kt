package com.yourtube.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.QueueMusic
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.yourtube.core.common.model.Playlist
import com.yourtube.core.common.model.Track

private val PlaylistCoverSize: Dp = 56.dp

/**
 * A playlist entry in a library list.
 * Shows a cover image, playlist name, and track count.
 *
 * The [cover] slot lets downstream features (4-up grid, HCT-hash gradient,
 * hybrid empty/single/multi composition) plug in without modifying
 * `PlaylistRow`. The default delegates to [DefaultPlaylistCover], which
 * preserves the original "first thumbnail or QueueMusic placeholder"
 * behavior so existing call sites render identically.
 */
@Composable
fun PlaylistRow(
    playlist: Playlist,
    onClick: () -> Unit,
    onMoreClick: () -> Unit,
    modifier: Modifier = Modifier,
    cover: @Composable (size: Dp) -> Unit = { size -> DefaultPlaylistCover(playlist, size) },
    /**
     * YT-0153 — when non-null, replaces the default trailing `MoreVert` `IconButton`. Lets a
     * caller wrap their own icon button + `DropdownMenu` inside a `Box` so the menu anchors
     * directly under the dot rather than at the row's leading edge. [onMoreClick] is ignored
     * when [trailingContent] is provided.
     */
    trailingContent: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Open playlist ${playlist.name}", onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics { contentDescription = "${playlist.name}, ${playlist.tracks.size} tracks" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        cover(PlaylistCoverSize)

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = playlist.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${playlist.tracks.size} track${if (playlist.tracks.size != 1) "s" else ""}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (trailingContent != null) {
            trailingContent()
        } else {
            IconButton(
                onClick = onMoreClick,
                modifier = Modifier.semantics { contentDescription = "More options for ${playlist.name}" },
            ) {
                Icon(
                    imageVector = Icons.Rounded.MoreVert,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Default cover renderer used by [PlaylistRow]. Shows the first track's
 * thumbnail when available, otherwise a QueueMusic glyph on the surfaceVariant
 * background. Extracted from the previous inline implementation so callers
 * can compose against it (e.g. as a fallback inside a richer cover slot).
 */
@Composable
private fun DefaultPlaylistCover(playlist: Playlist, size: Dp) {
    val firstThumbnail = playlist.tracks.firstOrNull()?.thumbnailUrl.orEmpty()
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (firstThumbnail.isNotEmpty()) {
            AsyncImage(
                model = firstThumbnail,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        } else {
            Icon(
                imageVector = Icons.Rounded.QueueMusic,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
        }
    }
}

// ── Previews ──────────────────────────────────────────────────────────────────

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun PlaylistRowPreview() {
    val sampleTracks = listOf(
        Track("t1", "Track One", "Artist A", 200, ""),
        Track("t2", "Track Two", "Artist B", 300, ""),
    )
    MaterialTheme {
        PlaylistRow(
            playlist = Playlist("p1", "My Chill Playlist", "2026-01-01", "2026-04-01", sampleTracks),
            onClick = {},
            onMoreClick = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun PlaylistRowFourUpCoverPreview() {
    val sampleTracks = listOf(
        Track("t1", "Track One", "Artist A", 200, ""),
        Track("t2", "Track Two", "Artist B", 300, ""),
        Track("t3", "Track Three", "Artist C", 240, ""),
        Track("t4", "Track Four", "Artist D", 180, ""),
    )
    MaterialTheme {
        PlaylistRow(
            playlist = Playlist("p2", "Custom 4-up Cover", "2026-01-01", "2026-04-01", sampleTracks),
            onClick = {},
            onMoreClick = {},
            cover = { size ->
                // 4-up cover stub — two-by-two solid quadrants. Real
                // implementation lands in YT-0014.
                Column(
                    modifier = Modifier
                        .size(size)
                        .clip(RoundedCornerShape(4.dp)),
                ) {
                    Row(modifier = Modifier.weight(1f)) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .background(Color(0xFF7E57C2)),
                        )
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .background(Color(0xFF26A69A)),
                        )
                    }
                    Row(modifier = Modifier.weight(1f)) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .background(Color(0xFFFFB300)),
                        )
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .background(Color(0xFFEF5350)),
                        )
                    }
                }
            },
        )
    }
}
