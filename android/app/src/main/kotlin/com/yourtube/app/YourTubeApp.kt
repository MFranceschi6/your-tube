package com.yourtube.app

import android.app.Application
import androidx.glance.appwidget.updateAll
import com.yourtube.app.widget.NowPlayingWidget
import com.yourtube.core.player.PlayerController
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.launchIn

@HiltAndroidApp
class YourTubeApp : Application() {

    /**
     * YT-0100 — injected at Application scope so the widget-refresh observer can react to
     * track changes without binding to a specific Activity lifecycle.
     *
     * [PlayerController] is a [javax.inject.Singleton] in the Hilt graph; injecting it
     * here is safe because [YourTubeApp.onCreate] runs after the Hilt component is created.
     */
    @Inject
    lateinit var playerController: PlayerController

    /**
     * Application-lifetime scope used exclusively for the widget-refresh side effect.
     * Cancelled when the process is killed (Application.onTerminate is unreliable; process
     * death is the natural teardown path for a SupervisorJob-backed app scope).
     */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        observeTrackChangesForWidgetRefresh()
    }

    /**
     * YT-0100 — observe [PlayerController.playerState] and call [NowPlayingWidget.updateAll]
     * whenever the current video ID changes. Distinct-by `videoId` avoids spurious refreshes
     * on every position tick; null→null transitions are suppressed.
     *
     * [NowPlayingWidget.updateAll] is safe to call from a coroutine and is a no-op when no
     * widget instances are pinned to the home screen.
     */
    private fun observeTrackChangesForWidgetRefresh() {
        playerController.playerState
            .map { state -> state.currentTrack?.videoId }
            .distinctUntilChanged()
            .onEach {
                runCatching { NowPlayingWidget().updateAll(applicationContext) }
            }
            .launchIn(appScope)
    }
}

