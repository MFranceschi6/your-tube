package com.yourtube.core.ui

/**
 * Canonical list-driven screen state per the cross-platform empty/loading/error
 * state catalog (see `design-system/handoff/state-catalog/`). Every list-driven
 * screen renders exactly one of [Loading], [Empty], [Content], or [Error] at
 * any time — never two simultaneously.
 *
 * - [Loading]: initial load; UI shows a skeleton (spinner only for opaque ops).
 * - [Empty]: request succeeded with zero items.
 * - [Content]: request succeeded with one or more items.
 * - [Error]: request failed; UI surfaces a retry affordance.
 *
 * Refresh-while-content keeps the existing [Content] in place and surfaces a
 * subtle inline progress indicator — *not* a state swap back to [Loading].
 */
sealed interface ListUiState<out T> {
    data object Loading : ListUiState<Nothing>
    data object Empty : ListUiState<Nothing>
    data class Content<T>(val items: T) : ListUiState<T>
    data class Error(val cause: Throwable? = null) : ListUiState<Nothing>
}

/**
 * Convenience mapper for repositories that emit a list shape: empty list maps
 * to [ListUiState.Empty], non-empty to [ListUiState.Content]. Failures must be
 * mapped explicitly by callers via `.catch { emit(ListUiState.Error(it)) }`.
 */
fun <T : List<*>> T.toListUiState(): ListUiState<T> =
    if (isEmpty()) ListUiState.Empty else ListUiState.Content(this)
