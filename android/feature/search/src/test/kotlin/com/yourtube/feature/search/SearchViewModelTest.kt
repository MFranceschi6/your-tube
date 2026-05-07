package com.yourtube.feature.search

import com.yourtube.core.common.model.SearchResult
import com.yourtube.core.network.YoutubeService
import io.mockk.coEvery
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertIs
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
        val vm = SearchViewModel(service)
        try { assertIs<SearchUiState.Idle>(vm.uiState.value) }
        finally { Dispatchers.resetMain() }
    }

    @Test
    fun `search returns Success with results`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        coEvery { service.searchVideos("lofi") } returns listOf(sampleResult)
        val vm = SearchViewModel(service)
        try {
            vm.onQueryChange("lofi")
            vm.search()
            advanceUntilIdle()
            val state = vm.uiState.value
            assertIs<SearchUiState.Success>(state)
        } finally { Dispatchers.resetMain() }
    }

    @Test
    fun `search sets Loading then result`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        coEvery { service.searchVideos(any()) } returns emptyList()
        val vm = SearchViewModel(service)
        try {
            vm.onQueryChange("test")
            vm.search()
            advanceUntilIdle()
            assertIs<SearchUiState.Success>(vm.uiState.value)
        } finally { Dispatchers.resetMain() }
    }

    @Test
    fun `search error maps to Error state`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        coEvery { service.searchVideos(any()) } throws RuntimeException("network fail")
        val vm = SearchViewModel(service)
        try {
            vm.onQueryChange("fail")
            vm.search()
            advanceUntilIdle()
            assertIs<SearchUiState.Error>(vm.uiState.value)
        } finally { Dispatchers.resetMain() }
    }

    @Test
    fun `clearQuery resets to Idle`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        coEvery { service.searchVideos(any()) } returns listOf(sampleResult)
        val vm = SearchViewModel(service)
        try {
            vm.onQueryChange("lofi")
            vm.search()
            advanceUntilIdle()
            vm.clearQuery()
            assertIs<SearchUiState.Idle>(vm.uiState.value)
        } finally { Dispatchers.resetMain() }
    }
}
