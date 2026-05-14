package com.yourtube.core.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * YT-0102 — DataStore-backed [AmoledPreferences].
 *
 * Reads/writes the single `amoled_black_enabled` boolean preference. Shares
 * the same `Preferences` instance as the other `DataStore*Preferences` types;
 * keys do not collide.
 */
class DataStoreAmoledPreferences @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : AmoledPreferences {

    override val amoledBlackEnabled: Flow<Boolean> = dataStore.data
        .map { prefs -> prefs[AMOLED_BLACK_KEY] ?: AmoledPreferences.DEFAULT_AMOLED_BLACK }

    override suspend fun setAmoledBlackEnabled(enabled: Boolean) {
        dataStore.edit { it[AMOLED_BLACK_KEY] = enabled }
    }

    private companion object {
        val AMOLED_BLACK_KEY = booleanPreferencesKey("amoled_black_enabled")
    }
}
