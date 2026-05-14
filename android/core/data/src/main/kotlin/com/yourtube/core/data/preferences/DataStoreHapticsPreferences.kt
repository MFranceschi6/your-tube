package com.yourtube.core.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class DataStoreHapticsPreferences @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : HapticsPreferences {

    override val hapticsEnabled: Flow<Boolean> = dataStore.data
        .map { prefs -> prefs[HAPTICS_KEY] ?: HapticsPreferences.DEFAULT_HAPTICS_ENABLED }

    override suspend fun setHapticsEnabled(enabled: Boolean) {
        dataStore.edit { it[HAPTICS_KEY] = enabled }
    }

    private companion object {
        val HAPTICS_KEY = booleanPreferencesKey("haptics_enabled")
    }
}
