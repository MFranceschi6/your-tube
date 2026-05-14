package com.yourtube.feature.history

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Formats a playback timestamp as a human-readable relative or absolute string.
 *
 * D2 table:
 *  - < 60 s        → "just now"
 *  - 1–59 min      → "N minute(s) ago"
 *  - 1–23 h        → "N hour(s) ago"
 *  - same calendar day (≥ 24 h handled by the hour bucket above) → "Today, HH:mm"
 *  - yesterday     → "Yesterday, HH:mm"
 *  - older         → "d MMM, HH:mm"  (e.g. "12 May, 18:04")
 */
fun formatHistoryTimestamp(
    now: Instant,
    playedAt: Instant,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String {
    val diff = Duration.between(playedAt, now)
    val nowDate = LocalDate.ofInstant(now, zone)
    val playedDate = LocalDate.ofInstant(playedAt, zone)
    val timeStr = playedAt.atZone(zone).toLocalTime()
        .format(DateTimeFormatter.ofPattern("HH:mm"))
    return when {
        diff.seconds < 60 -> "just now"
        diff.toMinutes() < 60 -> {
            val mins = diff.toMinutes()
            if (mins == 1L) "1 minute ago" else "$mins minutes ago"
        }
        diff.toHours() < 24 -> {
            val hrs = diff.toHours()
            if (hrs == 1L) "1 hour ago" else "$hrs hours ago"
        }
        playedDate == nowDate -> "Today, $timeStr"
        playedDate == nowDate.minusDays(1) -> "Yesterday, $timeStr"
        else -> {
            val dateStr = playedDate.format(
                DateTimeFormatter.ofPattern("d MMM", locale),
            )
            "$dateStr, $timeStr"
        }
    }
}
