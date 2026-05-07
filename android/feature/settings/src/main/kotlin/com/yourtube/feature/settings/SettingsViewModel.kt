package com.yourtube.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourtube.core.data.preferences.AudioQualityPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class AudioQuality(val label: String, val maxBitrateKbps: Int) {
    LOW("Low (64 kbps)", 64),
    MEDIUM("Medium (128 kbps)", 128),
    HIGH("High (160 kbps)", 160),
    VERY_HIGH("Very high (320 kbps)", 320),
}

data class SettingsUiState(
    val audioQuality: AudioQuality = AudioQuality.HIGH,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val audioQualityPreferences: AudioQualityPreferences,
) : ViewModel() {

    val uiState: StateFlow<SettingsUiState> = audioQualityPreferences.bitrateKbps
        .map { bitrate ->
            val quality = AudioQuality.entries.firstOrNull { it.maxBitrateKbps == bitrate }
                ?: AudioQuality.HIGH
            SettingsUiState(audioQuality = quality)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsUiState())

    fun setAudioQuality(quality: AudioQuality) {
        viewModelScope.launch {
            audioQualityPreferences.setBitrateKbps(quality.maxBitrateKbps)
        }
    }
}
