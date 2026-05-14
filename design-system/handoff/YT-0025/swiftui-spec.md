# YT-0025 — SwiftUI Implementation Spec

> Companion to `decision-log.md`. This file is the technical contract: view hierarchy, exact file paths, modifier placement, and `@Namespace` contract.
> Status markers: `[Implemented]` = confirmed in source as of 2026-05-09. `[Proposed]` = specified here but not yet in source.

---

## File layout (actual)

```
ios/YourTube/
  App/
    YourTubeApp.swift             ← @main, ModelContainer init, AVAudioSession activation
    ContentView.swift             ← Shell root: TabView + MiniPlayerInset + fullScreenCover
    AppShellViewModel.swift       ← Shell state + playback proxy
    AppRouter.swift               ← (navigation routing support)
  Features/
    NowPlaying/
      NowPlayingView.swift        ← fullScreenCover content, drag-to-dismiss, progress state
      NowPlayingArtworkView.swift ← matchedGeometryEffect source, scale/radius animation
      NowPlayingAnimation.swift   ← All motion tokens (NowPlayingAnimation enum)
      NowPlayingScrubber.swift    ← Custom DragGesture scrubber
      NowPlayingTransportRow.swift← Transport controls + haptics
      NowPlayingActionRow.swift   ← AirPlay / queue / share / add-to-playlist
      NowPlayingUpNextPreview.swift← Inline Up Next preview
      NowPlayingBackground.swift  ← Solid / radial / extracted-color background (flagged)
      Background/
        NowPlayingBackground.swift← (subfolder variant)
      AirPlayRoutePicker.swift    ← UIViewRepresentable wrapping AVRoutePickerView
      NowPlayingInfoCenterProtocol.swift ← MPNowPlayingInfoCenter abstraction
      QueueView.swift             ← NavigationStack destination for full queue
      SystemVolumeSlider.swift    ← UIViewRepresentable wrapping MPVolumeView
    Search/
      SearchScreen.swift
      SearchViewModel.swift
      SearchModule.swift
    Library/
      LibraryScreen.swift
      LibraryViewModel.swift
      LibraryModule.swift
      PlaylistDetailScreen.swift
    History/
      RecentlyPlayedScreen.swift
      HistoryViewModel.swift
      HistoryStore.swift
    Player/
      PlayerCoordinator.swift
      PlayerModule.swift
      PlayerState.swift
      RepeatMode.swift
    Settings/
      SettingsScreen.swift
      SettingsModule.swift
  DesignSystem/
    Theme.swift                   ← Color/tint tokens (Theme.accent = #8B5CF6)
    Tokens.swift                  ← Spacing, HitTarget, cornerStyle constants
    Components/
      MiniPlayer.swift            ← Persistent MiniPlayer card component
      TrackRow.swift              ← Reusable track list row
      SkeletonRow.swift           ← Loading skeleton row
      PlaylistRow.swift           ← Playlist list row
      PlaylistCover.swift         ← 1/2-3/4+ hybrid cover
      EmptyStateView.swift        ← ContentUnavailableView wrapper
      ErrorStateView.swift        ← Error state with retry
      PlaybackErrorBanner.swift   ← Dismissable error banner above MiniPlayer
      SearchField.swift           ← Search text field
  Core/
    Audio/
      AVPlayerAudioEngine.swift
      AudioEngineProtocol.swift
      HLSProxyAsset.swift
      PlaybackPerfTracer.swift
    Models/
      Models.swift                ← Track, SearchResult, PlaylistPayload, ListUiState<T>
    Persistence/
      PersistenceSchema.swift
      PlaylistEntity.swift
      PlaylistTrackEntity.swift
      TrackEntity.swift
      HistoryEntryEntity.swift
      PlaylistStore.swift
      HistoryStore.swift
    Sharing/
      PlaylistExportService.swift
      PlaylistImportService.swift
      SharingService.swift
    YouTube/
      InnerTubePlayerClient.swift
      LiveYouTubeService.swift
      YouTubeServiceProtocol.swift
      YouTubeServiceError.swift
  Resources/
    Info.plist
```

---

## View hierarchy (top-down) [Implemented]

