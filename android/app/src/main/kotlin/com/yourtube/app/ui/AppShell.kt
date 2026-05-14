package com.yourtube.app.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LibraryMusic
import com.yourtube.core.designsystem.Icon as MaterialSymbolIcon
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.yourtube.app.BuildConfig
import com.yourtube.app.navigation.TopLevelDestination
import com.yourtube.app.sharing.PlaylistImportResult
import com.yourtube.app.sharing.PlaylistShareLauncher
import com.yourtube.app.sharing.SharingViewModel
import com.yourtube.core.common.model.PlaybackStatus
import com.yourtube.core.common.model.SleepTimerPreset
import com.yourtube.core.common.model.Track
import com.yourtube.core.data.update.UpdateStatus
import com.yourtube.core.ui.AppShellSlots
import com.yourtube.core.ui.LocalAppShellInsets
import com.yourtube.core.ui.LocalAppShellSlots
import com.yourtube.core.ui.LocalMiniPlayerHeight
import com.yourtube.core.ui.LocalPlayerOverlayState
import com.yourtube.core.ui.LocalReduceMotion
import com.yourtube.core.ui.MotionSpec
import com.yourtube.core.ui.PlayerOverlay
import com.yourtube.core.ui.PlayerOverlayState
import com.yourtube.feature.history.RecentlyPlayedScreen
import com.yourtube.feature.library.AddToPlaylistSheet
import com.yourtube.feature.library.LibraryScreen
import com.yourtube.feature.library.PlaylistDetailScreen
import com.yourtube.feature.player.PlayerViewModel
import com.yourtube.feature.search.SearchScreen
import com.yourtube.feature.settings.SettingsImportOutcome
import com.yourtube.feature.settings.SettingsScreen
import com.yourtube.feature.settings.SettingsViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Round 4 root composable. Layered top-to-bottom inside the outer `Box`:
 *
 *  1. `Scaffold { NavHost }` — content + Material 3 bottom navigation bar (only).
 *  2. `PlayerOverlay` (when a track is loaded) — the SINGLE composable that renders BOTH
 *     the MiniPlayer chrome AND the NowPlaying surface, driven by a single `Animatable`
 *     in [PlayerOverlayState]. The NavHost no longer renders any player UI.
 *  3. `AddToPlaylistSheet` (overlays everything when active).
 *
 * The NavHost is a pure router. Route changes flip a `LaunchedEffect` driver that calls
 * `state.autoOpen()` or `state.autoClose()`. The visual transition is owned entirely by
 * the overlay's `expandProgress` animation.
 */
