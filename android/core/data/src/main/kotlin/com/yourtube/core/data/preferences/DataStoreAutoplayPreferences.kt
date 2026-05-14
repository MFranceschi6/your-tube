package com.yourtube.core.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * YT-0089 — DataStore-backed [AutoplayPreferences]. Reads/writes the single
 * `autoplay_enabled` boolean preference; this is the only place in the codebase
 * that should reference [AUTOPLAY_KEY] directly.
 */
class DataStoreAutoplayPreferences @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : AutoplayPreferences {

    override val autoplayEnabled: Flow<Boolean> = dataStore.data
        .map { prefs -> prefs[AUTOPLAY_KEY] ?: AutoplayPreferences.DEFAULT_AUTOPLAY_ENABLED }

    override suspend fun setAutoplayEnabled(enabled: Boolean) {
        dataStore.edit { it[AUTOPLAY_KEY] = enabled }
    }

    private companion object {
        val AUTOPLAY_KEY = booleanPreferencesKey("autoplay_enabled")
    }
}
