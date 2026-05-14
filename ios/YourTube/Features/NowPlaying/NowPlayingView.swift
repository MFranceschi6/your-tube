import SwiftUI
import UIKit

// MARK: - NowPlayingView

/// Full-screen Now Playing experience, YT-0167 motion spec.
///
/// **Motion architecture** (single `progress` state — spec §Implementation Notes):
///
/// A single `@State progress: CGFloat` (0 = MiniPlayer, 1 = NowPlaying) drives every
/// per-frame value: artwork corner radius morph, scrim opacity cross-fade, transport
/// controls delayed entry, blur layer, tab bar slide in the parent, and drag-driven
/// collapse. No parallel `withAnimation` blocks — they desync.
///
/// **Reduce-motion contract** (spec §4, AC5): When `accessibilityReduceMotion` is ON,
/// the matched-geometry container is replaced by a plain `if/else` view switch wrapped
/// in a `.transition(.opacity.animation(.linear(duration: 0.12)))`. No transforms,
/// no delays, no drag-follow — back-gesture/swipe-down completes as a 120 ms cross-fade
/// in both directions. Announcements (AC8) fire regardless.
///
/// **Blur layer** (AC9, spec §2 "Scrim / background"):
/// `UIBlurEffect(.systemThickMaterialDark)` fades in 0–200 ms during expand, replaced
/// by solid `Color.black` after settle. On iOS 26+, the system `.regularMaterial`
/// provides an equivalent Liquid Glass blur — the `UIBlurEffect` `UIViewRepresentable`
/// path is preserved because `.regularMaterial` on iOS 26 adapts to Liquid Glass
/// automatically when applied in a full-screen dark context. If the design system
/// later mandates a specific LG API, update `NowPlayingBlurLayer` accordingly.
///
/// **Accessibility** (AC8, spec §7): On expand settle (+50 ms), posts
/// `UIAccessibility.Notification.screenChanged` focused on the artwork view.
/// On collapse, posts focused on the MiniPlayer thumbnail. Independent of reduce-motion.
///
/// **Hit-testing** (AC7, spec §7): `.allowsHitTesting(false)` on the in-flight surface
/// while `isTransitioning` is `true`; re-enabled on settle.
struct NowPlayingView: View {

    @Bindable var shell: AppShellViewModel
    let namespace: Namespace.ID

    // MARK: Local view state

