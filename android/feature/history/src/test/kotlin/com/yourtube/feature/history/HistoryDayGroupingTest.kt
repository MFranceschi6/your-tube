package com.yourtube.feature.history

import com.yourtube.core.common.model.Track
import com.yourtube.core.data.model.PlaybackHistoryEntry
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class HistoryDayGroupingTest {

    private val utc = ZoneId.of("UTC")
    private val today = LocalDate.of(2026, 1, 15)

    private fun entry(id: String, playedAt: String) = PlaybackHistoryEntry(
        id = id,
        track = Track(id, "Song $id", "Artist", 200, ""),
        playedAt = playedAt,
    )

    @Test
    fun `empty input returns empty groups`() {
        assertEquals(emptyList(), groupByDay(emptyList(), today, utc))
    }

    @Test
    fun `single today entry produces Today group`() {
        val entries = listOf(entry("1", "2026-01-15T10:00:00Z"))
        val groups = groupByDay(entries, today, utc)
        assertEquals(1, groups.size)
        assertEquals("Today", groups[0].header)
        assertEquals(1, groups[0].entries.size)
    }

    @Test
    fun `yesterday entry produces Yesterday group`() {
        val entries = listOf(entry("1", "2026-01-14T10:00:00Z"))
        val groups = groupByDay(entries, today, utc)
        assertEquals(1, groups.size)
        assertEquals("Yesterday", groups[0].header)
    }

    @Test
    fun `older entry produces formatted date label`() {
        val entries = listOf(entry("1", "2026-01-10T10:00:00Z"))
        val groups = groupByDay(entries, today, utc)
        val expected = LocalDate.of(2026, 1, 10)
            .format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault()))
        assertEquals(1, groups.size)
        assertEquals(expected, groups[0].header)
    }

    @Test
    fun `multiple days produce separate groups newest-first`() {
        val entries = listOf(
            entry("1", "2026-01-15T10:00:00Z"),
            entry("2", "2026-01-14T08:00:00Z"),
            entry("3", "2026-01-10T06:00:00Z"),
        )
        val groups = groupByDay(entries, today, utc)
        val expectedOlder = LocalDate.of(2026, 1, 10)
            .format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault()))
        assertEquals(3, groups.size)
        assertEquals("Today", groups[0].header)
        assertEquals("Yesterday", groups[1].header)
        assertEquals(expectedOlder, groups[2].header)
    }

    @Test
    fun `same-day entries are sorted newest-first within group`() {
        val entries = listOf(
            entry("1", "2026-01-15T08:00:00Z"),
            entry("2", "2026-01-15T10:00:00Z"),
        )
        val groups = groupByDay(entries, today, utc)
        assertEquals(1, groups.size)
        assertEquals("2", groups[0].entries[0].id)
        assertEquals("1", groups[0].entries[1].id)
    }

    @Test
    fun `day boundary at midnight UTC keeps entries in correct groups`() {
        val entries = listOf(
            entry("1", "2026-01-15T00:00:00Z"),
            entry("2", "2026-01-14T23:59:59Z"),
        )
        val groups = groupByDay(entries, today, utc)
        assertEquals(2, groups.size)
        assertEquals("Today", groups[0].header)
        assertEquals("Yesterday", groups[1].header)
    }
}
