package com.yourtube.core.ui

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.yourtube.core.common.model.QueueItem
import com.yourtube.core.common.model.SleepTimerPreset
import com.yourtube.core.common.model.SleepTimerState
import com.yourtube.core.common.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Linear interpolation between [a] and [b] by [t] (clamped to 0..1 by callers when needed).
 */
internal fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

internal fun lerp(a: Dp, b: Dp, t: Float): Dp = (a.value + (b.value - a.value) * t).dp

/**
 * Sub-window normalisation. Maps the global [p] (0..1) onto the sub-window `[a, b]` and
 * clamps to 0..1. Used for staggered chrome fades that should kick in / settle within
 * a sub-portion of the morph.
 */
internal fun clampNorm(p: Float, a: Float, b: Float): Float {
    if (b <= a) return if (p >= b) 1f else 0f
    return ((p - a) / (b - a)).coerceIn(0f, 1f)
}

/**
 * YT-0166 round 4 — single source of truth for the MiniPlayer ↔ NowPlaying transition.
 *
 * `expandProgress` is the only canonical animation state. Renderers read it via
 * `progressProvider()` per frame. Drivers (`autoOpen`, `autoClose`, `onDragDelta`,
 * `onDragStopped`) mutate it via `animateTo` / `snapTo`.
 */
@Stable
class PlayerOverlayState(
    private val coroutineScope: CoroutineScope,
    private val onCloseSettled: () -> Unit,
    private val isReducedMotionProvider: () -> Boolean,
    private val screenHeightPxProvider: () -> Float,
    private val velocityThresholdPxPerSecProvider: () -> Float,
    initialExpanded: Boolean = false,
) {
    /**
     * The single Animatable. `0f` = MiniPlayer slot (collapsed), `1f` = NowPlaying surface
     * (fully expanded). Mutated by all four drivers; read by every visual property.
     */
    val expandProgress: Animatable<Float, AnimationVector1D> =
        Animatable(if (initialExpanded) 1f else 0f)

    /** Stable provider lambda — readers should call this every frame. */
    val progressProvider: () -> Float = { expandProgress.value }

    private var activeJob: Job? = null

    /**
     * Drive progress to `1f` over [MotionSpec.DURATION_EXPAND_MS] (or
     * [MotionSpec.DURATION_REDUCE_MOTION_MS] in reduce-motion). Cancels any in-flight
     * animation. Idempotent — if progress is already at 1f and no animation is running,
     * this is a no-op.
     */
    fun autoOpen() {
        // Cancel in-flight animation so a rapid open→close→open sequence does not
        // race two coroutines. `Animatable.animateTo` cooperatively cancels when its
        // hosting coroutine is cancelled.
        activeJob?.cancel()
        activeJob = coroutineScope.launch {
            val reduce = isReducedMotionProvider()
            val duration = if (reduce) MotionSpec.DURATION_REDUCE_MOTION_MS
                else MotionSpec.DURATION_EXPAND_MS
            expandProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = duration, easing = MotionSpec.Standard),
            )
        }
    }

    /**
     * Drive progress to `0f` over [MotionSpec.DURATION_COLLAPSE_MS] (or
     * [MotionSpec.DURATION_REDUCE_MOTION_MS] in reduce-motion). Calls [onCloseSettled]
     * after settle. Idempotent — if progress is already 0f, [onCloseSettled] is NOT
     * called (callers wire this to `popBackStack()` and the back-stack is already
     * popped in that case).
     */
    fun autoClose() {
        activeJob?.cancel()
        // Idempotency guard: if progress is already 0 we have nothing to animate AND
        // the back-stack pop has already happened (the LaunchedEffect that calls us
        // fires off the route change which is what popped the stack in the first
        // place). Skipping the callback here prevents a double-pop.
        if (expandProgress.value <= 0f) return
        activeJob = coroutineScope.launch {
            val reduce = isReducedMotionProvider()
            val duration = if (reduce) MotionSpec.DURATION_REDUCE_MOTION_MS
                else MotionSpec.DURATION_COLLAPSE_MS
            val easing = if (reduce) MotionSpec.Standard else MotionSpec.Exit
            expandProgress.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = duration, easing = easing),
            )
            onCloseSettled()
        }
    }

    /**
     * Drag mutator. [dy] is positive for downward drag (which collapses). The formula
     * `progress -= dy / screenHeightPx` maps the full screen height to a 1f → 0f sweep.
     *
     * Cancels any in-flight auto-animation: drag wins. Reduce-motion drags are a no-op
     * (the handle remains visible for visual continuity but is not interactive).
     */
    fun onDragDelta(dy: Float) {
        if (isReducedMotionProvider()) return
        val screenH = screenHeightPxProvider()
        if (screenH <= 0f) return
        // Cancel any running animateTo so the snap-during-drag sticks.
        activeJob?.cancel()
        coroutineScope.launch {
            val next = (expandProgress.value - dy / screenH).coerceIn(0f, 1f)
            expandProgress.snapTo(next)
        }
    }

    /**
     * Release decision. Threshold per spec §3: collapse if `(1 - progress) >= 0.30` OR
     * `velocity >= velocityThreshold`; otherwise spring back to 1f.
     *
     * Reduce-motion: still computes the threshold but uses tween durations matching
     * [MotionSpec.DURATION_REDUCE_MOTION_MS].
     */
    fun onDragStopped(velocityPxPerSec: Float) {
        if (isReducedMotionProvider()) return
        activeJob?.cancel()
        val velocityThreshold = velocityThresholdPxPerSecProvider()
        val draggedFraction = 1f - expandProgress.value
        val triggerByDistance = draggedFraction >= MotionSpec.DRAG_COLLAPSE_DISTANCE_FRACTION
        val triggerByVelocity = velocityPxPerSec >= velocityThreshold
        // YT-0229: convert finger px/sec to progress/sec. Positive Y velocity = downward
        // = progress decreasing, so the progress-space initial velocity is NEGATED. The
        // post-release animation continues from this velocity instead of restarting from
        // rest, removing the visible frame-freeze at drag-end.
        val screenH = screenHeightPxProvider()
        val initialVelocityProgressPerSec =
            if (screenH > 0f) -velocityPxPerSec / screenH else 0f
        activeJob = coroutineScope.launch {
            if (triggerByDistance || triggerByVelocity) {
                expandProgress.animateTo(
                    targetValue = 0f,
                    animationSpec = spring(
                        dampingRatio = MotionSpec.DRAG_RELEASE_COLLAPSE_DAMPING_RATIO,
                        stiffness = MotionSpec.DRAG_RELEASE_COLLAPSE_STIFFNESS,
                    ),
                    initialVelocity = initialVelocityProgressPerSec,
                )
                onCloseSettled()
            } else {
                expandProgress.animateTo(
                    targetValue = 1f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMediumLow,
                    ),
                    initialVelocity = initialVelocityProgressPerSec,
                )
            }
        }
    }
}

