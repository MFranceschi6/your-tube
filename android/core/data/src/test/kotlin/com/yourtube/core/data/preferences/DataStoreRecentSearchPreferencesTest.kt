package com.yourtube.core.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for [DataStoreRecentSearchPreferences] against the real
 * DataStore-backed implementation. Verifies the four contract points:
 *
 * 1. Head-insertion (newest first).
 * 2. Case-insensitive deduplication on insert.
 * 3. 10-entry cap ([RecentSearchPreferences.MAX_RECENTS]).
 * 4. [DataStoreRecentSearchPreferences.removeRecentSearch] is a no-op when
 *    the entry is absent.
 *
 * Uses Robolectric for the Android [Context] required by DataStore and
 * [PreferenceDataStoreFactory] to create an isolated on-disk store per test
 * run (temp file deleted in [tearDown]).
 *
 * The DataStore scope uses [UnconfinedTestDispatcher] so that `edit` blocks
 * execute eagerly (no `advanceUntilIdle` needed). Each `runTest` block
 * creates a fresh [TestScope] so the DataStore scope lifetime is independent
 * of the coroutine test harness lifecycle.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DataStoreRecentSearchPreferencesTest {

    private lateinit var datastoreFile: File

    /** Recreated for every test to ensure a clean file and a fresh scope. */
    private lateinit var dataStoreScope: TestScope
    private lateinit var prefs: DataStoreRecentSearchPreferences

    @BeforeTest
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        // Give each test an isolated file so tests cannot interfere with each other.
        datastoreFile = File(
            context.filesDir,
            "test_recent_searches_${System.nanoTime()}.preferences_pb",
        )
        // UnconfinedTestDispatcher makes DataStore's internal IO run eagerly
        // without needing explicit clock advancement.
        dataStoreScope = TestScope(UnconfinedTestDispatcher())
        val dataStore = PreferenceDataStoreFactory.create(
            scope = dataStoreScope,
            produceFile = { datastoreFile },
        )
        prefs = DataStoreRecentSearchPreferences(dataStore)
    }

    @AfterTest
    fun tearDown() {
        dataStoreScope.cancel()
        datastoreFile.delete()
    }

    // ── 1. Head-insertion ─────────────────────────────────────────────────

    @Test
    fun `addRecentSearch inserts at head`() = runTest {
        prefs.addRecentSearch("alpha")
        prefs.addRecentSearch("beta")
        prefs.addRecentSearch("gamma")

        val result = prefs.getRecentSearches().first()

        // Newest entry must be first
        assertEquals("gamma", result[0])
        assertEquals("beta", result[1])
        assertEquals("alpha", result[2])
    }

    // ── 2. Case-insensitive deduplication ─────────────────────────────────

    @Test
    fun `addRecentSearch deduplicates case-insensitively and promotes to head`() = runTest {
        prefs.addRecentSearch("lofi")
        prefs.addRecentSearch("ambient")
        // Re-insert "LOFI" — must remove the original and head-insert with new casing
        prefs.addRecentSearch("LOFI")

        val result = prefs.getRecentSearches().first()

        assertEquals(2, result.size, "List must stay at 2 after dedup")
        assertEquals("LOFI", result[0], "Latest casing must win")
        assertEquals("ambient", result[1])
    }

    @Test
    fun `addRecentSearch dedup is case-insensitive for mixed casing`() = runTest {
        prefs.addRecentSearch("Jazz Piano")
        prefs.addRecentSearch("jazz piano")

        val result = prefs.getRecentSearches().first()

        assertEquals(1, result.size)
        assertEquals("jazz piano", result[0])
    }

    // ── 3. 10-entry cap ───────────────────────────────────────────────────

    @Test
    fun `addRecentSearch caps list at MAX_RECENTS`() = runTest {
        // Insert MAX_RECENTS + 2 unique entries
        val over = RecentSearchPreferences.MAX_RECENTS + 2
        repeat(over) { i -> prefs.addRecentSearch("query-$i") }

        val result = prefs.getRecentSearches().first()

        assertEquals(RecentSearchPreferences.MAX_RECENTS, result.size)
        // Most recent entry must be at head
        assertEquals("query-${over - 1}", result[0])
        // Oldest two entries must have been dropped
        assertTrue("query-0" !in result, "query-0 should have been evicted")
        assertTrue("query-1" !in result, "query-1 should have been evicted")
    }

    // ── 4. removeRecentSearch is a no-op on missing entry ─────────────────

    @Test
    fun `removeRecentSearch on absent entry is a no-op`() = runTest {
        prefs.addRecentSearch("existing")

        // Remove something that was never added
        prefs.removeRecentSearch("nonexistent")

        val result = prefs.getRecentSearches().first()

        assertEquals(listOf("existing"), result)
    }

    @Test
    fun `removeRecentSearch removes the matching entry case-insensitively`() = runTest {
        prefs.addRecentSearch("Jazz")
        prefs.addRecentSearch("Blues")

        prefs.removeRecentSearch("jazz") // different casing from stored "Jazz"

        val result = prefs.getRecentSearches().first()

        assertEquals(listOf("Blues"), result)
    }

    // ── 5. deleteAll clears all entries ───────────────────────────────────

    @Test
    fun `deleteAll clears all entries`() = runTest {
        prefs.addRecentSearch("alpha")
        prefs.addRecentSearch("beta")
        prefs.addRecentSearch("gamma")

        prefs.deleteAll()

        val result = prefs.getRecentSearches().first()

        assertTrue(result.isEmpty(), "Expected empty list after deleteAll")
    }
}
