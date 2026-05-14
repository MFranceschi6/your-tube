package com.yourtube.core.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import com.yourtube.core.common.model.Playlist
import com.yourtube.core.common.model.Track

private val PlaylistCoverSize: Dp = 56.dp

// ── PlaylistCover ─────────────────────────────────────────────────────────────

/**
 * YT-0063a v2 Q2 — deterministic 4-up playlist cover.
 *
 * Layout by track count:
 *  - 0  → single-color tile `surfaceContainerHighest` + centered `QueueMusic` 32dp
 *  - 1  → first thumbnail fills the entire tile
 *  - 2  → vertical split (left = thumb 1, right = thumb 2)
 *  - 3  → T-split (top half = thumb 1, bottom-left = thumb 2, bottom-right = thumb 3)
 *  - 4+ → 2×2 grid (thumbs 1–4)
 *
 * Gutters: 2dp `colorScheme.surface` between cells. The outer `Box` applies
 * `RoundedCornerShape(12.dp)` and `clip`; inner tiles are NOT individually rounded —
 * they inherit the clip from the parent.
 *
 * Each tile uses `SubcomposeAsyncImage` to `Crossfade(tween(180))` from the
 * `surfaceVariant` placeholder to the resolved image; under reduce-motion the
 * crossfade snaps (durationMillis = 0).
 *
 * Accessibility: `contentDescription` is set on the outer `Box` semantics.
 */
@Composable
fun PlaylistCover(
    tracks: List<Track>,
    name: String,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = LocalReduceMotion.current
    val fadeDuration = if (reduceMotion) 0 else 180
    val gutterColor = MaterialTheme.colorScheme.surface
    val placeholderColor = MaterialTheme.colorScheme.surfaceVariant
    val emptyBg = MaterialTheme.colorScheme.surfaceContainerHighest
    val iconTint = MaterialTheme.colorScheme.onSurfaceVariant

    val trackCount = tracks.size
    val accessibilityDescription = if (trackCount == 0) {
        stringResource(R.string.cd_playlist_cover_empty, name)
    } else {
        stringResource(R.string.cd_playlist_cover_filled, name, trackCount.coerceAtMost(4))
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .semantics { contentDescription = accessibilityDescription },
    ) {
        when {
            trackCount == 0 -> {
                // Empty: solid tile with QueueMusic icon
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(emptyBg),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.QueueMusic,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = iconTint,
                    )
                }
            }

            trackCount == 1 -> {
                // Single thumbnail fills
                CoverTile(
                    url = tracks[0].thumbnailUrl,
                    placeholderColor = placeholderColor,
                    fadeDuration = fadeDuration,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            trackCount == 2 -> {
                // Vertical split: left | right with 2dp gutter
                Row(modifier = Modifier.fillMaxSize()) {
                    CoverTile(
                        url = tracks[0].thumbnailUrl,
                        placeholderColor = placeholderColor,
                        fadeDuration = fadeDuration,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                    Box(
                        modifier = Modifier
                            .width(2.dp)
                            .fillMaxHeight()
                            .background(gutterColor),
                    )
                    CoverTile(
                        url = tracks[1].thumbnailUrl,
                        placeholderColor = placeholderColor,
                        fadeDuration = fadeDuration,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                }
            }

            trackCount == 3 -> {
                // T-split: top half = thumb 1, bottom row = thumb 2 (left) + thumb 3 (right)
                Column(modifier = Modifier.fillMaxSize()) {
                    CoverTile(
                        url = tracks[0].thumbnailUrl,
                        placeholderColor = placeholderColor,
                        fadeDuration = fadeDuration,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(gutterColor)
                            .size(height = 2.dp, width = 0.dp),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    ) {
                        CoverTile(
                            url = tracks[1].thumbnailUrl,
                            placeholderColor = placeholderColor,
                            fadeDuration = fadeDuration,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                        )
                        Box(
                            modifier = Modifier
                                .width(2.dp)
                                .fillMaxHeight()
                                .background(gutterColor),
                        )
                        CoverTile(
                            url = tracks[2].thumbnailUrl,
                            placeholderColor = placeholderColor,
                            fadeDuration = fadeDuration,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                        )
                    }
                }
            }

            else -> {
                // 4+ tracks: 2×2 grid (thumbs 1–4)
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    ) {
                        CoverTile(
                            url = tracks[0].thumbnailUrl,
                            placeholderColor = placeholderColor,
                            fadeDuration = fadeDuration,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                        )
                        Box(
                            modifier = Modifier
                                .width(2.dp)
                                .fillMaxHeight()
                                .background(gutterColor),
                        )
                        CoverTile(
                            url = tracks[1].thumbnailUrl,
                            placeholderColor = placeholderColor,
                            fadeDuration = fadeDuration,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(gutterColor)
                            .size(height = 2.dp, width = 0.dp),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    ) {
                        CoverTile(
                            url = tracks[2].thumbnailUrl,
                            placeholderColor = placeholderColor,
                            fadeDuration = fadeDuration,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                        )
                        Box(
                            modifier = Modifier
                                .width(2.dp)
                                .fillMaxHeight()
                                .background(gutterColor),
                        )
                        CoverTile(
                            url = tracks[3].thumbnailUrl,
                            placeholderColor = placeholderColor,
                            fadeDuration = fadeDuration,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                        )
                    }
                }
            }
        }
    }
}

/**
 * A single tile in a [PlaylistCover]. Crossfades from the placeholder color to
 * the resolved image over [fadeDuration] ms. Decorative — no contentDescription.
 */
@Composable
private fun CoverTile(
    url: String,
    placeholderColor: Color,
    fadeDuration: Int,
    modifier: Modifier = Modifier,
) {
    SubcomposeAsyncImage(
        model = url.ifEmpty { null },
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier,
    ) {
        val state = painter.state
        Crossfade(
            targetState = state is AsyncImagePainter.State.Success,
            animationSpec = tween(fadeDuration),
            label = "cover-tile-fade",
        ) { loaded ->
            if (loaded) {
                SubcomposeAsyncImageContent()
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(placeholderColor),
                )
            }
        }
    }
}

