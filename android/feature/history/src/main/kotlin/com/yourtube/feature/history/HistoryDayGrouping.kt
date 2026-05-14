package com.yourtube.feature.history

import com.yourtube.core.data.model.PlaybackHistoryEntry
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class HistoryGroup(
    val header: String,
    val entries: List<PlaybackHistoryEntry>,
)

fun groupByDay(
    entries: List<PlaybackHistoryEntry>,
    now: LocalDate = LocalDate.now(),
    zone: ZoneId = ZoneId.systemDefault(),
): List<HistoryGroup> {
    if (entries.isEmpty()) return emptyList()

    val yesterday = now.minusDays(1)
    val formatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())

    return entries
        .groupBy { entry ->
            Instant.parse(entry.playedAt).atZone(zone).toLocalDate()
        }
        .entries
        .sortedByDescending { it.key }
        .map { (date, dayEntries) ->
            val header = when (date) {
                now -> "Today"
                yesterday -> "Yesterday"
                else -> date.format(formatter)
            }
            HistoryGroup(
                header = header,
                entries = dayEntries.sortedByDescending { Instant.parse(it.playedAt) },
            )
        }
}
