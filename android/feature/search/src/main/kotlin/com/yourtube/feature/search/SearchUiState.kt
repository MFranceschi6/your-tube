package com.yourtube.feature.search

import com.yourtube.core.common.model.SearchResult

sealed interface SearchUiState {
    data object Idle : SearchUiState
    data object Loading : SearchUiState
    data class Success(val results: List<SearchResult>) : SearchUiState
    data class Error(val message: String) : SearchUiState
}
