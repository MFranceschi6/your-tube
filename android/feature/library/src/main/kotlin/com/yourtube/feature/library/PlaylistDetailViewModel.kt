package com.yourtube.feature.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourtube.core.common.model.Playlist
import com.yourtube.core.data.codec.PlaylistCodec
import com.yourtube.core.data.repository.PlaylistRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Playlist-detail state machine per the cross-platform state catalog
 * (`design-system/handoff/state-catalog/`).
 *
 * - [Loading] — C9: initial load; UI shows a skeleton header + skeleton rows.
 * - [Empty]   — C10: the playlist exists but has zero tracks. The header
 *               (cover/name) still renders so the destination keeps its
 *               identity.
 * - [Content] — playlist with one or more tracks.
 * - [Error]   — C11: the observe-by-id flow surfaced an exception, OR the
 *               flow emitted `null` after a non-null value (the row was
 *               deleted out from under us).
 */
sealed interface PlaylistDetailUiState {
    data object Loading : PlaylistDetailUiState
    data class Empty(val playlist: Playlist) : PlaylistDetailUiState
    data class Content(val playlist: Playlist) : PlaylistDetailUiState
    data object Error : PlaylistDetailUiState
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PlaylistDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: PlaylistRepository,
    private val codec: PlaylistCodec,
) : ViewModel() {

    private val playlistId: String = checkNotNull(savedStateHandle["playlistId"])

    /** Bumped by [retry] to re-subscribe to [PlaylistRepository.observePlaylist]. */
    private val retryTick = MutableStateFlow(0)

    /**
     * Legacy: kept for any caller still reading the raw nullable playlist. New
     * code should consume [state] which carries the catalog-aligned states.
     *
     * `.catch { emit(null) }` is required because the new error-state mapping
     * tests deliberately route a throwing flow into both this stream and
     * [state]; without the catch, the exception would propagate out of the
     * eager `stateIn` and crash the test (and any consumer that isn't ready
     * for it). Existing callers already treat `null` as "no playlist", which
     * is the right fallback when collection fails.
     */
    val playlist: StateFlow<Playlist?> = repository.observePlaylist(playlistId)
        .catch { emit(null) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * Pragmatic null-handling: the `observePlaylist` flow primes with `null`
     * before Room returns the first row. The first non-null emission flips a
     * latch; only later `null`s indicate the playlist was deleted (→ Error).
     * Earlier `null`s stay [PlaylistDetailUiState.Loading].
     */
    val state: StateFlow<PlaylistDetailUiState> = retryTick
        .flatMapLatest {
            var seenNonNull = false
            repository.observePlaylist(playlistId)
                .map<_, PlaylistDetailUiState> { p ->
                    when {
                        p == null && seenNonNull -> PlaylistDetailUiState.Error
                        p == null -> PlaylistDetailUiState.Loading
                        else -> {
                            seenNonNull = true
                            if (p.tracks.isEmpty()) PlaylistDetailUiState.Empty(p)
                            else PlaylistDetailUiState.Content(p)
                        }
                    }
                }
                .catch { emit(PlaylistDetailUiState.Error) }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, PlaylistDetailUiState.Loading)

    /** Catalog C11 retry contract: re-fetch the playlist by ID. */
    fun retry() {
        retryTick.value += 1
    }

    data class SharePayload(val playlistName: String, val payload: String)

    fun encodeForShare(): SharePayload? {
        val current = playlist.value ?: return null
        return SharePayload(current.name, codec.export(current))
    }

    fun removeTrack(position: Int) {
        viewModelScope.launch { repository.removeTrackFromPlaylist(playlistId, position) }
    }

    /**
     * YT-0184 — content-keyed swipe-to-remove. Indices captured by `SwipeToDismissBox`
     * become stale the instant another concurrent swipe completes; routing through
     * `videoId` keeps the request stable so two simultaneous removals don't crash on
     * an out-of-range position.
     */
    fun removeTrack(track: com.yourtube.core.common.model.Track) {
        viewModelScope.launch { repository.removeTrackFromPlaylist(playlistId, track.videoId) }
    }

    // ── YT-0063a v2 Q3 — edit-mode remove with toast-undo ────────────────────

    /**
     * Pending removal: a track optimistically removed from the UI but whose Room
     * delete has not yet been committed. The [commitJob] fires after the snackbar
     * window expires; cancelling it aborts the delete for Undo.
     */
    data class PendingRemoval(
        val trackId: String,
        val oldPosition: Int,
        val track: com.yourtube.core.common.model.Track,
        val commitJob: Job,
    )

    /**
     * Live set of pending removals, keyed by `trackId`. The UI removes these rows
     * optimistically from its local list; committed when the snackbar expires.
     */
    private val _pendingRemovals = MutableStateFlow<Map<String, PendingRemoval>>(emptyMap())
    val pendingRemovals: StateFlow<Map<String, PendingRemoval>> = _pendingRemovals

    /**
     * YT-0063a v2 Q3 — optimistically queue a removal. The commit coroutine fires
     * after [commitDelayMs] if not cancelled by [undoRemoval]. The Room delete is
     * committed there.
     *
     * @param track       the track being removed
     * @param oldPosition its current index in the playlist (before removal)
     * @param commitDelayMs delay before the Room delete fires (defaults to 4 s to
     *                      match `SnackbarDuration.Short`)
     */
    fun queueRemoval(
        track: com.yourtube.core.common.model.Track,
        oldPosition: Int,
        commitDelayMs: Long = 4_000L,
    ) {
        // Cancel existing job for this track (double-tap guard)
        _pendingRemovals.value[track.videoId]?.commitJob?.cancel()

        val newJob = viewModelScope.launch {
            delay(commitDelayMs)
            commitRemoval(track.videoId)
        }

        _pendingRemovals.update { current ->
            // Re-arm all existing pending jobs to a shared deadline (now + commitDelayMs)
            // so the entire visible batch remains undoable until the new snackbar expires.
            val rearmed = current.mapValues { (_, pending) ->
                pending.commitJob.cancel()
                val job = viewModelScope.launch {
                    delay(commitDelayMs)
                    commitRemoval(pending.trackId)
                }
                pending.copy(commitJob = job)
            }
            rearmed + (track.videoId to PendingRemoval(track.videoId, oldPosition, track, newJob))
        }
    }

    /**
     * YT-0063a v2 Q3 — undo a pending removal. Cancels the commit job; the track
     * was never deleted from Room so no re-insert is needed.
     */
    fun undoRemoval(trackId: String) {
        val pending = _pendingRemovals.value[trackId] ?: return
        pending.commitJob.cancel()
        _pendingRemovals.update { it - trackId }
    }

    /**
     * YT-0063a v2 Q3 — commit all pending removals immediately (e.g. on "Done"
     * exit from edit mode). This fires Room deletes for all items in the window.
     */
    fun commitAllPendingRemovals() {
        val snapshot = _pendingRemovals.value.values.toList()
        val snapshotKeys = snapshot.map { it.trackId }.toSet()
        _pendingRemovals.update { it - snapshotKeys }
        snapshot.forEach { pending ->
            pending.commitJob.cancel()
            viewModelScope.launch {
                repository.removeTrackFromPlaylist(playlistId, pending.trackId)
            }
        }
    }

    private suspend fun commitRemoval(trackId: String) {
        _pendingRemovals.update { it - trackId }
        repository.removeTrackFromPlaylist(playlistId, trackId)
    }

    fun reorderTracks(fromIndex: Int, toIndex: Int) {
        viewModelScope.launch { repository.reorderTracks(playlistId, fromIndex, toIndex) }
    }

    fun renamePlaylist(newName: String) {
        viewModelScope.launch { repository.renamePlaylist(playlistId, newName.trim()) }
    }

    fun deletePlaylist() {
        viewModelScope.launch { repository.deletePlaylist(playlistId) }
    }
}
