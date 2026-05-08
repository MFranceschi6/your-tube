package com.yourtube.core.data.preferences

import kotlinx.coroutines.flow.Flow

/**
 * Typed accessor for playback-lifecycle preferences -- currently a single
 * opt-in toggle that controls whether swiping the app card from recents
 * also stops audio playback (YT-0241).
 *
 * Default for [stopOnTaskRemoved] is `false`, which preserves the
 * Spotify-style persistence shipped by YT-0076 AC#5: notification + audio
 * survive `Activity.onTaskRemoved`. Flipping to `true` is an explicit user
 * opt-in to a single-gesture kill (swipe-from-recents stops playback and
 * dismisses the foreground service notification in one step).
 */
interface PlaybackLifecyclePreferences {

    /** Latest persisted value of the swipe-from-recents stop toggle. Default: `false`. */
    val stopOnTaskRemoved: Flow<Boolean>

    /** Persists the user's choice for the swipe-from-recents stop toggle. */
    suspend fun setStopOnTaskRemoved(value: Boolean)

    companion object {
        /**
         * Ships disabled so existing users see no behaviour change. YT-0076 AC#5
         * (notification persists after swipe-away) remains the default contract.
         */
        const val DEFAULT_STOP_ON_TASK_REMOVED: Boolean = false
    }
}
