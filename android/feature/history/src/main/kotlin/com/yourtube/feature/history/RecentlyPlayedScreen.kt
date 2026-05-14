package com.yourtube.feature.history

import androidx.compose.foundation.background
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.runtime.collectAsState
import com.yourtube.core.common.model.Track
import com.yourtube.core.data.model.PlaybackHistoryEntry
import com.yourtube.core.ui.EmptyState
import com.yourtube.core.ui.ErrorState
import com.yourtube.core.ui.LoadingList
import com.yourtube.core.ui.LocalAppShellInsets
import com.yourtube.core.ui.R as CoreUiR
import com.yourtube.core.ui.SkeletonRow
import com.yourtube.core.ui.TrackRow
import java.time.Instant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecentlyPlayedScreen(
    onBackClick: () -> Unit,
    onPlayTrack: (Track) -> Unit,
    /** YT-0164 C13: navigate to the Search tab from the empty state's "Browse search" CTA. */
    onGoToSearch: () -> Unit = {},
    onPlayNext: (Track) -> Unit = {},
    onAddToQueue: (Track) -> Unit = {},
    onAddToPlaylist: (Track) -> Unit = {},
    onShare: (Track) -> Unit = {},
    currentTrackVideoId: String? = null,
    modifier: Modifier = Modifier,
    viewModel: HistoryViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val shellInsets = LocalAppShellInsets.current

    // B1 — overflow menu + clear-all confirm dialog state
    var showOverflowMenu by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }

    // B5 — long-press sheet state
    var selectedEntry by remember { mutableStateOf<PlaybackHistoryEntry?>(null) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(CoreUiR.string.lbl_history_title)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(CoreUiR.string.cd_history_back),
                        )
                    }
                },
                actions = {
                    // B1: overflow icon + DropdownMenu replace the old inline TextButton.
                    // Only shown when there are entries (same guard as before).
                    if (uiState is HistoryUiState.Content) {
                        Box {
                            IconButton(onClick = { showOverflowMenu = true }) {
                                Icon(
                                    Icons.Rounded.MoreVert,
                                    contentDescription = "More options",
                                )
                            }
                            DropdownMenu(
                                expanded = showOverflowMenu,
                                onDismissRequest = { showOverflowMenu = false },
                            ) {
                                DropdownMenuItem(
                                    text = {
                                        Text(stringResource(CoreUiR.string.lbl_history_clear_overflow_item))
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Rounded.DeleteSweep, contentDescription = null)
                                    },
                                    onClick = {
                                        showOverflowMenu = false
                                        showClearDialog = true
                                    },
                                )
                            }
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
                        title = stringResource(CoreUiR.string.lbl_history_empty_title),
                        body = stringResource(CoreUiR.string.lbl_history_empty_body),
                        actionLabel = stringResource(CoreUiR.string.lbl_history_empty_action),
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
                    contentPadding = PaddingValues(
                        bottom = shellInsets.calculateBottomPadding(),
                    ),
                ) {
                    state.groupedEntries.forEach { group ->
                        stickyHeader(key = "header-${group.header}") {
                            DayHeader(group.header)
                        }
                        items(group.entries, key = { it.id }) { entry ->
                            // B2 — positionalThreshold added
                            val dismissState = rememberSwipeToDismissBoxState(
                                confirmValueChange = { value ->
                                    if (value == SwipeToDismissBoxValue.EndToStart) {
                                        viewModel.removeEntry(entry.id)
                                        true
                                    } else false
                                },
                                positionalThreshold = { it * 0.5f },
                            )
                            SwipeToDismissBox(
                                state = dismissState,
                                // B2 — errorContainer background + Delete icon
                                backgroundContent = {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(MaterialTheme.colorScheme.errorContainer),
                                        contentAlignment = Alignment.CenterEnd,
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Delete,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onErrorContainer,
                                            modifier = Modifier.padding(end = 16.dp),
                                        )
                                    }
                                },
                            ) {
                                // B3 — timestamp; B4 — semantics with contentDescription + customActions
                                val timestamp = formatHistoryTimestamp(
                                    now = Instant.now(),
                                    playedAt = Instant.parse(entry.playedAt),
                                )
                                TrackRow(
                                    track = entry.track,
                                    isPlaying = currentTrackVideoId == entry.track.videoId,
                                    onClick = { onPlayTrack(entry.track) },
                                    onLongClick = { selectedEntry = entry },
                                    onMoreClick = null,
                                    supportingExtra = timestamp,
                                    // Opaque surface so the SwipeToDismissBox errorContainer
                                    // background is only visible during the drag gesture.
                                    modifier = Modifier
                                        .background(MaterialTheme.colorScheme.surface)
                                        .semantics(mergeDescendants = true) {
                                        contentDescription = "${entry.track.title}, ${entry.track.channel}, played $timestamp"
                                        customActions = listOf(
                                            CustomAccessibilityAction("Remove from history") {
                                                viewModel.removeEntry(entry.id); true
                                            },
                                            CustomAccessibilityAction("Play next") {
                                                onPlayNext(entry.track); true
                                            },
                                            CustomAccessibilityAction("Add to queue") {
                                                onAddToQueue(entry.track); true
                                            },
                                            CustomAccessibilityAction("Add to playlist") {
                                                onAddToPlaylist(entry.track); true
                                            },
                                            CustomAccessibilityAction("Share") {
                                                onShare(entry.track); true
                                            },
                                        )
                                    },
                                )
                            }
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
                        title = stringResource(CoreUiR.string.err_history_load_title),
                        body = stringResource(CoreUiR.string.err_history_load_body),
                        onRetry = viewModel::retry,
                        scrollable = true,
                    )
                }
            }
        }

        // B1 — clear-all confirm dialog
        if (showClearDialog) {
            AlertDialog(
                onDismissRequest = { showClearDialog = false },
                title = {
                    Text(stringResource(CoreUiR.string.lbl_history_clear_dialog_title))
                },
                text = {
                    Text(stringResource(CoreUiR.string.lbl_history_clear_dialog_body))
                },
                dismissButton = {
                    TextButton(onClick = { showClearDialog = false }) {
                        Text(stringResource(CoreUiR.string.lbl_history_clear_dialog_cancel))
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel.clearHistory()
                            showClearDialog = false
                        },
                    ) {
                        Text(
                            text = stringResource(CoreUiR.string.lbl_history_clear_dialog_confirm),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                },
            )
        }
    }

    // B5 — HistoryRowSheet (long-press context sheet)
    selectedEntry?.let { entry ->
        HistoryRowSheet(
            track = entry.track,
            onPlayNext = { onPlayNext(entry.track); selectedEntry = null },
            onAddToQueue = { onAddToQueue(entry.track); selectedEntry = null },
            onAddToPlaylist = { onAddToPlaylist(entry.track); selectedEntry = null },
            onRemove = { viewModel.removeEntry(entry.id); selectedEntry = null },
            onShare = { onShare(entry.track); selectedEntry = null },
            onDismiss = { selectedEntry = null },
        )
    }
}

// B6 — semantics { heading() } moved from Box to Text
@Composable
private fun DayHeader(header: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = header,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { heading() },
        )
    }
}
