package com.yourtube.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourtube.core.common.ConnectivityMonitor
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
    private val connectivityMonitor: ConnectivityMonitor,
) : ViewModel() {

    private val _uiState = MutableStateFlow<SearchUiState>(SearchUiState.Idle)
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /**
     * The most recent submitted query. Persisted across emissions so [retry]
     * can re-issue the same request that produced the error (catalog retry
     * contract).
     */
    private var lastSubmittedQuery: String? = null

    fun onQueryChange(query: String) {
        _query.value = query
    }

    fun search() {
        val q = _query.value.trim()
        if (q.isEmpty()) return
        lastSubmittedQuery = q
        runSearch(q)
    }

    /** Re-runs the most recent submitted query. No-op if nothing was submitted yet. */
    fun retry() {
        val q = lastSubmittedQuery ?: return
        runSearch(q)
    }

    private fun runSearch(q: String) {
        viewModelScope.launch {
            _uiState.value = SearchUiState.Loading
            _uiState.value = try {
                val results = youtubeService.searchVideos(q)
                if (results.isEmpty()) SearchUiState.Empty(q) else SearchUiState.Content(results)
            } catch (e: Exception) {
                // Catalog C4 vs. C5: only flip to "offline" when the platform
                // connectivity API confirms it. A generic timeout alone is not
                // enough — that's a server/parse error (C4).
                SearchUiState.Error(offline = !connectivityMonitor.isOnline())
            }
        }
    }

    fun clearQuery() {
        _query.value = ""
        lastSubmittedQuery = null
        _uiState.value = SearchUiState.Idle
    }
}
