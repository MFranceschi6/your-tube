import SwiftUI
import SwiftData

// MARK: - ContentView (App Shell)

/// Root view of the app. Hosts the three-tab shell (Search, Library, Settings)
/// with a persistent MiniPlayer docked above the tab bar via `safeAreaInset`.
///
/// History is reached from Library (NavigationLink). The full player is reached
/// by tapping the MiniPlayer — it presents `NowPlayingView` via
/// `.fullScreenCover` per YT-0027 Q1.
struct ContentView: View {

    @State private var viewModel = AppShellViewModel()
    @Environment(\.modelContext) private var modelContext

    // MARK: Import alert state (YT-0030)

    /// Set when a `.ytplaylist.json` URL was successfully imported via
    /// `.onOpenURL`. Drives the success alert.
    @State private var importSuccess: PlaylistImportOutcome?
    /// Set when an open-URL import failed. Drives the failure alert.
    @State private var importFailure: PlaylistImportError?

    /// Namespace for the YT-0027 `matchedGeometryEffect` now-playing hero
    /// transition. Lives at shell scope so YT-0027 is a pure additive change.
    @Namespace private var nowPlayingNamespace

    var body: some View {
        TabView {
            // MARK: Search tab
            NavigationStack {
                SearchScreen(
                    currentTrack: viewModel.currentTrack,
                    onPlay: { viewModel.play($0) },
                    onAddToQueue: { viewModel.appendToQueue($0) }
                )
            }
            .miniPlayerInset(viewModel: viewModel, namespace: nowPlayingNamespace)
            .tabItem { Label("Search", systemImage: "magnifyingglass") }
            .accessibilityIdentifier("tab.search")

            // MARK: Library tab
            LibraryScreen(
                currentVideoId: viewModel.currentTrack?.videoId,
                onPlay: { viewModel.play($0) }
            )
            .miniPlayerInset(viewModel: viewModel, namespace: nowPlayingNamespace)
            .tabItem { Label("Library", systemImage: "books.vertical") }
            .accessibilityIdentifier("tab.library")

            // MARK: Settings tab
            SettingsScreen()
                .miniPlayerInset(viewModel: viewModel, namespace: nowPlayingNamespace)
                .tabItem { Label("Settings", systemImage: "gear") }
                .accessibilityIdentifier("tab.settings")
        }
        .tint(Theme.accent)
        .animation(.easeInOut(duration: 0.25), value: viewModel.hasMiniPlayer)
        // YT-0167: slide tab bar down during expand (0–200 ms) and slide back on collapse (65–260 ms).
        // `toolbar(.hidden, for: .tabBar)` with animation is the SwiftUI-idiomatic approach
        // to hiding the system tab bar without losing its safe-area contribution.
        // The fullScreenCover covers the entire screen including the tab bar when presented,
        // so this animation targets the shell-level tab bar visibility for the MiniPlayer phase.
        .toolbar(viewModel.isNowPlayingOpen ? .hidden : .visible, for: .tabBar)
        .animation(
            viewModel.isNowPlayingOpen
                ? .timingCurve(0.2, 0.0, 0.0, 1.0, duration: 0.20)   // expand: 0–200 ms
                : .timingCurve(0.2, 0.0, 0.0, 1.0, duration: 0.195),  // collapse: 65–260 ms (195 ms from 65)
            value: viewModel.isNowPlayingOpen
        )
        // Attach the SwiftData-backed history recorder once the environment's
        // modelContext is available. Idempotent — safe to call repeatedly.
        .task { viewModel.attachHistory(context: modelContext) }
        // MARK: Imported playlist files (YT-0030)
        .onOpenURL { url in handleOpenURL(url) }
        .alert(
            "Playlist Imported",
            isPresented: Binding(
                get: { importSuccess != nil },
                set: { if !$0 { importSuccess = nil } }
            ),
            presenting: importSuccess
        ) { _ in
            Button("Done", role: .cancel) { importSuccess = nil }
        } message: { outcome in
            Text("\(outcome.name) was added to your library.")
        }
        .alert(
            importFailure?.alertTitle ?? "Import Failed",
            isPresented: Binding(
                get: { importFailure != nil },
                set: { if !$0 { importFailure = nil } }
            ),
            presenting: importFailure
        ) { _ in
            Button("OK", role: .cancel) { importFailure = nil }
        } message: { error in
            Text(error.userMessage)
        }
        // MARK: Now Playing — `.fullScreenCover` per YT-0027 Q1
        //
        // The custom drag-to-dismiss + matchedGeometryEffect artwork hero
        // both live inside `NowPlayingView`. The shell only owns the
        // namespace and the visibility flag.
        .fullScreenCover(isPresented: Binding(
            get: { viewModel.isNowPlayingOpen },
            set: { if !$0 { viewModel.closeNowPlaying() } }
        )) {
            NowPlayingView(shell: viewModel, namespace: nowPlayingNamespace)
        }
    }

