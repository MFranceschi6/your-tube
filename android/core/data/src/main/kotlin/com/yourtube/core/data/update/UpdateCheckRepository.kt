package com.yourtube.core.data.update

/**
 * YT-0251 — contract for fetching and evaluating hosted release metadata.
 *
 * Implementations fetch the JSON feed at [DEFAULT_FEED_URL], compare the
 * `versionCode` field against the installed build, and return an [UpdateStatus].
 *
 * Failure modes (network error, malformed JSON, HTTP error) return
 * [UpdateStatus.CheckFailed] — the caller must never crash when the update service
 * is unavailable.
 */
interface UpdateCheckRepository {

    /**
     * Fetch the hosted release feed and classify the result.
     *
     * @param installedVersionCode the current installed build number.
     *        Use `BuildConfig.VERSION_CODE.toLong()` at the call site.
     * @return [UpdateStatus] — never throws; network/parse errors return [UpdateStatus.CheckFailed].
     */
    suspend fun checkForUpdates(installedVersionCode: Long): UpdateStatus

    companion object {
        /**
         * Hosted metadata JSON path on GitHub Pages.
         *
         * Source of truth: docs/update-channel.md. The feed at this URL is served from
         * the orphan `gh-pages` branch of MFranceschi6/your-tube.
         */
        const val DEFAULT_FEED_URL =
            "https://mfranceschi6.github.io/your-tube/android/update.json"
    }
}
