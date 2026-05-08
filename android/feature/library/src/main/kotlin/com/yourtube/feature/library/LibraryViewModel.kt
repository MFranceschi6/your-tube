package com.yourtube.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val repository: PlaylistRepository,
    private val codec: PlaylistCodec,
) : ViewModel() {

    /**
     * Bumped by [retry] to re-resubscribe to [PlaylistRepository.observePlaylists].
     * Catalog retry contract: re-run the exact request that produced the error,
     * not a full re-init of the screen.
     */
    private val retryTick = MutableStateFlow(0)

    val uiState: StateFlow<LibraryUiState> = retryTick
        .flatMapLatest {
            repository.observePlaylists()
                .map<_, LibraryUiState> { playlists ->
                    if (playlists.isEmpty()) LibraryUiState.Empty
                    else LibraryUiState.Content(playlists)
                }
                .catch { emit(LibraryUiState.Error(it)) }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, LibraryUiState.Loading)

    fun createPlaylist(name: String) {
        viewModelScope.launch { repository.createPlaylist(name.trim()) }
    }

    fun renamePlaylist(playlistId: String, newName: String) {
        viewModelScope.launch { repository.renamePlaylist(playlistId, newName.trim()) }
    }

    fun deletePlaylist(playlistId: String) {
        viewModelScope.launch { repository.deletePlaylist(playlistId) }
    }

    /** Re-subscribe to the playlists flow after a [LibraryUiState.Error]. */
    fun retry() {
        retryTick.value += 1
    }

    /**
     * YT-0156 — encode a playlist for sharing. Reuses [PlaylistDetailViewModel.SharePayload]
     * so `AppShell`'s existing `PlaylistShareLauncher` handoff works for both entry points
     * unchanged. Returns `null` when the playlist is no longer in the snapshot (e.g. deleted
     * between menu open and tap).
     */
    fun encodeForShare(playlistId: String): PlaylistDetailViewModel.SharePayload? {
        val current = uiState.value as? LibraryUiState.Content ?: return null
        val playlist = current.playlists.firstOrNull { it.id == playlistId } ?: return null
        return PlaylistDetailViewModel.SharePayload(playlist.name, codec.export(playlist))
    }
}
