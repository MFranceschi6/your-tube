package com.yourtube.feature.search

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.navigation.compose.hiltViewModel
import com.yourtube.core.common.model.Track
import com.yourtube.core.ui.EmptyState
import com.yourtube.core.ui.LocalAppShellInsets
import com.yourtube.core.ui.TrackRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onTrackClick: (Track) -> Unit,
    onAddToQueue: (Track) -> Unit = {},
    onPlayNext: (Track) -> Unit = {},
    onAddToPlaylist: (Track) -> Unit = {},
    currentTrackVideoId: String? = null,
    modifier: Modifier = Modifier,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val query by viewModel.query.collectAsState()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val shellInsets = LocalAppShellInsets.current

    Scaffold(
        modifier = modifier.pointerInput(Unit) { detectTapGestures { focusManager.clearFocus() } },
        topBar = {
            TopAppBar(
                title = {
                    OutlinedTextField(
                        value = query,
                        onValueChange = viewModel::onQueryChange,
                        placeholder = { Text("Search YouTube…") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Text,
                            imeAction = ImeAction.Search,
                        ),
                        keyboardActions = KeyboardActions(onSearch = {
                            keyboardController?.hide()
                            focusManager.clearFocus()
                            viewModel.search()
                        }),
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = viewModel::clearQuery) {
                                    Icon(Icons.Rounded.Clear, contentDescription = "Clear search")
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
                actions = {
                    IconButton(onClick = { focusManager.clearFocus(); viewModel.search() }) {
                        Icon(Icons.Rounded.Search, contentDescription = "Search")
                    }
                },
            )
        },
    ) { innerPadding ->
        when (val state = uiState) {
            SearchUiState.Idle -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                EmptyState(
                    icon = { mod -> Icon(Icons.Rounded.Search, contentDescription = null, modifier = mod) },
                    title = "Search for something to start listening",
                    body = "Type an artist, song, or album name above.",
                )
            }

            SearchUiState.Loading -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            is SearchUiState.Success -> {
                if (state.results.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                        contentAlignment = Alignment.Center,
                    ) {
                        EmptyState(
                            icon = { mod -> Icon(Icons.Rounded.Search, contentDescription = null, modifier = mod) },
                            title = "No results found",
                            body = "Try a different search term.",
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                        // YT-0061: bottom inset from AppShell so the last result clears the
                        // MiniPlayer + nav bar.
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            bottom = shellInsets.calculateBottomPadding(),
                        ),
                    ) {
                        items(state.results, key = { it.videoId }) { result ->
                            var menuVisible by remember { mutableStateOf(false) }
                            val track = result.toTrack()
                            Box {
                                TrackRow(
                                    track = track,
                                    isPlaying = currentTrackVideoId == track.videoId,
                                    onClick = { onTrackClick(track) },
                                    onMoreClick = { menuVisible = true },
                                )
                                DropdownMenu(
                                    expanded = menuVisible,
                                    onDismissRequest = { menuVisible = false },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Play next") },
                                        onClick = {
                                            onPlayNext(track)
                                            menuVisible = false
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Add to queue") },
                                        onClick = {
                                            onAddToQueue(track)
                                            menuVisible = false
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Add to playlist") },
                                        onClick = {
                                            onAddToPlaylist(track)
                                            menuVisible = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            is SearchUiState.Error -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                Text(state.message, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

private fun com.yourtube.core.common.model.SearchResult.toTrack() = Track(
    videoId = videoId,
    title = title,
    channel = channel,
    durationSec = durationSec,
    thumbnailUrl = thumbnailUrl,
)
