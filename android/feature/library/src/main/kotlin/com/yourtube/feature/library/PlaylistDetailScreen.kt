package com.yourtube.feature.library

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RemoveCircle
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.yourtube.core.common.model.Playlist
import com.yourtube.core.common.model.Track
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import com.yourtube.core.ui.R as CoreUiR
import com.yourtube.core.ui.BottomSheetActionItem
import com.yourtube.core.ui.EmptyState
import com.yourtube.core.ui.ErrorState
import com.yourtube.core.ui.LoadingList
import com.yourtube.core.ui.LocalAppShellInsets
import com.yourtube.core.ui.LocalSnackbarHostState
import com.yourtube.core.ui.PlaylistCover
import com.yourtube.core.ui.SkeletonPlaylistHeader
import com.yourtube.core.ui.SkeletonRow
import com.yourtube.core.ui.TrackRow
import com.yourtube.core.ui.buildTrackMeta
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailScreen(
    onBackClick: () -> Unit,
    onPlaylistDeleted: () -> Unit,
    onShare: (PlaylistDetailViewModel.SharePayload) -> Unit,
    onPlayPlaylist: (List<Track>) -> Unit = {},
    onShufflePlaylist: (List<Track>) -> Unit = {},
    /**
     * YT-0155: tap-on-row plays the full playlist (natural order) starting at the
     * tapped index. Falls back to [onPlayPlaylist] semantics for index 0 so callers
     * that only wire the header buttons keep working.
     */
    onPlayPlaylistAt: (List<Track>, Int) -> Unit = { tracks, _ -> onPlayPlaylist(tracks) },
    /** YT-0164 C10: navigate to the Search tab from the empty state's "Find tracks" CTA. */
    onGoToSearch: () -> Unit = {},
    onPlayNext: (Track) -> Unit = {},
    onAddToQueue: (Track) -> Unit = {},
    onShareTrack: (Track) -> Unit = {},
    currentTrackVideoId: String? = null,
    modifier: Modifier = Modifier,
    viewModel: PlaylistDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val pendingRemovals by viewModel.pendingRemovals.collectAsState()
    var menuExpanded by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current

    // v2 Q3: edit-mode toggle state
    var isEditing by remember { mutableStateOf(false) }

    // v2 Q6: context menus
    var trackContextMenu by remember { mutableStateOf<Track?>(null) }

    // Snackbar host for toast-undo pattern (v2 Q3).
    // YT-0328 — pull the global SnackbarHostState owned by AppShell so toasts render
    // above the persistent MiniPlayer overlay instead of being clipped behind it.
    val snackbarHostState = LocalSnackbarHostState.current
    val scope = rememberCoroutineScope()
    var pendingSnackbarJob by remember { mutableStateOf<Job?>(null) }
    // Hoisted resources for use in coroutine lambdas (non-composable context).
    val resources = LocalContext.current.resources
    val snackbarUndoLabel = stringResource(CoreUiR.string.lbl_snackbar_undo)

    // YT-0063a Q12: consume the AppShell-supplied bottom inset so the last track
    // never sits under the MiniPlayer / FAB / nav bar.
    val shellInsets = LocalAppShellInsets.current

    // v2 Q3: predictive-back exits edit mode without leaving screen
    BackHandler(enabled = isEditing) {
        viewModel.commitAllPendingRemovals()
        isEditing = false
    }

    // YT-0164 catalog rule: the toolbar title hides while loading and during
    // an Error state — only render the playlist name once we have it.
    val toolbarTitle: String = when (val s = state) {
        is PlaylistDetailUiState.Empty -> s.playlist.name
        is PlaylistDetailUiState.Content -> s.playlist.name
        else -> ""
    }
    val canShowOverflow: Boolean = state is PlaylistDetailUiState.Empty ||
        state is PlaylistDetailUiState.Content

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = modifier,
        // YT-0328 — snackbar host is now owned globally by AppShell. No local host needed.
        topBar = {
            // v2 Q7: LargeTopAppBar collapses on scroll automatically via exitUntilCollapsed
            LargeTopAppBar(
                title = {
                    Text(
                        text = toolbarTitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(CoreUiR.string.cd_playlist_detail_back))
                    }
                },
                scrollBehavior = scrollBehavior,
                actions = {
                    if (canShowOverflow) {
                        // v2 Q3: Edit / Done toggle
                        TextButton(
                            onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                if (isEditing) {
                                    viewModel.commitAllPendingRemovals()
                                    isEditing = false
                                } else {
                                    isEditing = true
                                }
                            },
                        ) {
                            Text(
                                if (isEditing) stringResource(CoreUiR.string.lbl_playlist_detail_done)
                                else stringResource(CoreUiR.string.lbl_playlist_detail_edit),
                            )
                        }
                        // Overflow menu: Rename / Delete / Share
                        Box {
                            IconButton(onClick = { menuExpanded = true }) {
                                Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(CoreUiR.string.cd_more_options))
                            }
                            DropdownMenu(
                                expanded = menuExpanded,
                                onDismissRequest = { menuExpanded = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(CoreUiR.string.lbl_menu_rename_playlist)) },
                                    onClick = { menuExpanded = false; showRenameDialog = true },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(CoreUiR.string.lbl_menu_delete_playlist)) },
                                    onClick = { menuExpanded = false; showDeleteDialog = true },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(CoreUiR.string.lbl_menu_share)) },
                                    onClick = {
                                        menuExpanded = false
                                        viewModel.encodeForShare()?.let(onShare)
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        when (val current = state) {
            PlaylistDetailUiState.Loading -> {
                // C9 — skeleton header + 6 skeleton rows.
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                ) {
                    SkeletonPlaylistHeader()
                    LoadingList(
                        rowCount = 6,
                        rowFactory = { i -> SkeletonRow(staggerIndex = i) },
                    )
                }
            }

            is PlaylistDetailUiState.Empty -> {
                // C10 — the toolbar still shows the playlist name (above), but
                // we hide the Play/Shuffle action row since there are no tracks
                // to play. The CTA punts the user to Search.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                ) {
                    EmptyState(
                        icon = { mod ->
                            Icon(Icons.Rounded.MusicNote, contentDescription = null, modifier = mod)
                        },
                        title = stringResource(CoreUiR.string.lbl_playlist_detail_empty_title),
                        body = stringResource(CoreUiR.string.lbl_playlist_detail_empty_body),
                        actionLabel = stringResource(CoreUiR.string.lbl_playlist_detail_empty_action),
                        onAction = onGoToSearch,
                        scrollable = true,
                    )
                }
            }

            is PlaylistDetailUiState.Content -> {
                val playlist = current.playlist
                // v2 Q3: local optimistic track list — starts from DB, removes pending
                val visibleTracks = remember(playlist.tracks, pendingRemovals) {
                    playlist.tracks.filterNot { it.videoId in pendingRemovals }
                }

                PlaylistDetailContent(
                    playlist = playlist,
                    visibleTracks = visibleTracks,
                    isEditing = isEditing,
                    currentTrackVideoId = currentTrackVideoId,
                    shellInsets = shellInsets,
                    scrollBehavior = scrollBehavior,
                    onPlay = { onPlayPlaylist(playlist.tracks) },
                    onShuffle = { onShufflePlaylist(playlist.tracks) },
                    onPlayAt = { index -> onPlayPlaylistAt(playlist.tracks, index) },
                    onTrackLongClick = { track ->
                        if (!isEditing) trackContextMenu = track
                    },
                    onRemoveTrack = { track, position ->
                        viewModel.queueRemoval(track, position)
                        pendingSnackbarJob?.cancel()
                        pendingSnackbarJob = scope.launch {
                            val pending = viewModel.pendingRemovals.value
                            val message = if (pending.size == 1) {
                                resources.getString(CoreUiR.string.lbl_snackbar_removed_track, track.title)
                            } else {
                                resources.getQuantityString(
                                    CoreUiR.plurals.lbl_snackbar_removed_tracks,
                                    pending.size,
                                    pending.size,
                                )
                            }
                            val result = snackbarHostState.showSnackbar(
                                message = message,
                                actionLabel = snackbarUndoLabel,
                                duration = SnackbarDuration.Short,
                            )
                            if (result == SnackbarResult.ActionPerformed) {
                                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                                viewModel.pendingRemovals.value.keys.toList().forEach { viewModel.undoRemoval(it) }
                            }
                        }
                    },
                    onReorderTracks = { from, to ->
                        viewModel.reorderTracks(from, to)
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                )
            }

            PlaylistDetailUiState.Error -> {
                // C11 — full error state. Catalog: header does NOT render in
                // this state (we hide the toolbar title and overflow above).
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                ) {
                    ErrorState(
                        title = stringResource(CoreUiR.string.err_playlist_detail_load_title),
                        body = stringResource(CoreUiR.string.err_playlist_detail_load_body),
                        onRetry = viewModel::retry,
                        scrollable = true,
                    )
                }
            }
        }
    }

    // v2 Q6: TrackRow long-press context menu (read mode only)
    trackContextMenu?.let { track ->
        TrackContextMenu(
            track = track,
            onDismiss = { trackContextMenu = null },
            onPlayNext = {
                trackContextMenu = null
                onPlayNext(track)
            },
            onAddToQueue = {
                trackContextMenu = null
                onAddToQueue(track)
            },
            onShare = {
                trackContextMenu = null
                onShareTrack(track)
            },
        )
    }

    if (showRenameDialog) {
        val currentName: String = when (val s = state) {
            is PlaylistDetailUiState.Empty -> s.playlist.name
            is PlaylistDetailUiState.Content -> s.playlist.name
            else -> ""
        }
        // N4: reuse the shared RenamePlaylistDialog (same copy/validation as LibraryScreen).
        RenamePlaylistDialog(
            currentName = currentName,
            onConfirm = { newName ->
                viewModel.renamePlaylist(newName)
                showRenameDialog = false
            },
            onDismiss = { showRenameDialog = false },
        )
    }

    if (showDeleteDialog) {
        val playlistName: String = when (val s = state) {
            is PlaylistDetailUiState.Empty -> s.playlist.name
            is PlaylistDetailUiState.Content -> s.playlist.name
            else -> ""
        }
        DeletePlaylistDialog(
            playlistName = playlistName,
            onConfirm = {
                viewModel.deletePlaylist()
                showDeleteDialog = false
                onPlaylistDeleted()
            },
            onDismiss = { showDeleteDialog = false },
        )
    }
}

