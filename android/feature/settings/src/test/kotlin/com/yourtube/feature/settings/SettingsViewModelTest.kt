package com.yourtube.feature.settings

import com.yourtube.core.data.preferences.AudioQualityPreferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Test
    fun `default audio quality is HIGH`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val viewModel = SettingsViewModel(FakeAudioQualityPreferences())
        try {
            advanceUntilIdle()
            assertEquals(AudioQuality.HIGH, viewModel.uiState.value.audioQuality)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `setAudioQuality persists and uiState reflects new quality`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val prefs = FakeAudioQualityPreferences()
        val viewModel = SettingsViewModel(prefs)
        try {
            advanceUntilIdle()
            viewModel.setAudioQuality(AudioQuality.LOW)
            advanceUntilIdle()
            assertEquals(AudioQuality.LOW, viewModel.uiState.value.audioQuality)
            assertEquals(AudioQuality.LOW.maxBitrateKbps, prefs.state.value)
        } finally {
            Dispatchers.resetMain()
        }
    }

    private class FakeAudioQualityPreferences(
        initialKbps: Int = AudioQualityPreferences.DEFAULT_BITRATE_KBPS,
    ) : AudioQualityPreferences {
        val state = MutableStateFlow(initialKbps)
        override val bitrateKbps: Flow<Int> = state
        override suspend fun setBitrateKbps(kbps: Int) {
            state.value = kbps
        }
    }
}