/**
 * A playlist entry in a library list.
 * Shows a cover image, playlist name, and track count.
 *
 * The [cover] slot lets downstream features plug in custom renderers without
 * modifying `PlaylistRow`. The default uses [PlaylistCover] which implements
 * the v2 Q2 deterministic 4-up cover with 0/1/2/3/4+ fallbacks.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlaylistRow(
    playlist: Playlist,
    onClick: () -> Unit,
    onMoreClick: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * YT-0063a v2 Q6 — long-press opens the contextual bottom sheet.
     * Defaults to null (no long-press) for back-compat.
     */
    onLongClick: (() -> Unit)? = null,
    cover: @Composable (size: Dp) -> Unit = { size ->
        PlaylistCover(
            tracks = playlist.tracks,
            name = playlist.name,
            modifier = Modifier.size(size),
        )
    },
    /**
     * YT-0153 — when non-null, replaces the default trailing `MoreVert` `IconButton`. Lets a
     * caller wrap their own icon button + `DropdownMenu` inside a `Box` so the menu anchors
     * directly under the dot rather than at the row's leading edge. [onMoreClick] is ignored
     * when [trailingContent] is provided.
     */
    trailingContent: (@Composable () -> Unit)? = null,
) {
    val openLabel = stringResource(R.string.lbl_playlist_row_open, playlist.name)
    val longPressLabel = if (onLongClick != null) {
        stringResource(R.string.lbl_playlist_row_long_press, playlist.name)
    } else null
    val rowContentDescription = stringResource(R.string.cd_playlist_row, playlist.name, playlist.tracks.size)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                onClickLabel = openLabel,
                onLongClickLabel = longPressLabel,
            )
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics { contentDescription = rowContentDescription },
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
                // Plurals resource: "1 track" / "N tracks"
                text = pluralStringResource(
                    R.plurals.lbl_playlist_track_count,
                    playlist.tracks.size,
                    playlist.tracks.size,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (trailingContent != null) {
            trailingContent()
        } else {
            val moreOptionsContentDescription = stringResource(R.string.cd_more_options)
            IconButton(
                onClick = onMoreClick,
                modifier = Modifier.semantics { contentDescription = moreOptionsContentDescription },
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

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F, name = "PlaylistRow — 4-up cover (4 tracks)")
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
            playlist = Playlist("p2", "4-Track Playlist", "2026-01-01", "2026-04-01", sampleTracks),
            onClick = {},
            onMoreClick = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F, name = "PlaylistRow — empty cover")
@Composable
private fun PlaylistRowEmptyCoverPreview() {
    MaterialTheme {
        PlaylistRow(
            playlist = Playlist("p3", "Empty Playlist", "2026-01-01", "2026-04-01", emptyList()),
            onClick = {},
            onMoreClick = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F, name = "PlaylistCover — standalone 240dp")
@Composable
private fun PlaylistCoverLargePreview() {
    val sampleTracks = listOf(
        Track("t1", "Track One", "Artist A", 200, ""),
        Track("t2", "Track Two", "Artist B", 300, ""),
        Track("t3", "Track Three", "Artist C", 240, ""),
    )
    MaterialTheme {
        PlaylistCover(
            tracks = sampleTracks,
            name = "My Playlist",
            modifier = Modifier.size(240.dp),
        )
    }
}