    /// Normalized progress of this expand/collapse (0 = MiniPlayer, 1 = NowPlaying).
    /// Driven 1:1 by drag; animated on release. Single source for all per-frame values.
    @State private var progress: CGFloat = 0
    /// Whether the scrubber drag is active — drives artwork shrink (Q4).
    @State private var isScrubbing: Bool = false
    /// `NavigationPath` for the embedded stack.
    @State private var path = NavigationPath()
    /// Blur layer is shown only during the expand window (0–200 ms).
    @State private var showBlur: Bool = true
    /// Artwork `AccessibilityElement` reference for `UIAccessibility.post` on settle.
    @State private var artworkAXRef: AnyObject? = nil
    /// `true` when NowPlaying was opened before the MiniPlayer thumbnail had a chance
    /// to render (deep-link or programmatic open with no prior playback).
    /// Drives the cold-open fallback: plain fade + 4 pt upward translate over 240 ms
    /// per spec §6 "MiniPlayer not yet rendered". Captured at `onAppear` and fixed
    /// for the lifetime of this view instance.
    @State private var isColdOpen: Bool = false
    /// Logical-point height of the window scene. Updated via `.onGeometryChange` on the
    /// full-motion body — avoids the deprecated `UIScreen.main.bounds` API (iOS 16+).
    @State private var screenHeight: CGFloat = 852 // iPhone 16 default; overwritten immediately
    // `liveReduceMotion` removed: `@Environment(\.accessibilityReduceMotion)` is re-read on
    // every render so the `.onReceive` notification observer was redundant. Deleted both.

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.dismiss) private var dismiss

    private enum Route: Hashable { case queue }

    // MARK: Computed per-frame values (all derived from `progress`)

    /// Artwork corner radius interpolated 4 pt → 12 pt per spec §1.
    private var artworkCornerRadius: CGFloat {
        NowPlayingAnimation.lerp(4, 12, progress)
    }

    /// Scrim overlay opacity cross-fades over 0–240 ms (progress 0…0.75 of 320 ms total).
    private var scrimOpacity: Double {
        Double(min(1, progress / 0.75))
    }

    /// NowPlaying chrome opacity: fades in 80–280 ms (progress 0.25…0.875).
    private var chromeOpacity: Double {
        let start: CGFloat = 0.25
        let end: CGFloat = 0.875
        return Double(max(0, min(1, (progress - start) / (end - start))))
    }

    // MARK: Body

    var body: some View {
        Group {
            if reduceMotion {
                reduceMotionBody
            } else {
                fullMotionBody
            }
        }
        .preferredColorScheme(.dark)
        .tint(Theme.accent)
        .sensoryFeedback(.impact(weight: .light), trigger: path.count) { old, new in
            old < new
        }
        // Edge case AC6: app backgrounded mid-transition — jump to destination, no recovery.
        .onChange(of: scenePhase) { _, phase in
            if phase == .background || phase == .inactive {
                if shell.isNowPlayingOpen {
                    progress = 1
                    showBlur = false
                    shell.isTransitioning = false
                }
            }
        }
        .onAppear {
            // AC6 cold-open: snapshot whether the MiniPlayer source was already
            // rendered before this present. Fixed for this view instance's lifetime.
            isColdOpen = !shell.isMiniPlayerSourceRendered
            animateExpand()
        }
    }

    // MARK: Full-motion body

    private var fullMotionBody: some View {
        NavigationStack(path: $path) {
            ZStack {
                // Scrim cross-fade: surface color → bg color
                Color(red: 0.11, green: 0.11, blue: 0.11) // --color-bg (#1C1C1E equivalent)
                    .ignoresSafeArea()
                    .opacity(1 - scrimOpacity)

                Color(red: 0.06, green: 0.06, blue: 0.06) // --color-bg (#0F0F0F)
                    .ignoresSafeArea()
                    .opacity(scrimOpacity)

                // Blur layer: fades in 0–200 ms behind artwork; removed after settle (AC9).
                if showBlur {
                    NowPlayingBlurLayer()
                        .ignoresSafeArea()
                        .opacity(Double(min(1, progress / 0.625))) // 0→1 over 0–200ms of 320ms
                }

                // Cold-open (spec §6): 4 pt upward translate over 240 ms.
                // The matched-geometry pair has no source when the MiniPlayer was never
                // rendered, so the artwork just appears at its destination. The whole
                // content surface additionally slides up 4 pt to signal intentional entry.
                // `progress` is animated by `animateExpand()` on `onAppear` — the offset
                // derives directly from it (no separate `withAnimation` block).
                mainContent
                    .offset(y: isColdOpen ? NowPlayingAnimation.lerp(NowPlayingAnimation.coldOpenTranslateOffset, 0, progress) : 0)
            }
            .gesture(dragToDismiss)
            .allowsHitTesting(!shell.isTransitioning)
            .navigationDestination(for: Route.self) { route in
                switch route {
                case .queue: QueueView(shell: shell)
                }
            }
        }
        // B1 fix: read screen height from geometry instead of UIScreen.main (deprecated iOS 16+).
        // `.onGeometryChange` is iOS 17.1+, matching our deployment target; minimal layout impact.
        .onGeometryChange(for: CGFloat.self) { proxy in
            proxy.size.height
        } action: { newHeight in
            if newHeight > 0 { screenHeight = newHeight }
        }
    }

    // MARK: Reduce-motion body (AC5, spec §4)

    /// Under reduce-motion: plain if/else view switch, 120 ms cross-fade only.
    /// No matched-geometry, no transforms, no delays.
    private var reduceMotionBody: some View {
        NavigationStack(path: $path) {
            mainContent
                .background(Color(red: 0.06, green: 0.06, blue: 0.06).ignoresSafeArea())
                .transition(.opacity.animation(NowPlayingAnimation.reduceMotionCrossFade))
                .gesture(reduceMotionDismissGesture)
                .navigationDestination(for: Route.self) { route in
                    switch route {
                    case .queue: QueueView(shell: shell)
                    }
                }
        }
    }

    // MARK: Main content

    @ViewBuilder
    private var mainContent: some View {
        if let track = shell.currentTrack {
            VStack(spacing: 0) {
                topBar
                    .padding(.top, 12)
                    .opacity(chromeOpacity)

                Spacer(minLength: 8)

                // Artwork — uses matchedGeometryEffect in full-motion path.
                // In reduce-motion path, NowPlayingArtworkView still has
                // matchedGeometryEffect but the if/else switch (no shared container)
                // prevents the effect from triggering per spec §4.
                NowPlayingArtworkView(
                    track: track,
                    isPlaying: shell.isPlaying,
                    isScrubbing: isScrubbing,
                    namespace: namespace,
                    transitionCornerRadius: reduceMotion ? nil : artworkCornerRadius
                )
                .padding(.horizontal, 32)
                .padding(.top, 8)
                .accessibilityElement(children: .contain)
                .accessibilityLabel("Artwork for \(track.title), by \(track.channel). Double-tap for player controls.")
                // Capture the view for UIAccessibility.post on settle (AC8).
                .background(
                    AccessibilityAnchorView { ref in artworkAXRef = ref }
                )

                titleBlock(for: track)
                    .padding(.horizontal, Tokens.Spacing.lg)
                    .padding(.top, Tokens.Spacing.lg)
                    .opacity(chromeOpacity)

                // YT-0070: error banner inside NowPlayingView.
                if let message = shell.errorMessage {
                    PlaybackErrorBanner(
                        message: message,
                        onRetry: { shell.retryPlayback() },
                        onDismiss: { shell.dismissError() }
                    )
                    .padding(.horizontal, Tokens.Spacing.sm)
                    .padding(.top, Tokens.Spacing.sm)
                    .transition(.opacity)
                }

                NowPlayingScrubber(
                    elapsed: shell.currentTime,
                    duration: shell.duration > 0 ? shell.duration : TimeInterval(track.durationSec),
                    isDragging: $isScrubbing,
                    onSeek: { time in shell.seek(to: time) }
                )
                .padding(.horizontal, Tokens.Spacing.lg)
                .padding(.top, 8)
                .opacity(chromeOpacity)

                // Transport controls: delayed entry 200–320 ms on expand (AC2).
                // Uses `.transition` + `if shell.isNowPlayingOpen` for delayed fade + offset.
                if shell.isNowPlayingOpen {
                    NowPlayingTransportRow(
                        isPlaying: shell.isPlaying,
                        isLoading: shell.isLoading,
                        shuffleEnabled: shell.shuffleEnabled,
                        repeatMode: shell.repeatMode,
                        canSkipPrevious: shell.hasPrevious,
                        canSkipNext: shell.hasNext,
                        onShuffle: { shell.toggleShuffle() },
                        onSkipPrevious: { shell.skipPrevious() },
                        onTogglePlayPause: { shell.togglePlayPause() },
                        onSkipNext: { shell.skipNext() },
                        onRepeat: { shell.cycleRepeat() }
                    )
                    .padding(.top, 24)
                    .transition(
                        reduceMotion
                            ? .opacity.animation(NowPlayingAnimation.reduceMotionCrossFade)
                            : NowPlayingAnimation.transportEntryTransition
                    )
                }

                volumeRow
                    .padding(.top, 18)
                    .opacity(chromeOpacity)

                NowPlayingActionRow(
                    track: track,
                    onShowQueue: { path.append(Route.queue) },
                    onAddToPlaylist: { /* hooked up by add-to-playlist follow-up */ }
                )
                .padding(.top, 16)
                .opacity(chromeOpacity)

                if !upcoming.isEmpty {
                    NowPlayingUpNextPreview(
                        upcoming: upcoming,
                        onSeeAll: { path.append(Route.queue) }
                    )
                    .padding(.top, 28)
                    .transition(NowPlayingAnimation.queueTransition(reduceMotion: reduceMotion))
                    .opacity(chromeOpacity)
                }

                Spacer(minLength: 16)
            }
            .containerRelativeFrame(.horizontal)
            .animation(NowPlayingAnimation.queueSlide(reduceMotion: reduceMotion), value: upcoming.count)
        } else {
            Color.clear
        }
    }

    // MARK: Expand animation

    private func animateExpand() {
        if reduceMotion {
            // Reduce-motion: instant jump, 120 ms cross-fade handled by transition modifier.
            progress = 1
            showBlur = false
            shell.didFinishExpand() // clears both isTransitioning AND isExpandInFlight (N2 fix)
            postExpandAccessibilityNotification()
            return
        }
        if isColdOpen {
            // Cold-open (spec §6): no shared element — plain fade + 4 pt upward translate
            // over 240 ms using `motion.easing.standard`. The blur layer is skipped because
            // there is no artwork morph to accompany it.
            showBlur = false
            withAnimation(.timingCurve(0.2, 0.0, 0.0, 1.0, duration: NowPlayingAnimation.coldOpenDuration)) {
                progress = 1
            }
            DispatchQueue.main.asyncAfter(deadline: .now() + NowPlayingAnimation.coldOpenDuration) {
                shell.didFinishExpand()
                postExpandAccessibilityNotification()
            }
            return
        }
        // Full-motion expand (320 ms, spec §2).
        withAnimation(NowPlayingAnimation.expandCurve) {
            progress = 1
        }
        // Blur layer fades in 0–200 ms, then we remove it after settle.
        // Settle = 320 ms + 50 ms VoiceOver margin.
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.32) {
            withAnimation(.easeOut(duration: 0.08)) {
                showBlur = false
            }
            shell.didFinishExpand()
            postExpandAccessibilityNotification()
        }
    }

    // MARK: Collapse animation

    private func animateCollapse(from currentProgress: CGFloat = 1) {
        if reduceMotion {
            dismiss()
            shell.closeNowPlaying()
            postCollapseAccessibilityNotification()
            return
        }
        shell.isTransitioning = true
        withAnimation(NowPlayingAnimation.collapseCurve) {
            progress = 0
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.26) {
            shell.didFinishCollapse()
            dismiss()
            shell.closeNowPlaying()
            postCollapseAccessibilityNotification()
        }
    }

    // MARK: Accessibility notifications (AC8, spec §7)

    private func postExpandAccessibilityNotification() {
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.05) {
            UIAccessibility.post(notification: .screenChanged, argument: artworkAXRef)
        }
    }

    private func postCollapseAccessibilityNotification() {
        // Post .screenChanged focused on the MiniPlayer thumbnail.
        // The MiniPlayer view itself is not directly accessible here,
        // so we post nil and let VoiceOver fall back to the first element
        // in the new screen — the MiniPlayer card.
        UIAccessibility.post(notification: .screenChanged, argument: nil)
    }

    // MARK: Top bar

    private var topBar: some View {
        HStack {
            Button(action: { animateCollapse() }) {
                Image(systemName: "chevron.down")
                    .font(.system(size: 22, weight: .semibold))
                    .foregroundStyle(Color.white)
                    .frame(width: Tokens.HitTarget.minimum, height: Tokens.HitTarget.minimum)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Close player")
            .accessibilityHint("Swipe down to dismiss")

            Spacer()

            VStack(spacing: 1) {
                Text("Now Playing")
                    .font(.caption.weight(.semibold))
                    .tracking(1.4)
                    .textCase(.uppercase)
                    .foregroundStyle(Color.white.opacity(0.55))
            }

            Spacer()

            Menu {
                Button("Add to playlist", systemImage: "text.badge.plus") {}
                ShareLink(item: shareURLForCurrent ?? URL(string: "https://www.youtube.com")!)
            } label: {
                Image(systemName: "ellipsis")
                    .font(.system(size: 22, weight: .medium))
                    .foregroundStyle(Color.white)
                    .frame(width: Tokens.HitTarget.minimum, height: Tokens.HitTarget.minimum)
                    .contentShape(Rectangle())
            }
            .menuStyle(.borderlessButton)
            .accessibilityLabel("More options")
        }
        .padding(.horizontal, Tokens.Spacing.lg)
    }

    private var shareURLForCurrent: URL? {
        guard let id = shell.currentTrack?.videoId else { return nil }
        return URL(string: "https://www.youtube.com/watch?v=\(id)")
    }

    // MARK: Title block

    /// Reserved 2-line height for the title `Text` (YT-0304).
    ///
    /// Computed from `UIFont.systemFont(ofSize: 22, weight: .semibold).lineHeight × 2` so
    /// that the title region keeps a constant vertical footprint regardless of whether the
    /// rendered title wraps to 1 line or 2. Without this reservation, a 1-line title
    /// collapses the title block and shifts the scrubber + transport row up, causing the
    /// skip-next button to walk under the user's finger across track changes (matches the
    /// Android YT-0303 fix). Kept as a static computed constant — derived once from
    /// `UIFont`, no `GeometryReader` per the task notes.
    ///
    /// Exposed at `internal` access so the YT-0304 height-stability test in
    /// `NowPlayingTitleHeightStabilityTests` can assert the contract.
    static let titleReservedHeight: CGFloat =
        UIFont.systemFont(ofSize: 22, weight: .semibold).lineHeight * 2

    private func titleBlock(for track: Track) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text(track.title)
                    .font(.system(size: 22, weight: .semibold))
                    .foregroundStyle(Color.white)
                    .lineLimit(2)
                    .truncationMode(.tail)
                    .minimumScaleFactor(0.7)
                    .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
                    // YT-0304: pin the title to a 2-line reserved region so 1-line and
                    // 4-line titles produce the same Y position for the transport row.
                    // Align `.top` so 1-line titles render in the top line and the
                    // remaining vertical space is empty but reserved (spec AC).
                    .frame(
                        maxWidth: .infinity,
                        minHeight: Self.titleReservedHeight,
                        maxHeight: Self.titleReservedHeight,
                        alignment: .topLeading
                    )
                    .accessibilityAddTraits(.isHeader)

                if shell.isLoading {
                    ProgressView()
                        .progressViewStyle(.circular)
                        .controlSize(.small)
                        .tint(Color.white.opacity(0.7))
                        .accessibilityHidden(true)
                        .transition(.opacity)
                }
            }
            .padding(.trailing, Tokens.HitTarget.minimum + 8)

            Text(track.channel)
                .font(.system(size: 15))
                .foregroundStyle(Color.white.opacity(0.6))
                .lineLimit(1)
                .truncationMode(.tail)
                .dynamicTypeSize(...DynamicTypeSize.accessibility2)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
        .accessibilityValue(shell.isLoading ? "Loading \(track.title)" : "")
        .animation(reduceMotion ? nil : .easeInOut(duration: 0.2), value: shell.isLoading)
        .overlay(alignment: .topTrailing) {
            Button {
                // Add-to-playlist follow-up
            } label: {
                Image(systemName: "plus")
                    .font(.system(size: 22, weight: .semibold))
                    .foregroundStyle(Color.white.opacity(0.7))
                    .frame(width: Tokens.HitTarget.minimum, height: Tokens.HitTarget.minimum)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Add to playlist")
        }
    }

    // MARK: Volume row

    private var volumeRow: some View {
        HStack(spacing: 12) {
            Image(systemName: "speaker.fill")
                .font(.system(size: 14, weight: .medium))
                .foregroundStyle(Color.white.opacity(0.55))
            SystemVolumeSlider()
                .frame(height: 32)
            Image(systemName: "speaker.wave.3.fill")
                .font(.system(size: 14, weight: .medium))
                .foregroundStyle(Color.white.opacity(0.55))
        }
        .padding(.horizontal, Tokens.Spacing.lg)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Volume")
    }

    // MARK: Up Next slice

    private var upcoming: [Track] {
        guard let i = shell.currentIndex else { return [] }
        let next = shell.queue.dropFirst(i + 1)
        return Array(next.prefix(3))
    }

    // MARK: Drag-to-dismiss gesture (spec §3, AC4)

    /// Full-motion drag: 1:1 finger follow (no curve), release triggers collapse or spring-back.
    private var dragToDismiss: some Gesture {
        DragGesture(minimumDistance: 0, coordinateSpace: .global)
            .onChanged { value in
                guard !isScrubbing, !reduceMotion else { return }
                // 1:1 finger follow — no animation curve during drag (spec §3).
                // screenHeight is read from @State, populated via .onGeometryChange (B1 fix).
                let raw = value.translation.height / screenHeight
                if raw > 0 {
                    // Pulling down: drive progress from 1 toward 0.
                    progress = max(0, 1 - raw)
                }
                shell.setDragProgress(progress)
            }
            .onEnded { value in
                guard !isScrubbing, !reduceMotion else { return }
                let dragRatio = value.translation.height / screenHeight
                // Velocity approximation: predicted end vs current location in pt/s.
                // DragGesture provides predictedEndLocation at gesture sample rate;
                // dividing translation delta by ~0.016 s yields an approximate pt/s.
                let velocityApprox = (value.predictedEndLocation.y - value.location.y) / 0.016

                let shouldCollapse =
                    dragRatio >= NowPlayingAnimation.collapseDistanceRatio ||
                    velocityApprox >= NowPlayingAnimation.collapseVelocityThreshold

                if shouldCollapse {
                    // AC4: animate to collapsed via 260 ms curve from current state.
                    shell.isTransitioning = true
                    withAnimation(NowPlayingAnimation.collapseCurve) {
                        progress = 0
                    }
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.26) {
                        shell.didFinishCollapse()
                        dismiss()
                        shell.closeNowPlaying()
                        postCollapseAccessibilityNotification()
                    }
                } else {
                    // Spring back over 200 ms (spec §3).
                    withAnimation(NowPlayingAnimation.dragSpringBack) {
                        progress = 1
                    }
                    shell.setDragProgress(1)
                }
            }
    }

    /// Reduce-motion drag: a downward swipe dismisses as a 120 ms cross-fade (spec §4).
    private var reduceMotionDismissGesture: some Gesture {
        DragGesture(minimumDistance: 20, coordinateSpace: .global)
            .onEnded { value in
                guard value.translation.height > 0 else { return }
                animateCollapse()
            }
    }
}

