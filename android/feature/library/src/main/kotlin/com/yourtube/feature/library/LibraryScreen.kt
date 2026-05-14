package com.yourtube.feature.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.yourtube.core.ui.R as CoreUiR
import com.yourtube.core.common.model.Playlist
import com.yourtube.core.ui.BottomSheetActionItem
import com.yourtube.core.ui.EmptyState
import com.yourtube.core.ui.ErrorState
import com.yourtube.core.ui.LoadingList
import com.yourtube.core.ui.LocalAppShellInsets
import com.yourtube.core.ui.LocalAppShellSlots
import com.yourtube.core.ui.PlaylistRow
import com.yourtube.core.ui.SkeletonPlaylistRow

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    onOpenRecentlyPlayed: () -> Unit,
    onOpenPlaylistDetail: (playlistId: String) -> Unit,
    /**
     * YT-0156 — sink for playlist share intents fired from the row overflow menu.
     * Wired by `AppShell` to `PlaylistShareLauncher`; defaults to a no-op so screen
     * previews / tests don't have to thread it.
     */
    onShare: (PlaylistDetailViewModel.SharePayload) -> Unit = {},
    onPlayNext: (Playlist) -> Unit = {},
    onAddToQueue: (Playlist) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    var showCreateDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<Playlist?>(null) }
    var deleteTarget by remember { mutableStateOf<Playlist?>(null) }
    // v2 Q6: long-press context menu for PlaylistRow
    var playlistContextMenu by remember { mutableStateOf<Playlist?>(null) }

    // YT-0063a v2 Q5: FAB stays expanded at all times. v2 §5 explicitly rejects
    // collapse-on-scroll because (a) typical user has <30 playlists, (b) collapse hides
    // the only "create" affordance from the empty state, (c) MiniPlayer-aware lift
    // handles the chrome conflict the collapse pattern was designed for.
    val listState = rememberLazyListState()

    // YT-0061 FAB seam: register the "New playlist" Extended FAB with the AppShell while
    // this destination is composed; clear on disposal so other tabs don't inherit it.
    val slots = LocalAppShellSlots.current
    val newPlaylistLabel = stringResource(CoreUiR.string.cd_extended_fab_new_playlist)
    // H1: capture haptic outside DisposableEffect so the stable reference is enclosed.
    val fabHaptic = LocalHapticFeedback.current
    // A4: capture current density to clamp fontScale for the FAB label.
    val fabDensity = LocalDensity.current
    DisposableEffect(slots) {
        slots.setFab {
            ExtendedFloatingActionButton(
                onClick = {
                    fabHaptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    showCreateDialog = true
                },
                icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                text = {
                    // A4: clamp font scale so the FAB label doesn't overflow at large text sizes.
                    CompositionLocalProvider(
                        LocalDensity provides Density(
                            density = fabDensity.density,
                            fontScale = fabDensity.fontScale.coerceAtMost(1.5f),
                        ),
                    ) {
                        Text(newPlaylistLabel)
                    }
                },
                modifier = Modifier.semantics {
                    contentDescription = newPlaylistLabel
                    // A3: traverse FAB before list content so TalkBack reaches it first.
                    traversalIndex = 0f
                },
            )
        }
        onDispose { slots.setFab(null) }
    }

    val shellInsets = LocalAppShellInsets.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = modifier,
        topBar = {
            // v2 Q1: LargeTopAppBar. History is reachable via the top-app-bar action icon
            // rather than a dedicated row below the toolbar.
            LargeTopAppBar(
                title = { Text(stringResource(CoreUiR.string.lbl_library_title)) },
                scrollBehavior = scrollBehavior,
                actions = {
                    val recentlyPlayedCd = stringResource(CoreUiR.string.cd_library_recently_played)
                    IconButton(
                        onClick = onOpenRecentlyPlayed,
                        modifier = Modifier.semantics { contentDescription = recentlyPlayedCd },
                    ) {
                        Icon(Icons.Rounded.History, contentDescription = null)
                    }
                },
            )
        },
    ) { innerPadding ->
        when (val state = uiState) {
            LibraryUiState.Loading -> {
                // C6 — skeleton ×4 below the Recently Played row. Header stays
                // visible above the skeletons so the destination doesn't flash
                // empty during initial load.
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                ) {
                    RecentlyPlayedRow(onClick = onOpenRecentlyPlayed)
                    LoadingList(
                        rowCount = 4,
                        rowFactory = { i -> SkeletonPlaylistRow(staggerIndex = i) },
                    )
                }
            }

            LibraryUiState.Empty -> {
                // C7 — Recently Played row at top + EmptyState. YT-0063a v2 Q9: no
                // inline CTA — the Extended FAB is on screen as the single "create"
                // entry point and the body copy points at it.
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                ) {
                    RecentlyPlayedRow(onClick = onOpenRecentlyPlayed)
                    EmptyState(
                        icon = { mod ->
                            Icon(Icons.Rounded.LibraryMusic, contentDescription = null, modifier = mod)
                        },
                        title = stringResource(CoreUiR.string.lbl_library_empty_title),
                        body = stringResource(CoreUiR.string.lbl_library_empty_body),
                    )
                }
            }

            is LibraryUiState.Error -> {
                // C8 — full-screen error. Library is local-first, so an error
                // here is rare; assertive live region per catalog accessibility
                // rules for initial-load failures.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                ) {
                    ErrorState(
                        title = stringResource(CoreUiR.string.err_library_load_title),
                        body = stringResource(CoreUiR.string.err_library_load_body),
                        onRetry = viewModel::retry,
                        assertive = true,
                        scrollable = true,
                    )
                }
            }

            is LibraryUiState.Content -> {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .nestedScroll(scrollBehavior.nestedScrollConnection),
                    // YT-0061: bottom inset from AppShell ensures the last item clears the
                    // MiniPlayer + FAB + nav bar without per-screen inset math.
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        bottom = shellInsets.calculateBottomPadding(),
                    ),
                ) {
                    item {
                        ListItem(
                            headlineContent = { Text(stringResource(CoreUiR.string.lbl_library_recently_played)) },
                            leadingContent = {
                                Icon(Icons.Rounded.History, contentDescription = null)
                            },
                            trailingContent = {
                                Icon(Icons.Rounded.ChevronRight, contentDescription = null)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(
                                    onClickLabel = stringResource(CoreUiR.string.lbl_library_open_recently_played),
                                    onClick = onOpenRecentlyPlayed,
                                ),
                        )
                        HorizontalDivider()
                    }

                    item {
                        Text(
                            text = stringResource(CoreUiR.string.lbl_library_section_playlists),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    items(state.playlists, key = { it.id }) { playlist ->
                        var menuExpanded by remember { mutableStateOf(false) }
                        val haptic = LocalHapticFeedback.current
                        // YT-0153: dropdown lives inside the same Box as the IconButton
                        // (via PlaylistRow's `trailingContent` slot) so it anchors under
                        // the dot instead of at the row's leading edge.
                        // v2 Q6: long-press opens the contextual bottom sheet.
                        PlaylistRow(
                            playlist = playlist,
                            onClick = { onOpenPlaylistDetail(playlist.id) },
                            onMoreClick = {},
                            onLongClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                playlistContextMenu = playlist
                            },
                            trailingContent = {
                                Box {
                                    val moreOptionsContentDescription = stringResource(CoreUiR.string.cd_more_options)
                                    val sharePlaylistCd = stringResource(CoreUiR.string.cd_menu_share_playlist)
                                    val shareLabel = stringResource(CoreUiR.string.lbl_menu_share)
                                    val renameLabel = stringResource(CoreUiR.string.lbl_menu_rename)
                                    val deleteLabel = stringResource(CoreUiR.string.lbl_menu_delete)
                                    IconButton(
                                        onClick = { menuExpanded = true },
                                        modifier = Modifier.semantics {
                                            contentDescription = moreOptionsContentDescription
                                        },
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.MoreVert,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    DropdownMenu(
                                        expanded = menuExpanded,
                                        onDismissRequest = { menuExpanded = false },
                                    ) {
                                        // YT-0156 — Share is the first item, mirroring the
                                        // PlaylistDetail toolbar overflow order.
                                        DropdownMenuItem(
                                            text = { Text(shareLabel) },
                                            modifier = Modifier.semantics {
                                                contentDescription = sharePlaylistCd
                                            },
                                            onClick = {
                                                menuExpanded = false
                                                viewModel.encodeForShare(playlist.id)?.let(onShare)
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text(renameLabel) },
                                            onClick = {
                                                menuExpanded = false
                                                renameTarget = playlist
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text(deleteLabel) },
                                            onClick = {
                                                menuExpanded = false
                                                deleteTarget = playlist
                                            },
                                        )
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        CreatePlaylistDialog(
            onConfirm = { name ->
                viewModel.createPlaylist(name)
                showCreateDialog = false
            },
            onDismiss = { showCreateDialog = false },
        )
    }

    renameTarget?.let { playlist ->
        RenamePlaylistDialog(
            currentName = playlist.name,
            onConfirm = { newName ->
                viewModel.renamePlaylist(playlist.id, newName)
                renameTarget = null
            },
            onDismiss = { renameTarget = null },
        )
    }

    deleteTarget?.let { playlist ->
        DeletePlaylistDialog(
            playlistName = playlist.name,
            onConfirm = {
                viewModel.deletePlaylist(playlist.id)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
        )
    }

    // v2 Q6: PlaylistRow long-press contextual bottom sheet
    playlistContextMenu?.let { playlist ->
        PlaylistContextMenu(
            playlist = playlist,
            onDismiss = { playlistContextMenu = null },
            onPlayNext = { playlistContextMenu = null; onPlayNext(playlist) },
            onAddToQueue = { playlistContextMenu = null; onAddToQueue(playlist) },
            onRename = {
                playlistContextMenu = null
                renameTarget = playlist
            },
            onDelete = {
                playlistContextMenu = null
                deleteTarget = playlist
            },
            onShare = {
                playlistContextMenu = null
                viewModel.encodeForShare(playlist.id)?.let(onShare)
            },
        )
    }
}

/**
 * Shared "Recently Played" row used by Loading and Empty branches. The
 * Content branch renders the same destination via the LazyColumn header so the
 * cell can be the first scroll item; this helper exists for the static
 * (non-list) layouts to avoid duplicating the ListItem wiring.
 */
@Composable
private fun RecentlyPlayedRow(onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(CoreUiR.string.lbl_library_recently_played)) },
        leadingContent = { Icon(Icons.Rounded.History, contentDescription = null) },
        trailingContent = { Icon(Icons.Rounded.ChevronRight, contentDescription = null) },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                onClickLabel = stringResource(CoreUiR.string.lbl_library_open_recently_played),
                onClick = onClick,
            ),
    )
    HorizontalDivider()
}

@Composable
private fun CreatePlaylistDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(CoreUiR.string.lbl_dialog_new_playlist_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(CoreUiR.string.lbl_dialog_name_label)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onConfirm(name) },
                enabled = name.isNotBlank(),
            ) { Text(stringResource(CoreUiR.string.lbl_dialog_btn_create)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CoreUiR.string.lbl_dialog_btn_cancel)) }
        },
    )
}

/**
 * v2 Q6 — contextual bottom sheet for a `PlaylistRow` long-press (read mode only).
 *
 * Items: Play next, Add to queue, Rename, [HorizontalDivider], Delete (destructive), Share.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaylistContextMenu(
    playlist: Playlist,
    onDismiss: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(modifier = Modifier.padding(bottom = 16.dp)) {
            Text(
                text = playlist.name,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            HorizontalDivider()
            BottomSheetActionItem(stringResource(CoreUiR.string.lbl_menu_play_next), onClick = onPlayNext)
            BottomSheetActionItem(stringResource(CoreUiR.string.lbl_menu_add_to_queue), onClick = onAddToQueue)
            BottomSheetActionItem(stringResource(CoreUiR.string.lbl_menu_rename), onClick = onRename)
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            BottomSheetActionItem(
                label = stringResource(CoreUiR.string.lbl_menu_delete),
                onClick = onDelete,
                contentColor = MaterialTheme.colorScheme.error,
            )
            BottomSheetActionItem(stringResource(CoreUiR.string.lbl_menu_share), onClick = onShare)
        }
    }
}