/**
 * v2 Q7 — the main content layout for a populated playlist. Single `LazyColumn`
 * with header items: cover → title + meta → action row → tracks.
 *
 * Extracted from `PlaylistDetailScreen` for readability; all state is passed down.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun PlaylistDetailContent(
    playlist: Playlist,
    visibleTracks: List<Track>,
    isEditing: Boolean,
    currentTrackVideoId: String?,
    shellInsets: PaddingValues,
    scrollBehavior: TopAppBarScrollBehavior,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onPlayAt: (Int) -> Unit,
    onTrackLongClick: (Track) -> Unit,
    onRemoveTrack: (track: Track, position: Int) -> Unit,
    onReorderTracks: (from: Int, to: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current

    // A2: focus management — when entering edit mode, TalkBack jumps to first drag handle.
    val dragHandleFocusRequester = remember { FocusRequester() }
    LaunchedEffect(isEditing) {
        if (isEditing) {
            try { dragHandleFocusRequester.requestFocus() } catch (_: IllegalStateException) { /* not attached yet */ }
        }
    }

    // v2 Q3 reorder: backed by sh.calvin.reorderable. Replaces the hand-rolled
    // CR4-CR10 implementation (pointerInput + Animatable + manual compensation) which
    // never converged on stable visual behavior across multiple review rounds.
    val workingTracks = remember(visibleTracks) {
        mutableStateListOf<Track>().also { it.addAll(visibleTracks) }
    }
    // Track the dragged track's start index so we commit a single reorderTracks(from, to)
    // on drag stop (matching the existing repository contract — one DB upsert per gesture).
    var dragStartIndex by remember { mutableIntStateOf(-1) }
    val lazyListState = rememberLazyListState()
    val reorderableLazyListState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val fromKey = from.key as? String
        val toKey = to.key as? String
        if (fromKey == null || toKey == null) return@rememberReorderableLazyListState
        val fromIdx = workingTracks.indexOfFirst { it.videoId == fromKey }
        val toIdx = workingTracks.indexOfFirst { it.videoId == toKey }
        if (fromIdx >= 0 && toIdx >= 0 && fromIdx != toIdx) {
            workingTracks.add(toIdx, workingTracks.removeAt(fromIdx))
        }
    }

    LaunchedEffect(visibleTracks) {
        // Sync working list when source changes (e.g. a committed removal propagates from DB).
        // Skip while a drag is in progress to avoid clobbering the optimistic reorder preview.
        if (dragStartIndex == -1) {
            workingTracks.clear()
            workingTracks.addAll(visibleTracks)
        }
    }

    // A2: live-region announcement for edit-mode entry/exit. Zero-size Box so it does
    // not take layout space; liveRegion = Polite fires on every recomposition where
    // the contentDescription changes (entering vs. exiting edit mode).
    val editModeAnnouncement = if (isEditing) {
        "Edit mode. Reorder or remove tracks."
    } else {
        "Edit mode off. Saved."
    }

    LazyColumn(
        state = lazyListState,
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentPadding = PaddingValues(
            bottom = shellInsets.calculateBottomPadding(),
        ),
    ) {
        // ── A2 edit-mode live-region announcement ─────────────────────────────
        item(key = "__edit_announcement") {
            Box(
                Modifier
                    .size(0.dp)
                    .semantics {
                        liveRegion = LiveRegionMode.Polite
                        contentDescription = editModeAnnouncement
                    },
            )
        }

        // ── Cover ────────────────────────────────────────────────────────────
        item(key = "__cover") {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp, bottom = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                PlaylistCover(
                    tracks = playlist.tracks,
                    name = playlist.name,
                    modifier = Modifier.size(240.dp),
                )
            }
        }

        // ── Title + meta ────────────────────────────────────────────────────
        item(key = "__header") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = playlist.name,
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(4.dp))
                val trackCountText = pluralStringResource(
                    CoreUiR.plurals.lbl_playlist_track_count,
                    playlist.tracks.size,
                    playlist.tracks.size,
                )
                val duration = formatTotalDuration(playlist.tracks)
                Text(
                    text = if (duration.isNotEmpty()) "$trackCountText · $duration" else trackCountText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
            }
        }

        // ── Play + Shuffle action row (hidden in edit mode per v2 Q7) ───────
        if (!isEditing) {
            item(key = "__actions") {
                PlaylistActions(
                    onPlay = onPlay,
                    onShuffle = onShuffle,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }

        // ── Track rows ───────────────────────────────────────────────────────
        itemsIndexed(
            items = workingTracks,
            key = { _, t -> t.videoId },
        ) { index, track ->
            if (isEditing) {
                ReorderableItem(reorderableLazyListState, key = track.videoId) { isDragging ->
                    // Material drag-and-drop: 8dp shadow elevation while dragged so the row
                    // visually lifts off the list. Surface keeps the shadow opaque against
                    // the scrolling background.
                    val elevation by animateDpAsState(
                        targetValue = if (isDragging) 8.dp else 0.dp,
                        label = "edit-row-elevation",
                    )
                    // Per-row interaction source so the drag handle can draw the default
                    // ripple while the gesture is active. We emit Press on drag start and
                    // Release on drag stop instead of routing the gesture through a
                    // `clickable` (which would consume the pointer chain the reorderable
                    // library wires through `draggableHandle`).
                    val dragHandleInteractionSource = remember { MutableInteractionSource() }
                    var dragPressInteraction by remember {
                        mutableStateOf<PressInteraction.Press?>(null)
                    }
                    val rowScope = rememberCoroutineScope()
                    Surface(
                        shadowElevation = elevation,
                        tonalElevation = elevation,
                        color = MaterialTheme.colorScheme.surface,
                    ) {
                    // A1: custom accessibility actions for keyboard/switch users.
                    val a11yExtraModifier = Modifier.semantics {
                        customActions = listOf(
                            CustomAccessibilityAction("Move up") {
                                val cur = workingTracks.indexOfFirst { it.videoId == track.videoId }
                                if (cur > 0) onReorderTracks(cur, cur - 1)
                                true
                            },
                            CustomAccessibilityAction("Move down") {
                                val cur = workingTracks.indexOfFirst { it.videoId == track.videoId }
                                if (cur < workingTracks.size - 1) onReorderTracks(cur, cur + 1)
                                true
                            },
                            CustomAccessibilityAction("Move to top") {
                                val cur = workingTracks.indexOfFirst { it.videoId == track.videoId }
                                if (cur > 0) onReorderTracks(cur, 0)
                                true
                            },
                            CustomAccessibilityAction("Move to bottom") {
                                val cur = workingTracks.indexOfFirst { it.videoId == track.videoId }
                                val last = workingTracks.size - 1
                                if (cur < last) onReorderTracks(cur, last)
                                true
                            },
                        )
                    }.let { mod ->
                        // A2: focus the first drag handle when entering edit mode.
                        if (index == 0) mod.focusRequester(dragHandleFocusRequester) else mod
                    }
                    EditModeTrackRow(
                        track = track,
                        onRemove = {
                            val position = workingTracks.indexOf(track)
                            if (position >= 0) {
                                workingTracks.removeAt(position)
                                onRemoveTrack(track, position)
                            }
                        },
                        dragHandleInteractionSource = dragHandleInteractionSource,
                        dragHandleExtraModifier = a11yExtraModifier,
                        dragHandleModifier = Modifier.draggableHandle(
                            onDragStarted = { offset ->
                                dragStartIndex = workingTracks.indexOfFirst { it.videoId == track.videoId }
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                val press = PressInteraction.Press(offset)
                                dragPressInteraction = press
                                rowScope.launch { dragHandleInteractionSource.emit(press) }
                            },
                            onDragStopped = {
                                val endIndex = workingTracks.indexOfFirst { it.videoId == track.videoId }
                                if (dragStartIndex >= 0 && endIndex >= 0 && dragStartIndex != endIndex) {
                                    onReorderTracks(dragStartIndex, endIndex)
                                }
                                dragStartIndex = -1
                                dragPressInteraction?.let { press ->
                                    rowScope.launch {
                                        dragHandleInteractionSource.emit(PressInteraction.Release(press))
                                    }
                                    dragPressInteraction = null
                                }
                                haptic.performHapticFeedback(HapticFeedbackType.GestureEnd)
                            },
                        ),
                    )
                    }
                }
            } else {
                // Read-mode row: thumbnail, title, tap-to-play, long-press context menu
                TrackRow(
                    track = track,
                    isPlaying = currentTrackVideoId == track.videoId,
                    onClick = { onPlayAt(index) },
                    onLongClick = { onTrackLongClick(track) },
                    onMoreClick = { onTrackLongClick(track) },
                )
            }
        }
    }
}

