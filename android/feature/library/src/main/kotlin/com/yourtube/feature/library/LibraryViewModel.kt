package com.yourtube.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourtube.core.data.repository.PlaylistRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val repository: PlaylistRepository,
) : ViewModel() {

    val uiState: StateFlow<LibraryUiState> = repository.observePlaylists()
        .map<_, LibraryUiState> { LibraryUiState.Success(it) }
        .catch { emit(LibraryUiState.Error(it.message ?: "Unknown error")) }
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
}
