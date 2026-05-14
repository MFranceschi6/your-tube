package com.yourtube.core.data.update

/**
 * YT-0251 — result of comparing the installed build against the latest hosted release.
 *
 * - [UpToDate]        — installed `versionCode` >= latest; no prompt needed.
 * - [UpdateAvailable] — newer build exists; non-blocking prompt with release notes.
 * - [UpdateRequired]  — `minimumSupportedVersionCode` > installed build; blocking gate
 *                       before the main experience. Only the "Update" CTA is available.
 *
 * `notes` is the raw release-notes string from the hosted JSON feed; `apkUrl` is the
 * direct download URL that should be opened in the system browser via Intent.ACTION_VIEW.
 */
sealed class UpdateStatus {
    data object UpToDate : UpdateStatus()

    /** Network failure, HTTP error, or malformed feed. Playback continues; Settings shows error note. */
    data object CheckFailed : UpdateStatus()

    data class UpdateAvailable(
        val latestVersionName: String,
        val notes: String,
        val apkUrl: String,
    ) : UpdateStatus()

    data class UpdateRequired(
        val latestVersionName: String,
        val notes: String,
        val apkUrl: String,
    ) : UpdateStatus()
}
