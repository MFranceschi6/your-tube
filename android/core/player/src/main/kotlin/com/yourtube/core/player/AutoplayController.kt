package com.yourtube.core.player

/**
 * YT-0089 — orchestrates the autoplay-next-track feature.
 *
 * Two concerns are separated:
 * - [onTrackStarted]: registers a track id in the rolling history buffer so
 *   loop-avoidance can exclude it from future autoplay candidates.
 * - [fetchAndEnqueue]: triggered when the queue empties at end-of-track; fetches
 *   related videos, applies eligibility rules, and enqueues the first eligible
 *   candidate via [PlayerController.addToQueue]. Returns `true` if a candidate
 *   was enqueued, `false` on any failure or absence of eligible candidates.
 *
 * See `docs/autoplay.md` for the full cross-platform contract.
 */
interface AutoplayController {

    /**
     * Records [videoId] in the in-memory history buffer (last 20).
     * Must be called when any track starts playing (from any source: queue,
     * autoplay, manual tap). Not coroutine-bound — purely in-memory.
     */
    fun onTrackStarted(videoId: String)

    /**
     * Fetches related videos for [finishedVideoId], selects the first eligible
     * candidate per the contract, enqueues it via [PlayerController], and advances
     * the queue. Returns `true` if a track was enqueued, `false` otherwise.
     *
     * Eligibility rules applied in order:
     *  1. `videoId` non-empty (resolvable).
     *  2. `durationSec >= 60` (not a Short / not a livestream).
     *  3. Not in the last-20 history buffer (loop avoidance).
     *  4. Soft language preference: if any eligible candidate matches device locale
     *     language, prefer the first such candidate; otherwise pick the first eligible
     *     by extractor order.
     *
     * A `false` return is always silent — no toast, no error state update.
     */
    suspend fun fetchAndEnqueue(finishedVideoId: String): Boolean

    /** YT-0300 — returns true when the user has autoplay enabled. Default: false (safe for test doubles). */
    suspend fun isAutoplayEnabled(): Boolean = false
}
