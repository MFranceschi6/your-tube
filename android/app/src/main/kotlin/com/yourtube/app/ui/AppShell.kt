package com.yourtube.app.ui

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.yourtube.app.R
import com.yourtube.app.navigation.TopLevelDestination
import com.yourtube.app.sharing.PlaylistImportResult
import com.yourtube.app.sharing.PlaylistShareLauncher
import com.yourtube.app.sharing.SharingViewModel
import com.yourtube.core.common.model.Track
import com.yourtube.core.ui.AppShellSlots
import com.yourtube.core.ui.LocalAppShellInsets
import com.yourtube.core.ui.LocalAppShellSlots
import com.yourtube.core.ui.LocalNavAnimatedVisibilityScope
import com.yourtube.core.ui.LocalSharedTransitionScope
import com.yourtube.core.ui.MiniPlayer
import com.yourtube.core.ui.NowPlayingScreen
import com.yourtube.feature.history.RecentlyPlayedScreen
import com.yourtube.feature.library.AddToPlaylistSheet
import com.yourtube.feature.library.LibraryScreen
import com.yourtube.feature.library.PlaylistDetailScreen
import com.yourtube.feature.player.PlayerViewModel
import com.yourtube.feature.search.SearchScreen
import com.yourtube.feature.settings.SettingsScreen
import kotlinx.coroutines.flow.Flow

