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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