/**
 * Composition local for the overlay state. Defaults to a no-op state so previews / tests
 * that compose the chrome composables in isolation do not crash. Production AppShell
 * always provides a non-default value.
 */
val LocalPlayerOverlayState =
    compositionLocalOf<PlayerOverlayState?> { null }

/**
 * The overlay composable. Renders Z-ordered (bottom → top in declaration order):
 *
 *  1. Scrim (full-screen, alpha = scrimAlpha).
 *  2. NowPlayingChrome (alpha = nowPlayingChromeAlpha).
 *  3. Artwork (positioned + sized + scaled + rounded by `progress`). The ONE artwork
 *     instance — neither MiniPlayerChrome nor NowPlayingChrome render artwork themselves.
 *  4. MiniPlayerChrome (alpha = miniChromeAlpha).
 *
 * Every property is a pure function of `progress` (and optionally `isPlaying`). The
 * single source of truth eliminates the entire class of "two animations desync" bugs
 * that rounds 1–3 fought.
 */
@Composable
fun PlayerOverlay(
    state: PlayerOverlayState,
    currentTrack: Track?,
    isPlaying: Boolean,
    isBuffering: Boolean,
    progressFraction: Float,
    shuffleOn: Boolean = false,
    repeatMode: Int = 0,
    onPlayPauseClick: () -> Unit,
    onSkipNextClick: () -> Unit,
    onSkipPreviousClick: () -> Unit,
    onExpandClick: () -> Unit,
    onCollapseClick: () -> Unit,
    onSeek: (Float) -> Unit,
    onShuffleModeChange: (Boolean) -> Unit = {},
    onRepeatModeChange: (Int) -> Unit = {},
    onShareTrack: () -> Unit = {},
    onAddToPlaylist: () -> Unit = {},
    navBarHeight: Dp,
    queue: List<QueueItem> = emptyList(),
    currentQueueIndex: Int = 0,
    onRemoveQueueItem: (queueId: String) -> Unit = {},
    onMoveQueueItem: (Int, Int) -> Unit = { _, _ -> },
    onJumpToQueueItem: (Int) -> Unit = {},
    onPlayNextFromQueue: (queueId: String) -> Unit = {},
    onAddToQueueFromQueue: (queueId: String) -> Unit = {},
    onMiniPlayerSizeChanged: (Dp) -> Unit = {},
    playbackSpeed: Float = 1.0f,
    onSpeedChange: (Float) -> Unit = {},
    durationMs: Long = 0L,
    positionMs: Long = 0L,
    /** YT-0093 — current sleep-timer state, forwarded to NowPlayingChrome. */
    sleepTimerState: SleepTimerState = SleepTimerState.Inactive,
    onSetSleepTimer: (SleepTimerPreset) -> Unit = {},
    onCancelSleepTimer: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    if (currentTrack == null) return

    val density = LocalDensity.current
    val background = MaterialTheme.colorScheme.background

    // Window-relative rects of the artwork slot reserved by each chrome. Reported
    // by the chromes via `Modifier.onGloballyPositioned`. ArtworkBox lerps between
    // these two rects based on `progress` — no hardcoded paddings, no
    // `configuration.screenHeightDp` math, no inset guessing. The chromes own
    // where their artwork goes; the overlay just morphs between them.
    var miniSlotRect by remember { mutableStateOf<Rect?>(null) }
    var nowPlayingSlotRect by remember { mutableStateOf<Rect?>(null) }

    Box(modifier = modifier.fillMaxSize()) {
        // ── (1) Scrim: cross-fade transparent → BACKGROUND.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val p = state.progressProvider()
                    alpha = clampNorm(p, 0f, 240f / 320f)
                }
                .background(background),
        )

        // ── (2) NowPlayingChrome — composed only when progress > 0 to keep its
        //  IconButtons from hit-testing over the bottom-nav at MiniPlayer state.
        if (state.progressProvider() > 0f) {
            NowPlayingChrome(
                track = currentTrack,
                isPlaying = isPlaying,
                isBuffering = isBuffering,
                progressFraction = progressFraction,
                shuffleOn = shuffleOn,
                repeatMode = repeatMode,
                onPlayPauseClick = onPlayPauseClick,
                onSkipNextClick = onSkipNextClick,
                onSkipPreviousClick = onSkipPreviousClick,
                onCollapseClick = onCollapseClick,
                onSeek = onSeek,
                onShuffleModeChange = onShuffleModeChange,
                onRepeatModeChange = onRepeatModeChange,
                onShareTrack = onShareTrack,
                onAddToPlaylist = onAddToPlaylist,
                queue = queue,
                currentQueueIndex = currentQueueIndex,
                onRemoveQueueItem = onRemoveQueueItem,
                onMoveQueueItem = onMoveQueueItem,
                onJumpToQueueItem = onJumpToQueueItem,
                onPlayNextFromQueue = onPlayNextFromQueue,
                onAddToQueueFromQueue = onAddToQueueFromQueue,
                playbackSpeed = playbackSpeed,
                onSpeedChange = onSpeedChange,
                durationMs = durationMs,
                positionMs = positionMs,
                sleepTimerState = sleepTimerState,
                onSetSleepTimer = onSetSleepTimer,
                onCancelSleepTimer = onCancelSleepTimer,
                chromeAlphaProvider = {
                    clampNorm(state.progressProvider(), 80f / 320f, 280f / 320f)
                },
                transportRowAlphaProvider = {
                    clampNorm(state.progressProvider(), 200f / 320f, 1f)
                },
                onDragDelta = state::onDragDelta,
                onDragStopped = state::onDragStopped,
                onArtworkSlotPositioned = { rect -> nowPlayingSlotRect = rect },
            )
        }

        // ── (3) MiniPlayerChrome — composed only when progress < 1; reports its
        //  artwork-slot rect via `onArtworkSlotPositioned`. Z-order: BEFORE the
        //  artwork so the (Z-4) artwork draws on top of the chrome's opaque
        //  surfaceContainerHigh background.
        if (state.progressProvider() < 1f) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(bottom = navBarHeight)
                    .onSizeChanged { size ->
                        onMiniPlayerSizeChanged(with(density) { size.height.toDp() })
                    },
            ) {
                MiniPlayerChrome(
                    track = currentTrack,
                    isPlaying = isPlaying,
                    isBuffering = isBuffering,
                    progressFraction = progressFraction,
                    onPlayPauseClick = onPlayPauseClick,
                    onSkipNextClick = onSkipNextClick,
                    onExpandClick = onExpandClick,
                    chromeAlphaProvider = {
                        1f - clampNorm(state.progressProvider(), 0f, 60f / 320f)
                    },
                    onArtworkSlotPositioned = { rect -> miniSlotRect = rect },
                )
            }
        }

        // ── (4) Artwork — single shared instance. Position + size lerp from the
        //  measured chrome slot rects; no hardcoded paddings, no screen-height
        //  math. Rendered last so it draws on top of MiniPlayerChrome's
        //  surfaceContainerHigh background at progress=0.
        //
        //  At first composition either rect can still be null (chrome not yet
        //  laid out). We render even if one is missing, falling back to the
        //  available rect so the artwork doesn't pop in at frame 1. If both
        //  are null we skip — track will surface again on the next layout pass.
        val mini = miniSlotRect
        val expanded = nowPlayingSlotRect
        val effectiveMini = mini ?: expanded
        val effectiveExpanded = expanded ?: mini
        if (effectiveMini != null && effectiveExpanded != null) {
            Box(modifier = Modifier.fillMaxSize()) {
                ArtworkBox(
                    track = currentTrack,
                    progressProvider = state.progressProvider,
                    miniSlot = effectiveMini,
                    expandedSlot = effectiveExpanded,
                )
            }
        }
    }
}

