package com.yourtube.app.widget

import androidx.glance.appwidget.GlanceAppWidgetReceiver

/**
 * YT-0100 — [GlanceAppWidgetReceiver] entry point for the Now Playing home-screen widget.
 *
 * Declared in AndroidManifest.xml with the `android.appwidget.action.APPWIDGET_UPDATE`
 * intent filter and the `@xml/now_playing_widget_info` provider metadata.
 *
 * Widget refresh on track change is driven by [PlaybackService] calling
 * [NowPlayingWidget.updateAll] from its service scope whenever the current track changes.
 */
class NowPlayingWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: NowPlayingWidget = NowPlayingWidget()
}