    // MARK: - Import handling

    /// Route an inbound `.ytplaylist.json` URL through ``PlaylistImportService``
    /// and surface the outcome via alert state.
    ///
    /// We accept any file URL ending in `.ytplaylist.json` regardless of the
    /// hosting scheme (`file:`, `inbox:` from Files, etc.). The store does the
    /// heavy lifting; we only translate errors into UI state.
    private func handleOpenURL(_ url: URL) {
        guard url.isFileURL,
              url.lastPathComponent.lowercased().hasSuffix(".ytplaylist.json") else {
            return
        }
        let store = PlaylistStore(context: modelContext)
        do {
            importSuccess = try PlaylistImportService.importPlaylist(from: url, into: store)
            importFailure = nil
        } catch let error as PlaylistImportError {
            importFailure = error
            importSuccess = nil
        } catch {
            importFailure = .invalidSchema(error.localizedDescription)
            importSuccess = nil
        }
    }
}

// MARK: - MiniPlayer per-tab inset (YT-0045 v2)

/// View modifier that docks the persistent ``MiniPlayer`` above the
/// owning tab's content via `.safeAreaInset(edge: .bottom)`.
///
/// **Why per-tab?** Applying `.safeAreaInset(edge: .bottom)` on the outer
/// `TabView` does not reliably push the system `UITabBar` above the inset
/// content on iOS 17/18 — the tab bar and inset render in roughly the same
/// vertical region, producing the overlap captured during YT-0033 manual
/// validation. Applying the inset on each tab's content (the level UIKit's
/// `UITabBarController` actually queries for safe-area additions) makes the
/// system tab bar correctly sit below the MiniPlayer.
///
/// Only one tab is visible at a time, so exactly one ``MiniPlayer`` is on
/// screen at any moment — no duplication. State remains owned by the shared
/// `viewModel`, so all instances reflect the same playback state.
private struct MiniPlayerInset: ViewModifier {
    let viewModel: AppShellViewModel
    let namespace: Namespace.ID

    func body(content: Content) -> some View {
        content.safeAreaInset(edge: .bottom, spacing: 0) {
            VStack(spacing: 0) {
                // YT-0070: dismissable error banner above the MiniPlayer.
                // Visibility is derived from `viewModel.errorMessage` which
                // mirrors `PlayerCoordinator.state == .error(message:)`.
                // Banner and NowPlayingView read the same source — no
                // parallel error bool. The transition is intentionally a
                // simple opacity fade; honors Reduce Motion implicitly
                // because no movement is involved beyond the visibility
                // toggle.
                if let message = viewModel.errorMessage {
                    PlaybackErrorBanner(
                        message: message,
                        onRetry: { viewModel.retryPlayback() },
                        onDismiss: { viewModel.dismissError() }
                    )
                    .padding(.bottom, Tokens.Spacing.xs)
                    .transition(.opacity)
                }
                if let track = viewModel.currentTrack {
                    MiniPlayer(
                        track: track,
                        isPlaying: viewModel.isPlaying,
                        progress: viewModel.progress,
                        isLoading: viewModel.isLoading,
                        onTogglePlayPause: { viewModel.togglePlayPause() },
                        onSkipForward: { viewModel.skipNext() },
                        onExpand: { viewModel.openNowPlaying() },
                        nowPlayingNamespace: namespace
                    )
                    // YT-0167 cold-open (spec §6): mark that the MiniPlayer source
                    // has rendered so matchedGeometryEffect is available for the
                    // next openNowPlaying() call. Without this, NowPlayingView
                    // falls back to the plain fade + 4 pt translate cold-open path.
                    .onAppear { viewModel.markMiniPlayerRendered() }
                    .transition(.move(edge: .bottom).combined(with: .opacity))
                }
            }
        }
        // YT-0055: single light haptic per track tap, fired anywhere in the
        // app via `AppShellViewModel.play(_:)`. SwiftUI manages the
        // generator lifecycle (no per-tap allocation) and silences feedback
        // automatically when the user disables System Haptics.
        .sensoryFeedback(.impact(weight: .light), trigger: viewModel.trackTapHapticTrigger)
        // YT-0070: light haptic when the error banner appears so the user
        // notices the state change without needing to look at the screen.
        .sensoryFeedback(.error, trigger: viewModel.hasError) { old, new in
            !old && new
        }
    }
}

