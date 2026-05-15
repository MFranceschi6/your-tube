package com.yourtube.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourtube.core.common.ConnectivityMonitor
import com.yourtube.core.data.preferences.RecentSearchPreferences
import com.yourtube.core.network.SuggestClient
import com.yourtube.core.network.YoutubeService
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val youtubeService: YoutubeService,
    private val connectivityMonitor: ConnectivityMonitor,
    private val suggestClient: SuggestClient,
    private val recentSearchPreferences: RecentSearchPreferences,
) : ViewModel() {

    private val _uiState = MutableStateFlow<SearchUiState>(SearchUiState.Idle)
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _filters = MutableStateFlow(SearchFilters())
    val filters: StateFlow<SearchFilters> = _filters.asStateFlow()

    /**
     * Controls visibility of the suggestion panel in the UI.
     *
     * Set to `true` whenever [onQueryChange] receives a non-empty value.
     * Immediately reset to `false` on [search], [onSuggestionTap], and
     * [clearQuery] so the panel disappears on submit even before the
     * [suggestions] flow emits an updated (empty) list.
     */
    private val _showSuggestions = MutableStateFlow(false)
    val showSuggestions: StateFlow<Boolean> = _showSuggestions.asStateFlow()

    /**
     * Live autocomplete suggestions from the YouTube suggest API.
     * Emits after a 200ms debounce; empty when the query is blank or the
     * device is offline. Returns empty on any network/parse error.
     *
     * Uses [SharingStarted.Eagerly] so the debounce pipeline is active as soon
     * as the ViewModel is created. In practice the debounced mapLatest only
     * executes when [_query] changes, so no network call is made at startup.
     */
    val suggestions: StateFlow<List<String>> = _query
        .debounce(SUGGEST_DEBOUNCE_MS)
        .mapLatest { q ->
            if (q.isBlank() || !connectivityMonitor.isOnline()) return@mapLatest emptyList()
            suggestClient.fetchSuggestions(q)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList(),
        )

    /**
     * Recent search queries from DataStore, newest first.
     * The UI is responsible for deduplicating against [suggestions] before
     * rendering (contract: remove recents that match a suggestion,
     * case-insensitive).
     *
     * Uses [SharingStarted.Eagerly] so the DataStore flow is subscribed as soon
     * as the ViewModel is created and recents are immediately available when the
     * screen opens — regardless of whether a UI collector is active.
     */
    val recentSearches: StateFlow<List<String>> =
        recentSearchPreferences.getRecentSearches()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = emptyList(),
            )

    /**
     * At most [MAX_CHIP_RECENTS] most-recent queries, used to populate the chip
     * strip in the idle state. Derived from [recentSearches]. YT-0326 removed the
     * curated fallback list — the strip renders recents only.
     */
    val recentSuggestions: StateFlow<List<String>> =
        recentSearchPreferences.getRecentSearches()
            .map { it.take(MAX_CHIP_RECENTS) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = emptyList(),
            )

    /**
     * The most recent submitted query. Persisted across emissions so [retry]
     * can re-issue the same request that produced the error (catalog retry
     * contract).
     */
    private var lastSubmittedQuery: String? = null

    /**
     * The `sp` param that was active when the last search was issued. Used to
     * detect identical filter/query combinations and avoid redundant requests.
     */
    private var lastSubmittedSp: String? = null

    fun onQueryChange(query: String) {
        _query.value = query
        _showSuggestions.value = query.isNotEmpty()
    }

    fun search() {
        val q = _query.value.trim()
        if (q.isEmpty()) return
        _showSuggestions.value = false
        lastSubmittedQuery = q
        lastSubmittedSp = _filters.value.sp
        viewModelScope.launch { recentSearchPreferences.addRecentSearch(q) }
        runSearch(q, _filters.value.sp)
    }

    /**
     * Fills the query field with [suggestion], runs a search, and persists the
     * suggestion to the recent-searches list.
     */
    fun onSuggestionTap(suggestion: String) {
        _query.value = suggestion
        _showSuggestions.value = false
        lastSubmittedQuery = suggestion
        lastSubmittedSp = _filters.value.sp
        viewModelScope.launch { recentSearchPreferences.addRecentSearch(suggestion) }
        runSearch(suggestion, _filters.value.sp)
    }

    /** Removes [query] from the recent-searches DataStore list. */
    fun onRemoveRecentSearch(query: String) {
        viewModelScope.launch { recentSearchPreferences.removeRecentSearch(query) }
    }

    /** Re-inserts [query] at MRU head — used by Snackbar Undo after long-press remove. */
    fun onRestoreRecentSearch(query: String) {
        viewModelScope.launch { recentSearchPreferences.addRecentSearch(query) }
    }

    /**
     * Update the active filters. If a query has already been submitted the
     * search is re-run immediately, unless the resolved `sp` param is identical
     * to the one used in the last request (avoids duplicate round-trips).
     */
    fun onFilterChange(filters: SearchFilters) {
        val previous = _filters.value
        if (filters == previous) return
        _filters.value = filters

        val q = lastSubmittedQuery ?: return
        val newSp = filters.sp
        if (newSp == lastSubmittedSp) return

        lastSubmittedSp = newSp
        runSearch(q, newSp)
    }

    /** Re-runs the most recent submitted query. No-op if nothing was submitted yet. */
    fun retry() {
        val q = lastSubmittedQuery ?: return
        runSearch(q, _filters.value.sp)
    }

    private fun runSearch(q: String, sp: String?) {
        viewModelScope.launch {
            _uiState.value = SearchUiState.Loading
            _uiState.value = try {
                val results = youtubeService.searchVideos(q, sp)
                if (results.isEmpty()) SearchUiState.Empty(q) else SearchUiState.Content(results)
            } catch (e: Exception) {
                // Catalog C4 vs. C5: only flip to "offline" when the platform
                // connectivity API confirms it. A generic timeout alone is not
                // enough — that's a server/parse error (C4).
                SearchUiState.Error(offline = !connectivityMonitor.isOnline())
            }
        }
    }

    /**
     * Receives the top transcription result from the system SpeechRecognizer.
     * Sets the query field to [text] and immediately submits a search — identical
     * to the user typing the text and pressing the IME search action.
     */
    fun onVoiceResult(text: String) {
        _query.value = text
        search()
    }

    fun clearQuery() {
        _query.value = ""
        _showSuggestions.value = false
        lastSubmittedQuery = null
        lastSubmittedSp = null
        _filters.value = SearchFilters()
        _uiState.value = SearchUiState.Idle
    }

    private companion object {
        private const val SUGGEST_DEBOUNCE_MS = 200L
        private const val MAX_CHIP_RECENTS = 6
    }
}
