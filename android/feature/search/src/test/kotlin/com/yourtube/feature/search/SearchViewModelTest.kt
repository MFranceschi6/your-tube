package com.yourtube.feature.search

import com.yourtube.core.common.ConnectivityMonitor
import com.yourtube.core.common.model.SearchResult
import com.yourtube.core.network.YoutubeService
import io.mockk.coEvery
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val service = mockk<YoutubeService>()

    /**
     * Hand-written fake (no Mockito) — the catalog rule is "use the platform
     * connectivity API to discriminate offline vs. server error", so the test
     * surface needs to control [isOnline] directly. A `ConnectivityMonitor`
     * fun-interface fake is the smallest seam.
     */
    private class FakeConnectivityMonitor(var online: Boolean = true) : ConnectivityMonitor {
        override fun isOnline(): Boolean = online
    }

    private val sampleResult = SearchResult(
        videoId = "abc123",
        title = "Test Song",
        channel = "Test Artist",
        durationSec = 200,
        thumbnailUrl = "",
    )

    @Test
    fun `initial state is Idle`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val vm = SearchViewModel(service, FakeConnectivityMonitor())
        try { assertIs<SearchUiState.Idle>(vm.uiState.value) }
        finally { Dispatchers.resetMain() }
    }

    @Test
    fun `search Loading transitions to Content when results are non-empty`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        coEvery { service.searchVideos("lofi") } returns listOf(sampleResult)
        val vm = SearchViewModel(service, FakeConnectivityMonitor())
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
        coEvery { service.searchVideos(any()) } returns emptyList()
        val vm = SearchViewModel(service, FakeConnectivityMonitor())
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
        coEvery { service.searchVideos(any()) } throws RuntimeException("network fail")
        val vm = SearchViewModel(service, FakeConnectivityMonitor(online = false))
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
        coEvery { service.searchVideos(any()) } throws RuntimeException("server fail")
        val vm = SearchViewModel(service, FakeConnectivityMonitor(online = true))
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
        coEvery { service.searchVideos(any()) } returns listOf(sampleResult)
        val vm = SearchViewModel(service, FakeConnectivityMonitor())
        try {
            vm.onQueryChange("lofi")
            vm.search()
            advanceUntilIdle()
            vm.clearQuery()
            assertIs<SearchUiState.Idle>(vm.uiState.value)
        } finally { Dispatchers.resetMain() }
    }
}
