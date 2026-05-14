package com.yourtube.app.widget

import android.content.Context
import android.content.Intent
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.layout.wrapContentHeight
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.yourtube.app.MainActivity
import com.yourtube.core.data.repository.PlayerSnapshotRepository
import com.yourtube.core.database.entity.PlayerSnapshotEntity
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * YT-0100 / YT-0274 — home-screen widget showing the last-known now-playing track.
 *
 * State is loaded via a one-shot [PlayerSnapshotRepository.loadSnapshot] in
 * [provideGlance]. The widget is refreshed by [NowPlayingWidgetReceiver] whenever
 * [PlaybackService] calls [updateAll] after a track change.
 *
 * Hilt cannot inject into Glance workers directly; we reach into the Hilt graph via
 * [EntryPoint] so the repository is resolved from the application component.
 *
 * Two responsive buckets are registered:
 *   - Small (140×56 dp, 2×1 cells): title only, no thumbnail
 *   - Large (250×84 dp, 4×2 cells): title + channel + thumbnail placeholder
 */
class NowPlayingWidget : GlanceAppWidget() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface NowPlayingWidgetEntryPoint {
        fun playerSnapshotRepository(): PlayerSnapshotRepository
    }

    override val sizeMode = SizeMode.Responsive(
        setOf(
            DpSize(140.dp, 56.dp),  // small: 2×1
            DpSize(250.dp, 84.dp),  // large: 4×2
        ),
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            NowPlayingWidgetEntryPoint::class.java,
        )
        val snapshot: PlayerSnapshotEntity? =
            runCatching { entryPoint.playerSnapshotRepository().loadSnapshot() }
                .getOrNull()

        provideContent {
            GlanceTheme {
                NowPlayingWidgetContent(
                    snapshot = snapshot,
                    onTap = actionStartActivity(
                        Intent(context, MainActivity::class.java).apply {
                            action = DEEP_LINK_ACTION_PLAYER
                            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                        },
                    ),
                )
            }
        }
    }

    companion object {
        /** Intent action placed on the launch intent so MainActivity can navigate to player. */
        const val DEEP_LINK_ACTION_PLAYER = "com.yourtube.action.OPEN_NOW_PLAYING"

        // Responsive size buckets — referenced in tests to avoid duplicating the threshold.
        internal val SIZE_SMALL = DpSize(140.dp, 56.dp)
        internal val SIZE_LARGE = DpSize(250.dp, 84.dp)

        /** Width threshold below which only the title is shown (no channel / thumbnail). */
        internal val SMALL_WIDTH_THRESHOLD = 200.dp
    }
}

/**
 * Stateless Glance composable that renders the widget surface.
 *
 * Reads [LocalSize] to branch between a compact single-line layout (width < 200 dp)
 * and a full layout that includes channel name and a thumbnail placeholder.
 *
 * Shows [PlayerSnapshotEntity.title] / [PlayerSnapshotEntity.channelName] when a track
 * is active, or a "Nothing playing" fallback when [snapshot] is null or has no
 * [PlayerSnapshotEntity.currentVideoId].
 *
 * Colors come exclusively from [GlanceTheme.colors] — no hardcoded hex values.
 *
 * The thumbnail is an intentional placeholder box until Coil 3 + GlanceImage integration
 * (tracked as a follow-up task).
 */
@androidx.compose.runtime.Composable
internal fun NowPlayingWidgetContent(
    snapshot: PlayerSnapshotEntity?,
    onTap: Action,
) {
    val hasTrack = snapshot?.currentVideoId != null
    val title = snapshot?.title ?: "Unknown track"
    val channel = snapshot?.channelName ?: ""
    val isSmall = LocalSize.current.width < NowPlayingWidget.SMALL_WIDTH_THRESHOLD

    val widgetContentDescription = if (hasTrack) {
        "Now playing: $title by $channel. Tap to open player."
    } else {
        "No track playing. Tap to open player."
    }

    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.widgetBackground)
            .semantics { contentDescription = widgetContentDescription }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            modifier = GlanceModifier
                .fillMaxWidth()
                .wrapContentHeight(),
            verticalAlignment = Alignment.Vertical.CenterVertically,
        ) {
            // Thumbnail placeholder — only shown in large layout.
            // Solid tinted box until Coil/GlanceImage integration.
            if (!isSmall) {
                Box(
                    modifier = GlanceModifier
                        .size(56.dp)
                        .background(GlanceTheme.colors.primary),
                ) {}
                Spacer(modifier = GlanceModifier.width(12.dp))
            }

            Column(
                modifier = GlanceModifier
                    .defaultWeight()
                    .wrapContentHeight(),
            ) {
                if (hasTrack) {
                    Text(
                        text = title,
                        style = TextStyle(
                            color = GlanceTheme.colors.onBackground,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                        maxLines = 1,
                    )
                    if (!isSmall && channel.isNotEmpty()) {
                        Spacer(modifier = GlanceModifier.size(2.dp))
                        Text(
                            text = channel,
                            style = TextStyle(
                                color = GlanceTheme.colors.onSurfaceVariant,
                                fontSize = 12.sp,
                            ),
                            maxLines = 1,
                        )
                    }
                } else {
                    Text(
                        text = "Nothing playing",
                        style = TextStyle(
                            color = GlanceTheme.colors.onSurfaceVariant,
                            fontSize = 14.sp,
                        ),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
