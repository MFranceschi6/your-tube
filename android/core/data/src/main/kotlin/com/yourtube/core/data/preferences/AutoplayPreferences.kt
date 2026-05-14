package com.yourtube.core.data.preferences

import kotlinx.coroutines.flow.Flow

/**
 * YT-0089 — typed accessor for the user's autoplay-enabled preference.
 *
 * Key: `autoplay_enabled`. Default: [DEFAULT_AUTOPLAY_ENABLED] (`true`).
 * Setting key aligns with the cross-platform contract in `docs/autoplay.md`.
 */
interface AutoplayPreferences {

    /** Latest persisted preference, defaulting to [DEFAULT_AUTOPLAY_ENABLED] when unset. */
    val autoplayEnabled: Flow<Boolean>

    /** Persists the user's autoplay on/off preference. */
    suspend fun setAutoplayEnabled(enabled: Boolean)

    companion object {
        const val DEFAULT_AUTOPLAY_ENABLED: Boolean = true
    }
}
