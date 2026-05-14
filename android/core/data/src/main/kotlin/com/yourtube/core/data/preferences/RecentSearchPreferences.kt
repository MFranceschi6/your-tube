package com.yourtube.core.data.preferences

import kotlinx.coroutines.flow.Flow

/**
 * Typed accessor for the user's recent search queries.
 *
 * - Bounded at [MAX_RECENTS] entries.
 * - Dedup is case-insensitive; the new casing wins on insert.
 * - Insertion order is newest-first (head insertion).
 */
interface RecentSearchPreferences {

    /** Ordered list of recent search queries, newest first. Never exceeds [MAX_RECENTS]. */
    fun getRecentSearches(): Flow<List<String>>

    /**
     * Inserts [query] at the head of the list, removing any prior entry that
     * matches case-insensitively, then caps the list to [MAX_RECENTS].
     * Blank queries are ignored.
     */
    suspend fun addRecentSearch(query: String)

    /** Removes the entry that equals [query] (case-insensitive). No-op when absent. */
    suspend fun removeRecentSearch(query: String)

    /** Clears all recent search entries. */
    suspend fun deleteAll()

    companion object {
        const val MAX_RECENTS = 20
    }
}
