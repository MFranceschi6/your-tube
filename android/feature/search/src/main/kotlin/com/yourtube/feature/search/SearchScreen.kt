package com.yourtube.feature.search

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.yourtube.core.common.model.Track
import com.yourtube.core.designsystem.IconKey
import com.yourtube.core.designsystem.Icon as MaterialSymbolIcon
import com.yourtube.core.ui.EmptyState
import com.yourtube.core.ui.ErrorState
import com.yourtube.core.ui.LoadingList
import com.yourtube.core.ui.LocalAppShellInsets
import com.yourtube.core.ui.R as CoreUiR
import com.yourtube.core.ui.SkeletonRow
import com.yourtube.core.ui.TrackRow
import java.util.Locale
import kotlinx.coroutines.launch

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
    val filters by viewModel.filters.collectAsState()
    val suggestions by viewModel.suggestions.collectAsState()
    val showSuggestions by viewModel.showSuggestions.collectAsState()
    val recentSearches by viewModel.recentSearches.collectAsState()
    val recentSuggestions by viewModel.recentSuggestions.collectAsState()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val shellInsets = LocalAppShellInsets.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val haptics = LocalHapticFeedback.current
    val permissionDeniedMessage = stringResource(CoreUiR.string.lbl_search_voice_permission_denied)
    val removedFromRecentsMessage = stringResource(CoreUiR.string.lbl_search_recent_removed_toast)
    val undoLabel = stringResource(CoreUiR.string.lbl_snackbar_undo)

    // Launches the system speech-recognition activity. On a successful result the
    // top transcription fills the query field and search is submitted immediately.
    val speechLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val text = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
        if (text != null) {
            viewModel.onVoiceResult(text)
        }
        // Cancel (RESULT_CANCELED) or null result: leave the field unchanged.
    }

    // Requests RECORD_AUDIO at runtime. On grant, opens the speech-recognizer.
    // On denial, shows a non-blocking Snackbar (no retry loop).
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            }
            speechLauncher.launch(intent)
        } else {
            scope.launch { snackbarHostState.showSnackbar(permissionDeniedMessage) }
        }
    }

    // Invoked when the mic button is tapped.
    val onMicClick: () -> Unit = {
        val activity = context as? Activity
        val showRationale = activity != null &&
            ActivityCompat.shouldShowRequestPermissionRationale(
                activity,
                android.Manifest.permission.RECORD_AUDIO,
            )
        if (showRationale) {
            // Second denial: explain without re-requesting.
            scope.launch { snackbarHostState.showSnackbar(permissionDeniedMessage) }
        } else {
            permissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
        }
    }

    Scaffold(
        modifier = modifier.pointerInput(Unit) { detectTapGestures { focusManager.clearFocus() } },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    OutlinedTextField(
                        value = query,
                        onValueChange = viewModel::onQueryChange,
                        placeholder = { Text(stringResource(CoreUiR.string.lbl_search_placeholder)) },
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
                                    Icon(Icons.Rounded.Clear, contentDescription = stringResource(CoreUiR.string.cd_search_clear))
                                }
                            } else {
                                IconButton(onClick = onMicClick) {
                                    MaterialSymbolIcon(
                                        icon = IconKey.Mic,
                                        contentDescription = stringResource(CoreUiR.string.cd_search_voice),
                                    )
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
                actions = {
                    IconButton(onClick = { focusManager.clearFocus(); viewModel.search() }) {
                        Icon(Icons.Rounded.Search, contentDescription = stringResource(CoreUiR.string.cd_search_submit))
                    }
                },
            )
        },
    ) { innerPadding ->
        // When the query field is non-empty, the ViewModel has not yet submitted (i.e.
        // showSuggestions is true), and live suggestions are available, show the
        // suggestion list in place of the normal uiState content. showSuggestions is
        // reset to false immediately on search() / onSuggestionTap() / clearQuery(),
        // so the panel disappears at submit time — before the suggestions flow has a
        // chance to emit an empty list.
        if (showSuggestions && suggestions.isNotEmpty()) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                items(suggestions, key = { "suggest_$it" }) { suggestion ->
                    ListItem(
                        headlineContent = { Text(suggestion) },
                        leadingContent = {
                            Icon(Icons.Rounded.Search, contentDescription = null)
                        },
                        modifier = Modifier.clickable {
                            keyboardController?.hide()
                            focusManager.clearFocus()
                            viewModel.onSuggestionTap(suggestion)
                        },
                    )
                    HorizontalDivider()
                }
            }
            return@Scaffold
        }

        when (val state = uiState) {
            SearchUiState.Idle -> {
                // C2 — Search idle (no query yet).
                // Chip strip: up to MAX_CHIP_RECENTS recent chips + curated fallback, total ≤6.
                val curatedSlots = (6 - recentSuggestions.size).coerceAtLeast(0)
                val curatedChips = IDLE_SUGGESTION_CHIPS.take(curatedSlots)
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .verticalScroll(rememberScrollState()),
                ) {
                    if (recentSuggestions.isNotEmpty() || curatedChips.isNotEmpty()) {
                        LazyRow(
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(recentSuggestions, key = { "recent_$it" }) { recent ->
                                RecentSearchChip(
                                    label = recent,
                                    onClick = {
                                        viewModel.onQueryChange(recent)
                                        viewModel.search()
                                    },
                                    onLongClick = {
                                        haptics.performHapticFeedback(HapticFeedbackType.Reject)
                                        viewModel.onRemoveRecentSearch(recent)
                                        scope.launch {
                                            val result = snackbarHostState.showSnackbar(
                                                message = removedFromRecentsMessage,
                                                actionLabel = undoLabel,
                                            )
                                            if (result == SnackbarResult.ActionPerformed) {
                                                viewModel.onRestoreRecentSearch(recent)
                                            }
                                        }
                                    },
                                )
                            }
                            items(curatedChips, key = { "curated_$it" }) { label ->
                                AssistChip(
                                    onClick = {
                                        viewModel.onQueryChange(label)
                                        viewModel.search()
                                    },
                                    label = { Text(label) },
                                )
                            }
                        }
                    }
                    EmptyState(
                        icon = { mod -> Icon(Icons.Rounded.Search, contentDescription = null, modifier = mod) },
                        title = stringResource(CoreUiR.string.lbl_search_idle_title),
                        body = stringResource(CoreUiR.string.lbl_search_idle_body),
                    )
                }
            }

            SearchUiState.Loading -> {
                // C1 — Loading: skeleton ×6 (no spinner) with filter chips above.
                // LoadingList exposes the "Loading" state description so TalkBack
                // reads it once on focus.
                Column(modifier = Modifier.padding(innerPadding)) {
                    SearchFilterRow(filters = filters, onFilterChange = viewModel::onFilterChange)
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
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                ) {
                    SearchFilterRow(filters = filters, onFilterChange = viewModel::onFilterChange)
                    EmptyState(
                        icon = { mod -> Icon(Icons.Rounded.SearchOff, contentDescription = null, modifier = mod) },
                        title = stringResource(CoreUiR.string.lbl_search_no_results_title, truncated),
                        body = stringResource(CoreUiR.string.lbl_search_no_results_body),
                        actionLabel = stringResource(CoreUiR.string.lbl_search_no_results_action),
                        onAction = viewModel::clearQuery,
                        scrollable = true,
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
                    contentPadding = PaddingValues(
                        bottom = shellInsets.calculateBottomPadding(),
                    ),
                ) {
                    item(key = "filter_row") {
                        SearchFilterRow(
                            filters = filters,
                            onFilterChange = viewModel::onFilterChange,
                        )
                    }
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
                                    val moreOptionsContentDescription = stringResource(CoreUiR.string.cd_more_options)
                                    IconButton(
                                        onClick = { menuVisible = true },
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
                                        expanded = menuVisible,
                                        onDismissRequest = { menuVisible = false },
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(CoreUiR.string.lbl_menu_play_next_track)) },
                                            onClick = {
                                                onPlayNext(track)
                                                menuVisible = false
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text(stringResource(CoreUiR.string.lbl_menu_add_to_queue_track)) },
                                            onClick = {
                                                onAddToQueue(track)
                                                menuVisible = false
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text(stringResource(CoreUiR.string.lbl_menu_add_to_playlist_track)) },
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
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                ) {
                    SearchFilterRow(filters = filters, onFilterChange = viewModel::onFilterChange)
                    if (state.offline) {
                        ErrorState(
                            title = stringResource(CoreUiR.string.err_search_offline_title),
                            body = stringResource(CoreUiR.string.err_search_offline_body),
                            icon = Icons.Rounded.WifiOff,
                            onRetry = viewModel::retry,
                            secondaryActionLabel = stringResource(CoreUiR.string.err_search_offline_secondary_action),
                            onSecondaryAction = onGoToLibrary,
                            scrollable = true,
                        )
                    } else {
                        ErrorState(
                            title = stringResource(CoreUiR.string.err_search_generic_title),
                            body = stringResource(CoreUiR.string.err_search_generic_body),
                            onRetry = viewModel::retry,
                            scrollable = true,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentSearchChip(
    label: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        AssistChip(
            onClick = {},
            label = { Text(label) },
            leadingIcon = {
                Icon(
                    Icons.Rounded.History,
                    contentDescription = null,
                    modifier = Modifier.size(AssistChipDefaults.IconSize),
                )
            },
        )
    }
}

/**
 * Horizontally-scrollable row of [FilterChip] components for the three
 * search filter groups (duration, upload date, type). Visible whenever a
 * query has been submitted (all non-Idle states). State is held in the
 * ViewModel; this composable is purely presentation.
 *
 * Each group has a leading "Any" chip that is selected when no specific filter
 * value is active. Tapping a non-Any chip selects it and clears sibling groups
 * (mutually-exclusive groups — see [SearchFilters] for rationale).
 */
@Composable
private fun SearchFilterRow(
    filters: SearchFilters,
    onFilterChange: (SearchFilters) -> Unit,
    modifier: Modifier = Modifier,
) {
    val durationGroupLabel = stringResource(CoreUiR.string.lbl_filter_duration_any)
    val uploadGroupLabel = stringResource(CoreUiR.string.lbl_filter_upload_any)
    val typeGroupLabel = stringResource(CoreUiR.string.lbl_filter_type_any)

    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Duration group — leading "Any" chip
        item {
            FilterChip(
                selected = filters.duration == DurationFilter.Any,
                onClick = { onFilterChange(filters.withDuration(DurationFilter.Any)) },
                label = { Text(stringResource(CoreUiR.string.lbl_filter_duration_any)) },
                modifier = Modifier.semantics {
                    stateDescription = "$durationGroupLabel: ${if (filters.duration == DurationFilter.Any) "selected" else "not selected"}"
                },
            )
        }
        item {
            FilterChip(
                selected = filters.duration == DurationFilter.Short,
                onClick = {
                    onFilterChange(
                        if (filters.duration == DurationFilter.Short) filters.withDuration(DurationFilter.Any)
                        else filters.withDuration(DurationFilter.Short),
                    )
                },
                label = { Text(stringResource(CoreUiR.string.lbl_filter_duration_short)) },
                modifier = Modifier.semantics {
                    stateDescription = "Duration: Short, ${if (filters.duration == DurationFilter.Short) "selected" else "not selected"}"
                },
            )
        }
        item {
            FilterChip(
                selected = filters.duration == DurationFilter.Medium,
                onClick = {
                    onFilterChange(
                        if (filters.duration == DurationFilter.Medium) filters.withDuration(DurationFilter.Any)
                        else filters.withDuration(DurationFilter.Medium),
                    )
                },
                label = { Text(stringResource(CoreUiR.string.lbl_filter_duration_medium)) },
                modifier = Modifier.semantics {
                    stateDescription = "Duration: Medium, ${if (filters.duration == DurationFilter.Medium) "selected" else "not selected"}"
                },
            )
        }
        item {
            FilterChip(
                selected = filters.duration == DurationFilter.Long,
                onClick = {
                    onFilterChange(
                        if (filters.duration == DurationFilter.Long) filters.withDuration(DurationFilter.Any)
                        else filters.withDuration(DurationFilter.Long),
                    )
                },
                label = { Text(stringResource(CoreUiR.string.lbl_filter_duration_long)) },
                modifier = Modifier.semantics {
                    stateDescription = "Duration: Long, ${if (filters.duration == DurationFilter.Long) "selected" else "not selected"}"
                },
            )
        }

        // Upload date group — leading "Any" chip
        item {
            FilterChip(
                selected = filters.uploadDate == UploadDateFilter.Any,
                onClick = { onFilterChange(filters.withUploadDate(UploadDateFilter.Any)) },
                label = { Text(stringResource(CoreUiR.string.lbl_filter_upload_any)) },
                modifier = Modifier.semantics {
                    stateDescription = "$uploadGroupLabel: ${if (filters.uploadDate == UploadDateFilter.Any) "selected" else "not selected"}"
                },
            )
        }
        item {
            FilterChip(
                selected = filters.uploadDate == UploadDateFilter.Today,
                onClick = {
                    onFilterChange(
                        if (filters.uploadDate == UploadDateFilter.Today) filters.withUploadDate(UploadDateFilter.Any)
                        else filters.withUploadDate(UploadDateFilter.Today),
                    )
                },
                label = { Text(stringResource(CoreUiR.string.lbl_filter_upload_today)) },
                modifier = Modifier.semantics {
                    stateDescription = "Upload date: Today, ${if (filters.uploadDate == UploadDateFilter.Today) "selected" else "not selected"}"
                },
            )
        }
        item {
            FilterChip(
                selected = filters.uploadDate == UploadDateFilter.ThisWeek,
                onClick = {
                    onFilterChange(
                        if (filters.uploadDate == UploadDateFilter.ThisWeek) filters.withUploadDate(UploadDateFilter.Any)
                        else filters.withUploadDate(UploadDateFilter.ThisWeek),
                    )
                },
                label = { Text(stringResource(CoreUiR.string.lbl_filter_upload_week)) },
                modifier = Modifier.semantics {
                    stateDescription = "Upload date: This week, ${if (filters.uploadDate == UploadDateFilter.ThisWeek) "selected" else "not selected"}"
                },
            )
        }
        item {
            FilterChip(
                selected = filters.uploadDate == UploadDateFilter.ThisMonth,
                onClick = {
                    onFilterChange(
                        if (filters.uploadDate == UploadDateFilter.ThisMonth) filters.withUploadDate(UploadDateFilter.Any)
                        else filters.withUploadDate(UploadDateFilter.ThisMonth),
                    )
                },
                label = { Text(stringResource(CoreUiR.string.lbl_filter_upload_month)) },
                modifier = Modifier.semantics {
                    stateDescription = "Upload date: This month, ${if (filters.uploadDate == UploadDateFilter.ThisMonth) "selected" else "not selected"}"
                },
            )
        }
        item {
            FilterChip(
                selected = filters.uploadDate == UploadDateFilter.ThisYear,
                onClick = {
                    onFilterChange(
                        if (filters.uploadDate == UploadDateFilter.ThisYear) filters.withUploadDate(UploadDateFilter.Any)
                        else filters.withUploadDate(UploadDateFilter.ThisYear),
                    )
                },
                label = { Text(stringResource(CoreUiR.string.lbl_filter_upload_year)) },
                modifier = Modifier.semantics {
                    stateDescription = "Upload date: This year, ${if (filters.uploadDate == UploadDateFilter.ThisYear) "selected" else "not selected"}"
                },
            )
        }

        // Type group — leading "Any" chip
        item {
            FilterChip(
                selected = filters.type == TypeFilter.Any,
                onClick = { onFilterChange(filters.withType(TypeFilter.Any)) },
                label = { Text(stringResource(CoreUiR.string.lbl_filter_type_any)) },
                modifier = Modifier.semantics {
                    stateDescription = "$typeGroupLabel: ${if (filters.type == TypeFilter.Any) "selected" else "not selected"}"
                },
            )
        }
        item {
            FilterChip(
                selected = filters.type == TypeFilter.Video,
                onClick = {
                    onFilterChange(
                        if (filters.type == TypeFilter.Video) filters.withType(TypeFilter.Any)
                        else filters.withType(TypeFilter.Video),
                    )
                },
                label = { Text(stringResource(CoreUiR.string.lbl_filter_type_video)) },
                modifier = Modifier.semantics {
                    stateDescription = "Type: Video, ${if (filters.type == TypeFilter.Video) "selected" else "not selected"}"
                },
            )
        }
        item {
            FilterChip(
                selected = filters.type == TypeFilter.Playlist,
                onClick = {
                    onFilterChange(
                        if (filters.type == TypeFilter.Playlist) filters.withType(TypeFilter.Any)
                        else filters.withType(TypeFilter.Playlist),
                    )
                },
                label = { Text(stringResource(CoreUiR.string.lbl_filter_type_playlist)) },
                modifier = Modifier.semantics {
                    stateDescription = "Type: Playlist, ${if (filters.type == TypeFilter.Playlist) "selected" else "not selected"}"
                },
            )
        }
        item {
            FilterChip(
                selected = filters.type == TypeFilter.Channel,
                onClick = {
                    onFilterChange(
                        if (filters.type == TypeFilter.Channel) filters.withType(TypeFilter.Any)
                        else filters.withType(TypeFilter.Channel),
                    )
                },
                label = { Text(stringResource(CoreUiR.string.lbl_filter_type_channel)) },
                modifier = Modifier.semantics {
                    stateDescription = "Type: Channel, ${if (filters.type == TypeFilter.Channel) "selected" else "not selected"}"
                },
            )
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
