package com.yourtube.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourtube.core.common.model.Playlist
import com.yourtube.core.common.model.Track
import com.yourtube.core.data.repository.AddTrackResult
import com.yourtube.core.data.repository.PlaylistRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One-shot UI events emitted by [AddToPlaylistViewModel]. */
sealed interface AddToPlaylistEvent {
    /** The track was added successfully — the sheet should dismiss. */
    data object TrackAdded : AddToPlaylistEvent

    /** The track was already present in the playlist — show "Already in playlist" snackbar. */
    data object AlreadyInPlaylist : AddToPlaylistEvent
}

/**
 * Backs [AddToPlaylistSheet] — exposes the user's playlists and lets the caller add a [Track]
 * to an existing playlist or create a new playlist that immediately contains the track.
 *
 * The sheet itself owns the target [Track]; this ViewModel only knows about the operations it
 * can perform against [PlaylistRepository]. Each call is fire-and-forget on [viewModelScope],
 * mirroring the pattern used in [LibraryViewModel] and [PlaylistDetailViewModel].
 *
 * When the repository rejects a duplicate add, [events] emits [AddToPlaylistEvent.AlreadyInPlaylist]
 * so the sheet can show a "Already in playlist" snackbar without dismissing.
 */
@HiltViewModel
class AddToPlaylistViewModel @Inject constructor(
    private val repository: PlaylistRepository,
) : ViewModel() {

    val playlists: StateFlow<List<Playlist>> = repository.observePlaylists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _events = Channel<AddToPlaylistEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    fun addTrack(playlistId: String, track: Track) {
        viewModelScope.launch {
            when (repository.addTrackToPlaylist(playlistId, track)) {
                AddTrackResult.Added -> _events.send(AddToPlaylistEvent.TrackAdded)
                AddTrackResult.AlreadyPresent -> _events.send(AddToPlaylistEvent.AlreadyInPlaylist)
            }
        }
    }

    fun createAndAdd(name: String, track: Track) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch { repository.createPlaylist(trimmed, listOf(track)) }
    }
}
