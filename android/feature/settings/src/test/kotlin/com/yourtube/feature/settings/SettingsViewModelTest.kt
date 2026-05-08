package com.yourtube.feature.settings

import app.cash.turbine.test
import com.yourtube.core.data.preferences.AudioQualityPreferences
import com.yourtube.core.data.preferences.PlaybackLifecyclePreferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
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
        val viewModel = SettingsViewModel(FakeAudioQualityPreferences(), FakePlaybackLifecyclePreferences())
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
        val viewModel = SettingsViewModel(prefs, FakePlaybackLifecyclePreferences())
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

    // YT-0241: stop-on-task-removed toggle. Default OFF, reflects upstream pref, toggle
    // writes the inverse to the pref.
    @Test
    fun `default stopOnTaskRemoved is off`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val viewModel = SettingsViewModel(
            FakeAudioQualityPreferences(),
            FakePlaybackLifecyclePreferences(),
        )
        try {
            advanceUntilIdle()
            assertEquals(false, viewModel.uiState.value.stopOnTaskRemoved)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `uiState reflects upstream stopOnTaskRemoved pref change`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val lifecyclePrefs = FakePlaybackLifecyclePreferences(initial = true)
        val viewModel = SettingsViewModel(FakeAudioQualityPreferences(), lifecyclePrefs)
        try {
            advanceUntilIdle()
            assertEquals(true, viewModel.uiState.value.stopOnTaskRemoved)
            // Simulate an external write (e.g. another surface flipping the pref). uiState
            // recomputes via combine() and the screen sees the new value.
            lifecyclePrefs.setStopOnTaskRemoved(false)
            advanceUntilIdle()
            assertEquals(false, viewModel.uiState.value.stopOnTaskRemoved)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `toggleStopOnTaskRemoved writes the inverse of current value`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val lifecyclePrefs = FakePlaybackLifecyclePreferences()
        val viewModel = SettingsViewModel(FakeAudioQualityPreferences(), lifecyclePrefs)
        try {
            advanceUntilIdle()
            // Default = false. First toggle flips to true.
            assertEquals(false, viewModel.uiState.value.stopOnTaskRemoved)
            viewModel.toggleStopOnTaskRemoved()
            advanceUntilIdle()
            assertEquals(true, lifecyclePrefs.state.value)
            assertEquals(true, viewModel.uiState.value.stopOnTaskRemoved)
            // Second toggle flips back to false.
            viewModel.toggleStopOnTaskRemoved()
            advanceUntilIdle()
            assertEquals(false, lifecyclePrefs.state.value)
            assertEquals(false, viewModel.uiState.value.stopOnTaskRemoved)
        } finally {
            Dispatchers.resetMain()
        }
    }

    // YT-0164: import-row state machine. Idle → InProgress → Idle (success);
    // Idle → InProgress → Failed (failure); Failed → Idle (dismiss).
    @Test
    fun `importRow goes Idle to InProgress to Idle on success`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val viewModel = SettingsViewModel(FakeAudioQualityPreferences(), FakePlaybackLifecyclePreferences())
        try {
            advanceUntilIdle()
            assertEquals(ImportRowState.Idle, viewModel.uiState.value.importRow)
            viewModel.onImportStarted()
            advanceUntilIdle()
            assertEquals(ImportRowState.InProgress, viewModel.uiState.value.importRow)
            viewModel.onImportSucceeded()
            advanceUntilIdle()
            assertEquals(ImportRowState.Idle, viewModel.uiState.value.importRow)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `importRow goes Idle to InProgress to Failed on failure`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val viewModel = SettingsViewModel(FakeAudioQualityPreferences(), FakePlaybackLifecyclePreferences())
        try {
            advanceUntilIdle()
            viewModel.onImportStarted()
            advanceUntilIdle()
            assertEquals(ImportRowState.InProgress, viewModel.uiState.value.importRow)
            viewModel.onImportFailed()
            advanceUntilIdle()
            assertEquals(ImportRowState.Failed, viewModel.uiState.value.importRow)
        } finally {
            Dispatchers.resetMain()
        }
    }

    // YT-0187: events-driven transition. Simulates the SettingsScreen
    // LaunchedEffect that maps a SharingViewModel-style outcome flow into the
    // VM's onImportSucceeded / onImportFailed entry points. We hand-write a
    // MutableSharedFlow fake instead of mocking the upstream channel — the
    // shape we care about is the VM transition, not the upstream type.
    @Test
    fun `events-driven InProgress to Failed flips importRow on Failure outcome`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val viewModel = SettingsViewModel(FakeAudioQualityPreferences(), FakePlaybackLifecyclePreferences())
        val events = MutableSharedFlow<SettingsImportOutcome>(
            replay = 0,
            extraBufferCapacity = 1,
        )
        // Mirror the SettingsScreen LaunchedEffect: route Success → onImportSucceeded
        // and Failure → onImportFailed. Run inside the test scope so it tears down
        // cleanly when the test ends.
        val pump = launch {
            events.collect { outcome ->
                when (outcome) {
                    SettingsImportOutcome.Success -> viewModel.onImportSucceeded()
                    SettingsImportOutcome.Failure -> viewModel.onImportFailed()
                }
            }
        }
        try {
            viewModel.uiState.test {
                // Initial Idle.
                assertEquals(ImportRowState.Idle, awaitItem().importRow)

                // Picker returned a URI → InProgress.
                viewModel.onImportStarted()
                assertEquals(ImportRowState.InProgress, awaitItem().importRow)

                // Importer surfaced a Failure (InvalidPayload / Unreadable /
                // UnsupportedSchema all map here) → Failed (renders the C16 banner).
                events.tryEmit(SettingsImportOutcome.Failure)
                advanceUntilIdle()
                assertEquals(ImportRowState.Failed, awaitItem().importRow)

                // Subsequent Success outcome on the same row clears it.
                viewModel.onImportStarted()
                assertEquals(ImportRowState.InProgress, awaitItem().importRow)
                events.tryEmit(SettingsImportOutcome.Success)
                advanceUntilIdle()
                assertEquals(ImportRowState.Idle, awaitItem().importRow)
            }
        } finally {
            pump.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `retryImport clears Failed to Idle and emits a retry request`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val viewModel = SettingsViewModel(FakeAudioQualityPreferences(), FakePlaybackLifecyclePreferences())
        try {
            advanceUntilIdle()
            viewModel.onImportFailed()
            advanceUntilIdle()
            assertEquals(ImportRowState.Failed, viewModel.uiState.value.importRow)

            viewModel.retryImportRequests.test {
                viewModel.retryImport()
                advanceUntilIdle()
                // Banner clears immediately so the user doesn't see the
                // previous failure while the picker is being re-launched.
                assertEquals(ImportRowState.Idle, viewModel.uiState.value.importRow)
                // And the screen-level collector is woken to relaunch SAF.
                awaitItem()
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `dismissImportError returns Failed to Idle`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val viewModel = SettingsViewModel(FakeAudioQualityPreferences(), FakePlaybackLifecyclePreferences())
        try {
            advanceUntilIdle()
            viewModel.onImportFailed()
            advanceUntilIdle()
            assertEquals(ImportRowState.Failed, viewModel.uiState.value.importRow)
            viewModel.dismissImportError()
            advanceUntilIdle()
            assertEquals(ImportRowState.Idle, viewModel.uiState.value.importRow)
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

    private class FakePlaybackLifecyclePreferences(
        initial: Boolean = PlaybackLifecyclePreferences.DEFAULT_STOP_ON_TASK_REMOVED,
    ) : PlaybackLifecyclePreferences {
        val state = MutableStateFlow(initial)
        override val stopOnTaskRemoved: Flow<Boolean> = state
        override suspend fun setStopOnTaskRemoved(value: Boolean) {
            state.value = value
        }
    }
}
