package com.yourtube.core.network

// Desktop Chrome UA matches the InnerTube `WEB` clientName and avoids the bot-detection
// asymmetry that mobile-UA + WEB-client triggers.
internal const val YOUTUBE_DESKTOP_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

// TODO(YT-0042 follow-up): YouTube rejects stale clientVersions over time. Track upstream
// (e.g. yt-dlp's youtube extractor) and bump, or fall back to scraping ytcfg from /watch.
internal const val INNERTUBE_CLIENT_VERSION = "2.20240726.00.00"
