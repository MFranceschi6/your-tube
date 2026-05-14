package com.yourtube.core.data.preferences

import kotlinx.coroutines.flow.Flow

interface PlaybackSpeedPreferences {

    /** Latest persisted playback speed multiplier; range 0.5–2.0, default [DEFAULT_SPEED]. */
    val speed: Flow<Float>

    /** Persists the user's preferred playback speed multiplier. */
    suspend fun setSpeed(speed: Float)

    companion object {
        const val DEFAULT_SPEED: Float = 1.0f
    }
}
