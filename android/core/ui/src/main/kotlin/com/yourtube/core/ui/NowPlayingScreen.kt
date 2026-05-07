package com.yourtube.core.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.yourtube.core.common.model.QueueItem
import com.yourtube.core.common.model.Track

/**
 * Full-screen now-playing surface. Stateless — all state is passed in.
 *
 * The caller is responsible for overlaying this composable (e.g. inside a `Box`
 * at the root scaffold level) so it covers the bottom nav and MiniPlayer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(
    track: Track?,
    isPlaying: Boolean,
    progressFraction: Float,
    onPlayPauseClick: () -> Unit,
    onSkipNextClick: () -> Unit,
    onSkipPreviousClick: () -> Unit,
    onSeek: (Float) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    queue: List<QueueItem> = emptyList(),
    currentQueueIndex: Int = 0,
    onRemoveQueueItem: (queueId: String) -> Unit = {},
    onMoveQueueItem: (fromIndex: Int, toIndex: Int) -> Unit = { _, _ -> },
    artworkModifier: Modifier = Modifier,
) {
    var showQueueSheet by remember { mutableStateOf(false) }

    // YT-0062a Q3 + Q10: artwork pause-scale driven by a single `Animatable<Float>` keyed to
    // `isPlaying`. Spring spec from the YT-0013 decision-log; targets 0.85 ↔ 1.0 (pause →
    // play) with corner radius interpolating 20.dp → 12.dp in lockstep so the artwork
    // breathes rather than just shrinks. `LocalReduceMotion` swaps the spring for a snap so
    // users with `TRANSITION_ANIMATION_SCALE = 0` (or system "Remove animations") land at
    // the target instantly.
    val reduceMotion = LocalReduceMotion.current
    val artworkScale = remember { Animatable(if (isPlaying) ARTWORK_SCALE_PLAYING else ARTWORK_SCALE_PAUSED) }
    LaunchedEffect(isPlaying, reduceMotion) {
        val target = if (isPlaying) ARTWORK_SCALE_PLAYING else ARTWORK_SCALE_PAUSED
        if (reduceMotion) {
            artworkScale.snapTo(target)
        } else {
            artworkScale.animateTo(
                targetValue = target,
                animationSpec = spring(
                    stiffness = ARTWORK_SPRING_STIFFNESS,
                    dampingRatio = ARTWORK_SPRING_DAMPING_RATIO,
                ),
            )
        }
    }
    // Linear normalization 0.85..1.0 → 0..1, used to lerp the corner radius alongside scale.
    val scaleProgress = ((artworkScale.value - ARTWORK_SCALE_PAUSED) /
        (ARTWORK_SCALE_PLAYING - ARTWORK_SCALE_PAUSED)).coerceIn(0f, 1f)
    val artworkCornerDp = ARTWORK_CORNER_PAUSED_DP +
        (ARTWORK_CORNER_PLAYING_DP - ARTWORK_CORNER_PAUSED_DP) * scaleProgress

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                MaterialTheme.colorScheme.background,
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState()),
        ) {
            // Top bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.semantics { contentDescription = "Collapse player" },
                ) {
                    Icon(
                        imageVector = Icons.Rounded.KeyboardArrowDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(28.dp),
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "Now Playing",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(
                    onClick = {},
                    modifier = Modifier.semantics { contentDescription = "More options" },
                ) {
                    Icon(
                        imageVector = Icons.Rounded.MoreVert,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            // Artwork — `artworkModifier` is the YT-0061 sharedBounds seam, applied to the
            // Box containing the artwork so the morph target matches the MiniPlayer thumbnail.
            // Q3: explicit `transformOrigin = Center` via `graphicsLayer` keeps the breathe
            // animation centered when nested inside the (eventually scrolling) Column.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 40.dp)
                    .aspectRatio(1f)
                    .graphicsLayer {
                        scaleX = artworkScale.value
                        scaleY = artworkScale.value
                        transformOrigin = TransformOrigin.Center
                    }
                    .then(artworkModifier)
                    .clip(RoundedCornerShape(artworkCornerDp.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                if (track != null && track.thumbnailUrl.isNotEmpty()) {
                    AsyncImage(
                        model = track.thumbnailUrl,
                        contentDescription = "Album art for ${track.title}",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.QueueMusic,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f),
                        modifier = Modifier.size(64.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Track info + heart
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track?.title ?: "Nothing playing",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = track?.channel ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(
                    onClick = {},
                    modifier = Modifier.semantics { contentDescription = "Add to favourites" },
                ) {
                    Icon(
                        imageVector = Icons.Rounded.FavoriteBorder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Scrubber
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
            ) {
                val durationSec = track?.durationSec?.takeIf { it > 0 } ?: 240
                Slider(
                    value = progressFraction.coerceIn(0f, 1f),
                    onValueChange = onSeek,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics {
                            contentDescription =
                                "Seek bar, ${formatSeconds((durationSec * progressFraction).toInt())} of ${formatSeconds(durationSec)}"
                        },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = formatSeconds((durationSec * progressFraction).toInt()),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = formatSeconds(durationSec),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Transport controls
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = {},
                    modifier = Modifier.semantics { contentDescription = "Shuffle" },
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Shuffle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                IconButton(
                    onClick = onSkipPreviousClick,
                    modifier = Modifier
                        .size(56.dp)
                        .semantics { contentDescription = "Previous track" },
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SkipPrevious,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(32.dp),
                    )
                }

                // Primary FAB-style play button — 72dp, Material 3 primary color
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable(
                            onClickLabel = if (isPlaying) "Pause" else "Play",
                            onClick = onPlayPauseClick,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = null, // described by clickable label
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(36.dp),
                    )
                }

                IconButton(
                    onClick = onSkipNextClick,
                    modifier = Modifier
                        .size(56.dp)
                        .semantics { contentDescription = "Next track" },
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SkipNext,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(32.dp),
                    )
                }

                IconButton(
                    onClick = { showQueueSheet = true },
                    modifier = Modifier.semantics { contentDescription = "Show queue" },
                ) {
                    Icon(
                        imageVector = Icons.Rounded.QueueMusic,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    if (showQueueSheet) {
        QueuePanel(
            queue = queue,
            currentQueueIndex = currentQueueIndex,
            onDismiss = { showQueueSheet = false },
            onRemove = onRemoveQueueItem,
            onMoveUp = { index -> if (index > 0) onMoveQueueItem(index, index - 1) },
            onMoveDown = { index -> if (index < queue.lastIndex) onMoveQueueItem(index, index + 1) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QueuePanel(
    queue: List<QueueItem>,
    currentQueueIndex: Int,
    onDismiss: () -> Unit,
    onRemove: (queueId: String) -> Unit,
    onMoveUp: (index: Int) -> Unit,
    onMoveDown: (index: Int) -> Unit,
) {
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        if (queue.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) { Text("Queue is empty", style = MaterialTheme.typography.bodyMedium) }
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                itemsIndexed(queue, key = { _, item -> item.queueId }) { index, item ->
                    val isCurrent = index == currentQueueIndex
                    ListItem(
                        headlineContent = {
                            Text(
                                text = item.track.title,
                                color = if (isCurrent) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        supportingContent = {
                            Text(
                                text = item.track.channel,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        trailingContent = {
                            Row {
                                IconButton(
                                    onClick = { onMoveUp(index) },
                                    enabled = index > 0,
                                ) {
                                    Icon(
                                        Icons.Rounded.KeyboardArrowUp,
                                        contentDescription = "Move up",
                                    )
                                }
                                IconButton(
                                    onClick = { onMoveDown(index) },
                                    enabled = index < queue.lastIndex,
                                ) {
                                    Icon(
                                        Icons.Rounded.KeyboardArrowDown,
                                        contentDescription = "Move down",
                                    )
                                }
                                IconButton(onClick = { onRemove(item.queueId) }) {
                                    Icon(
                                        Icons.Rounded.Close,
                                        contentDescription = "Remove from queue",
                                    )
                                }
                            }
                        },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

// YT-0062a Q3 — artwork pause-scale spec from design-system/handoff/YT-0013/decision-log.md.
private const val ARTWORK_SCALE_PLAYING = 1f
private const val ARTWORK_SCALE_PAUSED = 0.85f
private const val ARTWORK_SPRING_STIFFNESS = 380f
private const val ARTWORK_SPRING_DAMPING_RATIO = 0.78f
private const val ARTWORK_CORNER_PLAYING_DP = 12f
private const val ARTWORK_CORNER_PAUSED_DP = 20f

internal fun formatSeconds(totalSec: Int): String {
    val m = totalSec / 60
    val s = totalSec % 60
    return "$m:${s.toString().padStart(2, '0')}"
}

// ── Previews ──────────────────────────────────────────────────────────────────

@Preview(showBackground = true, backgroundColor = 0xFF141218)
@Composable
private fun NowPlayingPlayingPreview() {
    MaterialTheme {
        NowPlayingScreen(
            track = Track("1", "lofi hip hop radio – beats to relax/study to", "Lofi Girl", 3612, ""),
            isPlaying = true,
            progressFraction = 0.35f,
            onPlayPauseClick = {},
            onSkipNextClick = {},
            onSkipPreviousClick = {},
            onSeek = {},
            onDismiss = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF141218)
@Composable
private fun NowPlayingNoTrackPreview() {
    MaterialTheme {
        NowPlayingScreen(
            track = null,
            isPlaying = false,
            progressFraction = 0f,
            onPlayPauseClick = {},
            onSkipNextClick = {},
            onSkipPreviousClick = {},
            onSeek = {},
            onDismiss = {},
        )
    }
}
