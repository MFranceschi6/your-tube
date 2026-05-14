package com.yourtube.feature.search

import com.yourtube.core.common.ConnectivityMonitor
import com.yourtube.core.common.model.SearchResult
import com.yourtube.core.data.preferences.RecentSearchPreferences
import com.yourtube.core.network.SuggestClient
import com.yourtube.core.network.YoutubeService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val service = mockk<YoutubeService>()
    private val suggestClient = mockk<SuggestClient>(relaxed = true)

    /**
     * Hand-written fake (no Mockito) — the catalog rule is "use the platform
     * connectivity API to discriminate offline vs. server error", so the test
     * surface needs to control [isOnline] directly. A `ConnectivityMonitor`
     * fun-interface fake is the smallest seam.
     */
    private class FakeConnectivityMonitor(var online: Boolean = true) : ConnectivityMonitor {
        override fun isOnline(): Boolean = online
    }

    /** Minimal no-op fake for [RecentSearchPreferences]. */
    private class FakeRecentSearchPreferences : RecentSearchPreferences {
        private val _searches = MutableStateFlow<List<String>>(emptyList())
        override fun getRecentSearches(): Flow<List<String>> = _searches
        override suspend fun addRecentSearch(query: String) {}
        override suspend fun removeRecentSearch(query: String) {}
        override suspend fun deleteAll() {}
    }

    /**
     * Stateful fake that enforces the contract: head-insert, case-insensitive
     * dedup, cap at [RecentSearchPreferences.MAX_RECENTS].
     */
    private class StatefulFakeRecentSearchPreferences : RecentSearchPreferences {
        private val _searches = MutableStateFlow<List<String>>(emptyList())
        override fun getRecentSearches(): Flow<List<String>> = _searches
        override suspend fun addRecentSearch(query: String) {
            val t = query.trim()
            if (t.isBlank()) return
            val current = _searches.value
            _searches.value =
                (listOf(t) + current.filterNot { it.equals(t, ignoreCase = true) })
                    .take(RecentSearchPreferences.MAX_RECENTS)
        }
        override suspend fun removeRecentSearch(query: String) {
            _searches.value = _searches.value.filterNot { it.equals(query, ignoreCase = true) }
        }
        override suspend fun deleteAll() {
            _searches.value = emptyList()
        }
    }

    private fun makeVm(
        connectivity: ConnectivityMonitor = FakeConnectivityMonitor(),
    ) = SearchViewModel(service, connectivity, suggestClient, FakeRecentSearchPreferences())

    private fun makeVmWithRecents(
        connectivity: ConnectivityMonitor = FakeConnectivityMonitor(),
        recents: StatefulFakeRecentSearchPreferences = StatefulFakeRecentSearchPreferences(),
    ) = Pair(
        SearchViewModel(service, connectivity, suggestClient, recents),
        recents,
    )

    private val sampleResult = SearchResult(
        videoId = "abc123",
        title = "Test Song",
        channel = "Test Artist",
        durationSec = 200,
        thumbnailUrl = "",
    )

    // ── Existing state-machine tests ─────────────────────────────────────────

    @Test
    fun `initial state is Idle`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val vm = makeVm()
        try { assertIs<SearchUiState.Idle>(vm.uiState.value) }
        finally { Dispatchers.resetMain() }
    }

    @Test
    fun `search Loading transitions to Content when results are non-empty`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        coEvery { service.searchVideos("lofi", null) } returns listOf(sampleResult)
        val vm = makeVm()
        try {
            vm.onQueryChange("lofi")
            vm.search()
            advanceUntilIdle()
            val state = vm.uiState.value
            assertIs<SearchUiState.Content>(state)
            assertEquals(listOf(sampleResult), state.results)
        } finally { Dispatchers.resetMain() }
    }

    @Test
    fun `search Loading transitions to Empty when results are empty`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        coEvery { service.searchVideos(any(), any()) } returns emptyList()
        val vm = makeVm()
        try {
            vm.onQueryChange("test")
            vm.search()
            advanceUntilIdle()
            val state = vm.uiState.value
            assertIs<SearchUiState.Empty>(state)
            assertEquals("test", state.query)
        } finally { Dispatchers.resetMain() }
    }

    @Test
    fun `search error maps to Error offline=true when device is offline`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        coEvery { service.searchVideos(any(), any()) } throws RuntimeException("network fail")
        val vm = makeVm(FakeConnectivityMonitor(online = false))
        try {
            vm.onQueryChange("fail")
            vm.search()
            advanceUntilIdle()
            val state = vm.uiState.value
            assertIs<SearchUiState.Error>(state)
            assertTrue(state.offline)
        } finally { Dispatchers.resetMain() }
    }

    @Test
    fun `search error maps to Error offline=false when device is online`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        coEvery { service.searchVideos(any(), any()) } throws RuntimeException("server fail")
        val vm = makeVm(FakeConnectivityMonitor(online = true))
        try {
            vm.onQueryChange("fail")
            vm.search()
            advanceUntilIdle()
            val state = vm.uiState.value
            assertIs<SearchUiState.Error>(state)
            assertEquals(false, state.offline)
        } finally { Dispatchers.resetMain() }
    }

    @Test
    fun `clearQuery resets to Idle`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        coEvery { service.searchVideos(any(), any()) } returns listOf(sampleResult)
        val vm = makeVm()
        try {
            vm.onQueryChange("lofi")
            vm.search()
            advanceUntilIdle()
            vm.clearQuery()
            assertIs<SearchUiState.Idle>(vm.uiState.value)
        } finally { Dispatchers.resetMain() }
    }

    // ── Filter tests ─────────────────────────────────────────────────────────

    @Test
    fun `filter change after query submitted re-runs search with sp param`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        coEvery { service.searchVideos("lofi", null) } returns listOf(sampleResult)
        coEvery { service.searchVideos("lofi", DurationFilter.Short.sp) } returns listOf(sampleResult)
        val vm = makeVm()
        try {
            vm.onQueryChange("lofi")
            vm.search()
            advanceUntilIdle()

            // Apply duration filter
            vm.onFilterChange(SearchFilters().withDuration(DurationFilter.Short))
            advanceUntilIdle()

            coVerify(exactly = 1) { service.searchVideos("lofi", DurationFilter.Short.sp) }
        } finally { Dispatchers.resetMain() }
    }

    @Test
    fun `filter change with identical sp does not re-run search`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        coEvery { service.searchVideos("lofi", null) } returns listOf(sampleResult)
        val vm = makeVm()
        try {
            vm.onQueryChange("lofi")
            vm.search()
            advanceUntilIdle()

            // Applying the same filters object (all Any → sp == null) must not issue another request
            vm.onFilterChange(SearchFilters())
            advanceUntilIdle()

            // searchVideos(lofi, null) called only once (the original search)
            coVerify(exactly = 1) { service.searchVideos("lofi", null) }
        } finally { Dispatchers.resetMain() }
    }

    @Test
    fun `filter change before any query does not trigger search`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val vm = makeVm()
        try {
            // No query submitted — changing filter must stay Idle
            vm.onFilterChange(SearchFilters().withDuration(DurationFilter.Short))
            advanceUntilIdle()

            assertIs<SearchUiState.Idle>(vm.uiState.value)
            coVerify(exactly = 0) { service.searchVideos(any(), any()) }
        } finally { Dispatchers.resetMain() }
    }

    @Test
    fun `selecting type filter clears duration group in sp`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val base = SearchFilters().withDuration(DurationFilter.Short)
        // withType clears duration
        val updated = base.withType(TypeFilter.Video)
        assertEquals(DurationFilter.Any, updated.duration)
        assertEquals(TypeFilter.Video, updated.type)
        assertEquals(TypeFilter.Video.sp, updated.sp)
    }

    @Test
    fun `clearing filter returns to unfiltered query`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        coEvery { service.searchVideos("lofi", null) } returns listOf(sampleResult)
        coEvery { service.searchVideos("lofi", DurationFilter.Short.sp) } returns listOf(sampleResult)
        coEvery { service.searchVideos("lofi", any()) } returns listOf(sampleResult)
        val vm = makeVm()
        try {
            vm.onQueryChange("lofi")
            vm.search()
            advanceUntilIdle()

            vm.onFilterChange(SearchFilters().withDuration(DurationFilter.Short))
            advanceUntilIdle()

            // Clear filter back to Any — sp becomes null
            vm.onFilterChange(SearchFilters())
            advanceUntilIdle()

            // Third call: searchVideos("lofi", null) — once on initial submit, once on clear
            coVerify(exactly = 2) { service.searchVideos("lofi", null) }
        } finally { Dispatchers.resetMain() }
    }

    @Test
    fun `clearQuery resets filters to default`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        coEvery { service.searchVideos(any(), any()) } returns listOf(sampleResult)
        val vm = makeVm()
        try {
            // Set a non-Any filter
            vm.onQueryChange("lofi")
            vm.search()
            advanceUntilIdle()
            vm.onFilterChange(SearchFilters().withDuration(DurationFilter.Short))
            advanceUntilIdle()
            // Confirm filter is active
            assertEquals(DurationFilter.Short, vm.filters.value.duration)

            // clearQuery must reset filters
            vm.clearQuery()
            assertEquals(SearchFilters(), vm.filters.value)
        } finally { Dispatchers.resetMain() }
    }

    @Test
    fun `switching from DurationFilter Short to UploadDateFilter Today re-runs search and resets duration`() =
        runTest(dispatcher) {
            Dispatchers.setMain(dispatcher)
            coEvery { service.searchVideos("lofi", null) } returns listOf(sampleResult)
            coEvery { service.searchVideos("lofi", DurationFilter.Short.sp) } returns listOf(sampleResult)
            coEvery { service.searchVideos("lofi", UploadDateFilter.Today.sp) } returns listOf(sampleResult)
            val vm = makeVm()
            try {
                vm.onQueryChange("lofi")
                vm.search()
                advanceUntilIdle()

                // Select Duration: Short
                vm.onFilterChange(SearchFilters().withDuration(DurationFilter.Short))
                advanceUntilIdle()
                assertEquals(DurationFilter.Short, vm.filters.value.duration)

                // Switch to Upload Date: Today — withUploadDate clears duration
                vm.onFilterChange(SearchFilters().withUploadDate(UploadDateFilter.Today))
                advanceUntilIdle()

                // Duration group must be reset to Any (mutually-exclusive contract)
                assertEquals(DurationFilter.Any, vm.filters.value.duration)
                assertEquals(UploadDateFilter.Today, vm.filters.value.uploadDate)

                // Search must have re-run with the upload date sp
                coVerify(exactly = 1) { service.searchVideos("lofi", UploadDateFilter.Today.sp) }
            } finally { Dispatchers.resetMain() }
        }

    // ── Suggestions ───────────────────────────────────────────────────────

    @Test
    fun `suggestions emitted after 200ms debounce when online`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        coEvery { suggestClient.fetchSuggestions("lofi") } returns listOf("lofi hip hop", "lofi music")
        val vm = makeVm()
        try {
            vm.onQueryChange("lofi")
            // Before debounce fires
            assertEquals(emptyList(), vm.suggestions.value)
            // Advance past 200ms debounce
            advanceTimeBy(250)
            advanceUntilIdle()
            assertEquals(listOf("lofi hip hop", "lofi music"), vm.suggestions.value)
        } finally { Dispatchers.resetMain() }
    }

    @Test
    fun `suggestions are empty when query is blank`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val vm = makeVm()
        try {
            // query starts blank; debounce fires but ViewModel returns empty when q.isBlank()
            advanceTimeBy(250)
            advanceUntilIdle()
            assertEquals(emptyList(), vm.suggestions.value)
        } finally { Dispatchers.resetMain() }
    }

    @Test
    fun `suggestions are empty when device is offline`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        coEvery { suggestClient.fetchSuggestions(any()) } returns listOf("should not appear")
        val vm = makeVm(connectivity = FakeConnectivityMonitor(online = false))
        try {
            vm.onQueryChange("lofi")
            advanceTimeBy(250)
            advanceUntilIdle()
            // ViewModel guards on isOnline() before calling suggestClient
            assertEquals(emptyList(), vm.suggestions.value)
        } finally { Dispatchers.resetMain() }
    }

    // ── Recent searches ───────────────────────────────────────────────────

    @Test
    fun `search saves query to recents`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        coEvery { service.searchVideos(any(), any()) } returns listOf(sampleResult)
        val (vm, _) = makeVmWithRecents()
        try {
            vm.onQueryChange("lofi")
            vm.search()
            advanceUntilIdle()
            assertEquals(listOf("lofi"), vm.recentSearches.value)
        } finally { Dispatchers.resetMain() }
    }

    @Test
    fun `onSuggestionTap fills query, saves to recents, and runs search`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        coEvery { service.searchVideos("lofi chill", null) } returns listOf(sampleResult)
        val (vm, _) = makeVmWithRecents()
        try {
            vm.onSuggestionTap("lofi chill")
            advanceUntilIdle()
            assertEquals("lofi chill", vm.query.value)
            assertEquals(listOf("lofi chill"), vm.recentSearches.value)
            assertIs<SearchUiState.Content>(vm.uiState.value)
        } finally { Dispatchers.resetMain() }
    }

    @Test
    fun `onRemoveRecentSearch removes entry from recents`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        coEvery { service.searchVideos(any(), any()) } returns listOf(sampleResult)
        val (vm, _) = makeVmWithRecents()
        try {
            vm.onQueryChange("lofi")
            vm.search()
            advanceUntilIdle()
            assertEquals(listOf("lofi"), vm.recentSearches.value)

            vm.onRemoveRecentSearch("lofi")
            advanceUntilIdle()
            assertEquals(emptyList(), vm.recentSearches.value)
        } finally { Dispatchers.resetMain() }
    }

    @Test
    fun `recents are deduped case-insensitively on insert`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        coEvery { service.searchVideos(any(), any()) } returns listOf(sampleResult)
        val (vm, _) = makeVmWithRecents()
        try {
            vm.onQueryChange("Lofi")
            vm.search()
            advanceUntilIdle()

            vm.onQueryChange("lofi")
            vm.search()
            advanceUntilIdle()

            // Newest casing wins; list has exactly one entry
            assertEquals(1, vm.recentSearches.value.size)
            assertEquals("lofi", vm.recentSearches.value.first())
        } finally { Dispatchers.resetMain() }
    }

    // ── AC5 regression: showSuggestions sentinel ──────────────────────────

    @Test
    fun `showSuggestions is false initially`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val vm = makeVm()
        try {
            assertFalse(vm.showSuggestions.value)
        } finally { Dispatchers.resetMain() }
    }

    @Test
    fun `showSuggestions becomes true when query is non-empty`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val vm = makeVm()
        try {
            vm.onQueryChange("lo")
            assertTrue(vm.showSuggestions.value)
        } finally { Dispatchers.resetMain() }
    }

    @Test
    fun `showSuggestions is false immediately after search() — panel hidden on submit`() =
        runTest(dispatcher) {
            Dispatchers.setMain(dispatcher)
            coEvery { suggestClient.fetchSuggestions("lofi") } returns listOf("lofi hip hop")
            coEvery { service.searchVideos("lofi", null) } returns listOf(sampleResult)
            val vm = makeVm()
            try {
                vm.onQueryChange("lofi")
                assertTrue(vm.showSuggestions.value)

                vm.search()
                // showSuggestions must be false immediately — before any coroutine settles
                assertFalse(vm.showSuggestions.value)

                advanceUntilIdle()
                // Still false after the search completes
                assertFalse(vm.showSuggestions.value)
            } finally { Dispatchers.resetMain() }
        }

    @Test
    fun `showSuggestions is false immediately after onSuggestionTap()`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        coEvery { suggestClient.fetchSuggestions("lofi") } returns listOf("lofi hip hop")
        coEvery { service.searchVideos("lofi hip hop", null) } returns listOf(sampleResult)
        val vm = makeVm()
        try {
            vm.onQueryChange("lofi")
            assertTrue(vm.showSuggestions.value)

            vm.onSuggestionTap("lofi hip hop")
            assertFalse(vm.showSuggestions.value)

            advanceUntilIdle()
            assertFalse(vm.showSuggestions.value)
        } finally { Dispatchers.resetMain() }
    }

    @Test
    fun `showSuggestions becomes true again after typing following a submit`() =
        runTest(dispatcher) {
            Dispatchers.setMain(dispatcher)
            coEvery { service.searchVideos(any(), any()) } returns listOf(sampleResult)
            val vm = makeVm()
            try {
                vm.onQueryChange("lofi")
                vm.search()
                assertFalse(vm.showSuggestions.value)

                // User edits the query after submission — panel should re-appear
                vm.onQueryChange("lofi ")
                assertTrue(vm.showSuggestions.value)
            } finally { Dispatchers.resetMain() }
        }

    @Test
    fun `showSuggestions is false after clearQuery()`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val vm = makeVm()
        try {
            vm.onQueryChange("lo")
            assertTrue(vm.showSuggestions.value)

            vm.clearQuery()
            assertFalse(vm.showSuggestions.value)
        } finally { Dispatchers.resetMain() }
    }

    // ── YT-0319: recentSuggestions, cap, and restore ─────────────────────────────

    @Test
    fun `recentSuggestions caps at 6 entries for chip strip`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        coEvery { service.searchVideos(any(), any()) } returns listOf(sampleResult)
        val recents = StatefulFakeRecentSearchPreferences()
        val vm = SearchViewModel(service, FakeConnectivityMonitor(), suggestClient, recents)
        try {
            for (i in 1..8) {
                vm.onQueryChange("query$i")
                vm.search()
                advanceUntilIdle()
            }
            assertEquals(6, vm.recentSuggestions.value.size)
            assertEquals(8, vm.recentSearches.value.size)
        } finally { Dispatchers.resetMain() }
    }

    @Test
    fun `cap enforcement 21st entry evicts oldest beyond 20`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        coEvery { service.searchVideos(any(), any()) } returns listOf(sampleResult)
        val recents = StatefulFakeRecentSearchPreferences()
        val vm = SearchViewModel(service, FakeConnectivityMonitor(), suggestClient, recents)
        try {
            for (i in 1..21) {
                vm.onQueryChange("query$i")
                vm.search()
                advanceUntilIdle()
            }
            assertEquals(RecentSearchPreferences.MAX_RECENTS, vm.recentSearches.value.size)
            assertFalse(vm.recentSearches.value.contains("query1"))
            assertTrue(vm.recentSearches.value.contains("query21"))
        } finally { Dispatchers.resetMain() }
    }

    @Test
    fun `onRestoreRecentSearch re-adds query at MRU head`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        coEvery { service.searchVideos(any(), any()) } returns listOf(sampleResult)
        val (vm, _) = makeVmWithRecents()
        try {
            vm.onQueryChange("lofi")
            vm.search()
            advanceUntilIdle()
            assertEquals(listOf("lofi"), vm.recentSearches.value)

            vm.onRemoveRecentSearch("lofi")
            advanceUntilIdle()
            assertEquals(emptyList(), vm.recentSearches.value)

            vm.onRestoreRecentSearch("lofi")
            advanceUntilIdle()
            assertEquals(listOf("lofi"), vm.recentSearches.value)
        } finally { Dispatchers.resetMain() }
    }

    // ── Voice search ─────────────────────────────────────────────────────────

    @Test
    fun `onVoiceResult sets query and submits search`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        coEvery { service.searchVideos("jazz", null) } returns listOf(sampleResult)
        val vm = makeVm()
        try {
            vm.onVoiceResult("jazz")
            advanceUntilIdle()

            // Query field must reflect the transcription.
            assertEquals("jazz", vm.query.value)
            // A search must have been issued and resolved to Content.
            assertIs<SearchUiState.Content>(vm.uiState.value)
            // The suggestion panel must be hidden (search() resets it).
            assertFalse(vm.showSuggestions.value)
        } finally { Dispatchers.resetMain() }
    }

}