/**
 * Root composable for the app. Layered top-to-bottom inside the outer `Box`:
 *
 *  1. `Scaffold { NavHost }` — content + Material 3 bottom navigation bar (only).
 *  2. `MiniPlayer` overlay (when a track is loaded) — anchored above the nav inset.
 *  3. FAB seam — destinations register a composable via [LocalAppShellSlots], rendered
 *     bottom-end above the MiniPlayer + nav inset.
 *  4. `AddToPlaylistSheet` (overlays everything when active).
 *
 * The full surface is wrapped in [SharedTransitionLayout] and exposes its scope through
 * [LocalSharedTransitionScope] so the MiniPlayer artwork morphs into the NowPlaying artwork
 * when navigating to the `nowPlaying` destination. Predictive-back is provided by `NavHost`
 * itself; the manifest sets `android:enableOnBackInvokedCallback="true"`.
 *
 * Player state is owned by `PlayerViewModel` (activity-scoped via Hilt).
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun AppShell(
    modifier: Modifier = Modifier,
    openNowPlayingRequests: Flow<Unit>? = null,
) {
    val playerViewModel: PlayerViewModel = hiltViewModel()
    val playerState by playerViewModel.playerState.collectAsState()
    val navController = rememberNavController()

    // YT-0062a Q11: when the activity receives `ACTION_OPEN_NOW_PLAYING` (from the
    // session-activity PendingIntent attached to the MediaSession), navigate into the
    // NowPlaying route. The activity surfaces these via a replay-1 SharedFlow so the
    // launching-intent emission survives until this composable subscribes.
    if (openNowPlayingRequests != null) {
        LaunchedEffect(openNowPlayingRequests, navController) {
            openNowPlayingRequests.collect {
                navController.navigate(AppRoute.NowPlaying.route) {
                    launchSingleTop = true
                }
            }
        }
    }

    // Hoisted target for the add-to-playlist sheet. Track is not Parcelable, so this state is
    // process-only — acceptable for a transient bottom sheet (the sheet dismisses on
    // configuration change rather than restoring).
    var addToPlaylistTarget by remember { mutableStateOf<Track?>(null) }

    val sharingViewModel: SharingViewModel = hiltViewModel()
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        sharingViewModel.events.collect { result ->
            val text = when (result) {
                is PlaylistImportResult.Success ->
                    context.getString(R.string.import_playlist_success, result.playlist.name)
                PlaylistImportResult.UnsupportedSchema ->
                    context.getString(R.string.import_playlist_unsupported)
                PlaylistImportResult.InvalidPayload ->
                    context.getString(R.string.import_playlist_invalid)
                PlaylistImportResult.Unreadable ->
                    context.getString(R.string.import_playlist_unreadable)
            }
            Toast.makeText(context, text, Toast.LENGTH_LONG).show()
        }
    }

    val currentTrack = playerState.currentTrack
    val isPlaying = playerState.isPlaying
    val progressFraction = if (playerState.durationMs > 0)
        (playerState.positionMs.toFloat() / playerState.durationMs).coerceIn(0f, 1f)
    else 0f

    val onPlayTrack: (Track) -> Unit = { track -> playerViewModel.playNow(track) }

    // Observe the active back-stack route so we can hide chrome while NowPlaying is on top.
    // `derivedStateOf` avoids re-running the body on every unrelated recomposition.
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val isNowPlayingActive by remember {
        derivedStateOf { navBackStackEntry?.destination?.route == AppRoute.NowPlaying.route }
    }

    // --- Slot state -----------------------------------------------------------
    // FAB content registered by the active destination. `null` means no FAB is rendered.
    var fabContent by remember { mutableStateOf<(@Composable () -> Unit)?>(null) }
    val slots = remember {
        object : AppShellSlots {
            override fun setFab(content: (@Composable () -> Unit)?) {
                fabContent = content
            }
        }
    }

    // Live-measured heights drive [LocalAppShellInsets]. Hard-coding would break under non-1.0
    // font scale and accessibility settings.
    val density = LocalDensity.current
    var miniPlayerHeightDp by remember { mutableStateOf(0.dp) }
    var fabHeightDp by remember { mutableStateOf(0.dp) }
    var navBarHeightDp by remember { mutableStateOf(0.dp) }

    // MiniPlayer is visible when a track is loaded AND NowPlaying is not the active destination.
    // When NowPlaying opens, AnimatedVisibility below fades the overlay out after the shared-
    // element artwork morph completes (the MiniPlayer stays in composition during the transition).
    val miniPlayerVisible = currentTrack != null && !isNowPlayingActive
    val fabVisible = fabContent != null && !isNowPlayingActive

    // Bottom-only padding — destinations only consume `.calculateBottomPadding()`. Reviewer
    // (YT-0061 round 1) flagged that exposing top/start/end derived from `navigationBars` is a
    // misuse trap; tighten the surface so it cannot leak into other axes.
    //
    // AC4 (YT-0151 round 2): drive the inset collapse directly from `isNowPlayingActive` rather
    // than from `onSizeChanged` timing. `onSizeChanged` callbacks fire only after the layout pass
    // that follows the `AnimatedVisibility` exit animation, so `navBarHeightDp` stays at its last
    // non-zero value for the full exit duration — causing phantom bottom padding in child screens
    // throughout the NowPlaying open transition. By zeroing the effective heights at the moment
    // `isNowPlayingActive` becomes true, the inset collapses immediately on state change, not
    // after the animation completes. `onSizeChanged` is still used so measured heights are
    // accurate when chrome reappears (!isNowPlayingActive).
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

    // Active destination scopes outside the NavHost. Each `composable { }` registers its
    // `AnimatedVisibilityScope` here on enter and clears it on dispose. The MiniPlayer overlay
    // (which lives outside the NavHost) reads the topmost-non-NowPlaying scope so its
    // `Modifier.sharedBounds(...)` registers against the screen the MiniPlayer is *attached to*
    // (i.e. the destination behind NowPlaying). NowPlaying registers the matching key against
    // its own scope, giving Compose the two-scope pairing it needs to morph artwork.
    val destinationScopes = remember { mutableStateListOf<DestinationScopeEntry>() }
    val miniPlayerScope = destinationScopes
        .lastOrNull { it.route != AppRoute.NowPlaying.route }
        ?.scope

    SharedTransitionLayout(modifier = modifier) {
        val sharedScope = this
        CompositionLocalProvider(
            LocalAppShellSlots provides slots,
            LocalAppShellInsets provides shellInsets,
            LocalSharedTransitionScope provides sharedScope,
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    bottomBar = {
                        // Measure the actual NavigationBar height (grows with font scale) so
                        // overlay padding doesn't collide at large text sizes. The outer Box
                        // always measures — AnimatedVisibility collapses it to zero when
                        // NowPlaying is active, which propagates a 0 dp height into shellInsets.
                        Box(
                            modifier = Modifier.onSizeChanged { size ->
                                navBarHeightDp = with(density) { size.height.toDp() }
                            },
                        ) {
                            AnimatedVisibility(
                                visible = !isNowPlayingActive,
                                enter = slideInVertically { it } + fadeIn(),
                                exit = slideOutVertically { it } + fadeOut(),
                            ) {
                                AppNavigationBar(navController = navController)
                            }
                        }
                    },
                ) { innerPadding ->
                    NavHost(
                        navController = navController,
                        startDestination = AppRoute.Search.route,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                    ) {
                        composable(AppRoute.Search.route) {
                            DestinationScope(AppRoute.Search.route, destinationScopes) {
                                SearchScreen(
                                    onTrackClick = onPlayTrack,
                                    onAddToQueue = { track -> playerViewModel.addToQueue(track) },
                                    onPlayNext = { track -> playerViewModel.playNext(track) },
                                    onAddToPlaylist = { track -> addToPlaylistTarget = track },
                                    currentTrackVideoId = currentTrack?.videoId,
                                )
                            }
                        }
                        composable(AppRoute.Library.route) {
                            DestinationScope(AppRoute.Library.route, destinationScopes) {
                                LibraryScreen(
                                    onOpenRecentlyPlayed = {
                                        navController.navigate(AppRoute.RecentlyPlayed.route)
                                    },
                                    onOpenPlaylistDetail = { playlistId ->
                                        navController.navigate(
                                            "${AppRoute.PlaylistDetail.route}/$playlistId",
                                        )
                                    },
                                )
                            }
                        }
                        composable("${AppRoute.PlaylistDetail.route}/{playlistId}") {
                            DestinationScope(AppRoute.PlaylistDetail.route, destinationScopes) {
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
                                    currentTrackVideoId = currentTrack?.videoId,
                                )
                            }
                        }
                        composable(AppRoute.RecentlyPlayed.route) {
                            DestinationScope(AppRoute.RecentlyPlayed.route, destinationScopes) {
                                RecentlyPlayedScreen(
                                    onBackClick = { navController.popBackStack() },
                                    onPlayTrack = onPlayTrack,
                                    currentTrackVideoId = currentTrack?.videoId,
                                )
                            }
                        }
                        composable(AppRoute.Settings.route) {
                            DestinationScope(AppRoute.Settings.route, destinationScopes) {
                                SettingsScreen(
                                    onImportFromUri = { uri -> sharingViewModel.import(uri) },
                                )
                            }
                        }
                        composable(AppRoute.NowPlaying.route) {
                            val nowPlayingScope: AnimatedVisibilityScope = this
                            DestinationScope(AppRoute.NowPlaying.route, destinationScopes) {
                                NowPlayingScreen(
                                    track = currentTrack,
                                    isPlaying = isPlaying,
                                    progressFraction = progressFraction,
                                    onPlayPauseClick = {
                                        if (isPlaying) playerViewModel.pause() else playerViewModel.resume()
                                    },
                                    onSkipNextClick = { playerViewModel.skipNext() },
                                    onSkipPreviousClick = { playerViewModel.skipPrevious() },
                                    onSeek = { fraction ->
                                        playerViewModel.seekTo(
                                            (fraction * playerState.durationMs).toLong(),
                                        )
                                    },
                                    onDismiss = { navController.popBackStack() },
                                    modifier = Modifier.fillMaxSize(),
                                    queue = playerState.queue,
                                    currentQueueIndex = playerState.currentQueueIndex,
                                    onRemoveQueueItem = { queueId ->
                                        playerViewModel.removeQueueItem(queueId)
                                    },
                                    onMoveQueueItem = { from, to ->
                                        playerViewModel.moveQueueItem(from, to)
                                    },
                                    artworkModifier = sharedArtworkModifier(
                                        sharedScope = sharedScope,
                                        animatedVisibilityScope = nowPlayingScope,
                                        videoId = currentTrack?.videoId,
                                    ),
                                )
                            }
                        }
                    }
                }

                // MiniPlayer overlay — anchored to the bottom inset, sits above content but
                // below the nav bar in the Scaffold. Its `artworkModifier` registers the
                // outbound side of the artwork shared-bounds morph against the *underlying*
                // destination's `AnimatedVisibilityScope` (e.g. Search), so when navigating to
                // NowPlaying (which registers the same key against its own scope) Compose
                // interpolates the bounds.
                //
                // YT-0151: gated via AnimatedVisibility (not a hard `if`) so the MiniPlayer
                // stays in composition during the NowPlaying enter transition, letting the
                // shared-element artwork morph complete before the overlay fades out.
                // `miniPlayerVisible` is false when NowPlaying is active, collapsing the
                // measured height to 0 dp and clearing the bottom inset from shellInsets.
                if (currentTrack != null) {
                    val miniArtworkModifier = sharedArtworkModifier(
                        sharedScope = sharedScope,
                        animatedVisibilityScope = miniPlayerScope,
                        videoId = currentTrack.videoId,
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .windowInsetsPadding(WindowInsets.navigationBars)
                            .padding(bottom = navBarHeightDp)
                            .onSizeChanged { size ->
                                miniPlayerHeightDp = with(density) { size.height.toDp() }
                            },
                    ) {
                        AnimatedVisibility(
                            visible = miniPlayerVisible,
                            enter = slideInVertically { it } + fadeIn(),
                            exit = slideOutVertically { it } + fadeOut(),
                        ) {
                            MiniPlayer(
                                track = currentTrack,
                                isPlaying = isPlaying,
                                progressFraction = progressFraction,
                                onPlayPauseClick = {
                                    if (isPlaying) playerViewModel.pause() else playerViewModel.resume()
                                },
                                onSkipNextClick = { playerViewModel.skipNext() },
                                onExpandClick = {
                                    navController.navigate(AppRoute.NowPlaying.route) {
                                        launchSingleTop = true
                                    }
                                },
                                artworkModifier = miniArtworkModifier,
                            )
                        }
                    }
                }

                // FAB seam — destinations register content via LocalAppShellSlots.setFab(...).
                // YT-0151: also hidden while NowPlaying is active (fabVisible = false).
                if (fabVisible) fabContent?.let { content ->
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .windowInsetsPadding(WindowInsets.navigationBars)
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

                // Add-to-playlist bottom sheet (YT-0014 AC4) — opened by Search's overflow menu.
                addToPlaylistTarget?.let { track ->
                    AddToPlaylistSheet(
                        track = track,
                        onDismiss = { addToPlaylistTarget = null },
                    )
                }
            }
        }
    }
}

/**
 * One entry per currently-composed Navigation destination. Tracked at the AppShell level so
 * overlay siblings of the NavHost (MiniPlayer, FAB) can attach `Modifier.sharedBounds(...)` to
 * the right [AnimatedVisibilityScope].
 */
