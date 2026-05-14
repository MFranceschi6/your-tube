package com.yourtube.app.pip

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.yourtube.core.player.PlayerController
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * YT-0104 — Receives PiP media-control actions broadcast by the three [android.app.RemoteAction]s
 * (previous / play-pause / next) shown in the PiP window chrome.
 *
 * Hilt cannot inject into [BroadcastReceiver] components that are not annotated with
 * `@AndroidEntryPoint` (Glance / process-lifecycle receivers). We reach into the Hilt
 * graph via [EntryPointAccessors.fromApplication], matching the pattern used by
 * [com.yourtube.app.widget.NowPlayingWidget].
 *
 * Each action dispatches a single suspend call on a fire-and-forget [CoroutineScope]. The
 * scope is backed by [SupervisorJob] so a failure on one action does not cancel siblings.
 */
class PipActionReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface PipReceiverEntryPoint {
        fun playerController(): PlayerController
    }

    // Receiver-scoped coroutine scope. Created per-receive; goAsync() is not needed
    // because the PlayerController calls are fast (in-memory state updates + forwarding to
    // the bound MediaController). The scope is not cancelled explicitly — the JVM GC
    // collects it after the receiver's onReceive returns and the launched job completes.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onReceive(context: Context, intent: Intent) {
        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            PipReceiverEntryPoint::class.java,
        )
        val playerController = entryPoint.playerController()

        when (intent.action) {
            ACTION_PIP_SKIP_PREV -> scope.launch { playerController.skipPrevious() }
            ACTION_PIP_PLAY_PAUSE -> scope.launch {
                val state = playerController.playerState.value
                if (state.isPlaying) playerController.pause() else playerController.resume()
            }
            ACTION_PIP_SKIP_NEXT -> scope.launch { playerController.skipNext() }
        }
    }

    companion object {
        const val ACTION_PIP_SKIP_PREV = "com.yourtube.pip.action.SKIP_PREV"
        const val ACTION_PIP_PLAY_PAUSE = "com.yourtube.pip.action.PLAY_PAUSE"
        const val ACTION_PIP_SKIP_NEXT = "com.yourtube.pip.action.SKIP_NEXT"
    }
}
