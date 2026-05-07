package com.yourtube.feature.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourtube.core.data.model.PlaybackHistoryEntry
import com.yourtube.core.data.repository.PlaylistRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface HistoryUiState {
    data object Loading : HistoryUiState
    data class Success(val entries: List<PlaybackHistoryEntry>) : HistoryUiState
}

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val repository: PlaylistRepository,
) : ViewModel() {

    val uiState: StateFlow<HistoryUiState> = repository.observeHistory()
        .map<_, HistoryUiState> { HistoryUiState.Success(it) }
        .catch { emit(HistoryUiState.Success(emptyList())) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, HistoryUiState.Loading)

    fun clearHistory() {
        viewModelScope.launch { repository.clearHistory() }
    }

    fun removeEntry(entryId: String) {
        viewModelScope.launch { repository.removeHistoryEntry(entryId) }
    }
}
