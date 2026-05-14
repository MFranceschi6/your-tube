package com.yourtube.feature.library

import com.yourtube.core.common.model.Playlist
import com.yourtube.core.data.codec.PlaylistCodec
import com.yourtube.core.data.repository.PlaylistRepository
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
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
    private val codec = mockk<PlaylistCodec>(relaxed = true)

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
        val viewModel = LibraryViewModel(repository, codec)
        try {
            assertIs<LibraryUiState.Loading>(viewModel.uiState.value)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `uiState emits Empty when repository returns an empty list`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        every { repository.observePlaylists() } returns flowOf(emptyList())
        val viewModel = LibraryViewModel(repository, codec)
        try {
            advanceUntilIdle()
            assertIs<LibraryUiState.Empty>(viewModel.uiState.value)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `uiState emits Content with playlists from repository`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        every { repository.observePlaylists() } returns flowOf(listOf(samplePlaylist))
        val viewModel = LibraryViewModel(repository, codec)
        try {
            // subscribe to trigger WhileSubscribed collection
            advanceUntilIdle()
            val state = viewModel.uiState.value
            assertIs<LibraryUiState.Content>(state)
            assertEquals(listOf(samplePlaylist), state.playlists)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `uiState emits Error when the playlists flow throws`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        every { repository.observePlaylists() } returns flow { throw RuntimeException("db boom") }
        val viewModel = LibraryViewModel(repository, codec)
        try {
            advanceUntilIdle()
            assertIs<LibraryUiState.Error>(viewModel.uiState.value)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `createPlaylist delegates to repository`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        every { repository.observePlaylists() } returns flowOf(emptyList())
        val viewModel = LibraryViewModel(repository, codec)
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
        val viewModel = LibraryViewModel(repository, codec)
        try {
            viewModel.renamePlaylist("p1", "New Name")
            advanceUntilIdle()
            coVerify { repository.renamePlaylist("p1", "New Name") }
        } finally {
            Dispatchers.resetMain()
        }
    }

    // YT-0156: encodeForShare reads the latest snapshot, runs the codec, and emits a SharePayload.
    @Test
    fun `encodeForShare returns codec-exported payload for the named playlist`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        every { repository.observePlaylists() } returns flowOf(listOf(samplePlaylist))
        every { codec.export(samplePlaylist) } returns "exported-payload"
        val viewModel = LibraryViewModel(repository, codec)
        try {
            advanceUntilIdle()
            val payload = viewModel.encodeForShare("p1")
            assertEquals(samplePlaylist.name, payload?.playlistName)
            assertEquals("exported-payload", payload?.payload)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `encodeForShare returns null when the playlist is no longer in the snapshot`() =
        runTest(dispatcher) {
            Dispatchers.setMain(dispatcher)
            every { repository.observePlaylists() } returns flowOf(emptyList())
            val viewModel = LibraryViewModel(repository, codec)
            try {
                advanceUntilIdle()
                assertNull(viewModel.encodeForShare("ghost"))
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun `deletePlaylist delegates to repository`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        every { repository.observePlaylists() } returns flowOf(emptyList())
        val viewModel = LibraryViewModel(repository, codec)
        try {
            viewModel.deletePlaylist("p1")
            advanceUntilIdle()
            coVerify { repository.deletePlaylist("p1") }
        } finally {
            Dispatchers.resetMain()
        }
    }

    // YT-0063a v2 Q9 cross-cutting — empty → non-empty → empty transition
    @Test
    fun `uiState transitions correctly through empty then content then empty`() =
        runTest(dispatcher) {
            Dispatchers.setMain(dispatcher)
            val playlistsFlow = MutableStateFlow<List<Playlist>>(emptyList())
            every { repository.observePlaylists() } returns playlistsFlow
            val viewModel = LibraryViewModel(repository, codec)
            try {
                // 1. starts empty
                advanceUntilIdle()
                assertIs<LibraryUiState.Empty>(viewModel.uiState.value)

                // 2. playlist appears → Content
                playlistsFlow.value = listOf(samplePlaylist)
                advanceUntilIdle()
                assertIs<LibraryUiState.Content>(viewModel.uiState.value)

                // 3. last playlist removed → back to Empty
                playlistsFlow.value = emptyList()
                advanceUntilIdle()
                assertIs<LibraryUiState.Empty>(viewModel.uiState.value)
            } finally {
                Dispatchers.resetMain()
            }
        }
}
