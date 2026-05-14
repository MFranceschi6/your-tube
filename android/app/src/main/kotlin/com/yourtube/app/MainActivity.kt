package com.yourtube.app

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import com.yourtube.app.pip.PipActionReceiver
import com.yourtube.app.pip.shouldDismissPip
import com.yourtube.app.pip.shouldEnterPip
import com.yourtube.app.sharing.PlaylistImportResult
import com.yourtube.app.sharing.PlaylistImporter
import com.yourtube.app.ui.AppShell
import com.yourtube.app.ui.theme.YourTubeTheme
import com.yourtube.core.common.model.PlayerState
import com.yourtube.core.data.preferences.AmoledPreferences
import com.yourtube.core.data.preferences.ThemePreference
import com.yourtube.core.data.preferences.ThemePreferences
import com.yourtube.core.player.PlaybackService
import com.yourtube.core.player.PlayerController
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * Single-activity entry point. All player state is owned by [PlayerViewModel] via Hilt,
 * collected inside [AppShell].
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var importer: PlaylistImporter
    // YT-0102 — AMOLED-black pref injected here so it is available before the first
    // setContent frame. Collecting at Activity scope (rather than inside a ViewModel)
    // keeps the preference lifecycle tied to the window, which is correct for a theme
    // configuration that must be in place before ANY composable is measured.
    @Inject lateinit var amoledPreferences: AmoledPreferences
    // YT-0316 — theme preference injected here so darkTheme is derived before the first frame.
    @Inject lateinit var themePreferences: ThemePreferences

    // YT-0104 — PlayerController injected at Activity scope so onUserLeaveHint() can read
    // the latest PlayerState snapshot synchronously without going through the Compose tree.
    @Inject lateinit var playerController: PlayerController

    /**
     * Replay-1 hot flow that emits each time the activity receives an intent asking it to
     * open the Now Playing screen (action `PlaybackService.ACTION_OPEN_NOW_PLAYING`). The
     * AppShell composable owns the `NavController` and can't be reached imperatively from
     * the activity, so we hand it a [SharedFlow] it can observe inside a `LaunchedEffect` to
     * trigger the actual navigation. Replay-1 covers the case where the launching intent
     * arrives in `onCreate` before the AppShell has subscribed.
     */
    private val openNowPlayingRequests = MutableSharedFlow<Unit>(replay = 1)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // YT-0102: collect AMOLED pref as Compose state. `collectAsState` is safe here
            // because it starts with the DataStore default (false) until the first emission.
            val amoledBlack by amoledPreferences.amoledBlackEnabled
                .collectAsState(initial = AmoledPreferences.DEFAULT_AMOLED_BLACK)
            // YT-0316: derive darkTheme from the stored preference, falling back to the
            // system setting when the user has not overridden it.
            val themePreference by themePreferences.themePreference
                .collectAsState(initial = ThemePreferences.DEFAULT_THEME_PREFERENCE)
            val darkTheme = when (themePreference) {
                ThemePreference.LIGHT -> false
                ThemePreference.DARK -> true
                ThemePreference.SYSTEM -> isSystemInDarkTheme()
            }
            YourTubeTheme(darkTheme = darkTheme, amoledBlack = amoledBlack) {
                AppShell(
                    modifier = Modifier.fillMaxSize(),
                    openNowPlayingRequests = openNowPlayingRequests.asSharedFlow(),
                )
            }
        }
        // YT-0285 — explicit restore trigger. Loads the persisted snapshot into
        // playerController.playerState immediately at Activity start, before any user
        // interaction and without waiting for Media3 service binding (which is lazy and
        // only fires on the first transport call). This closes the "controller rebind"
        // failure bucket: the singleton controller exists but its in-memory currentTrack
        // is null because onBind never ran with no user interaction. Idempotent — no-op
        // when currentTrack is already non-null (process survived background trip).
        lifecycleScope.launch {
            playerController.ensureRestored()
        }
        handleSharedIntent(intent)
        handleOpenNowPlayingIntent(intent)
        observePlayStateForPipSync()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSharedIntent(intent)
        handleOpenNowPlayingIntent(intent)
    }

    // ── YT-0104 PiP ──────────────────────────────────────────────────────────────────────

    /**
     * Enter PiP automatically when the user presses Home during active/paused video playback.
     *
     * [shouldEnterPip] is a pure function (extracted in PipHelper.kt) that checks:
     *  - currentTrack is non-null
     *  - playbackStatus is PLAYING / PAUSED / LOADING / BUFFERING
     *  - content is video (all content treated as video until Track.isVideo field exists)
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (shouldEnterPip(playerController.playerState.value)) {
            enterPictureInPictureMode(buildPipParams())
        }
    }

    /**
     * Keep the play/pause action icon in sync with the current playback state whenever PiP
     * mode is active. We push a new [PictureInPictureParams] on every isPlaying change.
     */
    override fun onPictureInPictureModeChanged(isInPipMode: Boolean) {
        super.onPictureInPictureModeChanged(isInPipMode)
        if (isInPipMode) {
            setPictureInPictureParams(buildPipParams())
        }
    }

    /**
     * Observe player state while in PiP for two purposes:
     *
     * 1. Keep the play/pause [RemoteAction] icon in sync with actual playback state whenever
     *    PiP is active, by pushing updated [PictureInPictureParams] on every `isPlaying` flip.
     *
     * 2. Dismiss the PiP window when playback stops entirely: if [PlayerState.currentTrack]
     *    transitions from non-null to null while the activity is in PiP mode we call
     *    [moveTaskToBack] so the stale frozen window is removed immediately rather than
     *    lingering until the user dismisses it manually.
     *
     * Started in [onCreate]; cancelled automatically when the Activity is destroyed because
     * [lifecycleScope] is bound to the Activity lifecycle.
     */
    private fun observePlayStateForPipSync() {
        lifecycleScope.launch {
            var prevState: PlayerState? = null
            playerController.playerState
                .collect { state ->
                    val prev = prevState
                    // Dismiss PiP when track disappears while the window is active.
                    if (shouldDismissPip(
                            prevTrack = prev?.currentTrack,
                            currTrack = state.currentTrack,
                            isInPip = isInPictureInPictureMode,
                        )
                    ) {
                        moveTaskToBack(false)
                    }
                    // Keep play/pause icon in sync.
                    if (isInPictureInPictureMode && prev?.isPlaying != state.isPlaying) {
                        setPictureInPictureParams(buildPipParams())
                    }
                    prevState = state
                }
        }
    }

    /**
     * Build [PictureInPictureParams] with three [RemoteAction]s: skip-previous, play/pause,
     * skip-next. The play/pause icon reflects the current [PlayerController.playerState].
     *
     * Icons use the platform's built-in media drawables which are guaranteed to exist on all
     * API levels this app targets (minSdk = 26).
     */
    private fun buildPipParams(): PictureInPictureParams {
        val isPlaying = playerController.playerState.value.isPlaying

        val prevAction = RemoteAction(
            Icon.createWithResource(this, android.R.drawable.ic_media_previous),
            getString(R.string.pip_action_previous),
            getString(R.string.pip_action_previous),
            buildPipPendingIntent(PipActionReceiver.ACTION_PIP_SKIP_PREV, requestCode = 1),
        )

        val playPauseIcon = if (isPlaying) {
            Icon.createWithResource(this, android.R.drawable.ic_media_pause)
        } else {
            Icon.createWithResource(this, android.R.drawable.ic_media_play)
        }
        val playPauseTitle = if (isPlaying) {
            getString(R.string.pip_action_pause)
        } else {
            getString(R.string.pip_action_play)
        }
        val playPauseAction = RemoteAction(
            playPauseIcon,
            playPauseTitle,
            playPauseTitle,
            buildPipPendingIntent(PipActionReceiver.ACTION_PIP_PLAY_PAUSE, requestCode = 2),
        )

        val nextAction = RemoteAction(
            Icon.createWithResource(this, android.R.drawable.ic_media_next),
            getString(R.string.pip_action_next),
            getString(R.string.pip_action_next),
            buildPipPendingIntent(PipActionReceiver.ACTION_PIP_SKIP_NEXT, requestCode = 3),
        )

        return PictureInPictureParams.Builder()
            .setActions(listOf(prevAction, playPauseAction, nextAction))
            .build()
    }

    /**
     * Build a [PendingIntent] that broadcasts [action] to [PipActionReceiver].
     * Uses unique [requestCode]s per action so the system does not collapse them into a
     * single pending intent.
     */
    private fun buildPipPendingIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            this,
            requestCode,
            Intent(this, PipActionReceiver::class.java).apply { this.action = action },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    // ── end YT-0104 PiP ──────────────────────────────────────────────────────────────────

    private fun handleOpenNowPlayingIntent(intent: Intent?) {
        if (intent?.action == PlaybackService.ACTION_OPEN_NOW_PLAYING) {
            // tryEmit always succeeds for replay-buffered hot flows with no slow collectors.
            openNowPlayingRequests.tryEmit(Unit)
        }
    }

    private fun handleSharedIntent(intent: Intent?) {
        val uri = intent?.extractPlaylistUri() ?: return
        lifecycleScope.launch {
            val result = importer.import(uri)
            Toast.makeText(this@MainActivity, result.toUserMessage(), Toast.LENGTH_LONG).show()
        }
    }

    private fun PlaylistImportResult.toUserMessage(): String = when (this) {
        is PlaylistImportResult.Success ->
            getString(R.string.import_playlist_success, playlist.name)
        PlaylistImportResult.UnsupportedSchema ->
            getString(R.string.import_playlist_unsupported)
        PlaylistImportResult.InvalidPayload ->
            getString(R.string.import_playlist_invalid)
        PlaylistImportResult.Unreadable ->
            getString(R.string.import_playlist_unreadable)
    }
}

private fun Intent.extractPlaylistUri(): Uri? = when (action) {
    Intent.ACTION_VIEW -> data
    Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(this, Intent.EXTRA_STREAM, Uri::class.java)
    else -> null
}
