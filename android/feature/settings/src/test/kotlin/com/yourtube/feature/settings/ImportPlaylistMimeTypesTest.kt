package com.yourtube.feature.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression tests for the SAF picker MIME filter used by Settings → Import playlist.
 *
 * See YT-0068: the previous filter included `text/plain` and the wildcard
 * `* / *` (without spaces), which collapsed SAF's filtering and surfaced
 * arbitrary system files (PNG, XML, screenshots, …).
 */
class ImportPlaylistMimeTypesTest {

    @Test
    fun `filter contains application json`() {
        assertTrue(
            IMPORT_PLAYLIST_MIME_TYPES.contains("application/json"),
            "Expected application/json so exported .ytplaylist.json files match",
        )
    }

    @Test
    fun `filter contains application octet-stream as a fallback`() {
        // Some content providers classify untyped JSON as octet-stream when no extension
        // mapping is available. Keep this entry until empirical evidence says otherwise.
        assertTrue(
            IMPORT_PLAYLIST_MIME_TYPES.contains("application/octet-stream"),
            "Expected application/octet-stream as a safety fallback",
        )
    }

    @Test
    fun `filter excludes text plain`() {
        assertFalse(
            IMPORT_PLAYLIST_MIME_TYPES.contains("text/plain"),
            "text/plain over-broadens the picker and is unnecessary",
        )
    }

    @Test
    fun `filter excludes wildcard`() {
        assertFalse(
            IMPORT_PLAYLIST_MIME_TYPES.contains("*/*"),
            "*/* collapses the SAF filter so every file appears — must not be present",
        )
    }

    @Test
    fun `filter has exactly the documented entries`() {
        // Pin the array contents so changes are deliberate. Update this list and the
        // KDoc on IMPORT_PLAYLIST_MIME_TYPES if narrowing further or relaxing.
        assertEquals(
            listOf("application/json", "application/octet-stream"),
            IMPORT_PLAYLIST_MIME_TYPES.toList(),
        )
    }
}
