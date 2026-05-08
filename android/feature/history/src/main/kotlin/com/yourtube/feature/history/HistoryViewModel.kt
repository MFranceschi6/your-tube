package com.yourtube.feature.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourtube.core.data.model.PlaybackHistoryEntry
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
 * History-screen state machine per the cross-platform state catalog
 * (`design-system/handoff/state-catalog/`).
 *
 * - [Loading] — C12: waiting on the first repository emission.
 * - [Empty]   — C13: history is empty (no track has played yet).
 * - [Content] — at least one playback entry.
 * - [Error]   — C14: the history flow surfaced an exception.
 */
sealed interface HistoryUiState {
    data object Loading : HistoryUiState
    data object Empty : HistoryUiState
    data class Content(val entries: List<PlaybackHistoryEntry>) : HistoryUiState
    data class Error(val cause: Throwable? = null) : HistoryUiState
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val repository: PlaylistRepository,
) : ViewModel() {

    /** Bumped by [retry] to re-subscribe to [PlaylistRepository.observeHistory]. */
    private val retryTick = MutableStateFlow(0)

    val uiState: StateFlow<HistoryUiState> = retryTick
        .flatMapLatest {
            repository.observeHistory()
                .map<_, HistoryUiState> { entries ->
                    if (entries.isEmpty()) HistoryUiState.Empty
                    else HistoryUiState.Content(entries)
                }
                .catch { emit(HistoryUiState.Error(it)) }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, HistoryUiState.Loading)

    /** Catalog C14 retry contract: re-fetch the history list. */
    fun retry() {
        retryTick.value += 1
    }

    fun clearHistory() {
        viewModelScope.launch { repository.clearHistory() }
    }

    fun removeEntry(entryId: String) {
        viewModelScope.launch { repository.removeHistoryEntry(entryId) }
    }
}
