package com.yourtube.core.network

import com.yourtube.core.common.model.SearchResult

/**
 * YT-0297 — one page of Mix candidates plus the continuation token (if any) needed to
 * fetch the next page. [items] is the ordered list of Mix entries parsed from the
 * current page; [nextToken] is the token to feed into [YoutubeService.getMixContinuation]
 * for the next page. `null` means the server did not return a continuation block — the
 * consumer must stop paginating and fall back to autoplay-related at the queue tail.
 *
 * See `docs/mix-queue.md#continuation--pagination` for the full pagination contract.
 */
data class MixPage(
    val items: List<SearchResult>,
    val nextToken: String?,
) {
    companion object {
        fun empty(): MixPage = MixPage(items = emptyList(), nextToken = null)
    }
}

interface YoutubeService {
    suspend fun searchVideos(query: String, sp: String? = null): List<SearchResult>

    suspend fun resolveAudioStream(
        videoId: String,
        preferredMaxBitrateKbps: Int = DEFAULT_PREFERRED_MAX_BITRATE_KBPS,
    ): ResolvedAudioStream

    /**
     * YT-0089 — returns the ordered list of related video candidates for [videoId].
     *
     * Corresponds to `StreamInfo.getRelatedItems()` in the NewPipeExtractor contract
     * (`docs/autoplay.md`). The list is sourced from the InnerTube `/next` endpoint's
     * `secondaryResults` ribbon and is returned in extractor-response order.
     *
     * An empty list is returned on any network or parse failure — callers must not throw.
     * Eligibility filtering (duration, history loop-avoidance, language) is applied by
     * [com.yourtube.core.player.DefaultAutoplayController], not here.
     */
    suspend fun getRelatedVideos(videoId: String): List<SearchResult>

    /**
     * YT-0293 — returns the ordered Mix queue for [videoId] from the InnerTube `/next` endpoint
     * with `playlistId = "RD$videoId"`. The first entry is the seed track itself.
     * Returns empty on failure; callers must not throw on empty.
     *
     * See `docs/mix-queue.md` for the full contract including parse path, field mapping,
     * and filtering rules.
     */
    suspend fun getMixQueue(videoId: String): List<SearchResult>

    /**
     * YT-0297 — returns the initial Mix page for [videoId] along with the first
     * continuation token (if any) so callers can lazily extend the Mix beyond the
     * initial ~25 entries. Same wire call as [getMixQueue]; the difference is the
     * exposed continuation token.
     *
     * Returns [MixPage.empty] on any network or parse failure. Callers must not throw.
     * See `docs/mix-queue.md#continuation--pagination` for the full contract.
     */
    suspend fun getMixQueueWithContinuation(videoId: String): MixPage

    /**
     * YT-0297 — fetches the next Mix page given a continuation [token] obtained from a
     * previous [getMixQueueWithContinuation] or [getMixContinuation] call.
     *
     * Returns [MixPage.empty] on HTTP failure, parse error, or if [token] is blank.
     * A non-null [MixPage.nextToken] means the consumer SHOULD continue paginating;
     * `null` means stop and fall back to autoplay-related at the queue tail.
     */
    suspend fun getMixContinuation(token: String): MixPage

    companion object {
        const val DEFAULT_PREFERRED_MAX_BITRATE_KBPS = 160
    }
}
