package com.yourtube.feature.library

import com.yourtube.core.common.model.Playlist

sealed interface LibraryUiState {
    data object Loading : LibraryUiState
    data class Success(val playlists: List<Playlist>) : LibraryUiState
    data class Error(val message: String) : LibraryUiState
}
