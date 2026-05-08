package com.yourtube.core.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * DataStore-backed [PlaybackLifecyclePreferences]. Reads/writes the single
 * `playback_stop_on_task_removed` boolean preference; this is the only place
 * in the codebase that should reference [STOP_ON_TASK_REMOVED_KEY] directly.
 *
 * Shares the same `Preferences` instance as [DataStoreAudioQualityPreferences];
 * keys do not collide.
 */
class DataStorePlaybackLifecyclePreferences @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : PlaybackLifecyclePreferences {

    override val stopOnTaskRemoved: Flow<Boolean> = dataStore.data
        .map { prefs ->
            prefs[STOP_ON_TASK_REMOVED_KEY]
                ?: PlaybackLifecyclePreferences.DEFAULT_STOP_ON_TASK_REMOVED
        }

    override suspend fun setStopOnTaskRemoved(value: Boolean) {
        dataStore.edit { it[STOP_ON_TASK_REMOVED_KEY] = value }
    }

    private companion object {
        val STOP_ON_TASK_REMOVED_KEY = booleanPreferencesKey("playback_stop_on_task_removed")
    }
}
