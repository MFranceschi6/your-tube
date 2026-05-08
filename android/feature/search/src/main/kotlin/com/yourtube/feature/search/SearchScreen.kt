package com.yourtube.feature.search

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.AssistChip
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.yourtube.core.common.model.Track
import com.yourtube.core.ui.EmptyState
import com.yourtube.core.ui.ErrorState
import com.yourtube.core.ui.LoadingList
import com.yourtube.core.ui.LocalAppShellInsets
import com.yourtube.core.ui.SkeletonRow
import com.yourtube.core.ui.TrackRow

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    onTrackClick: (Track) -> Unit,
    onAddToQueue: (Track) -> Unit = {},
    onPlayNext: (Track) -> Unit = {},
    onAddToPlaylist: (Track) -> Unit = {},
    onGoToLibrary: () -> Unit = {},
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
            SearchUiState.Idle -> {
                // C2 — Search idle (no query yet). The screen renders a flow row of
                // suggestion chips above the EmptyState; tapping a chip pre-fills the
                // query and submits it.
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .verticalScroll(rememberScrollState()),
                ) {
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        IDLE_SUGGESTION_CHIPS.forEach { label ->
                            AssistChip(
                                onClick = {
                                    viewModel.onQueryChange(label)
                                    viewModel.search()
                                },
                                label = { Text(label) },
                            )
                        }
                    }
                    EmptyState(
                        icon = { mod -> Icon(Icons.Rounded.Search, contentDescription = null, modifier = mod) },
                        title = "Search YourTube",
                        body = "Find tracks, channels, and topics from your subscriptions.",
                    )
                }
            }

            SearchUiState.Loading -> {
                // C1 — Loading: skeleton ×6 (no spinner). LoadingList exposes the
                // "Loading" state description so TalkBack reads it once on focus.
                Box(modifier = Modifier.padding(innerPadding)) {
                    LoadingList(
                        rowCount = 6,
                        rowFactory = { i -> SkeletonRow(staggerIndex = i) },
                    )
                }
            }

            is SearchUiState.Empty -> {
                // C3 — Submitted query returned zero results. Catalog rule: render
                // the query verbatim in straight double-quotes, truncated to 32
                // chars + ellipsis. The container is a polite live region so
                // TalkBack auto-announces the new state.
                val truncated = truncateQuery(state.query)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                ) {
                    EmptyState(
                        icon = { mod -> Icon(Icons.Rounded.SearchOff, contentDescription = null, modifier = mod) },
                        title = "No results for \"$truncated\"",
                        body = "Check your spelling or try a different search.",
                        actionLabel = "Clear search",
                        onAction = viewModel::clearQuery,
                    )
                }
            }

            is SearchUiState.Content -> {
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
                        // YT-0153: anchor the DropdownMenu under the trailing 3-dot
                        // button by placing both inside TrackRow's `trailingContent`
                        // slot rather than wrapping the whole row in a Box.
                        TrackRow(
                            track = track,
                            isPlaying = currentTrackVideoId == track.videoId,
                            onClick = { onTrackClick(track) },
                            onMoreClick = null,
                            trailingContent = {
                                Box {
                                    IconButton(
                                        onClick = { menuVisible = true },
                                        modifier = Modifier.semantics {
                                            contentDescription = "More options for ${track.title}"
                                        },
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.MoreVert,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
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
                            },
                        )
                    }
                }
            }

            is SearchUiState.Error -> {
                // C4 (offline=false) and C5 (offline=true). The ErrorState primitive
                // already wraps itself in a polite live region; C5 also surfaces a
                // secondary "Go to Library" action that switches the bottom-nav tab.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                ) {
                    if (state.offline) {
                        ErrorState(
                            title = "You're offline",
                            body = "Connect to the internet to search. Your saved playlists are still available in Library.",
                            icon = Icons.Rounded.WifiOff,
                            onRetry = viewModel::retry,
                            secondaryActionLabel = "Go to Library",
                            onSecondaryAction = onGoToLibrary,
                        )
                    } else {
                        ErrorState(
                            title = "Couldn't search",
                            body = "Something went wrong on our end. Try again in a moment.",
                            onRetry = viewModel::retry,
                        )
                    }
                }
            }
        }
    }
}

/** Idle suggestion chips per catalog C2. */
private val IDLE_SUGGESTION_CHIPS: List<String> = listOf("lofi", "focus", "ambient", "podcasts")

/**
 * Catalog rule for C3: render the submitted query verbatim, truncated to 32
 * characters + Unicode ellipsis when longer. Do not lower-case it; do not
 * strip punctuation.
 */
private fun truncateQuery(query: String): String =
    if (query.length <= 32) query else query.take(32) + "…"

private fun com.yourtube.core.common.model.SearchResult.toTrack() = Track(
    videoId = videoId,
    title = title,
    channel = channel,
    durationSec = durationSec,
    thumbnailUrl = thumbnailUrl,
)