```
YourTubeApp (@main)
  └─ WindowGroup
       └─ ContentView                          [ios/YourTube/App/ContentView.swift]
            ├─ @State viewModel: AppShellViewModel
            ├─ @Namespace nowPlayingNamespace
            │
            ├─ TabView
            │    ├─ NavigationStack            ← Search tab
            │    │    └─ SearchScreen(currentTrack:onPlay:onAddToQueue:)
            │    │   .miniPlayerInset(viewModel:namespace:)   ← MiniPlayerInset modifier
            │    │   .tabItem { Label("Search", "magnifyingglass") }
            │    │   .accessibilityIdentifier("tab.search")
            │    │
            │    ├─ LibraryScreen(currentVideoId:onPlay:)    ← Library tab
            │    │   .miniPlayerInset(viewModel:namespace:)
            │    │   .tabItem { Label("Library", "books.vertical") }
            │    │   .accessibilityIdentifier("tab.library")
            │    │
            │    └─ SettingsScreen()                         ← Settings tab
            │        .miniPlayerInset(viewModel:namespace:)
            │        .tabItem { Label("Settings", "gear") }
            │        .accessibilityIdentifier("tab.settings")
            │
            ├─ .tint(Theme.accent)
            ├─ .animation(.easeInOut(0.25), value: viewModel.hasMiniPlayer)
            ├─ .toolbar(isNowPlayingOpen ? .hidden : .visible, for: .tabBar)
            ├─ .animation(expandOrCollapseTabBarCurve, value: viewModel.isNowPlayingOpen)
            ├─ .task { viewModel.attachHistory(context: modelContext) }
            ├─ .onOpenURL { handleOpenURL($0) }
            ├─ .alert(...)   ← import success
            ├─ .alert(...)   ← import failure
            └─ .fullScreenCover(isPresented: isNowPlayingOpen) {
                    NowPlayingView(shell: viewModel, namespace: nowPlayingNamespace)
               }
```

### `MiniPlayerInset` modifier body [Implemented]

Applied to each tab's root content. Injects:

```
content
  .safeAreaInset(edge: .bottom, spacing: 0) {
      VStack(spacing: 0) {
          PlaybackErrorBanner(...)        ← when viewModel.errorMessage != nil
              .padding(.bottom, Tokens.Spacing.xs)
              .transition(.opacity)
          MiniPlayer(
              track: viewModel.currentTrack,
              isPlaying: viewModel.isPlaying,
              progress: viewModel.progress,
              isLoading: viewModel.isLoading,
              onTogglePlayPause: { viewModel.togglePlayPause() },
              onSkipForward: { viewModel.skipNext() },
              onExpand: { viewModel.openNowPlaying() },
              nowPlayingNamespace: namespace
          )
          .onAppear { viewModel.markMiniPlayerRendered() }
          .transition(.move(edge: .bottom).combined(with: .opacity))
      }
  }
  .sensoryFeedback(.impact(weight: .light), trigger: viewModel.trackTapHapticTrigger)
  .sensoryFeedback(.error, trigger: viewModel.hasError) { old, new in !old && new }
```

---

## `@Namespace` contract [Implemented]

The namespace is declared once:

```swift
// ContentView.swift
@Namespace private var nowPlayingNamespace
```

It is passed down by value (a `Namespace.ID`):

- To `MiniPlayerInset` → stored as `let namespace: Namespace.ID` → forwarded to `MiniPlayer(nowPlayingNamespace:)`.
- To `NowPlayingView(shell:namespace:)` → stored as `let namespace: Namespace.ID` → forwarded to `NowPlayingArtworkView(namespace:)`.

`NowPlayingArtworkView` applies `matchedGeometryEffect`:

```swift
.matchedGeometryEffect(id: NowPlayingHero.artworkID, in: namespace)
```

`NowPlayingHero.artworkID = "yt-0027.artwork"` — a stable string constant defined in `NowPlayingArtworkView.swift`. Changing this string breaks the hero transition.

