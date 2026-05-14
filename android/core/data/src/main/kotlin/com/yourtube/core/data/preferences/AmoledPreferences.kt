package com.yourtube.core.data.preferences

import kotlinx.coroutines.flow.Flow

/**
 * YT-0102 — typed accessor for the AMOLED-black theme preference.
 *
 * When [amoledBlackEnabled] is `true` and the system is in dark mode, the app
 * overrides Material 3 `background` and `surface` tokens to pure black
 * (`Color.Black` / #000000). The preference has no effect in light mode.
 *
 * Default is `false` (brand dark surfaces unchanged).
 */
interface AmoledPreferences {

    /** Latest persisted value of the AMOLED-black toggle. Default: [DEFAULT_AMOLED_BLACK]. */
    val amoledBlackEnabled: Flow<Boolean>

    /** Persists the user's AMOLED-black on/off preference. */
    suspend fun setAmoledBlackEnabled(enabled: Boolean)

    companion object {
        const val DEFAULT_AMOLED_BLACK: Boolean = false
    }
}
