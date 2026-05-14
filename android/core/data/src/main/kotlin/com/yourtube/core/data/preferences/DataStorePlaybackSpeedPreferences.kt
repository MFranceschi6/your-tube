package com.yourtube.core.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class DataStorePlaybackSpeedPreferences @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : PlaybackSpeedPreferences {

    override val speed: Flow<Float> = dataStore.data
        .map { prefs -> prefs[SPEED_KEY] ?: PlaybackSpeedPreferences.DEFAULT_SPEED }

    override suspend fun setSpeed(speed: Float) {
        require(speed in 0.5f..2.0f) { "speed must be in 0.5..2.0, got $speed" }
        dataStore.edit { it[SPEED_KEY] = speed }
    }

    private companion object {
        val SPEED_KEY = floatPreferencesKey("playback_speed")
    }
}
