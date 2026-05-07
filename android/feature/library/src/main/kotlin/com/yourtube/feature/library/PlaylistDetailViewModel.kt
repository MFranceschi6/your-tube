package com.yourtube.feature.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourtube.core.common.model.Playlist
import com.yourtube.core.data.codec.PlaylistCodec
import com.yourtube.core.data.repository.PlaylistRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class PlaylistDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: PlaylistRepository,
    private val codec: PlaylistCodec,
) : ViewModel() {

    private val playlistId: String = checkNotNull(savedStateHandle["playlistId"])

    val playlist: StateFlow<Playlist?> = repository.observePlaylist(playlistId)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    data class SharePayload(val playlistName: String, val payload: String)

    fun encodeForShare(): SharePayload? {
        val current = playlist.value ?: return null
        return SharePayload(current.name, codec.export(current))
    }

    fun removeTrack(position: Int) {
        viewModelScope.launch { repository.removeTrackFromPlaylist(playlistId, position) }
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
