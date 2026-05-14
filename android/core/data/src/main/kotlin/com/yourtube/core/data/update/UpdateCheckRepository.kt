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
         * TODO: Replace with the real URL before the first public release, e.g.:
         *   https://<github-user>.github.io/<repo-name>/android/update.json
         *
         * The placeholder deliberately triggers a connection failure so graceful-degradation
         * is exercised by default in non-mocked environments.
         */
        const val DEFAULT_FEED_URL =
            "https://placeholder.example.invalid/android/update.json"
    }
}
