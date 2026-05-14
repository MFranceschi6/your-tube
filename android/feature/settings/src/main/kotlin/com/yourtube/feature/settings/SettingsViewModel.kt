package com.yourtube.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourtube.core.data.preferences.AmoledPreferences
import com.yourtube.core.data.preferences.AudioQualityPreferences
import com.yourtube.core.data.preferences.AutoplayPreferences
import com.yourtube.core.data.preferences.HapticsPreferences
import com.yourtube.core.data.preferences.PlaybackLifecyclePreferences
import com.yourtube.core.data.preferences.ThemePreference
import com.yourtube.core.data.preferences.ThemePreferences
import com.yourtube.core.data.update.UpdateCheckRepository
import com.yourtube.core.data.update.UpdateStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class AudioQuality(val label: String, val maxBitrateKbps: Int) {
    LOW("Low (64 kbps)", 64),
    MEDIUM("Medium (128 kbps)", 128),
    HIGH("High (160 kbps)", 160),
    VERY_HIGH("Very high (320 kbps)", 320),
}

/**
 * YT-0164 C15/C16: state of the inline Import row. The Settings page itself
 * stays fully interactive while an op runs — only the import row is decorated.
 *
 * - [Idle]       — default; no operation in flight.
 * - [InProgress] — SAF picker returned a URI; we're handing it off downstream.
 * - [Failed]     — last attempt surfaced an error; the row renders an inline
 *                  banner with retry/dismiss actions.
 */
enum class ImportRowState { Idle, InProgress, Failed }

/**
 * YT-0251 — state of the "Check for updates" row in Settings.
 *
 * - [Idle]       — default; no check in flight.
 * - [Checking]   — network fetch in progress; spinner shown in trailing area.
 * - [Error]      — network failure or malformed feed; dismissible error note shown.
 */
enum class UpdateCheckRowState { Idle, Checking, Error }

