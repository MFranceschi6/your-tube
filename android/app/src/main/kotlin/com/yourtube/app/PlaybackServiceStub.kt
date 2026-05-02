package com.yourtube.app

import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Stub MediaSessionService so the manifest declaration resolves.
 * Real playback wiring belongs in :core:player.
 */
class PlaybackServiceStub : MediaSessionService() {
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = null
}
