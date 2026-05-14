# YT-0025 — Decision Log

iOS app shell for YourTube. Decisions below are drawn from `ContentView.swift`, `AppShellViewModel.swift`, and the related task history. Each entry records what was decided, the rationale, and whether it is currently in the codebase.

---

## 1. Tab structure: three tabs, fixed order

**Decision [Implemented]:** `TabView` with three tabs in this order: Search, Library, Settings. History is reachable from Library (NavigationLink), not a top-level tab.

**Tab identifiers:**

| Position | Label | SF Symbol | `accessibilityIdentifier` |
|---|---|---|---|
| 1 | Search | `magnifyingglass` | `tab.search` |
| 2 | Library | `books.vertical` | `tab.library` |
| 3 | Settings | `gear` | `tab.settings` |

**Rationale:** Three tabs keeps the bar uncrowded and the most-used destinations (search, library) first. History is secondary — a sub-destination of Library — and Settings is a utility destination that users access infrequently. Contrast with the Android shell which also has three primary tabs. NowPlaying is not a tab; it is a modal layer on top.

**Source:** `ios/YourTube/App/ContentView.swift`

---

## 2. MiniPlayer docking: per-tab `safeAreaInset` over outer TabView

**Decision [Implemented]:** The MiniPlayer is docked via `.safeAreaInset(edge: .bottom, spacing: 0)` applied **individually on each tab's content**, not on the outer `TabView`.

**Rationale:** Applying `.safeAreaInset(edge: .bottom)` on the outer `TabView` does not reliably push the system `UITabBar` above the inset content on iOS 17/18. The tab bar and inset content render in the same vertical region, producing overlap. Applying the inset at the level UIKit's `UITabBarController` actually queries for safe-area additions — i.e. each tab's root content view — makes the system tab bar correctly sit below the MiniPlayer. This was validated during YT-0033 manual validation.

Only one tab is visible at a time, so exactly one MiniPlayer instance is on screen at any moment. State is owned by the shared `AppShellViewModel`, so all three instances reflect identical playback state.

**Pattern name:** `MiniPlayerInset` (private `ViewModifier` in `ContentView.swift`). Applied via `.miniPlayerInset(viewModel:namespace:)`.

**Source:** `ios/YourTube/App/ContentView.swift` — `MiniPlayerInset` and `miniPlayerInset` extension.

---

## 3. `@Namespace` location: `ContentView`, injected into MiniPlayer and NowPlayingView

**Decision [Implemented]:** The single `@Namespace private var nowPlayingNamespace` lives in `ContentView`. It is passed down by value to `MiniPlayerInset` (which passes it to `MiniPlayer`) and to `NowPlayingView` via `fullScreenCover`.

**Why it must live at ContentView scope:** `matchedGeometryEffect` requires that both the source and the destination views share the same namespace. The source is the `MiniPlayer` thumbnail (inside the `safeAreaInset`); the destination is `NowPlayingArtworkView` (inside the `fullScreenCover`). Both are subtrees of `ContentView`, so the namespace must live at `ContentView` or higher. Placing it in a sub-view would break the shared-element contract because the covering `fullScreenCover` would be in a sibling subtree that cannot reach a namespace declared in a child.

**Hero ID:** `NowPlayingHero.artworkID = "yt-0027.artwork"` (defined in `NowPlayingArtworkView.swift` to keep YT-0025 and YT-0027 in lockstep).

**Source:** `ios/YourTube/App/ContentView.swift` line `@Namespace private var nowPlayingNamespace`; `ios/YourTube/Features/NowPlaying/NowPlayingArtworkView.swift` — `NowPlayingHero`.

---

## 4. NowPlaying presentation: `.fullScreenCover` (not `.sheet`, not NavigationStack push)

**Decision [Implemented]:** `NowPlayingView` is presented via `.fullScreenCover(isPresented:)` attached to the `TabView`. The custom drag-to-dismiss gesture and `matchedGeometryEffect` artwork hero both live inside `NowPlayingView`; the shell only owns the namespace and the `isNowPlayingOpen` visibility flag.

**Why not `.sheet`:** A `.sheet(.large)` always exposes ~10 pt of background and a system grabber that cannot be removed. Two-detent sheet implies the medium detent has a job — but the MiniPlayer already is the medium state. Sheet entry animation is not compatible with the artwork morph.

**Why not NavigationStack push:** Push transitions are horizontal; the canonical player entry is vertical (expand from bottom). Push also breaks the shared-element contract with `matchedGeometryEffect` across a navigation transition boundary.

**Configuration:**

```swift
.fullScreenCover(isPresented: Binding(
    get: { viewModel.isNowPlayingOpen },
    set: { if !$0 { viewModel.closeNowPlaying() } }
)) {
    NowPlayingView(shell: viewModel, namespace: nowPlayingNamespace)
}
```

`presentationBackground(.clear)` is not set at the shell; `NowPlayingView` sets `.preferredColorScheme(.dark)` internally and manages its own background.

**Source:** `ios/YourTube/App/ContentView.swift` — MARK: Now Playing section.

---

## 5. Tab bar hide/show during NowPlaying expand/collapse

**Decision [Implemented]:** The system tab bar is hidden via `.toolbar(viewModel.isNowPlayingOpen ? .hidden : .visible, for: .tabBar)` with separate animations for expand vs. collapse.

- Expand (tab bar slides down): `.timingCurve(0.2, 0.0, 0.0, 1.0, duration: 0.20)` — per YT-0074 §2 "Tab bar 0–200 ms".
- Collapse (tab bar slides back): `.timingCurve(0.2, 0.0, 0.0, 1.0, duration: 0.195)` — 195 ms from the 65 ms collapse-start offset.

