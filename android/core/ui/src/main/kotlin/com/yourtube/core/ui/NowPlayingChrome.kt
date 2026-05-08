package com.yourtube.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yourtube.core.common.model.QueueItem
import com.yourtube.core.common.model.Track

/**
 * Round 4 — extracted from the now-deleted `NowPlayingScreen.kt`. Renders ONLY the
 * NowPlaying chrome (top bar with collapse button + drag handle, scrubber, transport
 * row, queue button, more menu). NO artwork (rendered ONCE by [PlayerOverlay] above
 * this composable). NO pause-scale `Animatable` (the artwork's visible scale is owned
 * by the overlay's `lerp` from progress).
 *
 * Two alphas drive the visual fade: [chromeAlphaProvider] for the bulk of the screen
 * (top bar, track info, scrubber) and [transportRowAlphaProvider] for the play/pause
 * + skip transport row. Both are pure functions of `progress` computed by the overlay.
 *
 * The drag handle wires `Modifier.draggable` to [onDragDelta] and [onDragStopped] —
 * the overlay state owns the threshold decision and the release animation.
 *
 * YT-0196 — the primary play/pause button swaps for a `CircularProgressIndicator`
 * while [isBuffering] is true. Branch must survive future refactors.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NowPlayingChrome(
    track: Track,
    isPlaying: Boolean,
    isBuffering: Boolean,
    progressFraction: Float,
    onPlayPauseClick: () -> Unit,
    onSkipNextClick: () -> Unit,
    onSkipPreviousClick: () -> Unit,
    onCollapseClick: () -> Unit,
    onSeek: (Float) -> Unit,
    queue: List<QueueItem>,
    currentQueueIndex: Int,
    onRemoveQueueItem: (queueId: String) -> Unit,
    onMoveQueueItem: (Int, Int) -> Unit,
    chromeAlphaProvider: () -> Float,
    transportRowAlphaProvider: () -> Float,
    onDragDelta: (Float) -> Unit,
    onDragStopped: (Float) -> Unit,
    /**
     * Window-relative rect of the artwork slot reserved by this chrome. The
     * overlay reads this rect to position+size the single shared artwork
     * instance — no hardcoded constants. Fired on every layout pass.
     */
    onArtworkSlotPositioned: (Rect) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var showQueueSheet by remember { mutableStateOf(false) }
    val reduceMotion = LocalReduceMotion.current

    val dragState = rememberDraggableState { delta ->
        if (reduceMotion) return@rememberDraggableState
        onDragDelta(delta)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            // Single chrome alpha applied at the root so all NowPlaying surfaces fade
            // together; the transport row gets an additional inner alpha for the
            // staggered fade-in (200..320ms of an expand).
            .graphicsLayer { alpha = chromeAlphaProvider() }
            .semantics { paneTitle = "Now playing" },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState()),
        ) {
            // Top bar — collapse button + drag handle.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(NowPlayingDragHandleTestTag)
                    .draggable(
                        state = dragState,
                        orientation = Orientation.Vertical,
                        onDragStopped = { velocity -> onDragStopped(velocity) },
                    )
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onCollapseClick,
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

            // Reserved space where the artwork visually sits. A square Box that
            // fills the chrome width minus 24dp gutters; reports its window-relative
            // rect via [onArtworkSlotPositioned] so the overlay's ArtworkBox can
            // morph into THIS exact rect at progress=1 with zero hardcoded constants.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .aspectRatio(1f)
                    .onGloballyPositioned { coords ->
                        onArtworkSlotPositioned(coords.boundsInWindow())
                    },
            )

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
                        text = track.title,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = track.channel,
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
                val durationSec = track.durationSec.takeIf { it > 0 } ?: 240
                Slider(
                    value = progressFraction.coerceIn(0f, 1f),
                    onValueChange = { fraction -> onSeek(fraction) },
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

            // Transport controls — separate alpha layer for the staggered fade-in.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .graphicsLayer { alpha = transportRowAlphaProvider() },
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

                // Primary FAB-style play button — YT-0196 buffering branch preserved.
                val playPauseLabel = when {
                    isBuffering -> "Loading"
                    isPlaying -> "Pause"
                    else -> "Play"
                }
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable(
                            enabled = !isBuffering,
                            onClickLabel = playPauseLabel,
                            onClick = onPlayPauseClick,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isBuffering) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(36.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 3.dp,
                        )
                    } else {
                        Icon(
                            imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(36.dp),
                        )
                    }
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

internal fun formatSeconds(totalSec: Int): String {
    val m = totalSec / 60
    val s = totalSec % 60
    return "$m:${s.toString().padStart(2, '0')}"
}
