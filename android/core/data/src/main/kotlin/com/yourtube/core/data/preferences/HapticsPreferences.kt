package com.yourtube.core.data.preferences

import kotlinx.coroutines.flow.Flow

interface HapticsPreferences {

    /** Whether haptic feedback is enabled. Default: [DEFAULT_HAPTICS_ENABLED]. */
    val hapticsEnabled: Flow<Boolean>

    /** Persists the user's haptics on/off preference. */
    suspend fun setHapticsEnabled(enabled: Boolean)

    companion object {
        const val DEFAULT_HAPTICS_ENABLED: Boolean = true
    }
}