@Composable
fun AppShell(
    modifier: Modifier = Modifier,
    openNowPlayingRequests: Flow<Unit>? = null,
) {
    val playerViewModel: PlayerViewModel = hiltViewModel()
    val playerState by playerViewModel.playerState.collectAsState()
    // YT-0093 — sleep timer state for the NowPlaying action row icon + sheet.
    val sleepTimerState by playerViewModel.sleepTimerState.collectAsState()
    val navController = rememberNavController()

    // YT-0251 — update check. SettingsViewModel is scoped to the Activity (via hiltViewModel()
    // here in AppShell) so the same instance is reused by the Settings destination.
    // The initial check fires once on first composition; subsequent checks are triggered by
    // the Settings "Check for updates" row.
    val settingsViewModel: SettingsViewModel = hiltViewModel()
    val updateStatus by settingsViewModel.updateStatus.collectAsState()
    LaunchedEffect(Unit) {
        settingsViewModel.checkForUpdates(BuildConfig.VERSION_CODE.toLong())
    }

    if (openNowPlayingRequests != null) {
        LaunchedEffect(openNowPlayingRequests, navController) {
            openNowPlayingRequests.collect {
                navController.navigate(AppRoute.NowPlaying.route) {
                    launchSingleTop = true
                }
            }
        }
    }

    var addToPlaylistTarget by remember { mutableStateOf<Track?>(null) }

    val sharingViewModel: SharingViewModel = hiltViewModel()
    val context = LocalContext.current
    val settingsImportEvents = remember(sharingViewModel) {
        sharingViewModel.events.map { result ->
            when (result) {
                is PlaylistImportResult.Success -> SettingsImportOutcome.Success
                PlaylistImportResult.InvalidPayload,
                PlaylistImportResult.Unreadable,
                PlaylistImportResult.UnsupportedSchema -> SettingsImportOutcome.Failure
            }
        }
    }

    val currentTrack = playerState.currentTrack
    val isPlaying = playerState.isPlaying
    // YT-0244 — both LOADING (track-change resolve) and BUFFERING (in-track engine
    // re-buffer / seek window) surface as the loading spinner on the play/pause control.
    // `NowPlayingChrome` / `MiniPlayerChrome` consume a single boolean and stay agnostic
    // to which underlying state is active.
    val isBuffering = playerState.playbackStatus in setOf(
        PlaybackStatus.LOADING,
        PlaybackStatus.BUFFERING,
    )
    val progressFraction = if (playerState.durationMs > 0)
        (playerState.positionMs.toFloat() / playerState.durationMs).coerceIn(0f, 1f)
    else 0f

    val onPlayTrack: (Track) -> Unit = { track -> playerViewModel.playNow(track) }

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val isNowPlayingActive by remember {
        derivedStateOf { navBackStackEntry?.destination?.route == AppRoute.NowPlaying.route }
    }

    var fabContent by remember { mutableStateOf<(@Composable () -> Unit)?>(null) }
    val slots = remember {
        object : AppShellSlots {
            override fun setFab(content: (@Composable () -> Unit)?) {
                fabContent = content
            }
        }
    }

    val density = LocalDensity.current
    var miniPlayerHeightDp by remember { mutableStateOf(0.dp) }
    var fabHeightDp by remember { mutableStateOf(0.dp) }
    var navBarHeightDp by remember { mutableStateOf(0.dp) }

    val miniPlayerVisible = currentTrack != null && !isNowPlayingActive
    val fabVisible = fabContent != null && !isNowPlayingActive

    val effectiveNavBarHeight = if (isNowPlayingActive) 0.dp else navBarHeightDp
    val effectiveMiniPlayerHeight = if (isNowPlayingActive) 0.dp else miniPlayerHeightDp
    val effectiveFabHeight = if (isNowPlayingActive) 0.dp else fabHeightDp

    val shellInsets by remember(
        isNowPlayingActive,
        miniPlayerVisible,
        fabVisible,
        effectiveNavBarHeight,
        effectiveMiniPlayerHeight,
        effectiveFabHeight,
    ) {
        derivedStateOf {
            PaddingValues(
                bottom = effectiveNavBarHeight +
                    (if (miniPlayerVisible) effectiveMiniPlayerHeight else 0.dp) +
                    (if (fabVisible) effectiveFabHeight + 16.dp else 0.dp),
            )
        }
    }

    // ── Round 4 single-progress overlay state ──────────────────────────────────────────
    //
    // `PlayerOverlayState.expandProgress` is the only canonical animation state for the
    // MiniPlayer ↔ NowPlaying transition. Three drivers feed it:
    //
    //  - `autoOpen()` fires when the route enters NowPlaying.
    //  - `autoClose()` fires when the route leaves NowPlaying.
    //  - `onDragDelta(dy)` / `onDragStopped(v)` are the drag handle's per-frame mutators.
    //
    // The state owns the animateTo / snapTo dispatch; this composable wires the lifecycle
    // observer that routes route changes into the appropriate driver.
    val reduceMotion = LocalReduceMotion.current
    val overlayScope = rememberCoroutineScope()
    var screenHeightPx by remember { mutableFloatStateOf(0f) }
    val velocityThresholdPxPerSec = with(density) {
        MotionSpec.DRAG_COLLAPSE_VELOCITY_DP_PER_S.dp.toPx()
    }
    val overlayState = remember(navController) {
        PlayerOverlayState(
            coroutineScope = overlayScope,
            // popBackStack on close-settled. Note: when `autoClose()` fires from the
            // route-change LaunchedEffect (i.e. the user already pressed back / collapse),
            // the back-stack pop has ALREADY happened — that's the route change that
            // triggered autoClose in the first place. The state's idempotency guard skips
            // the callback when progress is already 0; for the drag-collapse path the
            // pop hasn't happened yet so the callback DOES fire.
            //
            // The "did the user already pop" check happens at call time: if the current
            // route is NOT NowPlaying when we hit settled, we do not pop again.
            onCloseSettled = {
                if (navController.currentBackStackEntry?.destination?.route ==
                    AppRoute.NowPlaying.route) {
                    navController.popBackStack()
                }
            },
            isReducedMotionProvider = { reduceMotion },
            screenHeightPxProvider = { screenHeightPx },
            velocityThresholdPxPerSecProvider = { velocityThresholdPxPerSec },
            initialExpanded = isNowPlayingActive,
        )
    }

    // Drive the overlay from route changes. When `isNowPlayingActive` flips:
    //   true  → call autoOpen() (animates progress 0 → 1).
    //   false → call autoClose() (animates progress 1 → 0; idempotent if already 0).
    // The autoClose's `onCloseSettled` guard above prevents double-pop because the
    // route is already non-NowPlaying when the settle callback fires.
    LaunchedEffect(isNowPlayingActive) {
        if (isNowPlayingActive) overlayState.autoOpen() else overlayState.autoClose()
    }

    // YT-0286 — when the last queue item is removed while NowPlaying is open,
    // `currentTrack` becomes null but the NowPlaying route stays on the back stack.
    // The PlayerOverlay is gated on `currentTrack != null` so it unmounts, leaving a
    // blank screen. Pop back automatically so the user lands on the previous destination.
    LaunchedEffect(currentTrack) {
        if (currentTrack == null && isNowPlayingActive) {
            navController.popBackStack()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { size -> screenHeightPx = size.height.toFloat() },
    ) {
        CompositionLocalProvider(
            LocalAppShellSlots provides slots,
            LocalAppShellInsets provides shellInsets,
            LocalMiniPlayerHeight provides effectiveMiniPlayerHeight,
            LocalPlayerOverlayState provides overlayState,
        ) {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                bottomBar = {
                    // The bar is ALWAYS composed so `navBarHeightDp` is stable at
                    // ~80 dp throughout the transition. If we conditionally
                    // unmounted it on `isNowPlayingActive`, `navBarHeightDp` would
                    // oscillate 80→0→80 during the morph, the MiniPlayerChrome
                    // would shift its `padding(bottom = navBarHeight)` accordingly,
                    // and its slot anchor would re-fire `onGloballyPositioned`
                    // mid-flight — causing the artwork to morph toward a stale
                    // rect and then visibly snap to the final position.
                    //
                    // Instead we keep the bar mounted, hide it visually with
                    // `alpha = 0f` while NowPlaying owns the screen, and disable
                    // its `NavigationBarItem`s so taps don't bleed through the
                    // (transparent but z-higher) NowPlayingChrome scrim.
                    Box(
                        modifier = Modifier
                            .onSizeChanged { size ->
                                navBarHeightDp = with(density) { size.height.toDp() }
                            }
                            .graphicsLayer { alpha = if (isNowPlayingActive) 0f else 1f },
                    ) {
                        AppNavigationBar(
                            navController = navController,
                            enabled = !isNowPlayingActive,
                        )
                    }
                },
            ) { innerPadding ->
                NavHost(
                    navController = navController,
                    startDestination = AppRoute.Search.route,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    // Instant cross-fade-free transitions: the NavHost is a pure
                    // router under round 4's overlay architecture — there's no
                    // visual transition to host. Without these overrides, the
                    // default 700 ms fadeIn/fadeOut keeps the OUTGOING destination
                    // composed during the cross-fade, which means side-effects
                    // anchored to its lifecycle (e.g. the Library FAB registered
                    // via `LocalAppShellSlots.setFab`) linger ~700 ms after the
                    // user has navigated away. Setting `EnterTransition.None` /
                    // `ExitTransition.None` disposes the outgoing destination
                    // immediately and the FAB clears on the next frame.
                    enterTransition = { EnterTransition.None },
                    exitTransition = { ExitTransition.None },
                    popEnterTransition = { EnterTransition.None },
                    popExitTransition = { ExitTransition.None },
                ) {
                    composable(AppRoute.Search.route) {
                        SearchScreen(
                            onTrackClick = onPlayTrack,
                            onAddToQueue = { track -> playerViewModel.addToQueue(track) },
                            onPlayNext = { track -> playerViewModel.playNext(track) },
                            onAddToPlaylist = { track -> addToPlaylistTarget = track },
                            onGoToLibrary = {
                                navController.navigate(AppRoute.Library.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            currentTrackVideoId = currentTrack?.videoId,
                        )
                    }
                    composable(AppRoute.Library.route) {
                        LibraryScreen(
                            onOpenRecentlyPlayed = {
                                navController.navigate(AppRoute.RecentlyPlayed.route)
                            },
                            onOpenPlaylistDetail = { playlistId ->
                                navController.navigate(
                                    "${AppRoute.PlaylistDetail.route}/$playlistId",
                                )
                            },
                            onShare = { share ->
                                PlaylistShareLauncher.share(
                                    context,
                                    share.playlistName,
                                    share.payload,
                                )
                            },
                            onPlayNext = { playlist -> playlist.tracks.forEach { playerViewModel.playNext(it) } },
                            onAddToQueue = { playlist -> playlist.tracks.forEach { playerViewModel.addToQueue(it) } },
                        )
                    }
                    composable("${AppRoute.PlaylistDetail.route}/{playlistId}") {
                        PlaylistDetailScreen(
                            onBackClick = { navController.popBackStack() },
                            onPlaylistDeleted = { navController.popBackStack() },
                            onShare = { share ->
                                PlaylistShareLauncher.share(
                                    context,
                                    share.playlistName,
                                    share.payload,
                                )
                            },
                            onPlayPlaylist = { tracks ->
                                playerViewModel.playList(tracks, shuffle = false)
                            },
                            onShufflePlaylist = { tracks ->
                                playerViewModel.playList(tracks, shuffle = true)
                            },
                            onPlayPlaylistAt = { tracks, index ->
                                playerViewModel.playList(tracks, startIndex = index)
                            },
                            onGoToSearch = {
                                navController.navigate(AppRoute.Search.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            onPlayNext = { track -> playerViewModel.playNext(track) },
                            onAddToQueue = { track -> playerViewModel.addToQueue(track) },
                            onShareTrack = { track ->
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(
                                        Intent.EXTRA_TEXT,
                                        "https://www.youtube.com/watch?v=${track.videoId}",
                                    )
                                }
                                context.startActivity(Intent.createChooser(intent, "Share"))
                            },
                            currentTrackVideoId = currentTrack?.videoId,
                        )
                    }
                    composable(AppRoute.RecentlyPlayed.route) {
                        RecentlyPlayedScreen(
                            onBackClick = { navController.popBackStack() },
                            onPlayTrack = onPlayTrack,
                            onGoToSearch = {
                                navController.navigate(AppRoute.Search.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            currentTrackVideoId = currentTrack?.videoId,
                        )
                    }
                    composable(AppRoute.Settings.route) {
                        SettingsScreen(
                            onImportFromUri = { uri -> sharingViewModel.import(uri) },
                            importEvents = settingsImportEvents,
                            // YT-0251: thread BuildConfig.VERSION_CODE from :app into :feature:settings
                            // so the settings module doesn't need an :app dependency.
                            installedVersionCode = BuildConfig.VERSION_CODE.toLong(),
                            viewModel = settingsViewModel,
                        )
                    }
                    composable(route = AppRoute.NowPlaying.route) {
                        // NavHost is a pure router. The visual NowPlaying surface is
                        // rendered by the PlayerOverlay above this NavHost. We render
                        // an empty Box here to keep the back-stack semantics intact.
                        Box(modifier = Modifier.fillMaxSize())
                    }
                }
            }

            // Player overlay — single instance. Renders MiniPlayer chrome + NowPlaying
            // surface + the single artwork that morphs between them. Keep it OUTSIDE
            // the Scaffold so it covers the bottom nav bar when expanded.
            if (currentTrack != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .onSizeChanged { size ->
                            // Live screen-height for the drag-distance fraction.
                            screenHeightPx = size.height.toFloat()
                        },
                ) {
                    // The MiniPlayer chrome's anchor is the bottom of the screen above the
                    // navigation bar. We measure the MiniPlayer height via a phantom Box
                    // ancestor in the overlay; the live nav-bar height comes from the
                    // Scaffold bottom-bar onSizeChanged above.
                    PlayerOverlay(
                        state = overlayState,
                        currentTrack = currentTrack,
                        isPlaying = isPlaying,
                        isBuffering = isBuffering,
                        progressFraction = progressFraction,
                        shuffleOn = playerState.shuffleOn,
                        repeatMode = playerState.repeatMode,
                        onPlayPauseClick = {
                            if (isPlaying) playerViewModel.pause() else playerViewModel.resume()
                        },
                        onSkipNextClick = { playerViewModel.skipNext() },
                        onSkipPreviousClick = { playerViewModel.skipPrevious() },
                        onExpandClick = {
                            navController.navigate(AppRoute.NowPlaying.route) {
                                launchSingleTop = true
                            }
                        },
                        onCollapseClick = { navController.popBackStack() },
                        onSeek = { fraction ->
                            playerViewModel.seekTo((fraction * playerState.durationMs).toLong())
                        },
                        onShuffleModeChange = { enabled -> playerViewModel.setShuffleMode(enabled) },
                        onRepeatModeChange = { mode -> playerViewModel.setRepeatMode(mode) },
                        onShareTrack = {
                            currentTrack?.let { track ->
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(
                                        Intent.EXTRA_TEXT,
                                        "https://www.youtube.com/watch?v=${track.videoId}",
                                    )
                                }
                                context.startActivity(
                                    Intent.createChooser(intent, "Share"),
                                )
                            }
                        },
                        onAddToPlaylist = {
                            currentTrack?.let { addToPlaylistTarget = it }
                        },
                        navBarHeight = navBarHeightDp,
                        queue = playerState.queue,
                        currentQueueIndex = playerState.currentQueueIndex,
                        onRemoveQueueItem = { id -> playerViewModel.removeQueueItem(id) },
                        onMoveQueueItem = { from, to -> playerViewModel.moveQueueItem(from, to) },
                        onJumpToQueueItem = { index -> playerViewModel.jumpToQueueItem(index) },
                        onPlayNextFromQueue = { queueId ->
                            playerState.queue.find { it.queueId == queueId }?.track?.let {
                                playerViewModel.playNext(it)
                            }
                        },
                        onAddToQueueFromQueue = { queueId ->
                            playerState.queue.find { it.queueId == queueId }?.track?.let {
                                playerViewModel.addToQueue(it)
                            }
                        },
                        onMiniPlayerSizeChanged = { miniPlayerHeightDp = it },
                        playbackSpeed = playerState.playbackSpeed,
                        onSpeedChange = { speed -> playerViewModel.setPlaybackSpeed(speed) },
                        durationMs = playerState.durationMs,
                        positionMs = playerState.positionMs,
                        // YT-0093 — sleep timer.
                        sleepTimerState = sleepTimerState,
                        onSetSleepTimer = { preset: SleepTimerPreset ->
                            playerViewModel.setSleepTimer(preset)
                        },
                        onCancelSleepTimer = { playerViewModel.cancelSleepTimer() },
                    )
                }
            }

            // FAB seam — destinations register content via LocalAppShellSlots.setFab(...).
            //
            // Bottom padding stack (composability-driven, no system-inset hardcoding):
            //  - `navBarHeightDp` is the AppNavigationBar's measured outer height. The
            //    Material3 NavigationBar internally applies `WindowInsets.systemBars
            //    .only(Bottom + Horizontal)` so `navBarHeightDp` ALREADY includes the
            //    system gesture/3-button inset. Adding a separate
            //    `windowInsetsPadding(navigationBars)` here would double-count it and
            //    push the FAB ~24dp too high on 3-button devices.
            //  - `miniPlayerHeightDp` is the MiniPlayerChrome's measured height,
            //    reported by PlayerOverlay via `onMiniPlayerSizeChanged`.
            //  - `+ 16.dp` is the Material FAB margin (design system constant).
            if (fabVisible) fabContent?.let { content ->
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(bottom = navBarHeightDp)
                        .padding(
                            bottom = (if (miniPlayerVisible) miniPlayerHeightDp else 0.dp) + 16.dp,
                            end = 16.dp,
                        )
                        .onSizeChanged { size ->
                            fabHeightDp = with(density) { size.height.toDp() }
                        },
                ) {
                    content()
                }
            }

            addToPlaylistTarget?.let { track ->
                AddToPlaylistSheet(
                    track = track,
                    onDismiss = { addToPlaylistTarget = null },
                )
            }

            // YT-0251 — blocking update-required gate. Shown as a full-screen overlay
            // when the hosted feed reports `minimumSupportedVersionCode > installed`.
            // The user cannot dismiss it — only the "Update" action is available.
            // The overlay is rendered LAST so it covers everything (navigation, player, etc.).
            if (updateStatus is UpdateStatus.UpdateRequired) {
                val required = updateStatus as UpdateStatus.UpdateRequired
                UpdateRequiredOverlay(
                    versionName = required.latestVersionName,
                    notes = required.notes,
                    apkUrl = required.apkUrl,
                )
            }
        }
    }
}

/**
 * YT-0251 — full-screen blocking overlay shown when the hosted feed reports that the
 * installed build is below [minimumSupportedVersionCode].
 *
 * The user cannot dismiss this screen. Only the "Update" button is actionable; it opens
 * [apkUrl] in the system browser via Intent.ACTION_VIEW (browser handoff — no
 * REQUEST_INSTALL_PACKAGES permission is required or declared in the manifest).
 *
 * If the user navigates away from the browser without installing, this overlay will
 * reappear on next launch because update status is re-fetched on every cold start.
 */
@Composable
internal fun UpdateRequiredOverlay(
    versionName: String,
    notes: String,
    apkUrl: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    // Intercept system back — overlay is non-dismissible.
    BackHandler(enabled = true) {}

    Surface(
        modifier = modifier
            .fillMaxSize()
            .semantics {
                isTraversalGroup = true
                traversalIndex = -1f
            },
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Update Required",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = "Version $versionName is required to continue. Please update to keep using YourTube.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (notes.isNotBlank()) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = notes,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(32.dp))
            Button(
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(apkUrl))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        context.startActivity(intent)
                    } catch (e: ActivityNotFoundException) {
                        Toast.makeText(context, apkUrl, Toast.LENGTH_LONG).show()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Update to version $versionName" },
            ) {
                Text("Update")
            }
        }
    }
}