// MARK: - NowPlayingBlurLayer

/// Blur layer shown behind artwork during the expand 0–200 ms window (AC9, spec §2).
///
/// Uses `UIVisualEffectView(effect: UIBlurEffect(style: .systemThickMaterialDark))`
/// via `UIViewRepresentable`.
///
/// iOS 26 / Liquid Glass equivalence call: On iOS 26, `UIBlurEffect.systemThickMaterialDark`
/// is not deprecated; however `UIVisualEffectView` adapts to Liquid Glass automatically in
/// full-screen dark contexts. The `UIViewRepresentable` bridge is retained because there is
/// no direct SwiftUI `.regularMaterial` equivalent that matches the documented
/// `systemThickMaterialDark` opacity/timing in a dark full-screen cover. If the design system
/// later confirms that `.background(.regularMaterial)` on iOS 26 is the canonical replacement,
/// swap the body here without touching any callsite.
struct NowPlayingBlurLayer: UIViewRepresentable {
    func makeUIView(context: Context) -> UIVisualEffectView {
        UIVisualEffectView(effect: UIBlurEffect(style: .systemThickMaterialDark))
    }
    func updateUIView(_ uiView: UIVisualEffectView, context: Context) {}
}

// MARK: - AccessibilityAnchorView

/// Zero-size `UIViewRepresentable` that captures a `UIView` reference for
/// `UIAccessibility.post(notification:argument:)` focus targeting (AC8, spec §7).
private struct AccessibilityAnchorView: UIViewRepresentable {
    var onCapture: (AnyObject) -> Void

    func makeUIView(context: Context) -> UIView {
        let v = UIView()
        v.isAccessibilityElement = false
        DispatchQueue.main.async { onCapture(v) }
        return v
    }
    func updateUIView(_ uiView: UIView, context: Context) {}
}
