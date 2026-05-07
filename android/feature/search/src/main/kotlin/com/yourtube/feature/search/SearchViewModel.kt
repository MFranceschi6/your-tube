package com.yourtube.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourtube.core.network.YoutubeService
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val youtubeService: YoutubeService,
) : ViewModel() {

    private val _uiState = MutableStateFlow<SearchUiState>(SearchUiState.Idle)
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    fun onQueryChange(query: String) {
        _query.value = query
    }

    fun search() {
        val q = _query.value.trim()
        if (q.isEmpty()) return
        viewModelScope.launch {
            _uiState.value = SearchUiState.Loading
            _uiState.value = try {
                val results = youtubeService.searchVideos(q)
                if (results.isEmpty()) SearchUiState.Success(emptyList())
                else SearchUiState.Success(results)
            } catch (e: Exception) {
                SearchUiState.Error(e.message ?: "Search failed")
            }
        }
    }

    fun clearQuery() {
        _query.value = ""
        _uiState.value = SearchUiState.Idle
    }
}
