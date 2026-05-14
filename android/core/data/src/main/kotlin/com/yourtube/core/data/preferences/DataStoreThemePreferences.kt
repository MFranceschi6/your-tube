package com.yourtube.core.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class DataStoreThemePreferences @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : ThemePreferences {

    override val themePreference: Flow<ThemePreference> = dataStore.data
        .map { prefs ->
            val stored = prefs[THEME_KEY] ?: ThemePreference.SYSTEM.name
            ThemePreference.entries.firstOrNull { it.name == stored }
                ?: ThemePreferences.DEFAULT_THEME_PREFERENCE
        }

    override suspend fun setThemePreference(preference: ThemePreference) {
        dataStore.edit { it[THEME_KEY] = preference.name }
    }

    private companion object {
        val THEME_KEY = stringPreferencesKey("theme_preference")
    }
}
