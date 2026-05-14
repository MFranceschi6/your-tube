package com.yourtube.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourtube.core.common.haptics.HapticsController
import com.yourtube.core.common.model.PlayerState
import com.yourtube.core.common.model.SleepTimerPreset
import com.yourtube.core.common.model.SleepTimerState
import com.yourtube.core.common.model.Track
import com.yourtube.core.player.PlaybackPerfTracer
import com.yourtube.core.player.PlayerController
import com.yourtube.core.player.SleepTimerController
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val playerController: PlayerController,
    private val perfTracer: PlaybackPerfTracer,
    private val hapticsController: HapticsController,
    private val sleepTimerController: SleepTimerController,
) : ViewModel() {
    // YT-0285 — expose the singleton controller's StateFlow directly. The controller is
    // @Singleton and its MutableStateFlow always holds the latest value, so wrapping with
    // stateIn(WhileSubscribed) is unnecessary and causes tests that read .value directly
    // (without an active collector) to observe stale initial state. Direct exposure also
    // removes a subscription layer, making state propagation simpler and test-predictable.
    val playerState: StateFlow<PlayerState> = playerController.playerState

    /** YT-0093 — live sleep-timer state surfaced from the singleton controller. */
    val sleepTimerState: StateFlow<SleepTimerState> = sleepTimerController.timerState

    fun playNow(track: Track) {
        // markTap fires synchronously on the UI thread before the coroutine
        // is scheduled so the elapsed-ms baseline includes any dispatcher
        // delay between tap and resolve.
        perfTracer.markTap(track.videoId, source = "trackTap")
        viewModelScope.launch {
            playerController.playNow(track)
        }
    }

    fun addToQueue(track: Track) {
        hapticsController.onQueueAdd()
        viewModelScope.launch {
            playerController.addToQueue(track)
        }
    }

    /**
     * Plays a list of tracks via the controller's [PlayerController.setQueueAndPlay]
     * primitive. Used by PlaylistDetail's Play / Shuffle action row (YT-0063a Q4) and the
     * Spotify-style tap-to-play-row entry point (YT-0155). `shuffle = true` randomizes the
     * order one-shot before queuing — this is *not* a persistent shuffle mode (that's Q11
     * territory). [startIndex] is ignored when shuffling. No-op on empty input.
     */
    fun playList(tracks: List<Track>, startIndex: Int = 0, shuffle: Boolean = false) {
        if (tracks.isEmpty()) return
        val ordered = if (shuffle) tracks.shuffled() else tracks
        val safeStart = if (shuffle) 0 else startIndex.coerceIn(0, ordered.lastIndex)
        // Source label distinguishes the playlist entry-points so the
        // perf log can correlate first-note latency with origin.
        val source = when {
            shuffle -> "shufflePlaylist"
            startIndex != 0 -> "playPlaylistAt"
            else -> "playPlaylist"
        }
        perfTracer.markTap(ordered[safeStart].videoId, source = source)
        viewModelScope.launch {
            playerController.setQueueAndPlay(ordered, safeStart)
        }
    }

    fun playNext(track: Track) {
        hapticsController.onPlayNext()
        viewModelScope.launch {
            playerController.playNext(track)
        }
    }

    fun pause() {
        hapticsController.onPlayPause()
        viewModelScope.launch {
            playerController.pause()
        }
    }

    fun resume() {
        hapticsController.onPlayPause()
        // Resume reuses the current track — no new extraction round-trip
        // expected, so the perf timeline will be (nearly) flat. We still
        // mark the tap so the user can see "resume" perf vs "fresh play"
        // perf side by side.
        playerState.value.currentTrack?.videoId?.let { videoId ->
            perfTracer.markTap(videoId, source = "miniPlayerPlayPause")
        }
        viewModelScope.launch {
            playerController.resume()
        }
    }

    fun seekTo(positionMs: Long) {
        viewModelScope.launch {
            playerController.seekTo(positionMs)
        }
    }

    fun skipNext() {
        hapticsController.onSkip()
        // Best-effort videoId lookup: the controller will clamp/no-op if the
        // queue cursor is at the end. Logging the upcoming track keeps the
        // perf line correlated with the actual extraction below.
        val state = playerState.value
        val nextTrack = state.queue.getOrNull(state.currentQueueIndex + 1)?.track
        nextTrack?.videoId?.let { videoId ->
            perfTracer.markTap(videoId, source = "skipNext")
        }
        viewModelScope.launch {
            playerController.skipNext()
        }
    }

    fun skipPrevious() {
        hapticsController.onSkip()
        val state = playerState.value
        val previousTrack = state.queue.getOrNull(state.currentQueueIndex - 1)?.track
        // skipPrevious sometimes restarts the current track instead of going
        // back (RESTART_THRESHOLD_MS in DefaultPlayerController). Log the
        // current track as a fallback so the marker is still informative.
        val videoId = previousTrack?.videoId ?: state.currentTrack?.videoId
        videoId?.let { perfTracer.markTap(it, source = "skipPrevious") }
        viewModelScope.launch {
            playerController.skipPrevious()
        }
    }

    fun removeQueueItem(queueId: String) {
        viewModelScope.launch {
            playerController.removeQueueItem(queueId)
        }
    }

    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        viewModelScope.launch {
            playerController.moveQueueItem(fromIndex, toIndex)
        }
    }

    /** YT-0307 — jump to a queue entry by index without clearing the queue. */
    fun jumpToQueueItem(index: Int) {
        val track = playerState.value.queue.getOrNull(index)?.track ?: return
        perfTracer.markTap(track.videoId, source = "jumpToQueueItem")
        viewModelScope.launch {
            playerController.jumpToQueueItem(index)
        }
    }

    /**
     * Persistent shuffle toggle (YT-0062a Q11). Routes through the controller so a UI tap
     * and a system-media-controls write hit the same call path. The controller updates
     * `playerState.shuffleOn` synchronously so the UI reflects intent without waiting on
     * the engine round-trip.
     */
    fun setShuffleMode(enabled: Boolean) {
        viewModelScope.launch {
            playerController.setShuffleMode(enabled)
        }
    }

    /**
     * Persistent repeat-mode write (YT-0062a Q11). Modes match `androidx.media3.common.Player`
     * constants (`REPEAT_MODE_OFF` = 0, `REPEAT_MODE_ONE` = 1, `REPEAT_MODE_ALL` = 2).
     */
    fun setRepeatMode(mode: Int) {
        viewModelScope.launch {
            playerController.setRepeatMode(mode)
        }
    }

    /**
     * YT-0095 — set playback speed. Controller clamps to 0.5–2.0, updates [playerState], and
     * persists the selection so subsequent track loads re-apply the rate automatically.
     */
    fun setPlaybackSpeed(speed: Float) {
        viewModelScope.launch {
            playerController.setPlaybackSpeed(speed)
        }
    }

    /**
     * YT-0093 — schedule (or replace) the sleep timer with [preset]. The timer runs in the
     * PlaybackService's CoroutineScope so it survives backgrounding. Calling this while a
     * timer is already active replaces it.
     */
    fun setSleepTimer(preset: SleepTimerPreset) {
        sleepTimerController.setTimer(preset)
    }

    /**
     * YT-0093 — cancel the active sleep timer. No-op when already inactive.
     */
    fun cancelSleepTimer() {
        sleepTimerController.cancel()
    }
}
