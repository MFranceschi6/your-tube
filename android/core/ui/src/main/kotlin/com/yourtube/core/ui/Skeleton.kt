package com.yourtube.core.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// ── Tokens ────────────────────────────────────────────────────────────────────
//
// Catalog: design-system/handoff/state-catalog/loading.md +
// design-system/handoff/state-catalog/README.md (Tokens table).

/** `--skeleton-bg` (`#2C2C2E`). */
private val SkeletonBg: Color = Color(0xFF2C2C2E)

/** `--skeleton-shimmer` (`rgba(255,255,255,0.06)`). */
private val SkeletonShimmer: Color = Color.White.copy(alpha = 0.06f)

/** Shimmer cycle: 1400ms linear infinite. */
private const val ShimmerCycleMs: Int = 1400

/** Stagger between rows: 80ms × index, prevents the "marching wall" effect. */
private const val ShimmerStaggerMs: Int = 80

/**
 * Hand-rolled shimmer modifier. Solid `--skeleton-bg` block + a 30%-wide diagonal
 * highlight gradient sweeping left → right on a 1400 ms linear infinite cycle.
 *
 * - When [LocalReduceMotion] is `true`, the shimmer is paused and only the static
 *   `--skeleton-bg` block remains (the *shape* still communicates loading).
 * - [staggerIndex] offsets the phase by `index × 80 ms` so multiple rows on the
 *   same screen don't pulse in lockstep.
 *
 * Skeletons are decorative — sets `contentDescription = ""` so TalkBack does not
 * announce them. The surrounding [LoadingList] sets `stateDescription = "Loading"`
 * on the container so screen readers read "Loading" once on focus.
 */
@Composable
fun Modifier.shimmerSkeleton(staggerIndex: Int = 0): Modifier {
    val base = this
        .semantics { contentDescription = "" }
        .background(SkeletonBg)
    val reduceMotion = LocalReduceMotion.current
    if (reduceMotion) {
        // Static block — the shape still reads as "loading" via the surrounding
        // LoadingList's `stateDescription = "Loading"`.
        return base
    }
    val transition = rememberInfiniteTransition(label = "skeleton-shimmer")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = ShimmerCycleMs, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
            initialStartOffset = StartOffset(staggerIndex * ShimmerStaggerMs),
        ),
        label = "skeleton-shimmer-phase",
    )
    return base.drawWithCache {
        val bandWidth = size.width * 0.30f
        val travel = size.width + bandWidth
        val x = phase * travel - bandWidth
        val brush = Brush.linearGradient(
            colors = listOf(Color.Transparent, SkeletonShimmer, Color.Transparent),
            start = Offset(x, 0f),
            end = Offset(x + bandWidth, size.height),
        )
        onDrawWithContent {
            drawContent()
            drawRect(
                brush = brush,
                topLeft = Offset.Zero,
                size = Size(size.width, size.height),
            )
        }
    }
}

// ── Skeleton building blocks ──────────────────────────────────────────────────

@Composable
private fun SkeletonBlock(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 8.dp,
    staggerIndex: Int = 0,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .shimmerSkeleton(staggerIndex),
    )
}

@Composable
private fun SkeletonLine(
    widthFraction: Float,
    height: Dp,
    staggerIndex: Int,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth(widthFraction)
            .height(height)
            .clip(RoundedCornerShape(4.dp))
            .shimmerSkeleton(staggerIndex),
    )
}

/**
 * Skeleton for a track row (Search C1, History C12, PlaylistDetail body C9).
 *
 * 56dp leading thumbnail block + two stacked text-line skeletons (line 1 60% × 14dp,
 * 6dp gap, line 2 35% × 11dp). 16dp inner padding on all sides.
 */
@Composable
fun SkeletonRow(
    staggerIndex: Int = 0,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SkeletonBlock(
            modifier = Modifier.size(56.dp),
            cornerRadius = 8.dp,
            staggerIndex = staggerIndex,
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.fillMaxWidth()) {
            SkeletonLine(widthFraction = 0.60f, height = 14.dp, staggerIndex = staggerIndex)
            Spacer(modifier = Modifier.height(6.dp))
            SkeletonLine(widthFraction = 0.35f, height = 11.dp, staggerIndex = staggerIndex)
        }
    }
}

/**
 * Skeleton for a playlist row (Library C6).
 *
 * 40dp leading 4-up cover block (solid, no inner grid in skeleton form) + line 1
 * 50% × 14dp + 6dp gap + line 2 25% × 11dp.
 */
@Composable
fun SkeletonPlaylistRow(
    staggerIndex: Int = 0,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SkeletonBlock(
            modifier = Modifier.size(40.dp),
            cornerRadius = 8.dp,
            staggerIndex = staggerIndex,
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.fillMaxWidth()) {
            SkeletonLine(widthFraction = 0.50f, height = 14.dp, staggerIndex = staggerIndex)
            Spacer(modifier = Modifier.height(6.dp))
            SkeletonLine(widthFraction = 0.25f, height = 11.dp, staggerIndex = staggerIndex)
        }
    }
}

/**
 * Skeleton for the playlist-detail header (C9): 140dp 4-up cover block centered,
 * 24dp gap, title placeholder 60% × 22dp centered, 8dp gap, subtitle 30% × 13dp
 * centered, 16dp gap, two side-by-side button skeletons (120dp × 44dp pill).
 */
@Composable
fun SkeletonPlaylistHeader(
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SkeletonBlock(
            modifier = Modifier.size(140.dp),
            cornerRadius = 12.dp,
            staggerIndex = 0,
        )
        Spacer(modifier = Modifier.height(24.dp))
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            SkeletonLine(widthFraction = 0.60f, height = 22.dp, staggerIndex = 0)
        }
        Spacer(modifier = Modifier.height(8.dp))
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            SkeletonLine(widthFraction = 0.30f, height = 13.dp, staggerIndex = 1)
        }
        Spacer(modifier = Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                modifier = Modifier
                    .size(width = 120.dp, height = 44.dp)
                    .clip(CircleShape)
                    .shimmerSkeleton(staggerIndex = 0),
            )
            Box(
                modifier = Modifier
                    .size(width = 120.dp, height = 44.dp)
                    .clip(CircleShape)
                    .shimmerSkeleton(staggerIndex = 1),
            )
        }
    }
}

/**
 * Wraps a column of skeleton rows for a list-driven screen.
 *
 * Adds a `stateDescription = "Loading"` semantic + `liveRegion = Polite` so
 * TalkBack reads "Loading" once when the loading container appears, satisfying
 * the catalog's loading-state accessibility contract without spamming on every
 * transition.
 *
 * Per the catalog, skeletons themselves are non-interactive and decorative — the
 * row composables already mark their leaf blocks with empty `contentDescription`.
 */
@Composable
fun LoadingList(
    rowCount: Int,
    rowFactory: @Composable (index: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .semantics(mergeDescendants = true) {
                stateDescription = "Loading"
                liveRegion = LiveRegionMode.Polite
            },
    ) {
        repeat(rowCount) { index -> rowFactory(index) }
    }
}

// ── Previews ──────────────────────────────────────────────────────────────────

@Preview(showBackground = true, backgroundColor = 0xFF0F0F0F)
@Composable
private fun SkeletonRowPreview() {
    Column { repeat(4) { SkeletonRow(staggerIndex = it) } }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F0F0F)
@Composable
private fun SkeletonPlaylistRowPreview() {
    Column { repeat(4) { SkeletonPlaylistRow(staggerIndex = it) } }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F0F0F)
@Composable
private fun SkeletonPlaylistHeaderPreview() {
    SkeletonPlaylistHeader()
}
