package com.yourtube.feature.history

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.runtime.collectAsState
import com.yourtube.core.common.model.Track
import com.yourtube.core.ui.EmptyState
import com.yourtube.core.ui.ErrorState
import com.yourtube.core.ui.LoadingList
import com.yourtube.core.ui.LocalAppShellInsets
import com.yourtube.core.ui.SkeletonRow
import com.yourtube.core.ui.TrackRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecentlyPlayedScreen(
    onBackClick: () -> Unit,
    onPlayTrack: (Track) -> Unit,
    /** YT-0164 C13: navigate to the Search tab from the empty state's "Browse search" CTA. */
    onGoToSearch: () -> Unit = {},
    currentTrackVideoId: String? = null,
    modifier: Modifier = Modifier,
    viewModel: HistoryViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val shellInsets = LocalAppShellInsets.current

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Recently Played") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Catalog C13: "Clear all" only renders while history has
                    // entries. Loading and Empty hide it; Error hides it too
                    // (no list to clear).
                    if (uiState is HistoryUiState.Content) {
                        TextButton(onClick = { viewModel.clearHistory() }) {
                            Text("Clear all")
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        when (val state = uiState) {
            HistoryUiState.Loading -> {
                // C12 — skeleton ×6.
                Box(modifier = Modifier.padding(innerPadding)) {
                    LoadingList(
                        rowCount = 6,
                        rowFactory = { i -> SkeletonRow(staggerIndex = i) },
                    )
                }
            }

            HistoryUiState.Empty -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                ) {
                    EmptyState(
                        icon = { mod ->
                            Icon(Icons.Rounded.History, contentDescription = null, modifier = mod)
                        },
                        title = "Nothing played yet",
                        body = "Tracks you play will show up here.",
                        actionLabel = "Browse search",
                        onAction = onGoToSearch,
                    )
                }
            }

            is HistoryUiState.Content -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    // YT-0061: bottom inset from AppShell so the last entry clears the
                    // MiniPlayer + nav bar.
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        bottom = shellInsets.calculateBottomPadding(),
                    ),
                ) {
                    items(state.entries, key = { it.id }) { entry ->
                        val dismissState = rememberSwipeToDismissBoxState(
                            confirmValueChange = { value ->
                                if (value == SwipeToDismissBoxValue.EndToStart) {
                                    viewModel.removeEntry(entry.id)
                                    true
                                } else false
                            },
                        )
                        SwipeToDismissBox(
                            state = dismissState,
                            backgroundContent = {},
                        ) {
                            TrackRow(
                                track = entry.track,
                                isPlaying = currentTrackVideoId == entry.track.videoId,
                                onClick = { onPlayTrack(entry.track) },
                                onMoreClick = {},
                            )
                        }
                    }
                }
            }

            is HistoryUiState.Error -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                ) {
                    ErrorState(
                        title = "Couldn't load history",
                        body = "Try again in a moment.",
                        onRetry = viewModel::retry,
                        scrollable = true,
                    )
                }
            }
        }
    }
}