/**
 * The single artwork instance. Position + size + corner radius come from
 * progress. No `sharedBounds`, no `AnimatedVisibility`. Pure transform.
 *
 * YT-0243: visible scale is pinned to 1.0 in every state — the previous
 * paused-only 0.85 shrink is gone.
 */
@Composable
private fun ArtworkBox(
    track: Track,
    progressProvider: () -> Float,
    /**
     * Window-relative rect of the MiniPlayerChrome's artwork slot.
     * Reported by the chrome via [Modifier.onGloballyPositioned]. The artwork
     * morphs FROM this rect at progress=0.
     */
    miniSlot: Rect,
    /**
     * Window-relative rect of the NowPlayingChrome's artwork slot.
     * Reported the same way. The artwork morphs TO this rect at progress=1.
     */
    expandedSlot: Rect,
) {
    val density = LocalDensity.current
    val startRadiusPx = with(density) { MotionSpec.ARTWORK_START_RADIUS_DP.dp.toPx() }
    val endRadiusPx = with(density) { MotionSpec.ARTWORK_END_RADIUS_DP.dp.toPx() }

    // The expanded slot defines the layout size of the artwork. We size the inner
    // Box once at expandedSlot's dimensions and SCALE down to mini at p=0. Same
    // primitive as before, but the source-of-truth rects come from the chromes'
    // measured layouts — no constants, no screen-height math.
    val expandedSizePx = expandedSlot.width
    val expandedSizeDp = with(density) { expandedSizePx.toDp() }

    Box(
        modifier = Modifier
            .testTag(MiniPlayerArtworkTestTag)
            .testTag(NowPlayingArtworkTestTag)
            .graphicsLayer {
                val p = progressProvider()
                // Lerp slot dimensions and top-left in window coordinates.
                val sizeAtP = lerp(miniSlot.width, expandedSlot.width, p)
                val shrink = sizeAtP / expandedSizePx
                scaleX = shrink
                scaleY = shrink

                val targetX = lerp(miniSlot.left, expandedSlot.left, p)
                val targetY = lerp(miniSlot.top, expandedSlot.top, p)
                // Inner Box sits at TopStart of the screen-sized parent; with
                // transformOrigin (0.5, 0.5) (default), the post-scale visible
                // top-left is at (expandedSize - sizeAtP)/2. Translate so the
                // visible top-left lands at the lerped target.
                val halfShrinkOffset = (expandedSizePx - sizeAtP) / 2f
                translationX = targetX - halfShrinkOffset
                translationY = targetY - halfShrinkOffset

                // YT-0243: artwork stays at full size in every playback state
                // (playing / paused / buffering). The previous Spotify-style
                // 0.85 pause-shrink was perceived as a flicker during the
                // stream-resolve gap on track changes, so the pause-scale
                // multiplier collapses to a constant 1.0 (no-op) and is
                // intentionally omitted here.
            },
    ) {
        Box(
            modifier = Modifier
                .size(expandedSizeDp)
                .graphicsLayer {
                    val p = progressProvider()
                    val radiusPx = lerp(startRadiusPx, endRadiusPx, p)
                    shape = RoundedCornerShape(radiusPx)
                    clip = true
                }
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            if (track.thumbnailUrl.isNotEmpty()) {
                AsyncImage(
                    model = track.thumbnailUrl,
                    contentDescription = stringResource(R.string.cd_now_playing_artwork, track.title),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/**
 * Mini thumbnail size constant — used by the overlay AND the MiniPlayer chrome's spacer
 * so the artwork's MiniPlayer-position aligns with the chrome's reserved leading area.
 */
internal const val MINI_THUMB_SIZE_DP = 44

/**
 * YT-0166 test contract — instrumented Compose UI tests grep these test-tags. Both tags
 * resolve to the SAME single artwork instance under round 4's overlay architecture; we
 * keep both names so the existing AC tests continue to find a node by either tag.
 */
const val MiniPlayerArtworkTestTag = "miniplayer-artwork"
const val NowPlayingArtworkTestTag = "nowplaying-artwork"
const val NowPlayingDragHandleTestTag = "nowplaying-drag-handle"
