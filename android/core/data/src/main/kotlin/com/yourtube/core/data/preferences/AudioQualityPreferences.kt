package com.yourtube.core.data.preferences

import kotlinx.coroutines.flow.Flow

/**
 * Typed accessor for the user's preferred audio bitrate (kbps), used by
 * stream selection in the player. The single source of truth for the
 * `audio_quality_bitrate` DataStore key — feature modules and player code
 * must depend on this interface instead of the raw key.
 */
interface AudioQualityPreferences {

    /** Latest persisted preference, defaulting to [DEFAULT_BITRATE_KBPS] when unset. */
    val bitrateKbps: Flow<Int>

    /** Persists the user's preferred maximum bitrate. */
    suspend fun setBitrateKbps(kbps: Int)

    companion object {
        /**
         * Matches `YoutubeService.DEFAULT_PREFERRED_MAX_BITRATE_KBPS`; duplicated here so
         * `core:data` does not need to depend on `core:network`. If you change one, change
         * both — the player resolves the default at extraction time when DataStore is empty.
         */
        const val DEFAULT_BITRATE_KBPS: Int = 160
    }
}
