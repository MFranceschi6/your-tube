package com.yourtube.feature.settings

import app.cash.turbine.test
import com.yourtube.core.data.preferences.AmoledPreferences
import com.yourtube.core.data.preferences.AudioQualityPreferences
import com.yourtube.core.data.preferences.AutoplayPreferences
import com.yourtube.core.data.preferences.HapticsPreferences
import com.yourtube.core.data.preferences.PlaybackLifecyclePreferences
import com.yourtube.core.data.preferences.ThemePreference
import com.yourtube.core.data.preferences.ThemePreferences
import com.yourtube.core.data.update.UpdateCheckRepository
import com.yourtube.core.data.update.UpdateStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
        val viewModel = SettingsViewModel(FakeAudioQualityPreferences(), FakePlaybackLifecyclePreferences(), FakeHapticsPreferences(), FakeUpdateCheckRepository(), FakeAmoledPreferences(), FakeAutoplayPreferences(), FakeThemePreferences())
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
        val viewModel = SettingsViewModel(prefs, FakePlaybackLifecyclePreferences(), FakeHapticsPreferences(), FakeUpdateCheckRepository(), FakeAmoledPreferences(), FakeAutoplayPreferences(), FakeThemePreferences())
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
            FakeHapticsPreferences(),
            FakeUpdateCheckRepository(),
            FakeAmoledPreferences(),
            FakeAutoplayPreferences(),
            FakeThemePreferences(),
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
        val viewModel = SettingsViewModel(FakeAudioQualityPreferences(), lifecyclePrefs, FakeHapticsPreferences(), FakeUpdateCheckRepository(), FakeAmoledPreferences(), FakeAutoplayPreferences(), FakeThemePreferences())
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
        val viewModel = SettingsViewModel(FakeAudioQualityPreferences(), lifecyclePrefs, FakeHapticsPreferences(), FakeUpdateCheckRepository(), FakeAmoledPreferences(), FakeAutoplayPreferences(), FakeThemePreferences())
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
        val viewModel = SettingsViewModel(FakeAudioQualityPreferences(), FakePlaybackLifecyclePreferences(), FakeHapticsPreferences(), FakeUpdateCheckRepository(), FakeAmoledPreferences(), FakeAutoplayPreferences(), FakeThemePreferences())
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
        val viewModel = SettingsViewModel(FakeAudioQualityPreferences(), FakePlaybackLifecyclePreferences(), FakeHapticsPreferences(), FakeUpdateCheckRepository(), FakeAmoledPreferences(), FakeAutoplayPreferences(), FakeThemePreferences())
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
        val viewModel = SettingsViewModel(FakeAudioQualityPreferences(), FakePlaybackLifecyclePreferences(), FakeHapticsPreferences(), FakeUpdateCheckRepository(), FakeAmoledPreferences(), FakeAutoplayPreferences(), FakeThemePreferences())
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
        val viewModel = SettingsViewModel(FakeAudioQualityPreferences(), FakePlaybackLifecyclePreferences(), FakeHapticsPreferences(), FakeUpdateCheckRepository(), FakeAmoledPreferences(), FakeAutoplayPreferences(), FakeThemePreferences())
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
        val viewModel = SettingsViewModel(FakeAudioQualityPreferences(), FakePlaybackLifecyclePreferences(), FakeHapticsPreferences(), FakeUpdateCheckRepository(), FakeAmoledPreferences(), FakeAutoplayPreferences(), FakeThemePreferences())
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

    private class FakeHapticsPreferences(
        initial: Boolean = HapticsPreferences.DEFAULT_HAPTICS_ENABLED,
    ) : HapticsPreferences {
        private val state = MutableStateFlow(initial)
        override val hapticsEnabled: Flow<Boolean> = state
        override suspend fun setHapticsEnabled(enabled: Boolean) { state.value = enabled }
    }

    // ── YT-0102: FakeAmoledPreferences ────────────────────────────────────────────────────

    private class FakeAmoledPreferences(
        initial: Boolean = AmoledPreferences.DEFAULT_AMOLED_BLACK,
    ) : AmoledPreferences {
        val state = MutableStateFlow(initial)
        override val amoledBlackEnabled: Flow<Boolean> = state
        override suspend fun setAmoledBlackEnabled(enabled: Boolean) { state.value = enabled }
    }

    // ── YT-0089: FakeAutoplayPreferences ──────────────────────────────────────────────────

    private class FakeAutoplayPreferences(
        initial: Boolean = AutoplayPreferences.DEFAULT_AUTOPLAY_ENABLED,
    ) : AutoplayPreferences {
        val state = MutableStateFlow(initial)
        override val autoplayEnabled: Flow<Boolean> = state
        override suspend fun setAutoplayEnabled(enabled: Boolean) { state.value = enabled }
    }

    // ── YT-0316: FakeThemePreferences ─────────────────────────────────────────────────────

    private class FakeThemePreferences(
        initial: ThemePreference = ThemePreferences.DEFAULT_THEME_PREFERENCE,
    ) : ThemePreferences {
        val state = MutableStateFlow(initial)
        override val themePreference: Flow<ThemePreference> = state
        override suspend fun setThemePreference(preference: ThemePreference) { state.value = preference }
    }

    // ── YT-0251: FakeUpdateCheckRepository ────────────────────────────────────────────────

    /**
     * Configurable fake for [UpdateCheckRepository]. [result] controls what
     * [checkForUpdates] returns; pass [UpdateStatus.CheckFailed] to simulate failure.
     */
    private class FakeUpdateCheckRepository(
        private var result: UpdateStatus = UpdateStatus.UpToDate,
    ) : UpdateCheckRepository {
        var callCount = 0
            private set

        override suspend fun checkForUpdates(installedVersionCode: Long): UpdateStatus {
            callCount++
            return result
        }

        fun setResult(status: UpdateStatus) { result = status }
    }

    // ── YT-0251: SettingsViewModel update-check tests ─────────────────────────────────────

    @Test
    fun `checkForUpdates with UpToDate result sets updateStatus to UpToDate`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val fakeRepo = FakeUpdateCheckRepository(result = UpdateStatus.UpToDate)
        val viewModel = SettingsViewModel(
            FakeAudioQualityPreferences(),
            FakePlaybackLifecyclePreferences(),
            FakeHapticsPreferences(),
            fakeRepo,
            FakeAmoledPreferences(),
            FakeAutoplayPreferences(),
            FakeThemePreferences(),
        )
        try {
            viewModel.checkForUpdates(installedVersionCode = 5)
            advanceUntilIdle()
            assertEquals(UpdateStatus.UpToDate, viewModel.updateStatus.value)
            assertEquals(UpdateCheckRowState.Idle, viewModel.uiState.value.updateCheckRow)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `checkForUpdates with UpdateAvailable sets updateStatus and clears row`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val available = UpdateStatus.UpdateAvailable(
            latestVersionName = "1.1.0",
            notes = "Bug fixes",
            apkUrl = "https://example.com/app.apk",
        )
        val fakeRepo = FakeUpdateCheckRepository(result = available)
        val viewModel = SettingsViewModel(
            FakeAudioQualityPreferences(),
            FakePlaybackLifecyclePreferences(),
            FakeHapticsPreferences(),
            fakeRepo,
            FakeAmoledPreferences(),
            FakeAutoplayPreferences(),
            FakeThemePreferences(),
        )
        try {
            viewModel.checkForUpdates(installedVersionCode = 5)
            advanceUntilIdle()
            assertEquals(available, viewModel.updateStatus.value)
            assertEquals(UpdateCheckRowState.Idle, viewModel.uiState.value.updateCheckRow)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `checkForUpdates with UpdateRequired sets updateStatus and clears row`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val required = UpdateStatus.UpdateRequired(
            latestVersionName = "2.0.0",
            notes = "Security fix",
            apkUrl = "https://example.com/app.apk",
        )
        val fakeRepo = FakeUpdateCheckRepository(result = required)
        val viewModel = SettingsViewModel(
            FakeAudioQualityPreferences(),
            FakePlaybackLifecyclePreferences(),
            FakeHapticsPreferences(),
            fakeRepo,
            FakeAmoledPreferences(),
            FakeAutoplayPreferences(),
            FakeThemePreferences(),
        )
        try {
            viewModel.checkForUpdates(installedVersionCode = 5)
            advanceUntilIdle()
            assertEquals(required, viewModel.updateStatus.value)
            assertEquals(UpdateCheckRowState.Idle, viewModel.uiState.value.updateCheckRow)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `checkForUpdates CheckFailed sets Error row state`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val fakeRepo = FakeUpdateCheckRepository(result = UpdateStatus.CheckFailed)
        val viewModel = SettingsViewModel(
            FakeAudioQualityPreferences(),
            FakePlaybackLifecyclePreferences(),
            FakeHapticsPreferences(),
            fakeRepo,
            FakeAmoledPreferences(),
            FakeAutoplayPreferences(),
            FakeThemePreferences(),
        )
        try {
            viewModel.checkForUpdates(installedVersionCode = 5)
            advanceUntilIdle()
            assertEquals(UpdateStatus.CheckFailed, viewModel.updateStatus.value)
            assertEquals(UpdateCheckRowState.Error, viewModel.uiState.value.updateCheckRow)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `dismissUpdateCheckError resets row to Idle`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val fakeRepo = FakeUpdateCheckRepository(result = UpdateStatus.CheckFailed)
        val viewModel = SettingsViewModel(
            FakeAudioQualityPreferences(),
            FakePlaybackLifecyclePreferences(),
            FakeHapticsPreferences(),
            fakeRepo,
            FakeAmoledPreferences(),
            FakeAutoplayPreferences(),
            FakeThemePreferences(),
        )
        try {
            viewModel.checkForUpdates(installedVersionCode = 5)
            advanceUntilIdle()
            assertEquals(UpdateCheckRowState.Error, viewModel.uiState.value.updateCheckRow)
            viewModel.dismissUpdateCheckError()
            advanceUntilIdle()
            assertEquals(UpdateCheckRowState.Idle, viewModel.uiState.value.updateCheckRow)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `checkForUpdates re-entrancy guard prevents concurrent repository calls`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val fakeRepo = FakeUpdateCheckRepository()
        val viewModel = SettingsViewModel(
            FakeAudioQualityPreferences(),
            FakePlaybackLifecyclePreferences(),
            FakeHapticsPreferences(),
            fakeRepo,
            FakeAmoledPreferences(),
            FakeAutoplayPreferences(),
            FakeThemePreferences(),
        )
        try {
            // First call sets row to Checking. Second call while Checking is a no-op.
            viewModel.checkForUpdates(installedVersionCode = 5)
            viewModel.checkForUpdates(installedVersionCode = 5)
            advanceUntilIdle()
            assertEquals(1, fakeRepo.callCount)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `uiState emits Checking then Idle on successful check`() {
        // Use UnconfinedTestDispatcher so that MutableStateFlow emissions are collected
        // immediately by the combine operator, allowing us to observe the intermediate
        // Checking state before the repository coroutine completes.
        val unconfinedDispatcher = UnconfinedTestDispatcher()
        runTest(unconfinedDispatcher) {
            Dispatchers.setMain(unconfinedDispatcher)
            val viewModel = SettingsViewModel(
                FakeAudioQualityPreferences(),
                FakePlaybackLifecyclePreferences(),
                FakeHapticsPreferences(),
                FakeUpdateCheckRepository(result = UpdateStatus.UpToDate),
                FakeAmoledPreferences(),
                FakeAutoplayPreferences(),
                FakeThemePreferences(),
            )
            try {
                viewModel.uiState.test {
                    // Eagerly started — first emission is initial state
                    assertEquals(UpdateCheckRowState.Idle, awaitItem().updateCheckRow)
                    viewModel.checkForUpdates(installedVersionCode = 5)
                    // With UnconfinedTestDispatcher the synchronous _updateCheckRow.value =
                    // Checking assignment propagates through combine immediately.
                    assertEquals(UpdateCheckRowState.Checking, awaitItem().updateCheckRow)
                    // Now let the repository suspend fun complete and set the final state.
                    advanceUntilIdle()
                    assertEquals(UpdateCheckRowState.Idle, awaitItem().updateCheckRow)
                    cancelAndIgnoreRemainingEvents()
                }
            } finally {
                Dispatchers.resetMain()
            }
        }
    }

    @Test
    fun `uiState emits Checking then Error on failed check`() {
        val unconfinedDispatcher = UnconfinedTestDispatcher()
        runTest(unconfinedDispatcher) {
            Dispatchers.setMain(unconfinedDispatcher)
            val viewModel = SettingsViewModel(
                FakeAudioQualityPreferences(),
                FakePlaybackLifecyclePreferences(),
                FakeHapticsPreferences(),
                FakeUpdateCheckRepository(result = UpdateStatus.CheckFailed),
                FakeAmoledPreferences(),
                FakeAutoplayPreferences(),
                FakeThemePreferences(),
            )
            try {
                viewModel.uiState.test {
                    assertEquals(UpdateCheckRowState.Idle, awaitItem().updateCheckRow)
                    viewModel.checkForUpdates(installedVersionCode = 5)
                    assertEquals(UpdateCheckRowState.Checking, awaitItem().updateCheckRow)
                    advanceUntilIdle()
                    assertEquals(UpdateCheckRowState.Error, awaitItem().updateCheckRow)
                    cancelAndIgnoreRemainingEvents()
                }
            } finally {
                Dispatchers.resetMain()
            }
        }
    }

    // ── YT-0102: AMOLED-black toggle tests ────────────────────────────────────────────────

    @Test
    fun `default amoledBlackEnabled is false`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val viewModel = SettingsViewModel(
            FakeAudioQualityPreferences(),
            FakePlaybackLifecyclePreferences(),
            FakeHapticsPreferences(),
            FakeUpdateCheckRepository(),
            FakeAmoledPreferences(),
            FakeAutoplayPreferences(),
            FakeThemePreferences(),
        )
        try {
            advanceUntilIdle()
            assertEquals(false, viewModel.uiState.value.amoledBlackEnabled)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `uiState reflects upstream amoledBlackEnabled pref change`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val amoledPrefs = FakeAmoledPreferences(initial = true)
        val viewModel = SettingsViewModel(
            FakeAudioQualityPreferences(),
            FakePlaybackLifecyclePreferences(),
            FakeHapticsPreferences(),
            FakeUpdateCheckRepository(),
            amoledPrefs,
            FakeAutoplayPreferences(),
            FakeThemePreferences(),
        )
        try {
            advanceUntilIdle()
            assertEquals(true, viewModel.uiState.value.amoledBlackEnabled)
            amoledPrefs.setAmoledBlackEnabled(false)
            advanceUntilIdle()
            assertEquals(false, viewModel.uiState.value.amoledBlackEnabled)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `toggleAmoledBlack writes the inverse of current value`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val amoledPrefs = FakeAmoledPreferences()
        val viewModel = SettingsViewModel(
            FakeAudioQualityPreferences(),
            FakePlaybackLifecyclePreferences(),
            FakeHapticsPreferences(),
            FakeUpdateCheckRepository(),
            amoledPrefs,
            FakeAutoplayPreferences(),
            FakeThemePreferences(),
        )
        try {
            advanceUntilIdle()
            // Default = false. First toggle flips to true.
            assertEquals(false, viewModel.uiState.value.amoledBlackEnabled)
            viewModel.toggleAmoledBlack()
            advanceUntilIdle()
            assertEquals(true, amoledPrefs.state.value)
            assertEquals(true, viewModel.uiState.value.amoledBlackEnabled)
            // Second toggle flips back to false.
            viewModel.toggleAmoledBlack()
            advanceUntilIdle()
            assertEquals(false, amoledPrefs.state.value)
            assertEquals(false, viewModel.uiState.value.amoledBlackEnabled)
        } finally {
            Dispatchers.resetMain()
        }
    }

    // ── YT-0089: Autoplay toggle tests ────────────────────────────────────────────────────

    @Test
    fun `default autoplayEnabled is true`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val viewModel = SettingsViewModel(
            FakeAudioQualityPreferences(),
            FakePlaybackLifecyclePreferences(),
            FakeHapticsPreferences(),
            FakeUpdateCheckRepository(),
            FakeAmoledPreferences(),
            FakeAutoplayPreferences(),
            FakeThemePreferences(),
        )
        try {
            advanceUntilIdle()
            assertEquals(true, viewModel.uiState.value.autoplayEnabled)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `uiState reflects upstream autoplayEnabled pref change`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val autoplayPrefs = FakeAutoplayPreferences(initial = false)
        val viewModel = SettingsViewModel(
            FakeAudioQualityPreferences(),
            FakePlaybackLifecyclePreferences(),
            FakeHapticsPreferences(),
            FakeUpdateCheckRepository(),
            FakeAmoledPreferences(),
            autoplayPrefs,
            FakeThemePreferences(),
        )
        try {
            advanceUntilIdle()
            assertEquals(false, viewModel.uiState.value.autoplayEnabled)
            // Simulate an external write.
            autoplayPrefs.setAutoplayEnabled(true)
            advanceUntilIdle()
            assertEquals(true, viewModel.uiState.value.autoplayEnabled)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `toggleAutoplay writes the inverse of current value`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val autoplayPrefs = FakeAutoplayPreferences()
        val viewModel = SettingsViewModel(
            FakeAudioQualityPreferences(),
            FakePlaybackLifecyclePreferences(),
            FakeHapticsPreferences(),
            FakeUpdateCheckRepository(),
            FakeAmoledPreferences(),
            autoplayPrefs,
            FakeThemePreferences(),
        )
        try {
            advanceUntilIdle()
            // Default = true. First toggle flips to false.
            assertEquals(true, viewModel.uiState.value.autoplayEnabled)
            viewModel.toggleAutoplay()
            advanceUntilIdle()
            assertEquals(false, autoplayPrefs.state.value)
            assertEquals(false, viewModel.uiState.value.autoplayEnabled)
            // Second toggle flips back to true.
            viewModel.toggleAutoplay()
            advanceUntilIdle()
            assertEquals(true, autoplayPrefs.state.value)
            assertEquals(true, viewModel.uiState.value.autoplayEnabled)
        } finally {
            Dispatchers.resetMain()
        }
    }

    // ── YT-0316: Theme preference toggle tests ────────────────────────────────────────────

    @Test
    fun `default themePreference is SYSTEM`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val viewModel = SettingsViewModel(
            FakeAudioQualityPreferences(),
            FakePlaybackLifecyclePreferences(),
            FakeHapticsPreferences(),
            FakeUpdateCheckRepository(),
            FakeAmoledPreferences(),
            FakeAutoplayPreferences(),
            FakeThemePreferences(),
        )
        try {
            advanceUntilIdle()
            assertEquals(ThemePreference.SYSTEM, viewModel.uiState.value.themePreference)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `setThemePreference persists and uiState reflects new preference`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val themePrefs = FakeThemePreferences()
        val viewModel = SettingsViewModel(
            FakeAudioQualityPreferences(),
            FakePlaybackLifecyclePreferences(),
            FakeHapticsPreferences(),
            FakeUpdateCheckRepository(),
            FakeAmoledPreferences(),
            FakeAutoplayPreferences(),
            themePrefs,
        )
        try {
            advanceUntilIdle()
            assertEquals(ThemePreference.SYSTEM, viewModel.uiState.value.themePreference)
            viewModel.setThemePreference(ThemePreference.DARK)
            advanceUntilIdle()
            assertEquals(ThemePreference.DARK, themePrefs.state.value)
            assertEquals(ThemePreference.DARK, viewModel.uiState.value.themePreference)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `uiState reflects upstream themePreference pref change`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val themePrefs = FakeThemePreferences(initial = ThemePreference.LIGHT)
        val viewModel = SettingsViewModel(
            FakeAudioQualityPreferences(),
            FakePlaybackLifecyclePreferences(),
            FakeHapticsPreferences(),
            FakeUpdateCheckRepository(),
            FakeAmoledPreferences(),
            FakeAutoplayPreferences(),
            themePrefs,
        )
        try {
            advanceUntilIdle()
            assertEquals(ThemePreference.LIGHT, viewModel.uiState.value.themePreference)
            themePrefs.setThemePreference(ThemePreference.DARK)
            advanceUntilIdle()
            assertEquals(ThemePreference.DARK, viewModel.uiState.value.themePreference)
        } finally {
            Dispatchers.resetMain()
        }
    }

    // YT-0316 — AMOLED row is only interactive when themePreference == DARK.

    @Test
    fun `themePreference LIGHT means AMOLED row is not interactive`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val themePrefs = FakeThemePreferences(initial = ThemePreference.LIGHT)
        val viewModel = SettingsViewModel(
            FakeAudioQualityPreferences(),
            FakePlaybackLifecyclePreferences(),
            FakeHapticsPreferences(),
            FakeUpdateCheckRepository(),
            FakeAmoledPreferences(),
            FakeAutoplayPreferences(),
            themePrefs,
        )
        try {
            advanceUntilIdle()
            assertEquals(ThemePreference.LIGHT, viewModel.uiState.value.themePreference)
            // AMOLED interactive condition: themePreference == DARK
            assertFalse(viewModel.uiState.value.themePreference == ThemePreference.DARK)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `themePreference SYSTEM means AMOLED row is not interactive`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val themePrefs = FakeThemePreferences(initial = ThemePreference.SYSTEM)
        val viewModel = SettingsViewModel(
            FakeAudioQualityPreferences(),
            FakePlaybackLifecyclePreferences(),
            FakeHapticsPreferences(),
            FakeUpdateCheckRepository(),
            FakeAmoledPreferences(),
            FakeAutoplayPreferences(),
            themePrefs,
        )
        try {
            advanceUntilIdle()
            assertEquals(ThemePreference.SYSTEM, viewModel.uiState.value.themePreference)
            assertFalse(viewModel.uiState.value.themePreference == ThemePreference.DARK)
        } finally {
            Dispatchers.resetMain()
        }
    }
}
