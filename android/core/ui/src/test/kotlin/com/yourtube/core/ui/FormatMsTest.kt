package com.yourtube.core.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * YT-0278 — unit tests for [formatMs].
 *
 * Previously `formatMs` used an integer minute accumulator that would overflow on tracks
 * longer than 59:59, producing outputs like "600:00" instead of "10:00:00".  These tests
 * pin the corrected hour-aware formatter:
 *
 *  - Under one hour: "M:SS"
 *  - One hour or more: "H:MM:SS"
 */
class FormatMsTest {

    @Test
    fun under_one_minute_formats_as_zero_colon_ss() {
        assertEquals("0:42", formatMs(42_000L))
    }

    @Test
    fun exactly_zero_formats_as_zero_colon_zero_zero() {
        assertEquals("0:00", formatMs(0L))
    }

    @Test
    fun typical_track_under_one_hour() {
        // 618 seconds = 10 minutes 18 seconds
        assertEquals("10:18", formatMs(618_000L))
    }

    @Test
    fun exactly_59_minutes_59_seconds() {
        val ms = (59 * 60 + 59) * 1000L
        assertEquals("59:59", formatMs(ms))
    }

    @Test
    fun exactly_one_hour_formats_with_hours_component() {
        assertEquals("1:00:00", formatMs(3_600_000L))
    }

    @Test
    fun long_track_regression_36018_seconds() {
        // YT-0278 regression: 36 018 s would previously produce "600:18" instead of "10:00:18".
        assertEquals("10:00:18", formatMs(36_018_000L))
    }

    @Test
    fun long_track_exactly_ten_hours() {
        assertEquals("10:00:00", formatMs(36_000_000L))
    }

    @Test
    fun hours_minutes_and_seconds_all_non_zero() {
        // 2h 3m 4s = 7384 s
        assertEquals("2:03:04", formatMs(7_384_000L))
    }
}
