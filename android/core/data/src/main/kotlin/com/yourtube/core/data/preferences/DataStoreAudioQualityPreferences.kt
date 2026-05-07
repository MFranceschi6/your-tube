package com.yourtube.core.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * DataStore-backed [AudioQualityPreferences]. Reads/writes the single
 * `audio_quality_bitrate` int preference; this is the only place in the
 * codebase that should reference [BITRATE_KEY] directly.
 */
class DataStoreAudioQualityPreferences @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : AudioQualityPreferences {

    override val bitrateKbps: Flow<Int> = dataStore.data
        .map { prefs -> prefs[BITRATE_KEY] ?: AudioQualityPreferences.DEFAULT_BITRATE_KBPS }

    override suspend fun setBitrateKbps(kbps: Int) {
        require(kbps > 0) { "kbps must be positive, got $kbps" }
        dataStore.edit { it[BITRATE_KEY] = kbps }
    }

    private companion object {
        val BITRATE_KEY = intPreferencesKey("audio_quality_bitrate")
    }
}