@Composable
private fun AppNavigationBar(
    navController: NavHostController,
    enabled: Boolean = true,
) {
    val destinations = TopLevelDestination.entries
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    NavigationBar {
        destinations.forEach { destination ->
            val selected = currentDestination
                ?.hierarchy
                ?.any { destination.matchesRoute(it.route) } == true
            NavigationBarItem(
                icon = {
                    // TODO YT-0276: music_note_list (U+F4E6) is absent from the shipped font
                    //  subset so LIBRARY falls back to Icons.Rounded.LibraryMusic until the
                    //  font subset is regenerated to include that glyph.
                    if (destination == TopLevelDestination.LIBRARY) {
                        Icon(
                            imageVector = Icons.Rounded.LibraryMusic,
                            contentDescription = null,
                        )
                    } else {
                        MaterialSymbolIcon(
                            icon = destination.icon,
                            filled = selected,
                            contentDescription = null,
                        )
                    }
                },
                label = { Text(destination.label) },
                selected = selected,
                enabled = enabled,
                onClick = {
                    navController.navigate(destination.rootRoute) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                modifier = Modifier.semantics {
                    contentDescription = destination.contentDescription +
                        if (selected) ", selected" else ""
                },
            )
        }
    }
}

private sealed class AppRoute(val route: String) {
    data object Search : AppRoute("search")
    data object Library : AppRoute("library")
    data object RecentlyPlayed : AppRoute("library/recently-played")
    data object PlaylistDetail : AppRoute("library/playlist")
    data object Settings : AppRoute("settings")
    data object NowPlaying : AppRoute("nowPlaying")
}

private val TopLevelDestination.rootRoute: String
    get() = when (this) {
        TopLevelDestination.SEARCH -> AppRoute.Search.route
        TopLevelDestination.LIBRARY -> AppRoute.Library.route
        TopLevelDestination.SETTINGS -> AppRoute.Settings.route
    }

private fun TopLevelDestination.matchesRoute(route: String?): Boolean = when (this) {
    TopLevelDestination.SEARCH -> route == AppRoute.Search.route
    TopLevelDestination.LIBRARY -> route == AppRoute.Library.route ||
        route == AppRoute.RecentlyPlayed.route ||
        route?.startsWith(AppRoute.PlaylistDetail.route) == true
    TopLevelDestination.SETTINGS -> route == AppRoute.Settings.route
}
