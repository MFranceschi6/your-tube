package com.yourtube.feature.library

import com.yourtube.core.common.model.Playlist
import com.yourtube.core.data.repository.PlaylistRepository
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val repository = mockk<PlaylistRepository>(relaxed = true)

    private val samplePlaylist = Playlist(
        id = "p1",
        name = "Test Playlist",
        createdAt = "2026-01-01T00:00:00Z",
        updatedAt = "2026-01-01T00:00:00Z",
        tracks = emptyList(),
    )

    @Test
    fun `uiState starts as Loading`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        every { repository.observePlaylists() } returns flowOf(emptyList())
        val viewModel = LibraryViewModel(repository)
        try {
            assertIs<LibraryUiState.Loading>(viewModel.uiState.value)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `uiState emits Success with playlists from repository`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        every { repository.observePlaylists() } returns flowOf(listOf(samplePlaylist))
        val viewModel = LibraryViewModel(repository)
        try {
            // subscribe to trigger WhileSubscribed collection
            advanceUntilIdle()
            val state = viewModel.uiState.value
            assertIs<LibraryUiState.Success>(state)
            assertEquals(listOf(samplePlaylist), state.playlists)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `createPlaylist delegates to repository`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        every { repository.observePlaylists() } returns flowOf(emptyList())
        val viewModel = LibraryViewModel(repository)
        try {
            viewModel.createPlaylist("My Playlist")
            advanceUntilIdle()
            coVerify { repository.createPlaylist("My Playlist") }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `renamePlaylist delegates to repository`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        every { repository.observePlaylists() } returns flowOf(emptyList())
        val viewModel = LibraryViewModel(repository)
        try {
            viewModel.renamePlaylist("p1", "New Name")
            advanceUntilIdle()
            coVerify { repository.renamePlaylist("p1", "New Name") }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `deletePlaylist delegates to repository`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        every { repository.observePlaylists() } returns flowOf(emptyList())
        val viewModel = LibraryViewModel(repository)
        try {
            viewModel.deletePlaylist("p1")
            advanceUntilIdle()
            coVerify { repository.deletePlaylist("p1") }
        } finally {
            Dispatchers.resetMain()
        }
    }
}