private data class DestinationScopeEntry(
    val route: String,
    val scope: AnimatedVisibilityScope,
)

/**
 * Wraps a destination's content with the active `AnimatedVisibilityScope` so children can
 * read [LocalNavAnimatedVisibilityScope] for shared-element transitions, and registers the
 * scope into the shell-level [registry] so MiniPlayer / FAB overlays (which live outside
 * `NavHost`) can also reach it.
 */
@Composable
private fun AnimatedVisibilityScope.DestinationScope(
    route: String,
    registry: SnapshotStateList<DestinationScopeEntry>,
    content: @Composable () -> Unit,
) {
    val scope: AnimatedVisibilityScope = this
    DisposableEffect(scope, route) {
        val entry = DestinationScopeEntry(route, scope)
        registry += entry
        onDispose { registry.remove(entry) }
    }
    CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides scope) {
        content()
    }
}

/**
 * Builds the `Modifier.sharedBounds(...)` that wires the artwork morph between MiniPlayer and
 * NowPlaying. Returns plain [Modifier] when either [animatedVisibilityScope] or [videoId] is
 * null — exposed `internal` so unit tests can assert the conditional branch directly.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun sharedArtworkModifier(
    sharedScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope?,
    videoId: String?,
): Modifier {
    if (videoId == null || animatedVisibilityScope == null) return Modifier
    return with(sharedScope) {
        Modifier.sharedBounds(
            sharedContentState = rememberSharedContentState(key = "artwork-$videoId"),
            animatedVisibilityScope = animatedVisibilityScope,
            boundsTransform = { _, _ ->
                spring(stiffness = 400f, dampingRatio = 0.82f)
            },
        )
    }
}

@Composable
private fun AppNavigationBar(navController: NavHostController) {
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
                    Icon(
                        imageVector = destination.icon,
                        contentDescription = null, // item has contentDescription below
                    )
                },
                label = { Text(destination.label) },
                selected = selected,
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
