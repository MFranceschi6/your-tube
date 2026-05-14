package com.yourtube.core.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cast // intentional: legacy chrome icon, state-driven weight not applicable
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.FavoriteBorder // intentional: legacy chrome icon, state-driven weight not applicable
import androidx.compose.material.icons.rounded.KeyboardArrowDown // intentional: legacy chrome icon, state-driven weight not applicable
import androidx.compose.material.icons.rounded.MoreVert // intentional: legacy chrome icon, state-driven weight not applicable
import androidx.compose.material.icons.rounded.Speed // intentional: legacy chrome icon — YT-0280 speed button fallback
import androidx.compose.material.icons.rounded.Bedtime // intentional: legacy chrome icon — YT-0282 sleep-timer fallback
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.yourtube.core.common.model.QueueItem
import com.yourtube.core.common.model.SleepTimerPreset
import com.yourtube.core.common.model.SleepTimerState
import com.yourtube.core.common.model.Track
import com.yourtube.core.designsystem.IconKey
import com.yourtube.core.designsystem.Icon as MaterialSymbolIcon
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * Round 4 — extracted from the now-deleted `NowPlayingScreen.kt`. Renders ONLY the
 * NowPlaying chrome (top bar with collapse button + drag handle, scrubber, transport
 * row, action row, speed sheet). NO artwork (rendered ONCE by [PlayerOverlay] above
 * this composable). NO pause-scale `Animatable` (the artwork's visible scale is owned
 * by the overlay's `lerp` from progress).
 *
 * YT-0062a Q5–Q10: hand-rolled scrubber (Canvas + draggable), FilledIconButton play-pause,
 * transport row resized per spec, queue sheet with drag-to-reorder + swipe-to-dismiss,
 * action row (cast placeholder, queue, share, playlist_add), haptics throughout,
 * reduce-motion gates on all animations.
 *
 * Two alphas drive the visual fade: [chromeAlphaProvider] for the bulk of the screen
 * (top bar, track info, scrubber) and [transportRowAlphaProvider] for the play/pause
 * + skip transport row. Both are pure functions of `progress` computed by the overlay.
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
    shuffleOn: Boolean,
    repeatMode: Int,
    onPlayPauseClick: () -> Unit,
    onSkipNextClick: () -> Unit,
    onSkipPreviousClick: () -> Unit,
    onCollapseClick: () -> Unit,
    /**
     * Called on scrubber drag-stop only; callers must translate fraction → ms.
     * `onSeek(fraction)` — fraction in [0f, 1f].
     */
    onSeek: (Float) -> Unit,
    onShuffleModeChange: (Boolean) -> Unit,
    onRepeatModeChange: (Int) -> Unit,
    onShareTrack: () -> Unit,
    onAddToPlaylist: () -> Unit,
    queue: List<QueueItem>,
    currentQueueIndex: Int,
    onRemoveQueueItem: (queueId: String) -> Unit,
    onMoveQueueItem: (Int, Int) -> Unit,
    onJumpToQueueItem: (Int) -> Unit = {},
    onPlayNextFromQueue: (queueId: String) -> Unit = {},
    onAddToQueueFromQueue: (queueId: String) -> Unit = {},
    chromeAlphaProvider: () -> Float,
    transportRowAlphaProvider: () -> Float,
    onDragDelta: (Float) -> Unit,
    onDragStopped: (Float) -> Unit,
    /**
     * Window-relative rect of the artwork slot reserved by this chrome. The
     * overlay reads this rect to position+size the single shared artwork
     * instance — no hardcoded constants. Fired on every layout pass.
     */
    playbackSpeed: Float = 1.0f,
    onSpeedChange: (Float) -> Unit = {},
    onArtworkSlotPositioned: (Rect) -> Unit = {},
    /**
     * Duration of the currently playing track in milliseconds — passed from the player
     * state so the scrubber can compute absolute seek positions. Falls back to
     * [track.durationSec] when not yet available.
     */
    durationMs: Long = 0L,
    /**
     * Current playback position in milliseconds — used to initialise the scrubber
     * preview value before a drag starts.
     */
    positionMs: Long = 0L,
    /** YT-0093 — current sleep-timer state; drives the sheet's countdown display. */
    sleepTimerState: SleepTimerState = SleepTimerState.Inactive,
    /** YT-0093 — called when the user selects a sleep-timer preset. */
    onSetSleepTimer: (SleepTimerPreset) -> Unit = {},
    /** YT-0093 — called when the user taps "Cancel timer". */
    onCancelSleepTimer: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var showQueueSheet by remember { mutableStateOf(false) }
    var showSpeedSheet by remember { mutableStateOf(false) }
    var showSleepTimerSheet by remember { mutableStateOf(false) }
    val reduceMotion = LocalReduceMotion.current
    val haptics = LocalHapticFeedback.current

    // ── String resources hoisted into composable scope ─────────────────────────
    // semantics {}, combinedClickable, and other non-composable lambdas cannot
    // call stringResource() directly — values must be read in composable context
    // first and then captured by the lambdas.
    val paneTitleStr = stringResource(R.string.cd_now_playing_pane_title)
    val collapseStr = stringResource(R.string.cd_now_playing_collapse)
    val nowPlayingHeaderStr = stringResource(R.string.lbl_now_playing_header)
    val previousTrackStr = stringResource(R.string.cd_now_playing_previous)
    val nextTrackStr = stringResource(R.string.cd_now_playing_next)
    val favouritesStr = stringResource(R.string.cd_now_playing_favourites)
    val castUnavailableStr = stringResource(R.string.cd_now_playing_cast_unavailable)
    val showQueueStr = stringResource(R.string.cd_now_playing_show_queue)
    val shareStr = stringResource(R.string.cd_now_playing_share)
    val addToPlaylistStr = stringResource(R.string.cd_now_playing_add_to_playlist)
    val seekForwardStr = stringResource(R.string.lbl_scrubber_seek_forward)
    val seekBackwardStr = stringResource(R.string.lbl_scrubber_seek_backward)
    val normalSpeedLabel = stringResource(R.string.lbl_speed_normal)
    val sleepTimerStr = stringResource(R.string.cd_now_playing_sleep_timer)

    val dragState = rememberDraggableState { delta ->
        if (reduceMotion) return@rememberDraggableState
        onDragDelta(delta)
    }

    // YT-0152 — vertical drag-to-dismiss covers the upper "header" region of the
    // NowPlaying surface: top bar, artwork slot, and track-info row. Below this
    // region the scrubber, transport row, and action row MUST stay tap-only so
    // a horizontal scrub or play-tap is never stolen by the dismiss gesture, and
    // the LazyColumn inside the queue sheet keeps its own scroll. We attach the
    // same `Modifier.draggable` (sharing one `dragState` + one onDragStopped
    // callback) to each header child rather than to the root Column to preserve
    // those carve-outs. Threshold and velocity decisions live in
    // PlayerOverlayState (30% distance / 800 dp/s) — see MotionSpec.
    val headerDragModifier = Modifier.draggable(
        state = dragState,
        orientation = Orientation.Vertical,
        onDragStopped = { velocity -> onDragStopped(velocity) },
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            // Single chrome alpha applied at the root so all NowPlaying surfaces fade
            // together; the transport row gets an additional inner alpha for the
            // staggered fade-in (200..320ms of an expand).
            .graphicsLayer { alpha = chromeAlphaProvider() }
            .semantics { paneTitle = paneTitleStr },
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
                    .then(headerDragModifier)
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onCollapseClick,
                    modifier = Modifier.semantics { contentDescription = collapseStr },
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
                        text = nowPlayingHeaderStr,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val speedLabel = stringResource(R.string.cd_now_playing_playback_speed, formatSpeed(playbackSpeed, normalSpeedLabel))
                IconButton(
                    onClick = { showSpeedSheet = true },
                    modifier = Modifier.semantics {
                        contentDescription = speedLabel
                    },
                ) {
                    // YT-0280: Icons.Rounded.Speed (legacy extended pack) used as fallback
                    // because IconKey.Speed (U+E9E4) is not present in the shipped font subset.
                    Icon(
                        imageVector = Icons.Rounded.Speed,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            // Reserved space where the artwork visually sits. A square Box that
            // fills the chrome width minus 24dp gutters; reports its window-relative
            // rect via [onArtworkSlotPositioned] so the overlay's ArtworkBox can
            // morph into THIS exact rect at progress=1 with zero hardcoded constants.
            // Carries the dismiss drag — `draggable` on this Box never fights with
            // taps because nothing inside it is interactive (the artwork is rendered
            // by the overlay above this chrome).
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .aspectRatio(1f)
                    .then(headerDragModifier)
                    .onGloballyPositioned { coords ->
                        onArtworkSlotPositioned(coords.boundsInWindow())
                    },
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Track info + heart. The Row itself takes the drag, but the
            // FavoriteBorder IconButton inside still receives taps because
            // `draggable` only intercepts gestures that begin as a drag — a
            // direct ACTION_DOWN/UP on the IconButton hit-tests through.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(headerDragModifier)
                    .padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        minLines = 2,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = track.channel,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(
                    onClick = {},
                    modifier = Modifier.semantics { contentDescription = favouritesStr },
                ) {
                    Icon(
                        imageVector = Icons.Rounded.FavoriteBorder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ── Q5 Scrubber ────────────────────────────────────────────────────────────
            // Hand-rolled Canvas scrubber per YT-0013 compose-spec §Q4.
            // Replaces the Material3 Slider with a draggable Canvas that:
            //  - Expands track 4dp→8dp and thumb 16dp→22dp on drag (tween 180ms / snap).
            //  - Commits seek ONLY on onDragStopped — never in onDrag.
            //  - Emits a SegmentTick haptic every 10 seconds of preview position.
            //  - Exposes CustomAccessibilityAction "seek ±10 s" for TalkBack users.
            val effectiveDurationMs = durationMs.takeIf { it > 0L }
                ?: (track.durationSec.toLong() * 1000L).takeIf { it > 0L }
                ?: (240L * 1000L)
            NowPlayingScrubber(
                positionMs = positionMs,
                durationMs = effectiveDurationMs,
                onSeek = { seekMs ->
                    onSeek(seekMs.toFloat() / effectiveDurationMs.toFloat())
                },
                reduceMotion = reduceMotion,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
            )

            Spacer(modifier = Modifier.height(16.dp))

            // ── Q6 Transport row ───────────────────────────────────────────────────────
            // FilledIconButton play-pause (72dp); sizing per spec:
            // shuffle(24sp/48dp) | skip_prev(32sp/56dp) | play_pause(72dp filled) |
            // skip_next(32sp/56dp) | repeat(24sp/48dp).
            // Queue button moved to Q8 action row.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
                    .graphicsLayer { alpha = transportRowAlphaProvider() },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Shuffle — wired to PlayerController.setShuffleMode via ViewModel.
                // Q9 haptics: Confirm on enable, SegmentTick on disable.
                val shuffleContentDescription = if (shuffleOn) {
                    stringResource(R.string.cd_shuffle_on)
                } else {
                    stringResource(R.string.cd_shuffle_off)
                }
                IconButton(
                    onClick = {
                        if (shuffleOn) {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        } else {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                        onShuffleModeChange(!shuffleOn)
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .semantics { contentDescription = shuffleContentDescription },
                ) {
                    MaterialSymbolIcon(
                        icon = IconKey.Shuffle,
                        weight = if (shuffleOn) 500 else 400,
                        contentDescription = null,
                        tint = if (shuffleOn) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(24.dp),
                    )
                }

                // Skip previous — 32sp glyph, 56dp hit area.
                IconButton(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onSkipPreviousClick()
                    },
                    modifier = Modifier
                        .size(56.dp)
                        .semantics { contentDescription = previousTrackStr },
                ) {
                    MaterialSymbolIcon(
                        icon = IconKey.SkipPrev,
                        weight = 500,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(32.dp),
                        size = 32.sp,
                    )
                }

                // Primary play/pause — FilledIconButton 72dp per spec.
                // YT-0196: shows spinner while isBuffering.
                // Q6: containerColor = onSurface, contentColor = surface.
                // Q9: Confirm haptic for play, TextHandleMove for pause.
                val playPauseLabel = when {
                    isBuffering -> stringResource(R.string.cd_now_playing_play_pause_loading)
                    isPlaying -> stringResource(R.string.cd_now_playing_play_pause_pause)
                    else -> stringResource(R.string.cd_now_playing_play_pause_play, track.title, track.channel)
                }
                if (isBuffering) {
                    Box(
                        modifier = Modifier.size(72.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(36.dp),
                            color = MaterialTheme.colorScheme.onSurface,
                            strokeWidth = 3.dp,
                        )
                    }
                } else {
                    FilledIconButton(
                        onClick = {
                            if (isPlaying) {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            } else {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                            onPlayPauseClick()
                        },
                        modifier = Modifier
                            .size(72.dp)
                            .semantics { contentDescription = playPauseLabel },
                        shape = CircleShape,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.onSurface,
                            contentColor = MaterialTheme.colorScheme.surface,
                        ),
                    ) {
                        // Q6 / Q10: Crossfade on play↔pause icon swap, gated on reduce-motion.
                        Crossfade(
                            targetState = isPlaying,
                            animationSpec = if (reduceMotion) snap() else tween(140),
                            label = "play-pause",
                        ) { playing ->
                            MaterialSymbolIcon(
                                icon = if (playing) IconKey.Pause else IconKey.Play,
                                filled = true,
                                weight = 600,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(36.dp)
                                    .then(
                                        if (playing) {
                                            Modifier
                                        } else {
                                            Modifier.offset(x = (-2).dp, y = (-1).dp)
                                        },
                                    ),
                                size = 36.sp,
                            )
                        }
                    }
                }

                // Skip next — 32sp glyph, 56dp hit area.
                IconButton(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onSkipNextClick()
                    },
                    modifier = Modifier
                        .size(56.dp)
                        .semantics { contentDescription = nextTrackStr },
                ) {
                    MaterialSymbolIcon(
                        icon = IconKey.SkipNext,
                        weight = 500,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(32.dp),
                        size = 32.sp,
                    )
                }

                // Repeat — cycles OFF → ONE → ALL.
                // Q9: SegmentTick on every repeat mode change.
                val nextRepeat = nextRepeatMode(repeatMode)
                val repeatLabel = when (repeatMode) {
                    1 -> stringResource(R.string.cd_repeat_one)
                    2 -> stringResource(R.string.cd_repeat_all)
                    else -> stringResource(R.string.cd_repeat_off)
                }
                IconButton(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onRepeatModeChange(nextRepeat)
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .semantics { contentDescription = repeatLabel },
                ) {
                    MaterialSymbolIcon(
                        icon = if (repeatMode == 1) IconKey.RepeatOne else IconKey.Repeat,
                        weight = if (repeatMode != 0) 500 else 400,
                        contentDescription = null,
                        tint = if (repeatMode != 0) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(24.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ── Q8 Action row ──────────────────────────────────────────────────────────
            // Row(Arrangement.SpaceAround) { cast(disabled placeholder); queue; share; playlist_add }
            // Cast: play-services-cast-framework is NOT on the dependency graph — the slot
            // renders a disabled Icons.Rounded.Cast placeholder with alpha 0.38.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .graphicsLayer { alpha = transportRowAlphaProvider() },
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Cast slot — disabled placeholder (no cast-framework dep).
                // TODO: Enable CastButton once play-services-cast-framework is added to the dep graph.
                //       Replace Icons.Rounded.Cast with a real MediaRouter/Cast widget at that point.
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .alpha(0.38f),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Cast,
                        contentDescription = castUnavailableStr,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp),
                    )
                }

                // Queue button — opens Q7 sheet.
                IconButton(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        showQueueSheet = true
                    },
                    modifier = Modifier.semantics { contentDescription = showQueueStr },
                ) {
                    MaterialSymbolIcon(
                        icon = IconKey.Queue,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // Share — fires system share sheet via Intent.ACTION_SEND.
                IconButton(
                    onClick = onShareTrack,
                    modifier = Modifier.semantics { contentDescription = shareStr },
                ) {
                    MaterialSymbolIcon(
                        icon = IconKey.Share,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // Add to playlist — reuses existing AddToPlaylistSheet route via callback.
                IconButton(
                    onClick = onAddToPlaylist,
                    modifier = Modifier.semantics { contentDescription = addToPlaylistStr },
                ) {
                    MaterialSymbolIcon(
                        icon = IconKey.PlaylistAdd,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // Sleep timer — YT-0093. Icon tinted primary when timer is active.
                val isTimerActive = sleepTimerState is SleepTimerState.Active
                IconButton(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        showSleepTimerSheet = true
                    },
                    modifier = Modifier.semantics { contentDescription = sleepTimerStr },
                ) {
                    // YT-0282: Icons.Rounded.Bedtime (legacy extended pack) used as fallback
                    // because IconKey.Bedtime (U+EF44) is not present in the shipped font subset.
                    Icon(
                        imageVector = Icons.Rounded.Bedtime,
                        contentDescription = null,
                        tint = if (isTimerActive) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(24.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    // ── Q7 Queue sheet ─────────────────────────────────────────────────────────────────
    // skipPartiallyExpanded = false per spec, drag-to-reorder + swipe-to-dismiss.
    if (showQueueSheet) {
        QueueSheet(
            queue = queue,
            currentQueueIndex = currentQueueIndex,
            onDismiss = { showQueueSheet = false },
            onRemove = onRemoveQueueItem,
            onMoveQueueItem = onMoveQueueItem,
            onJumpToQueueItem = onJumpToQueueItem,
            onPlayNext = onPlayNextFromQueue,
            onAddToQueue = onAddToQueueFromQueue,
            reduceMotion = reduceMotion,
        )
    }

    if (showSpeedSheet) {
        SpeedSheet(
            currentSpeed = playbackSpeed,
            onSelect = { speed ->
                onSpeedChange(speed)
                showSpeedSheet = false
            },
            onDismiss = { showSpeedSheet = false },
        )
    }

    // YT-0093 — sleep timer sheet.
    if (showSleepTimerSheet) {
        SleepTimerSheet(
            timerState = sleepTimerState,
            onSetTimer = { preset ->
                onSetSleepTimer(preset)
                showSleepTimerSheet = false
            },
            onCancel = {
                onCancelSleepTimer()
                showSleepTimerSheet = false
            },
            onDismiss = { showSleepTimerSheet = false },
        )
    }
}

// ── Q5 Scrubber ───────────────────────────────────────────────────────────────────────────

/**
 * Hand-rolled scrubber per YT-0013 compose-spec §Q4. Uses `Modifier.draggable` on `Canvas`
 * instead of Material3 `Slider`.
 *
 * Contract:
 *  - [onSeek] is called with an absolute position in milliseconds, ONLY on drag-stop.
 *  - Track height: 4dp at rest, 8dp while dragging (animate tween(180) / snap under reduce-motion).
 *  - Thumb: 16dp at rest, 22dp while dragging, white fill.
 *  - Time labels: `fontFeatureSettings = "tnum"`, labelSmall at rest, bodySmall while dragging.
 *  - Haptic tick every 10s boundary crossing during drag.
 *  - TalkBack: CustomAccessibilityAction "seek ±10 seconds".
 */
@Composable
private fun NowPlayingScrubber(
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    val seekForwardLabel = stringResource(R.string.lbl_scrubber_seek_forward)
    val seekBackwardLabel = stringResource(R.string.lbl_scrubber_seek_backward)

    var isDragging by remember { mutableStateOf(false) }
    // Preview position — initialised from positionMs when drag starts; updated per drag delta.
    var previewMs by remember { mutableLongStateOf(positionMs) }
    // Snap preview to playback position whenever NOT dragging.
    LaunchedEffect(positionMs, isDragging) {
        if (!isDragging) previewMs = positionMs
    }

    val haptics = LocalHapticFeedback.current

    // Haptic tick every 10s during drag: keyed on the 10s bucket.
    val tickKey by remember(previewMs, isDragging) {
        mutableIntStateOf((previewMs / 10_000L).toInt())
    }
    LaunchedEffect(tickKey, isDragging) {
        if (isDragging) {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    val trackHeightDp by animateDpAsState(
        targetValue = if (isDragging) 8.dp else 4.dp,
        animationSpec = if (reduceMotion) snap() else tween(180),
        label = "scrubber-track",
    )
    val thumbSizeDp by animateDpAsState(
        targetValue = if (isDragging) 22.dp else 16.dp,
        animationSpec = if (reduceMotion) snap() else tween(180),
        label = "scrubber-thumb",
    )
    val density = LocalDensity.current

    Column(modifier = modifier) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp),
        ) {
            val widthPx = constraints.maxWidth.toFloat()
            val trackHeightPx = with(density) { trackHeightDp.toPx() }
            val thumbRadiusPx = with(density) { (thumbSizeDp / 2).toPx() }
            val fraction = if (durationMs > 0L) {
                (previewMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
            } else 0f

            // Read theme tokens before entering the Canvas draw scope — Canvas lambdas
            // are not @Composable so MaterialTheme cannot be called inside them.
            val scrubberPrimary = MaterialTheme.colorScheme.primary
            val scrubberTrackBg = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp)
                    .semantics {
                        customActions = listOf(
                            CustomAccessibilityAction(seekForwardLabel) {
                                onSeek((previewMs + 10_000L).coerceAtMost(durationMs))
                                true
                            },
                            CustomAccessibilityAction(seekBackwardLabel) {
                                onSeek((previewMs - 10_000L).coerceAtLeast(0L))
                                true
                            },
                        )
                    }
                    .draggable(
                        orientation = Orientation.Horizontal,
                        state = rememberDraggableState { delta ->
                            if (durationMs <= 0L) return@rememberDraggableState
                            val deltaMs = (delta / widthPx) * durationMs
                            previewMs = (previewMs + deltaMs.toLong()).coerceIn(0L, durationMs)
                        },
                        onDragStarted = {
                            isDragging = true
                            previewMs = positionMs
                        },
                        onDragStopped = {
                            // Commit seek ONLY on release — never in onDrag.
                            onSeek(previewMs)
                            // GestureEnd (API 30+) — use LongPress as the safe Compose equivalent.
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            isDragging = false
                        },
                    ),
            ) {
                val centerY = size.height / 2f
                val trackTop = centerY - trackHeightPx / 2f
                val thumbX = (fraction * widthPx).coerceIn(thumbRadiusPx, widthPx - thumbRadiusPx)
                val cornerRadius = CornerRadius(trackHeightPx / 2f)

                // Background track — remaining portion uses a low-alpha onSurface tint
                // so it is visible on both light and dark surface/surfaceContainer backgrounds.
                drawRoundRect(
                    color = scrubberTrackBg,
                    topLeft = Offset(0f, trackTop),
                    size = Size(widthPx, trackHeightPx),
                    cornerRadius = cornerRadius,
                )
                // Filled / elapsed portion — primary token for ≥ 3:1 contrast on surface.
                drawRoundRect(
                    color = scrubberPrimary,
                    topLeft = Offset(0f, trackTop),
                    size = Size(thumbX, trackHeightPx),
                    cornerRadius = cornerRadius,
                )
                // Thumb — same primary token as the elapsed portion.
                drawCircle(
                    color = scrubberPrimary,
                    radius = thumbRadiusPx,
                    center = Offset(thumbX, centerY),
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // Q5 spec: tabular numbers for time labels — fontFeatureSettings is a
            // TextStyle property, not a direct Text() parameter.
            val baseStyle = if (isDragging) {
                MaterialTheme.typography.bodySmall
            } else {
                MaterialTheme.typography.labelSmall
            }
            val timeColor = if (isDragging) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
            // `copy` merges font-feature-settings into the role-specific TextStyle so
            // scale and weight are preserved alongside the tabular-nums feature flag.
            val timeStyle = baseStyle.copy(fontFeatureSettings = "tnum")
            Text(
                text = formatMs(previewMs),
                style = timeStyle,
                color = timeColor,
            )
            val remaining = (durationMs - previewMs).coerceAtLeast(0L)
            Text(
                text = "-${formatMs(remaining)}",
                style = timeStyle,
                color = timeColor,
            )
        }
    }
}

// ── Q7 Queue sheet ────────────────────────────────────────────────────────────────────────

/**
 * Queue bottom sheet — YT-0013 compose-spec §Q6 / YT-0091.
 *  - `skipPartiallyExpanded = false` so the sheet can be half-expanded.
 *  - Swipe-to-dismiss via `SwipeToDismissBox` (trailing edge only).
 *  - Drag-to-reorder via `sh.calvin.reorderable` library; drag handle on trailing edge.
 *  - Per-row overflow button exposes "Play next" and "Add to queue".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QueueSheet(
    queue: List<QueueItem>,
    currentQueueIndex: Int,
    onDismiss: () -> Unit,
    onRemove: (queueId: String) -> Unit,
    onMoveQueueItem: (Int, Int) -> Unit,
    onJumpToQueueItem: (index: Int) -> Unit,   // YT-0307
    onPlayNext: (queueId: String) -> Unit,
    onAddToQueue: (queueId: String) -> Unit,
    reduceMotion: Boolean,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val haptics = LocalHapticFeedback.current

    // Reorderable library state. The `onMove` lambda fires per-frame during a drag so
    // the list animates immediately; we commit the final reorder to the controller when
    // the drag handle is released via `onDragStopped` below.
    val lazyListState = rememberLazyListState()
    // Working copy of queue IDs so the library can track live reorder position.
    val workingIds = remember(queue) {
        androidx.compose.runtime.mutableStateListOf<String>().also { list ->
            list.addAll(queue.map { it.queueId })
        }
    }
    var dragStartIndex by remember { mutableIntStateOf(-1) }

    val reorderState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val fromIdx = workingIds.indexOf(from.key as? String ?: return@rememberReorderableLazyListState)
        val toIdx = workingIds.indexOf(to.key as? String ?: return@rememberReorderableLazyListState)
        if (fromIdx >= 0 && toIdx >= 0 && fromIdx != toIdx) {
            workingIds.add(toIdx, workingIds.removeAt(fromIdx))
        }
    }

    val dragHandleLabel = stringResource(R.string.cd_queue_drag_handle)
    val playNextLabel = stringResource(R.string.lbl_queue_play_next)
    val addToQueueLabel = stringResource(R.string.lbl_queue_add_to_queue)
    val moreOptionsLabel = stringResource(R.string.cd_more_options)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        if (queue.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) { Text(stringResource(R.string.lbl_queue_empty), style = MaterialTheme.typography.bodyMedium) }
        } else {
            // Build a stable lookup map so items can be found by queueId in O(1).
            val queueById = remember(queue) { queue.associateBy { it.queueId } }

            LazyColumn(
                state = lazyListState,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 600.dp),
            ) {
                items(
                    count = workingIds.size,
                    key = { workingIds[it] },
                ) { index ->
                    val queueId = workingIds[index]
                    val item = queueById[queueId] ?: return@items
                    val isCurrent = queue.indexOfFirst { it.queueId == queueId } == currentQueueIndex

                    ReorderableItem(reorderState, key = queueId) { isDragging ->
                        val elevation by animateDpAsState(
                            targetValue = if (isDragging) 6.dp else 0.dp,
                            label = "queue-row-elevation",
                        )

                        // Swipe-to-dismiss box (trailing/EndToStart only).
                        val dismissState = rememberSwipeToDismissBoxState(
                            confirmValueChange = { value ->
                                if (value == SwipeToDismissBoxValue.EndToStart) {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onRemove(item.queueId)
                                    true
                                } else false
                            },
                        )
                        // Cross-threshold haptic.
                        LaunchedEffect(dismissState.targetValue) {
                            if (dismissState.targetValue == SwipeToDismissBoxValue.EndToStart &&
                                dismissState.currentValue == SwipeToDismissBoxValue.Settled) {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                        }

                        Surface(shadowElevation = elevation) {
                            SwipeToDismissBox(
                                state = dismissState,
                                enableDismissFromStartToEnd = false,
                                enableDismissFromEndToStart = true,
                                backgroundContent = {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(MaterialTheme.colorScheme.errorContainer),
                                        contentAlignment = Alignment.CenterEnd,
                                    ) {
                                        Text(
                                            text = stringResource(R.string.lbl_queue_swipe_to_remove),
                                            color = MaterialTheme.colorScheme.onErrorContainer,
                                            style = MaterialTheme.typography.labelMedium,
                                            modifier = Modifier.padding(end = 16.dp),
                                        )
                                    }
                                },
                            ) {
                                // Per-row overflow menu state.
                                var showMenu by remember { mutableStateOf(false) }

                                ListItem(
                                    modifier = Modifier.clickable(
                                        enabled = !isCurrent,
                                        onClick = {
                                            val realIndex = queue.indexOfFirst { it.queueId == queueId }
                                            if (realIndex >= 0) onJumpToQueueItem(realIndex)
                                        },
                                    ),
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
                                    leadingContent = {
                                        // Drag handle — wrapped in a Box so draggableHandle
                                        // receives the gesture directly without an interposed
                                        // clickable consuming it.
                                        Box(
                                            modifier = Modifier
                                                .size(40.dp)
                                                .draggableHandle(
                                                    onDragStarted = {
                                                        dragStartIndex = workingIds.indexOf(queueId)
                                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    },
                                                    onDragStopped = {
                                                        val endIndex = workingIds.indexOf(queueId)
                                                        if (dragStartIndex >= 0 && endIndex >= 0 &&
                                                            dragStartIndex != endIndex) {
                                                            onMoveQueueItem(dragStartIndex, endIndex)
                                                        }
                                                        dragStartIndex = -1
                                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    },
                                                )
                                                .semantics { contentDescription = dragHandleLabel },
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.DragHandle,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(20.dp),
                                            )
                                        }
                                    },
                                    trailingContent = {
                                        Box {
                                            IconButton(
                                                onClick = { showMenu = true },
                                                modifier = Modifier.semantics {
                                                    contentDescription = moreOptionsLabel
                                                },
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Rounded.MoreVert,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                            DropdownMenu(
                                                expanded = showMenu,
                                                onDismissRequest = { showMenu = false },
                                            ) {
                                                DropdownMenuItem(
                                                    text = { Text(playNextLabel) },
                                                    onClick = {
                                                        showMenu = false
                                                        onPlayNext(queueId)
                                                    },
                                                )
                                                DropdownMenuItem(
                                                    text = { Text(addToQueueLabel) },
                                                    onClick = {
                                                        showMenu = false
                                                        onAddToQueue(queueId)
                                                    },
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
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpeedSheet(
    currentSpeed: Float,
    onSelect: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    val speeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val normalSpeedLabel = stringResource(R.string.lbl_speed_normal)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Text(
            text = stringResource(R.string.lbl_playback_speed_sheet_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
        speeds.forEach { speed ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(speed) }
                    .padding(horizontal = 8.dp),
            ) {
                RadioButton(
                    selected = speed == currentSpeed,
                    onClick = { onSelect(speed) },
                )
                Text(
                    text = formatSpeed(speed, normalSpeedLabel),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}

// ── YT-0093 Sleep timer sheet ─────────────────────────────────────────────────────────────

/**
 * YT-0093 — Sleep timer bottom sheet.
 *
 * Shows four preset chips (15 min, 30 min, 60 min, End of track) and — when a timer is
 * active — a live countdown "Turns off in M:SS" plus a Cancel button.
 *
 * Selecting a preset while another is active replaces it (the controller cancels the old
 * job internally before starting the new one).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SleepTimerSheet(
    timerState: SleepTimerState,
    onSetTimer: (SleepTimerPreset) -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Text(
            text = stringResource(R.string.lbl_sleep_timer_sheet_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )

        // Active countdown row — shown when a timer is running.
        if (timerState is SleepTimerState.Active) {
            val countdownLabel = if (timerState.preset == SleepTimerPreset.EndOfTrack) {
                stringResource(R.string.lbl_sleep_timer_preset_end_of_track)
            } else {
                stringResource(
                    R.string.lbl_sleep_timer_turns_off_in,
                    formatMs(timerState.remainingMs),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = countdownLabel,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                OutlinedButton(onClick = onCancel) {
                    Text(stringResource(R.string.lbl_sleep_timer_cancel))
                }
            }
        }

        // Preset buttons.
        val presets = listOf(
            SleepTimerPreset.Min15 to R.string.lbl_sleep_timer_preset_15,
            SleepTimerPreset.Min30 to R.string.lbl_sleep_timer_preset_30,
            SleepTimerPreset.Min60 to R.string.lbl_sleep_timer_preset_60,
            SleepTimerPreset.EndOfTrack to R.string.lbl_sleep_timer_preset_end_of_track,
        )
        presets.forEach { (preset, labelRes) ->
            val isSelected = timerState is SleepTimerState.Active &&
                timerState.preset == preset
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSetTimer(preset) }
                    .padding(horizontal = 8.dp),
            ) {
                RadioButton(
                    selected = isSelected,
                    onClick = { onSetTimer(preset) },
                )
                Text(
                    text = stringResource(labelRes),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────────────────

/**
 * Cycles repeat mode: OFF(0) → ONE(1) → ALL(2) → OFF(0).
 * Integer values match androidx.media3.common.Player constants.
 */
private fun nextRepeatMode(current: Int): Int = when (current) {
    0 -> 1 // OFF → ONE
    1 -> 2 // ONE → ALL
    else -> 0 // ALL → OFF
}

internal fun formatSpeed(speed: Float, normalLabel: String): String =
    if (speed == 1.0f) normalLabel else "${speed}×"

internal fun formatSeconds(totalSec: Int): String {
    val m = totalSec / 60
    val s = totalSec % 60
    return "$m:${s.toString().padStart(2, '0')}"
}

/**
 * Formats a duration in milliseconds to a human-readable time string.
 *
 * Output format:
 *  - Under 1 hour: "M:SS" (e.g. "10:18", "0:42")
 *  - 1 hour or more: "H:MM:SS" (e.g. "10:00:18", "1:05:02")
 *
 * YT-0278: previously used a minutes-only formatter which produced "-599:42" for long tracks.
 */
internal fun formatMs(ms: Long): String {
    val totalSeconds = ms / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
