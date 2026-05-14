package com.yourtube.core.data.repository

/**
 * Typed result for [PlaylistRepository.addTrackToPlaylist]. Returned instead of
 * Unit so callers can distinguish a successful insert from a duplicate rejection
 * without catching exceptions.
 */
sealed interface AddTrackResult {
    /** The track was inserted successfully. */
    data object Added : AddTrackResult

    /** The track was already present in the playlist; no insert was performed. */
    data object AlreadyPresent : AddTrackResult
}
