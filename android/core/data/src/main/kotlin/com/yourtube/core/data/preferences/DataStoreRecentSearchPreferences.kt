package com.yourtube.core.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * DataStore-backed [RecentSearchPreferences]. Stores the list as a JSON-
 * encoded string under [RECENTS_KEY] in the shared `settings` DataStore.
 *
 * The key does not collide with any existing keys in `AppModule`.
 */
class DataStoreRecentSearchPreferences @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : RecentSearchPreferences {

    private val json = Json { ignoreUnknownKeys = true }

    override fun getRecentSearches(): Flow<List<String>> = dataStore.data
        .map { prefs ->
            val raw = prefs[RECENTS_KEY] ?: return@map emptyList()
            try {
                json.decodeFromString<List<String>>(raw)
            } catch (_: Exception) {
                emptyList()
            }
        }

    override suspend fun addRecentSearch(query: String) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return
        dataStore.edit { prefs ->
            val current = decode(prefs[RECENTS_KEY])
            val deduped = current.filterNot { it.equals(trimmed, ignoreCase = true) }
            val updated = (listOf(trimmed) + deduped).take(RecentSearchPreferences.MAX_RECENTS)
            prefs[RECENTS_KEY] = json.encodeToString(updated)
        }
    }

    override suspend fun removeRecentSearch(query: String) {
        dataStore.edit { prefs ->
            val current = decode(prefs[RECENTS_KEY])
            val updated = current.filterNot { it.equals(query, ignoreCase = true) }
            prefs[RECENTS_KEY] = json.encodeToString(updated)
        }
    }

    override suspend fun deleteAll() {
        dataStore.edit { prefs ->
            prefs.remove(RECENTS_KEY)
        }
    }

    private fun decode(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            json.decodeFromString(raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private companion object {
        val RECENTS_KEY = stringPreferencesKey("recent_searches")
    }
}