**Why not `withAnimation`:** Two separate `.animation(_:value:)` modifiers keyed to `viewModel.isNowPlayingOpen` let SwiftUI pick the right curve depending on whether the value is going `false → true` (expand) or `true → false` (collapse). A single `withAnimation` block would use one curve for both directions.

**Source:** `ios/YourTube/App/ContentView.swift` — `.toolbar` + `.animation` modifiers on the `TabView`.

---

## 6. Glass materials: none on shell surfaces; `NowPlayingView` owns blur layer

**Decision [Implemented / Proposed]:**

- Shell `TabView`: no explicit material. The system tab bar uses its own frosted-glass material natively.
- MiniPlayer: no explicit material in the shell. The MiniPlayer view itself may use `.background(.ultraThinMaterial)` for its card surface — this is a decision owned by the MiniPlayer component, not the shell.
- NowPlayingView: `UIBlurEffect(.systemThickMaterialDark)` via `UIViewRepresentable` (`NowPlayingBlurLayer`), fading in during the 0–200 ms expand window only. After settle, solid background takes over.

**iOS 26 / Liquid Glass note [Proposed]:** The project ships on iOS 26 (per project memory). On iOS 26, `UIVisualEffectView` with `systemThickMaterialDark` adapts to Liquid Glass automatically in a full-screen dark context. The `UIViewRepresentable` bridge is retained because there is no direct SwiftUI `.regularMaterial` equivalent that matches the documented opacity/timing for this specific blur effect. If the design system later confirms `.background(.regularMaterial)` on iOS 26 is the canonical replacement for the NowPlaying blur layer, update `NowPlayingBlurLayer` without touching any call site. The shell itself has no glass surface decision to make today.

**Source:** `ios/YourTube/Features/NowPlaying/NowPlayingView.swift` — `NowPlayingBlurLayer`.

---

## 7. Swipe-dismiss / predictive back: custom `DragGesture` on NowPlayingView

**Decision [Implemented]:** Drag-to-dismiss is a custom `DragGesture` inside `NowPlayingView`, not an iOS 16+ interactive dismiss gesture on the `fullScreenCover`. The gesture drives `progress` 0→1 with 1:1 finger follow (no animation curve during drag). On release:

- `dragRatio ≥ 0.30` (30% of screen height) OR `velocity ≥ 800 pt/s` → collapse (260 ms, `NowPlayingAnimation.collapseCurve`).
- Below threshold → spring back (200 ms, `NowPlayingAnimation.dragSpringBack`).

The shell does not own the gesture. The `fullScreenCover` binding's `set:` closure handles the programmatic `closeNowPlaying()` path.

**Under reduce-motion:** A simplified `DragGesture(minimumDistance: 20)` fires `animateCollapse()` on any downward swipe, completing as a 120 ms cross-fade.

**Source:** `ios/YourTube/Features/NowPlaying/NowPlayingView.swift` — `dragToDismiss` and `reduceMotionDismissGesture`.

---

## 8. Provider hierarchy: `AppShellViewModel` injected at shell level, not environment

**Decision [Implemented]:** `AppShellViewModel` is a `@State private var` created once in `ContentView`. It is passed as an initializer argument to screens that need it (`SearchScreen`, `LibraryScreen`, `NowPlayingView`). It is not injected via `.environment` or `.environmentObject`.

**What each tab receives:**

| Screen | What it receives from shell |
|---|---|
| `SearchScreen` | `currentTrack`, `onPlay`, `onAddToQueue` callbacks |
| `LibraryScreen` | `currentVideoId`, `onPlay` callback |
| `SettingsScreen` | Nothing from shell directly |
| `NowPlayingView` | `shell: viewModel` (full `@Bindable` reference) + `namespace` |

`AppShellViewModel` is `@Observable`, so passed-down properties update views reactively without `ObservableObject` / `@Published`.

**SwiftData `ModelContainer`:** Injected at `YourTubeApp.body` via `.modelContainer(modelContainer)` — this is the standard SwiftData root injection and is environment-propagated to all descendants automatically.

**Source:** `ios/YourTube/App/ContentView.swift` — `@State private var viewModel = AppShellViewModel()`.

---

## 9. Dynamic Type: capped at appropriate size categories, not fully uncapped

**Decision [Implemented]:** NowPlaying title text is capped at `.dynamicTypeSize(...DynamicTypeSize.xxxLarge)`. Channel name is capped at `...DynamicTypeSize.accessibility2`. Rationale: at the three accessibility sizes (A1, A2, A3) the track title would overflow the fixed-height artwork region in the NowPlaying layout. The caps still provide significant scaling; layouts below the caps must not clip.

**Shell level:** No Dynamic Type caps are applied in `ContentView`. Tab labels scale freely. The MiniPlayer component should define its own caps internally.

**Source:** `ios/YourTube/Features/NowPlaying/NowPlayingView.swift` — `titleBlock(for:)`.

---

## 10. Transition guard: double-tap debounce and backgrounding

**Decision [Implemented]:** `AppShellViewModel` carries `isExpandInFlight` and `isTransitioning` booleans.

- `openNowPlaying()` is a no-op when `isExpandInFlight == true` — prevents rapid double-tap from triggering two concurrent expand animations.
- `NowPlayingView` body applies `.allowsHitTesting(!shell.isTransitioning)` to prevent interaction during any in-flight transition.
- `scenePhase` observer in `NowPlayingView`: if the app backgrounds mid-transition, `progress` snaps to 1 (destination), `isTransitioning` is cleared, and no recovery animation runs on resume.

**Source:** `ios/YourTube/App/AppShellViewModel.swift` — `openNowPlaying`, `isExpandInFlight`, `isTransitioning`; `ios/YourTube/Features/NowPlaying/NowPlayingView.swift` — `.onChange(of: scenePhase)`.