The MiniPlayer thumbnail must apply the same effect with `isSource: !showNowPlaying` (source when NowPlaying is closed). The NowPlaying artwork applies it with `isSource: showNowPlaying` (source when NowPlaying is open). Both must be in the view tree simultaneously during the transition for the effect to work — this is the key constraint that requires per-tab `safeAreaInset` (the MiniPlayer must remain in the tree while the `fullScreenCover` is presented).

---

## `safeAreaInset` exact signature [Implemented]

```swift
content.safeAreaInset(edge: .bottom, spacing: 0) { ... }
```

- `edge: .bottom` — docks content above the bottom safe area (above the home indicator + tab bar).
- `spacing: 0` — no gap between the inset content and the content below.
- The inset content itself provides its own internal padding.

**Why not `spacing:` default (8 pt)?** Default spacing would create a visible gap between the MiniPlayer and the tab bar on devices without a home indicator (or produce a floating gap on notched devices). The MiniPlayer's own bottom padding handles the inset from the system tab bar.

---

## Tab bar hide animation detail [Implemented]

Two separate `.animation` modifiers keyed to `viewModel.isNowPlayingOpen`:

```swift
.toolbar(viewModel.isNowPlayingOpen ? .hidden : .visible, for: .tabBar)
.animation(
    viewModel.isNowPlayingOpen
        ? .timingCurve(0.2, 0.0, 0.0, 1.0, duration: 0.20)   // expand: slides down 0–200 ms
        : .timingCurve(0.2, 0.0, 0.0, 1.0, duration: 0.195), // collapse: slides back 65–260 ms (195 ms from offset)
    value: viewModel.isNowPlayingOpen
)
```

Both use `motion.easing.standard` (`cubic-bezier(0.2, 0.0, 0.0, 1.0)`) per YT-0074 §2.

---

## MiniPlayer appearance animation [Implemented]

```swift
.animation(.easeInOut(duration: 0.25), value: viewModel.hasMiniPlayer)
```

Drives the `MiniPlayer` slide-in/out from the bottom (`.transition(.move(edge: .bottom).combined(with: .opacity))`).

---

## `AppShellViewModel` state surface [Implemented]

Properties the shell reads:

| Property | Type | Drives |
|---|---|---|
| `isNowPlayingOpen` | `Bool` | `fullScreenCover` + `.toolbar` |
| `hasMiniPlayer` | `Bool` | MiniPlayer visibility animation |
| `isTransitioning` | `Bool` | Hit-testing guard in `NowPlayingView` |
| `isMiniPlayerSourceRendered` | `Bool` | Cold-open detection |
| `currentTrack` | `Track?` | MiniPlayer content |
| `isPlaying` | `Bool` | MiniPlayer play/pause affordance |
| `progress` | `Double` | MiniPlayer progress bar |
| `isLoading` | `Bool` | MiniPlayer loading spinner |
| `errorMessage` | `String?` | `PlaybackErrorBanner` visibility |
| `hasError` | `Bool` | Error haptic trigger |
| `trackTapHapticTrigger` | `Int` | Track-tap haptic |

Actions the shell calls:

| Action | When |
|---|---|
| `viewModel.attachHistory(context:)` | `.task` on `TabView` appear |
| `viewModel.openNowPlaying()` | MiniPlayer `onExpand` callback |
| `viewModel.closeNowPlaying()` | `fullScreenCover` `set:` binding |
| `viewModel.markMiniPlayerRendered()` | `MiniPlayer.onAppear` |
| `handleOpenURL(_:)` | `.onOpenURL` on `TabView` |

---

## `NowPlayingView` shell contract [Implemented]

```swift
NowPlayingView(shell: viewModel, namespace: nowPlayingNamespace)
```

`NowPlayingView` receives `@Bindable var shell: AppShellViewModel` and calls back:

- `shell.didFinishExpand()` — after expand animation settles.
- `shell.didFinishCollapse()` + `shell.closeNowPlaying()` — after collapse.
- `shell.isTransitioning = true/false` — during drag-to-dismiss.
- `shell.setDragProgress(_:)` — 1:1 drag follow.

The shell does not own any NowPlaying animation state. `progress`, `isScrubbing`, `path`, `showBlur`, `isColdOpen`, `screenHeight` are all `@State` in `NowPlayingView`.