/**
 * v2 Q3 — a single edit-mode track row with drag handle (leading) and remove
 * button (trailing). Thumbnail hides in edit mode.
 *
 * Divergence from `core/ui/TrackRow`: drag handle leading instead of thumbnail,
 * no clickable row body (the row body must not absorb the pointer chain that
 * `sh.calvin.reorderable` wires through `dragHandleModifier`), and a trailing
 * destructive remove button. Unifying would require optional slots on
 * `TrackRow` for the leading icon, the trailing action, and the `onClick` —
 * three independent toggles whose combinations only collapse here, so we keep
 * the dedicated composable.
 *
 * Drag-to-reorder is delegated to `sh.calvin.reorderable` via [dragHandleModifier]
 * (typically `Modifier.draggableHandle(...)` from `ReorderableCollectionItemScope`).
 * The handle is a plain [Box] (not an [IconButton]) so the library's pointer chain
 * receives the gesture without an interposed `clickable` consuming it.
 *
 * Press feedback: [dragHandleInteractionSource] is passed in by the caller so the
 * drag-start / drag-stop callbacks can emit [PressInteraction] events; the handle
 * then draws the default `LocalIndication` ripple while the gesture is active.
 */
@Composable
private fun EditModeTrackRow(
    track: Track,
    onRemove: () -> Unit,
    dragHandleInteractionSource: MutableInteractionSource,
    dragHandleModifier: Modifier = Modifier,
    /** Additional modifier applied after the drag-handle base semantics, e.g. for
     *  custom accessibility actions or a [FocusRequester] on the first row. */
    dragHandleExtraModifier: Modifier = Modifier,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val dragReorderLabel = stringResource(CoreUiR.string.cd_drag_handle_reorder, track.title)
        Box(
            modifier = dragHandleModifier
                .size(48.dp)
                .indication(
                    interactionSource = dragHandleInteractionSource,
                    indication = LocalIndication.current,
                )
                .semantics { contentDescription = dragReorderLabel }
                .then(dragHandleExtraModifier),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.DragHandle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        }

        Column(modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildTrackMeta(track),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        val removeTrackLabel = stringResource(CoreUiR.string.cd_remove_track, track.title)
        IconButton(
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.Reject)
                onRemove()
            },
            modifier = Modifier
                .size(48.dp)
                .semantics { contentDescription = removeTrackLabel },
        ) {
            Icon(
                imageVector = Icons.Rounded.RemoveCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

/**
 * v2 Q6 — contextual bottom sheet for a track row in read mode.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrackContextMenu(
    track: Track,
    onDismiss: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onShare: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    val haptic = LocalHapticFeedback.current

    LaunchedEffect(Unit) {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(modifier = Modifier.padding(bottom = 16.dp)) {
            // Sheet title
            Text(
                text = track.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            HorizontalDivider()
            BottomSheetActionItem(label = stringResource(CoreUiR.string.lbl_menu_play_next), onClick = onPlayNext)
            BottomSheetActionItem(label = stringResource(CoreUiR.string.lbl_menu_add_to_queue), onClick = onAddToQueue)
            BottomSheetActionItem(label = stringResource(CoreUiR.string.lbl_menu_share_track), onClick = onShare)
        }
    }
}

/**
 * Format a list of tracks' total duration as "H:MM:SS" or "M:SS" for display in
 * the playlist meta line.
 */
internal fun formatTotalDuration(tracks: List<Track>): String {
    val totalSec = tracks.sumOf { it.durationSec }
    if (totalSec <= 0) return ""
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) {
        "$h:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
    } else {
        "$m:${s.toString().padStart(2, '0')}"
    }
}

/**
 * YT-0063a Q4 — Play (filled, leading `play_arrow`, weight 2) + Shuffle (tonal, leading
 * `shuffle`, weight 1) action row. 12 dp gap. 48 dp height.
 */
@Composable
private fun PlaylistActions(
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val playLabel = stringResource(CoreUiR.string.lbl_playlist_detail_play)
    val shuffleLabel = stringResource(CoreUiR.string.lbl_playlist_detail_shuffle)
    val playCd = stringResource(CoreUiR.string.cd_playlist_detail_play)
    val shuffleCd = stringResource(CoreUiR.string.cd_playlist_detail_shuffle)
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(
            onClick = onPlay,
            modifier = Modifier
                .weight(2f)
                .height(48.dp)
                .semantics { contentDescription = playCd },
        ) {
            Icon(
                imageVector = Icons.Rounded.PlayArrow,
                contentDescription = null,
            )
            Text(
                text = playLabel,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        FilledTonalButton(
            onClick = onShuffle,
            modifier = Modifier
                .weight(1f)
                .height(48.dp)
                .semantics { contentDescription = shuffleCd },
        ) {
            Icon(
                imageVector = Icons.Rounded.Shuffle,
                contentDescription = null,
            )
        }
    }
}

@Preview(widthDp = 360, showBackground = true)
@Composable
private fun PlaylistActionsPreview() {
    MaterialTheme { PlaylistActions(onPlay = {}, onShuffle = {}) }
}
