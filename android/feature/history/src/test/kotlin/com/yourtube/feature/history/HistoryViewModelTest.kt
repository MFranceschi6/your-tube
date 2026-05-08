package com.yourtube.feature.history

import com.yourtube.core.common.model.Track
import com.yourtube.core.data.model.PlaybackHistoryEntry
import com.yourtube.core.data.repository.PlaylistRepository
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val repository = mockk<PlaylistRepository>(relaxed = true)

    private val sampleEntry = PlaybackHistoryEntry(
        id = "e1",
        track = Track("v1", "Song", "Artist", 200, ""),
        playedAt = "2026-01-01T00:00:00Z",
    )

    @Test
    fun `uiState emits Empty when history is empty`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        every { repository.observeHistory() } returns flowOf(emptyList())
        val viewModel = HistoryViewModel(repository)
        try {
            advanceUntilIdle()
            assertIs<HistoryUiState.Empty>(viewModel.uiState.value)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `uiState emits Content with history from repository`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        every { repository.observeHistory() } returns flowOf(listOf(sampleEntry))
        val viewModel = HistoryViewModel(repository)
        try {
            advanceUntilIdle()
            val state = viewModel.uiState.value
            assertIs<HistoryUiState.Content>(state)
            assertEquals(listOf(sampleEntry), state.entries)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `uiState emits Error when the history flow throws`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        every { repository.observeHistory() } returns flow { throw RuntimeException("db boom") }
        val viewModel = HistoryViewModel(repository)
        try {
            advanceUntilIdle()
            assertIs<HistoryUiState.Error>(viewModel.uiState.value)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `clearHistory delegates to repository`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        every { repository.observeHistory() } returns flowOf(emptyList())
        val viewModel = HistoryViewModel(repository)
        try {
            viewModel.clearHistory()
            advanceUntilIdle()
            coVerify { repository.clearHistory() }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `removeEntry delegates to repository`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        every { repository.observeHistory() } returns flowOf(emptyList())
        val viewModel = HistoryViewModel(repository)
        try {
            viewModel.removeEntry("e1")
            advanceUntilIdle()
            coVerify { repository.removeHistoryEntry("e1") }
        } finally {
            Dispatchers.resetMain()
        }
    }
}
