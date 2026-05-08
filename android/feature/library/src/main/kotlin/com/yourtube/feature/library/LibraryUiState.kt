package com.yourtube.feature.library

import com.yourtube.core.common.model.Playlist

/**
 * Library-screen state machine per the cross-platform state catalog
 * (`design-system/handoff/state-catalog/`).
 *
 * - [Loading] — C6: waiting on the first repository emission.
 * - [Empty]   — C7: the playlist list is empty.
 * - [Content] — Library has at least one playlist.
 * - [Error]   — C8: the playlists flow surfaced an exception.
 *
 * The Recently Played row is a separate destination and stays visible above
 * [Empty] / [Content] — it is not part of this state machine.
 */
sealed interface LibraryUiState {
    data object Loading : LibraryUiState
    data object Empty : LibraryUiState
    data class Content(val playlists: List<Playlist>) : LibraryUiState
    data class Error(val cause: Throwable? = null) : LibraryUiState
}
