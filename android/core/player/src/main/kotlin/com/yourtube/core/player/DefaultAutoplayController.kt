package com.yourtube.core.player

import com.yourtube.core.common.model.Track
import com.yourtube.core.data.preferences.AutoplayPreferences
import com.yourtube.core.network.YoutubeService
import dagger.Lazy
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * YT-0089 — production [AutoplayController].
 *
 * History buffer:
 *  - [ArrayDeque] capped at [HISTORY_CAPACITY] (20) entries.
 *  - Populated by [onTrackStarted] on every track start.
 *  - In-memory only; does NOT persist across cold launches (per contract).
 *
 * Eligibility filter applied in order per `docs/autoplay.md`:
 *  1. Non-empty `videoId`.
 *  2. `durationSec >= 60` (excludes Shorts and zero-duration entries that
 *     indicate a livestream from the `/next` surface).
 *  3. Not in the history buffer (loop avoidance).
 *  4. Language preference (soft): title text is not language-tagged by the
 *     InnerTube `/next` endpoint, so language detection is unavailable.
 *     The contract specifies "do NOT skip when language is not exposed" —
 *     candidates pass through ordering by extractor return order.
 *
 * [fetchAndEnqueue] reads the autoplay pref first; returns `false` immediately
 * when disabled. On network or parse failure, returns `false` silently.
 */
@Singleton
class DefaultAutoplayController @Inject constructor(
    private val youtubeService: YoutubeService,
    private val autoplayPreferences: AutoplayPreferences,
    // YT-0100 — wrapped in Lazy<> to break the Dagger DependencyCycle between
    // DefaultPlayerController (depends on AutoplayController) and
    // DefaultAutoplayController (depends on PlayerController). Lazy defers resolution
    // until the first .get() call, which happens after both singletons are created.
    private val playerController: Lazy<PlayerController>,
) : AutoplayController {

    // in-memory rolling buffer — last HISTORY_CAPACITY videoIds that started playing.
    private val history = ArrayDeque<String>(HISTORY_CAPACITY)

    override fun onTrackStarted(videoId: String) {
        if (videoId.isBlank()) return
        // Enforce capacity: drop the oldest entry before adding the new one.
        if (history.size >= HISTORY_CAPACITY) {
            history.removeFirst()
        }
        history.addLast(videoId)
    }

    override suspend fun isAutoplayEnabled(): Boolean = autoplayPreferences.autoplayEnabled.first()

    override suspend fun fetchAndEnqueue(finishedVideoId: String): Boolean {
        // Check the toggle first — short-circuit before any network call.
        val enabled = autoplayPreferences.autoplayEnabled.first()
        if (!enabled) return false

        val candidates = runCatching {
            youtubeService.getRelatedVideos(finishedVideoId)
        }.getOrElse { return false }

        if (candidates.isEmpty()) return false

        val deviceLanguage = Locale.getDefault().language

        // Two-pass selection implementing the soft language preference:
        // Pass 1 — collect eligible candidates; also note the first that matches device language.
        // Pass 2 — pick the language-matching candidate if any; otherwise the first eligible.
        var firstEligible: Track? = null
        var firstLanguageMatch: Track? = null

        for (candidate in candidates) {
            if (!isEligible(candidate.videoId, candidate.durationSec)) continue
            val track = Track(
                videoId = candidate.videoId,
                title = candidate.title,
                channel = candidate.channel,
                durationSec = candidate.durationSec,
                thumbnailUrl = candidate.thumbnailUrl,
            )
            if (firstEligible == null) firstEligible = track

            // Language soft-filter: attempt a simple script/charset heuristic from the title.
            // The InnerTube `/next` surface does NOT expose an explicit language tag on related
            // items, so we use `Locale.getDefault().language` against a best-effort title
            // charset detection. Per contract: "When language is not exposed, do NOT skip."
            // This branch enriches selection but never eliminates candidates.
            if (firstLanguageMatch == null && titleMatchesLanguage(candidate.title, deviceLanguage)) {
                firstLanguageMatch = track
            }

            // We can stop scanning as soon as we have both a language match and a first eligible.
            if (firstEligible != null && firstLanguageMatch != null) break
        }

        val selected = firstLanguageMatch ?: firstEligible ?: return false

        runCatching { playerController.get().addToQueue(selected) }
            .onFailure { return false }

        return true
    }

    /**
     * Eligibility filter: resolvable + not a Short + not in history buffer.
     */
    internal fun isEligible(videoId: String, durationSec: Int): Boolean {
        if (videoId.isBlank()) return false
        if (durationSec < MIN_DURATION_SEC) return false
        if (history.contains(videoId)) return false
        return true
    }

    /**
     * Soft language-match heuristic. Per the contract, this is a preference, not a hard
     * filter. The InnerTube `/next` surface returns no explicit language tag, so we use a
     * Unicode-block scan on the title text as a proxy.
     *
     * Currently detects:
     * - `ja` (Japanese) — presence of Hiragana/Katakana.
     * - `ko` (Korean) — presence of Hangul.
     * - `zh` (Chinese) — presence of CJK Unified Ideographs (broad; includes Japanese Kanji).
     * - `ar` (Arabic) — presence of Arabic block.
     * - `th` (Thai) — presence of Thai block.
     * - All other languages (including `en`) fall through to `false`; selection then defaults
     *   to the first eligible by extractor order, per contract.
     *
     * This is intentionally conservative — a false-positive (wrong language selected) is
     * worse than no language promotion (first-eligible is still a sensible pick).
     */
    internal fun titleMatchesLanguage(title: String, language: String): Boolean {
        if (title.isBlank()) return false
        return when (language) {
            "ja" -> title.any { it.isInHiragana() || it.isInKatakana() }
            "ko" -> title.any { it.isInHangul() }
            "zh" -> title.any { it.isInCjkUnifiedIdeographs() }
            "ar" -> title.any { it.isInArabic() }
            "th" -> title.any { it.isInThai() }
            else -> false
        }
    }

    private fun Char.isInHiragana(): Boolean = this in '぀'..'ゟ'
    private fun Char.isInKatakana(): Boolean = this in '゠'..'ヿ'
    private fun Char.isInHangul(): Boolean = this in '가'..'힣'
    private fun Char.isInCjkUnifiedIdeographs(): Boolean = this in '一'..'鿿'
    private fun Char.isInArabic(): Boolean = this in '؀'..'ۿ'
    private fun Char.isInThai(): Boolean = this in '฀'..'๿'

    internal companion object {
        const val HISTORY_CAPACITY = 20
        const val MIN_DURATION_SEC = 60
    }
}