data class SettingsUiState(
    val audioQuality: AudioQuality = AudioQuality.HIGH,
    val importRow: ImportRowState = ImportRowState.Idle,
    /**
     * YT-0241 — opt-in toggle: when `true`, swiping the app from recents also stops audio
     * and dismisses the foreground notification. Default `false` preserves YT-0076 AC#5
     * Spotify-style persistence.
     */
    val stopOnTaskRemoved: Boolean = PlaybackLifecyclePreferences.DEFAULT_STOP_ON_TASK_REMOVED,
    /** YT-0097 — haptic feedback enabled. Default `true`. */
    val hapticsEnabled: Boolean = HapticsPreferences.DEFAULT_HAPTICS_ENABLED,
    /** YT-0251 — current state of the "Check for updates" row. */
    val updateCheckRow: UpdateCheckRowState = UpdateCheckRowState.Idle,
    /**
     * YT-0102 — AMOLED-black theme toggle. When `true` and system is in dark mode,
     * `background` and `surface` tokens are overridden to pure black. No effect in
     * light mode.
     */
    val amoledBlackEnabled: Boolean = AmoledPreferences.DEFAULT_AMOLED_BLACK,
    /**
     * YT-0089 — autoplay-next-track toggle. When `true`, the player fetches a related
     * track and continues playback when the queue empties. Default `true`.
     */
    val autoplayEnabled: Boolean = AutoplayPreferences.DEFAULT_AUTOPLAY_ENABLED,
    /** YT-0316 — user's preferred theme override. Default: follow system. */
    val themePreference: ThemePreference = ThemePreferences.DEFAULT_THEME_PREFERENCE,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val audioQualityPreferences: AudioQualityPreferences,
    private val playbackLifecyclePreferences: PlaybackLifecyclePreferences,
    private val hapticsPreferences: HapticsPreferences,
    private val updateCheckRepository: UpdateCheckRepository,
    private val amoledPreferences: AmoledPreferences,
    private val autoplayPreferences: AutoplayPreferences,
    private val themePreferences: ThemePreferences,
) : ViewModel() {

    private val _importRow = MutableStateFlow(ImportRowState.Idle)
    private val _updateCheckRow = MutableStateFlow(UpdateCheckRowState.Idle)

    /**
     * YT-0251 — the latest update-check result. Null until the first check completes.
     * Exposed as a StateFlow so AppShell can gate on [UpdateStatus.UpdateRequired] before
     * allowing the user into the main experience.
     */
    private val _updateStatus = MutableStateFlow<UpdateStatus?>(null)
    val updateStatus: StateFlow<UpdateStatus?> = _updateStatus.asStateFlow()

    /**
     * Fired when the C16 banner's "Try again" button is tapped. The screen
     * subscribes and re-launches the SAF picker. Replay-1 + DROP_OLDEST so a
     * retry tap that lands during a recomposition still wakes the collector
     * but doesn't accumulate a backlog of stale requests.
     */
    private val _retryImportRequests = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val retryImportRequests: SharedFlow<Unit> = _retryImportRequests.asSharedFlow()

    // YT-0102: Kotlin's combine overload caps at 5 flows. We nest combines to include
    // more than 5 flows without pulling in additional libraries.
    // YT-0089: added autoplayEnabled as the 7th flow via a third nesting level.
    // YT-0316: added themePreference as the 8th flow via a fourth nesting level.
    val uiState: StateFlow<SettingsUiState> = combine(
        combine(
            combine(
                combine(
                    audioQualityPreferences.bitrateKbps,
                    _importRow,
                    playbackLifecyclePreferences.stopOnTaskRemoved,
                    hapticsPreferences.hapticsEnabled,
                    _updateCheckRow,
                ) { bitrate, importRow, stopOnTaskRemoved, hapticsEnabled, updateCheckRow ->
                    Triple(bitrate, importRow, Triple(stopOnTaskRemoved, hapticsEnabled, updateCheckRow))
                },
                amoledPreferences.amoledBlackEnabled,
            ) { inner, amoledBlack -> Pair(inner, amoledBlack) },
            autoplayPreferences.autoplayEnabled,
        ) { innerWithAmoled, autoplay -> Pair(innerWithAmoled, autoplay) },
        themePreferences.themePreference,
    ) { (innerWithAmoled, autoplay), theme ->
        val (innerPair, amoledBlack) = innerWithAmoled
        val (bitrate, importRow, innerTriple) = innerPair
        val (stopOnTaskRemoved, hapticsEnabled, updateCheckRow) = innerTriple
        val quality = AudioQuality.entries.firstOrNull { it.maxBitrateKbps == bitrate }
            ?: AudioQuality.HIGH
        SettingsUiState(
            audioQuality = quality,
            importRow = importRow,
            stopOnTaskRemoved = stopOnTaskRemoved,
            hapticsEnabled = hapticsEnabled,
            updateCheckRow = updateCheckRow,
            amoledBlackEnabled = amoledBlack,
            autoplayEnabled = autoplay,
            themePreference = theme,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SettingsUiState())

    fun setAudioQuality(quality: AudioQuality) {
        viewModelScope.launch {
            audioQualityPreferences.setBitrateKbps(quality.maxBitrateKbps)
        }
    }

    /**
     * YT-0241 — persists the inverse of the current `stopOnTaskRemoved` preference. The
     * Settings row simply calls this on tap; the new value flips automatically once the
     * upstream DataStore re-emits and `uiState` recomputes via [combine].
     *
     * Reads `uiState.value.stopOnTaskRemoved` rather than re-collecting the upstream flow
     * so the toggle stays responsive (single allocation, no suspending `first()`).
     */
    fun toggleStopOnTaskRemoved() {
        val current = uiState.value.stopOnTaskRemoved
        viewModelScope.launch {
            playbackLifecyclePreferences.setStopOnTaskRemoved(!current)
        }
    }

    /** YT-0097 — flip the haptics enabled preference. */
    fun toggleHapticsEnabled() {
        val current = uiState.value.hapticsEnabled
        viewModelScope.launch {
            hapticsPreferences.setHapticsEnabled(!current)
        }
    }

    /**
     * YT-0102 — flip the AMOLED-black theme preference.
     *
     * Reads `uiState.value.amoledBlackEnabled` for the same single-read pattern used
     * by [toggleHapticsEnabled] and [toggleStopOnTaskRemoved]. The theme composable at
     * `MainActivity` level observes the same DataStore flow and recomposes automatically.
     */
    fun toggleAmoledBlack() {
        val current = uiState.value.amoledBlackEnabled
        viewModelScope.launch {
            amoledPreferences.setAmoledBlackEnabled(!current)
        }
    }

    /**
     * YT-0089 — flip the autoplay preference.
     *
     * When toggled during playback, the change takes effect on the NEXT end-of-queue event
     * (per `docs/autoplay.md`). No playback is interrupted by this call.
     */
    fun toggleAutoplay() {
        val current = uiState.value.autoplayEnabled
        viewModelScope.launch {
            autoplayPreferences.setAutoplayEnabled(!current)
        }
    }

    /** YT-0316 — persist the user's chosen theme override. */
    fun setThemePreference(preference: ThemePreference) {
        viewModelScope.launch {
            themePreferences.setThemePreference(preference)
        }
    }

    /**
     * YT-0251 — fetch the hosted update feed and update [updateStatus].
     *
     * Called on app launch (from AppShell) and by the "Check for updates" Settings row.
     * [installedVersionCode] should be [BuildConfig.VERSION_CODE].toLong() at the call
     * site; it is a parameter here so the ViewModel stays testable without Android deps.
     *
     * On network failure or malformed JSON, [UpdateCheckRepository] returns
     * [UpdateStatus.CheckFailed]. [updateCheckRow] is set to [UpdateCheckRowState.Error]
     * when the result is [UpdateStatus.CheckFailed] so the Settings row can show
     * a dismissible error note.
     */
    fun checkForUpdates(installedVersionCode: Long) {
        if (_updateCheckRow.value == UpdateCheckRowState.Checking) return
        _updateCheckRow.value = UpdateCheckRowState.Checking
        viewModelScope.launch {
            val result = updateCheckRepository.checkForUpdates(installedVersionCode)
            _updateStatus.value = result
            _updateCheckRow.value = if (result is UpdateStatus.CheckFailed) {
                UpdateCheckRowState.Error
            } else {
                UpdateCheckRowState.Idle
            }
        }
    }

    /** YT-0251 — dismiss the update-check error note shown in the Settings row. */
    fun dismissUpdateCheckError() {
        _updateCheckRow.value = UpdateCheckRowState.Idle
    }

    fun onImportStarted() { _importRow.value = ImportRowState.InProgress }
    fun onImportSucceeded() { _importRow.value = ImportRowState.Idle }
    fun onImportFailed() { _importRow.value = ImportRowState.Failed }
    fun dismissImportError() { _importRow.value = ImportRowState.Idle }

    /**
     * Catalog C16 retry binding. Emits on [retryImportRequests] so the
     * [SettingsScreen] can re-launch the SAF picker. Also resets the row to
     * [ImportRowState.Idle] before the new attempt so the previous banner
     * disappears immediately.
     */
    fun retryImport() {
        _importRow.value = ImportRowState.Idle
        _retryImportRequests.tryEmit(Unit)
    }
}
