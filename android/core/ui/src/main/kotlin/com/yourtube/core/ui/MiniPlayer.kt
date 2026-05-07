package com.yourtube.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.yourtube.core.common.model.Track

/**
 * Persistent mini-player docked above the navigation bar.
 * Shows thumbnail, title, channel, play/pause and skip-next controls,
 * plus a thin progress indicator at the bottom.
 *
 * Returns [Unit] but renders nothing when [track] is null.
 * Stateless — callers hoist all state.
 */
@Composable
fun MiniPlayer(
    track: Track?,
    isPlaying: Boolean,
    progressFraction: Float,
    onPlayPauseClick: () -> Unit,
    onSkipNextClick: () -> Unit,
    onExpandClick: () -> Unit,
    modifier: Modifier = Modifier,
    isBuffering: Boolean = false,
    artworkModifier: Modifier = Modifier,
) {
    if (track == null) return

    val playPauseLabel = if (isPlaying) "Pause ${track.title}" else "Play ${track.title}"

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 0.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .semantics { contentDescription = "Expand player" }
                .clickable(onClickLabel = "Expand player", onClick = onExpandClick),
        ) {
            // Content row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, top = 12.dp, bottom = 18.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Thumbnail — artworkModifier reserved as a clean seam for
                // future Modifier.sharedBounds wiring (YT-0061), so callers
                // can opt in without MiniPlayer knowing the SharedTransitionScope.
                Box(
                    modifier = artworkModifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (track.thumbnailUrl.isNotEmpty()) {
                        AsyncImage(
                            model = track.thumbnailUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.matchParentSize(),
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = track.channel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                // Play / Pause — 44dp to meet 48dp via padding from IconButton.
                // While buffering, swap the glyph for a 20 dp progress spinner
                // (Media3 STATE_BUFFERING parity). The IconButton itself stays
                // mounted so the tap target and semantics are preserved.
                IconButton(
                    onClick = onPlayPauseClick,
                    modifier = Modifier.semantics { contentDescription = playPauseLabel },
                ) {
                    if (isBuffering) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = MaterialTheme.colorScheme.onSurface,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }

                IconButton(
                    onClick = onSkipNextClick,
                    modifier = Modifier.semantics { contentDescription = "Skip to next track" },
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SkipNext,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            // Progress bar pinned to bottom of the container
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction = progressFraction.coerceIn(0f, 1f))
                        .height(3.dp)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))
    }
}

// ── Previews ──────────────────────────────────────────────────────────────────

@Preview(showBackground = true, backgroundColor = 0xFF141218)
@Composable
private fun MiniPlayerPlayingPreview() {
    MaterialTheme {
        MiniPlayer(
            track = Track("1", "lofi hip hop radio – beats to relax/study to", "Lofi Girl", 3612, ""),
            isPlaying = true,
            progressFraction = 0.4f,
            onPlayPauseClick = {},
            onSkipNextClick = {},
            onExpandClick = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF141218)
@Composable
private fun MiniPlayerPausedPreview() {
    MaterialTheme {
        MiniPlayer(
            track = Track("2", "Chill Lofi Mix – Deep Focus", "ChillHop Music", 3612, ""),
            isPlaying = false,
            progressFraction = 0.1f,
            onPlayPauseClick = {},
            onSkipNextClick = {},
            onExpandClick = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF141218)
@Composable
private fun MiniPlayerNullPreview() {
    MaterialTheme {
        MiniPlayer(
            track = null,
            isPlaying = false,
            progressFraction = 0f,
            onPlayPauseClick = {},
            onSkipNextClick = {},
            onExpandClick = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF141218)
@Composable
private fun MiniPlayerBufferingPreview() {
    MaterialTheme {
        MiniPlayer(
            track = Track("3", "Buffering Track Title", "Loading Channel", 240, ""),
            isPlaying = false,
            progressFraction = 0f,
            onPlayPauseClick = {},
            onSkipNextClick = {},
            onExpandClick = {},
            isBuffering = true,
        )
    }
}
