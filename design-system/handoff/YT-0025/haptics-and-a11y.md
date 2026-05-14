# YT-0025 — Haptics and Accessibility

> Companion to `decision-log.md` and `swiftui-spec.md`. Covers shell-level accessibility contracts and haptics. For NowPlaying-specific haptics and a11y, see `design-system/handoff/YT-0027/haptics-and-a11y.md`.

---

## VoiceOver focus contract

### NowPlaying open (expand settle)

**Contract [Implemented]:** After the expand animation settles (+50 ms, per YT-0074 §7), post:

```swift
UIAccessibility.post(notification: .screenChanged, argument: artworkAXRef)
```

`artworkAXRef` is a `UIView` reference captured via `AccessibilityAnchorView` (a zero-size `UIViewRepresentable` placed behind the artwork). VoiceOver moves focus to the artwork view, which announces:

> "Artwork for {title}, by {channel}. Double-tap for player controls."

This is set via:

```swift
.accessibilityLabel("Artwork for \(track.title), by \(track.channel). Double-tap for player controls.")
```

on the `NowPlayingArtworkView` wrapper in `NowPlayingView`.

### NowPlaying close (collapse settle)

**Contract [Implemented]:** After collapse completes, post:

```swift
UIAccessibility.post(notification: .screenChanged, argument: nil)
```

`nil` causes VoiceOver to fall back to the first accessible element in the newly visible screen — which is the MiniPlayer card. The MiniPlayer must be the first accessible element in the tab's view tree after dismissal. (A more precise implementation would capture the MiniPlayer thumbnail view reference and post it as the argument — this is a known gap, tracked as a future improvement.)

### Announcements are motion-independent

Announcements fire regardless of `accessibilityReduceMotion`. The motion contract and the VoiceOver contract are orthogonal.

---

## Tab bar accessibility

**Contract [Implemented]:** The system `TabView` automatically generates tab bar item accessibility labels from `Label` strings. The expected announcements are:

| Tab | Announcement |
|---|---|
| Search (first) | "Search, tab, 1 of 3" |
| Library (second) | "Library, tab, 2 of 3" |
| Settings (third) | "Settings, tab, 3 of 3" |

Trait: `isTabBar` is applied automatically by UIKit. No custom accessibility configuration needed at the shell level.

Additional identifiers (`tab.search`, `tab.library`, `tab.settings`) are applied via `.accessibilityIdentifier` for XCUITest targeting, not for VoiceOver.

---

## MiniPlayer accessibility group

**Contract [Proposed — to be implemented in the MiniPlayer component]:**

The MiniPlayer should be exposed as a single accessible group rather than three separate interactive elements (thumbnail, play/pause, skip-forward). The recommended approach:

```swift
// MiniPlayer.swift
MiniPlayerCard(...)
    .accessibilityElement(children: .ignore)
    .accessibilityLabel("Now Playing: \(track.title) — \(track.channel)")
    .accessibilityHint("Double-tap to expand")
    .accessibilityAddTraits(.isButton)
    // Additional: custom actions for play/pause and skip
    .accessibilityAction(named: "Play") { onTogglePlayPause() }
    .accessibilityAction(named: "Skip forward") { onSkipForward() }
```

Rationale: Three-element swiping through thumbnail → play button → skip button is slow for VoiceOver users. The primary action is expand; play/pause and skip are secondary and reachable via custom actions.

**Current state:** Not confirmed in source. The MiniPlayer component (`ios/YourTube/DesignSystem/Components/MiniPlayer.swift`) may have individual element accessibility. This should be audited and updated to the grouped pattern.

---

## Playback error banner accessibility

**Contract [Implemented]:** When `viewModel.hasError` transitions `false → true`, a `.sensoryFeedback(.error, ...)` fires at the shell level:

```swift
.sensoryFeedback(.error, trigger: viewModel.hasError) { old, new in
    !old && new
}
```

The `PlaybackErrorBanner` itself should use:

```swift
UIAccessibility.post(notification: .announcement, argument: errorMessage)
```

to announce the error politely when it appears, so VoiceOver users who are not looking at the screen are notified. The banner has `.transition(.opacity)` — this is purely visual and does not trigger a VoiceOver announcement by itself.

---

## Haptics table (shell level)

Shell-level haptics are applied in `MiniPlayerInset`:

| Event | Feedback | Trigger |
|---|---|---|
| User taps any track row to play | `.impact(weight: .light)` | `viewModel.trackTapHapticTrigger` increments |
| Playback error appears | `.error` | `viewModel.hasError` transitions false → true |

These are applied via `.sensoryFeedback(_:trigger:)` — SwiftUI manages the generator lifecycle and respects the system "Haptics" toggle automatically.

**NowPlaying haptics** (play/pause, skip, scrubber, shuffle/repeat) are owned by `NowPlayingTransportRow` and `NowPlayingScrubber`. See `design-system/handoff/YT-0027/haptics-and-a11y.md` for the full table.

**Tab switch haptics [Proposed]:** The system `TabView` does not generate haptics on tab switch by default. The HIG does not recommend adding them. This is intentionally left out unless user feedback requests it.

---

## Reduce motion contract (shell level)

**Contract [Implemented]:** The shell respects reduce motion in two places:

1. MiniPlayer slide-in/out transition (`.move(edge: .bottom).combined(with: .opacity)`): Under reduce motion, this transition still fires. The `.move` component is not suppressed at the shell level because the MiniPlayer appearance is a minor informational event (not vestibular-risk motion). If reduce-motion sensitivity requires it, replace with `.opacity` only.

2. Tab bar hide/show animation: The `.animation` modifiers on `.toolbar` are not gated on reduce motion at the shell level. The system tab bar hiding/showing is a brief utilitarian animation. This is acceptable under the HIG's guidance that reduce-motion applies primarily to decorative animations, not functional layout transitions.

The high-motion surface — the NowPlaying expand/collapse — is fully gated on `@Environment(\.accessibilityReduceMotion)` inside `NowPlayingView`. See `NowPlayingView.swift` and `NowPlayingAnimation.swift` for the full reduce-motion contract.

---

## Dynamic Type

**Shell level [Implemented]:**

- Tab labels use system font, fully scalable.
- No font size caps at shell level.

**NowPlaying level [Implemented]:**

- Title: capped at `...DynamicTypeSize.xxxLarge` (prevents overflow over artwork area).
- Channel: capped at `...DynamicTypeSize.accessibility2`.

At the largest Dynamic Type categories, NowPlaying content scrolls within its `VStack`. The shell's `TabView` and `MiniPlayer` must support scroll or layout adaptation — audit the MiniPlayer component at AX5 (the largest standard size) to confirm no clipping.

**Minimum size category:** No minimum is enforced. Text is allowed to scale down as small as the system permits.

---

## Hit targets

All interactive elements in the shell and MiniPlayer must meet the 44 × 44 pt minimum (iOS HIG). Enforce via `.contentShape(Rectangle())` padding on small glyphs. The `Tokens.HitTarget.minimum = 44` constant is used throughout the NowPlaying components and should be used in the MiniPlayer and any future shell controls.