private extension View {
    /// Docks the shared ``MiniPlayer`` above this tab's content. See
    /// ``MiniPlayerInset`` for the rationale on per-tab insets.
    func miniPlayerInset(viewModel: AppShellViewModel, namespace: Namespace.ID) -> some View {
        modifier(MiniPlayerInset(viewModel: viewModel, namespace: namespace))
    }
}

// MARK: - Preview

#Preview("App Shell") {
    ContentView()
}

#Preview("App Shell — MiniPlayer active") {
    MiniPlayerActivePreview()
}

private struct MiniPlayerActivePreview: View {
    @State private var viewModel: AppShellViewModel = {
        // Preview-only wiring: bypass live network resolution by handing the
        // coordinator a no-op engine + a fake YouTube service.
        let coordinator = PlayerCoordinator(
            audioEngine: PreviewNoopAudioEngine(),
            youtubeService: PreviewNoopYouTubeService(),
            qualityProvider: { .high }
        )
        let vm = AppShellViewModel(player: coordinator)
        vm.play(Track(
            videoId: "abc",
            title: "lofi hip hop radio – beats to relax/study to",
            channel: "Lofi Girl",
            durationSec: 3600,
            thumbnailUrl: ""
        ))
        return vm
    }()

    var body: some View {
        TabView {
            SearchScreen(currentTrack: viewModel.currentTrack, onPlay: { viewModel.play($0) })
                .tabItem { Label("Search", systemImage: "magnifyingglass") }
            LibraryScreen()
                .tabItem { Label("Library", systemImage: "books.vertical") }
            SettingsScreen()
                .tabItem { Label("Settings", systemImage: "gear") }
        }
        .safeAreaInset(edge: .bottom, spacing: 0) {
            if let track = viewModel.currentTrack {
                MiniPlayer(
                    track: track,
                    isPlaying: viewModel.isPlaying,
                    progress: 0.35,
                    onTogglePlayPause: { viewModel.togglePlayPause() },
                    onSkipForward: {},
                    onExpand: {}
                )
            }
        }
    }
}

// MARK: - Preview helpers

/// No-op audio engine used by SwiftUI previews so the canvas does not touch
/// real audio APIs.
@MainActor
private final class PreviewNoopAudioEngine: AudioEngineProtocol {
    var currentTrack: Track?
    var isPlaying: Bool = false
    var currentTime: TimeInterval = 0
    var duration: TimeInterval = 0

    func play(track: Track, url: URL?, resourceLoader: HLSProxyLoader?) {
        currentTrack = track
        isPlaying = true
        duration = TimeInterval(track.durationSec)
    }
    func prepare(track: Track) {
        currentTrack = track
        currentTime = 0
        duration = TimeInterval(track.durationSec)
        // YT-0049: mirror engine contract — prepare stops previous audio.
        isPlaying = false
    }
    func pause() { isPlaying = false }
    func resume() { isPlaying = true }
    func togglePlayPause() { isPlaying.toggle() }
    func seek(to time: TimeInterval) { currentTime = time }
    func skipNext() {}
    func skipPrevious() {}
    func stop() {
        currentTrack = nil
        isPlaying = false
        currentTime = 0
        duration = 0
    }
}

/// Stream-resolution stub used by SwiftUI previews. Returns a benign dummy URL
/// so `PlayerCoordinator` can advance through `.loading → .playing` without
/// network access.
private struct PreviewNoopYouTubeService: YouTubeServiceProtocol {
    func search(query: String, maxResults: Int) async throws -> [SearchResult] { [] }
    func resolveStreamURL(videoId: String, quality: AudioQuality) async throws -> ResolvedStream {
        ResolvedStream(url: URL(string: "https://example.invalid/preview")!, isMuxedFallback: false)
    }
}
