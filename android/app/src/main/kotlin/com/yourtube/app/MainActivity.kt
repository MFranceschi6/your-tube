package com.yourtube.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import com.yourtube.app.sharing.PlaylistImportResult
import com.yourtube.app.sharing.PlaylistImporter
import com.yourtube.app.ui.AppShell
import com.yourtube.app.ui.theme.YourTubeTheme
import com.yourtube.core.player.PlaybackService
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
            YourTubeTheme {
                AppShell(
                    modifier = Modifier.fillMaxSize(),
                    openNowPlayingRequests = openNowPlayingRequests.asSharedFlow(),
                )
            }
        }
        handleSharedIntent(intent)
        handleOpenNowPlayingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSharedIntent(intent)
        handleOpenNowPlayingIntent(intent)
    }

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
