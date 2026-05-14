package com.yourtube.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import com.yourtube.core.designsystem.IconKey
import com.yourtube.core.designsystem.Icon as MaterialSymbolIcon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.yourtube.core.common.model.Track

/**
 * Round 4 — extracted from the now-deleted `MiniPlayer.kt`. Renders ONLY the chrome
 * (title row, play/pause + skip buttons, thin bottom progress bar). The artwork is
 * rendered ONCE by [PlayerOverlay] above this composable in the Z-stack; this chrome
 * reserves a leading [MINI_THUMB_SIZE_DP]dp `Spacer` where the artwork visually sits at
 * `progress = 0`.
 *
 * Stateless. The fade alpha is provided per-frame via [chromeAlphaProvider] — the
 * overlay computes `1f - clampNorm(progress, 0, 60/320)` so the chrome fully fades by
 * the 60ms mark of an expand (per spec §2 mini-chrome fade-out).
 *
 * YT-0196 — the play/pause IconButton swaps its glyph for a CircularProgressIndicator
 * while [isBuffering] is true. The branch must be preserved in any future refactor.
 */
@Composable
internal fun MiniPlayerChrome(
    track: Track,
    isPlaying: Boolean,
    isBuffering: Boolean,
    progressFraction: Float,
    onPlayPauseClick: () -> Unit,
    onSkipNextClick: () -> Unit,
    onExpandClick: () -> Unit,
    /**
     * Window-relative rect of the artwork slot reserved by this chrome. The
     * overlay reads this rect to position+size the single shared artwork
     * instance — no hardcoded constants. Fired on every layout pass so insets,
     * padding, and font scaling all flow through automatically.
     */
    onArtworkSlotPositioned: (Rect) -> Unit = {},
    chromeAlphaProvider: () -> Float,
    modifier: Modifier = Modifier,
) {
    // YT-0196 — buffering label suffix.
    val playPauseLabel = when {
        isBuffering -> stringResource(R.string.cd_mini_player_play_pause_loading, track.title)
        isPlaying -> stringResource(R.string.cd_mini_player_play_pause_pause, track.title)
        else -> stringResource(R.string.cd_mini_player_play_pause_play, track.title)
    }
    val expandLabel = stringResource(R.string.cd_mini_player_expand)
    val skipNextLabel = stringResource(R.string.cd_mini_player_skip_next)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .graphicsLayer { alpha = chromeAlphaProvider() },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .semantics { contentDescription = expandLabel }
                .clickable(onClickLabel = expandLabel, onClick = onExpandClick),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, top = 12.dp, bottom = 18.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Reserved space for the artwork. The artwork itself is rendered by
                // PlayerOverlay on top; this Box reports its own window-relative rect
                // via `onArtworkSlotPositioned` so the overlay's ArtworkBox can lerp
                // its position+size from THIS measured rect to the NowPlaying slot,
                // instead of guessing with hardcoded paddings.
                Box(
                    modifier = Modifier
                        .size(MINI_THUMB_SIZE_DP.dp)
                        .onGloballyPositioned { coords ->
                            onArtworkSlotPositioned(coords.boundsInWindow())
                        },
                )
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

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Play / Pause — YT-0196 buffering branch preserved.
                    IconButton(
                        onClick = { if (!isBuffering) onPlayPauseClick() },
                        modifier = Modifier.semantics { contentDescription = playPauseLabel },
                    ) {
                        if (isBuffering) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = MaterialTheme.colorScheme.onSurface,
                                strokeWidth = 2.dp,
                            )
                        } else {
                            MaterialSymbolIcon(
                                icon = if (isPlaying) IconKey.Pause else IconKey.Play,
                                filled = true,
                                weight = 600,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }

                    IconButton(
                        onClick = onSkipNextClick,
                        modifier = Modifier.semantics { contentDescription = skipNextLabel },
                    ) {
                        MaterialSymbolIcon(
                            icon = IconKey.SkipNext,
                            weight = 500,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }

            // Thin progress bar pinned to the bottom of the chrome container.
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

@Preview(showBackground = true, backgroundColor = 0xFF141218)
@Composable
private fun MiniPlayerChromePlayingPreview() {
    MaterialTheme {
        MiniPlayerChrome(
            track = Track("1", "lofi hip hop radio", "Lofi Girl", 3612, ""),
            isPlaying = true,
            isBuffering = false,
            progressFraction = 0.4f,
            onPlayPauseClick = {},
            onSkipNextClick = {},
            onExpandClick = {},
            chromeAlphaProvider = { 1f },
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF141218)
@Composable
private fun MiniPlayerChromeBufferingPreview() {
    MaterialTheme {
        MiniPlayerChrome(
            track = Track("3", "Buffering Track", "Loading Channel", 240, ""),
            isPlaying = false,
            isBuffering = true,
            progressFraction = 0f,
            onPlayPauseClick = {},
            onSkipNextClick = {},
            onExpandClick = {},
            chromeAlphaProvider = { 1f },
        )
    }
}
