package com.yourtube.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourtube.core.data.preferences.AudioQualityPreferences
import com.yourtube.core.data.preferences.PlaybackLifecyclePreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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

data class SettingsUiState(
    val audioQuality: AudioQuality = AudioQuality.HIGH,
    val importRow: ImportRowState = ImportRowState.Idle,
    /**
     * YT-0241 — opt-in toggle: when `true`, swiping the app from recents also stops audio
     * and dismisses the foreground notification. Default `false` preserves YT-0076 AC#5
     * Spotify-style persistence.
     */
    val stopOnTaskRemoved: Boolean = PlaybackLifecyclePreferences.DEFAULT_STOP_ON_TASK_REMOVED,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val audioQualityPreferences: AudioQualityPreferences,
    private val playbackLifecyclePreferences: PlaybackLifecyclePreferences,
) : ViewModel() {

    private val _importRow = MutableStateFlow(ImportRowState.Idle)

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

    val uiState: StateFlow<SettingsUiState> = combine(
        audioQualityPreferences.bitrateKbps,
        _importRow,
        playbackLifecyclePreferences.stopOnTaskRemoved,
    ) { bitrate, importRow, stopOnTaskRemoved ->
        val quality = AudioQuality.entries.firstOrNull { it.maxBitrateKbps == bitrate }
            ?: AudioQuality.HIGH
        SettingsUiState(
            audioQuality = quality,
            importRow = importRow,
            stopOnTaskRemoved = stopOnTaskRemoved,
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
