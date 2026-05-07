package com.yourtube.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourtube.core.common.model.PlayerState
import com.yourtube.core.common.model.Track
import com.yourtube.core.player.PlayerController
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val playerController: PlayerController,
) : ViewModel() {
    val playerState: StateFlow<PlayerState> = playerController.playerState

    fun playNow(track: Track) {
        viewModelScope.launch {
            playerController.playNow(track)
        }
    }

    fun addToQueue(track: Track) {
        viewModelScope.launch {
            playerController.addToQueue(track)
        }
    }

    /**
     * Plays a list of tracks: the first track via `playNow`, the remainder appended to the
     * queue in order. Used by PlaylistDetail's Play / Shuffle action row (YT-0063a Q4) so
     * tapping "Play" loads the entire playlist as a queue. `shuffle = true` randomizes the
     * order one-shot before queuing — this is *not* a persistent shuffle mode (that's Q11
     * territory). No-op on empty input.
     *
     * Both calls are sequenced inside a single `viewModelScope.launch` so `playNow`'s queue
     * reset (which clears any prior queue) lands before the subsequent `addToQueue` writes.
     */
    fun playList(tracks: List<Track>, shuffle: Boolean = false) {
        if (tracks.isEmpty()) return
        val ordered = if (shuffle) tracks.shuffled() else tracks
        viewModelScope.launch {
            playerController.playNow(ordered.first())
            ordered.drop(1).forEach { playerController.addToQueue(it) }
        }
    }

    fun playNext(track: Track) {
        viewModelScope.launch {
            playerController.playNext(track)
        }
    }

    fun pause() {
        viewModelScope.launch {
            playerController.pause()
        }
    }

    fun resume() {
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
        viewModelScope.launch {
            playerController.skipNext()
        }
    }

    fun skipPrevious() {
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
}
