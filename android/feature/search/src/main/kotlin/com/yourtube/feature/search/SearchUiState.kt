package com.yourtube.feature.search

import com.yourtube.core.common.model.SearchResult

/**
 * Search-screen state machine per the cross-platform state catalog
 * (`design-system/handoff/state-catalog/`).
 *
 * - [Idle]   — C2: search tab opened, no query yet (suggestion chips visible).
 * - [Loading]— C1: query submitted, waiting on the network (skeleton ×6).
 * - [Empty]  — C3: query returned zero results (the [query] is rendered
 *              verbatim by the screen).
 * - [Content]— success path with one or more results.
 * - [Error]  — C4 (`offline = false`) or C5 (`offline = true`).
 *
 * Idle is distinct from Empty: Idle = no submission yet, Empty = submission
 * with zero matches.
 */
sealed interface SearchUiState {
    data object Idle : SearchUiState
    data object Loading : SearchUiState
    data class Empty(val query: String) : SearchUiState
    data class Content(val results: List<SearchResult>) : SearchUiState
    data class Error(val offline: Boolean) : SearchUiState
}
