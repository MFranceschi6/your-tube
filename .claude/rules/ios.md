---
paths:
  - "ios/**/*.swift"
  - "ios/**/*.xcodeproj/**"
  - "ios/**/*.xcworkspace/**"
  - "ios/**/*.plist"
  - "ios/**/*.entitlements"
---

# iOS rules

Idiomatic Swift on Apple platforms. Project is YourTube — see [docs/api-contracts.md](../../docs/api-contracts.md) and [docs/design-system.md](../../docs/design-system.md), and the active plan in `~/.claude/plans/`.

## Stack

- Swift 5.10+, deployment target **iOS 17+**.
- SwiftUI for new UI. UIKit only for system bridges (`UIActivityViewController`, document import).
- **Swift Concurrency** (async/await, actors, structured tasks) for new async code. Combine only if a framework hands you a publisher (e.g. `AVPlayer.publisher(for:)`); convert to async at the boundary.
- **SwiftData** for persistence. No Core Data, no Realm.
- **Swift Testing** (`@Test`, `#expect`) for new unit tests. XCTest only when a framework requires it (XCUITest, performance metrics).
- XCUITest for end-to-end UI smoke flows.
- AVFoundation + MediaPlayer for audio. **No** third-party player libs.
- YouTubeKit (SPM) for stream extraction.

## Architecture

Thin views, owned state, injected dependencies.

- **Views**: small, composable, no networking, no persistence, no side effects in `body`. Split `body` into private subviews when it crosses ~50 lines or 3 nesting levels.
- **State**: `@Observable` final classes for screen / domain state. `@State` only for view-local UI state.
- **Models**: SwiftData `@Model` types live in `Core/Persistence/`. Plain Swift structs for transient DTOs (`SearchResult`, `Track`) live in `Core/Models/`.
- **Services**: `actor` when wrapping shared mutable state (e.g. `AudioEngine`, smart cache). Plain final classes when stateless.
- **Dependencies**: pass via initializer or environment. Avoid singletons except for system-mandated ones (`MPNowPlayingInfoCenter.default()`, `MPRemoteCommandCenter.shared()`).
- **DI**: project-level container injected at `@main`. No third-party DI framework.

## SwiftUI

- Hoist state out of views. Views receive `@Bindable` / `@Observable` references, never construct stores in `body`.
- `init` of an `@Observable` view model is fine in `@State` once at view creation; do not reconstruct on every render.
- Don't use `.task { }` for fire-and-forget mutation that survives the view — own it on the model.
- `safeAreaInset(edge: .bottom)` for the persistent `MiniPlayer` per design system.
- Animations: implicit `.animation(_:value:)` first; reach for `withAnimation`, transitions, or phase animations only when state shape demands it.
- Previews must compile; use lightweight in-memory `ModelContainer` and fake services in `#Preview`.

## SwiftData

- One `ModelContainer` at app root, injected via `.modelContainer(_:)`.
- Write through `@Environment(\.modelContext)`; reads use `@Query` in views or `FetchDescriptor` in models.
- `@Model` classes: avoid stored computed-style logic. Keep them thin; put business rules on a service.
- **Autosave caveats**: SwiftData autosaves on context changes but not always before app suspension. For critical writes (playlist export, history append), call `context.save()` explicitly.
- **Predicate gotchas**: `#Predicate` does not support optional chaining on relationships in some iOS 17 builds; flatten relationships or filter in Swift after fetch.
- Migrations require `VersionedSchema` + `SchemaMigrationPlan` once the app ships — schedule before v0.1.0.

## Swift Concurrency

- `Task { }` in views must be cancelled on view disappear when long-running. Prefer `.task` modifier (auto-cancels).
- Actors isolate state but don't make async work cheap — avoid `await` inside hot loops; batch work first.
- `MainActor` for anything touching UIKit/SwiftUI types. `AudioEngine` is `@MainActor` because `AVPlayer`, `MPNowPlayingInfoCenter`, and `MPRemoteCommandCenter` are main-thread-bound.
- Structured cancellation: respect `Task.isCancelled` in long extractor calls; surface as `CancellationError`, not as a generic failure.

## Background audio (MVP-critical)

- `Info.plist`: `UIBackgroundModes` includes `audio`.
- Activate `AVAudioSession.sharedInstance()` with category `.playback`, mode `.default`, options `[.allowBluetooth, .allowAirPlay]` at app launch (in `App.init` or first play).
- Handle interruptions: subscribe to `AVAudioSession.interruptionNotification` and `routeChangeNotification`. Pause on `.began`, resume on `.ended` only when `.shouldResume` is set.
- `MPNowPlayingInfoCenter.default().nowPlayingInfo`: update on track change, on play/pause, and on seek; include `MPMediaItemPropertyTitle`, `MPMediaItemPropertyArtist`, `MPMediaItemPropertyPlaybackDuration`, `MPNowPlayingInfoPropertyElapsedPlaybackTime`, `MPNowPlayingInfoPropertyPlaybackRate`, `MPMediaItemPropertyArtwork`.
- `MPRemoteCommandCenter.shared()`: wire `playCommand`, `pauseCommand`, `togglePlayPauseCommand`, `nextTrackCommand`, `previousTrackCommand`, `changePlaybackPositionCommand`. All return `.success` only when the action actually fires.
- Tests can fake `AVPlayer` only via a thin protocol; do not subclass `AVPlayer`.

## Sharing / file I/O

- Define UTType `com.matteofranceschi.yourtube.playlist` (conforms to `public.json`) in `Info.plist` `UTExportedTypeDeclarations` and register the document type in `CFBundleDocumentTypes`.
- Export via `UIActivityViewController` from a `UIViewControllerRepresentable` host.
- Import via `.onOpenURL { }` at the scene root; route to playlist import service. Validate `schemaVersion` per [docs/api-contracts.md](../../docs/api-contracts.md) before insert.

## Accessibility

- Every interactive control: `accessibilityLabel`, `accessibilityHint` when action is non-obvious.
- Player controls expose state in label: "Pause" not "Toggle play/pause".
- Honor Dynamic Type end-to-end (no hard-coded font sizes; use `Font.body`, `.title`, etc., or `Font.system(.body, design: ...).leading(...)`).
- Honor Reduce Motion (`UIAccessibility.isReduceMotionEnabled`) for the now-playing transition.
- Min hit target 44 × 44 pt.

## Testing

- **Swift Testing** for unit tests. Use `@Suite` to group, `#expect` for assertions, `#require` to bail. Async tests use `async throws` directly — no expectations needed.
- ViewModels and services: inject all dependencies (clock, network, store). Hand-written fakes preferred over mocking libraries.
- SwiftData tests: in-memory `ModelContainer` (`isStoredInMemoryOnly: true`).
- AVPlayer-driven code: protocol-wrap `AVPlayer` interactions and fake the protocol. Do not hit a real audio file in unit tests.
- XCUITest for the search → play → MiniPlayer smoke. Background-audio verification requires a real device — flag as manual.

## Security & secrets

- No secrets in source. No bundle ids, signing identities, provisioning UUIDs, or API keys checked in.
- `.xcconfig` files for environment-specific values; `.gitignore` private overrides.
- Never log tokens, user identifiers, or playback URLs (they include short-lived signatures).

## Validation

After iOS changes, prefer the smallest relevant command:

- Schemes: `cd ios && xcodebuild -list`
- Build: `cd ios && xcodebuild -scheme YourTube -destination 'platform=iOS Simulator,name=iPhone 16' build`
- Unit tests: `cd ios && xcodebuild -scheme YourTube -destination 'platform=iOS Simulator,name=iPhone 16' test`
- Lint (when configured): `cd ios && swiftformat . --lint && swiftlint`

Per `CLAUDE.local.md`: ask before running long UI test suites.

## Skills to invoke

When working on iOS code, prefer these skills over generic exploration:

- **ios-dev** — entry point. Coordinates the others.
- **swiftui** / **guide-swiftui-ui-patterns** / **guide-swiftui-view-refactor** — building or cleaning up views.
- **swiftdata** / **guide-swiftdata** — persistence work; relationships, predicates, migrations.
- **swift-concurrency** / **guide-swift-concurrency** — async/await, actors, cancellation, AVPlayer bridging.
- **swift-testing** / **guide-swift-testing** — writing or migrating tests.
- **xcuitest** — UI smoke tests.
- **uikit** — `UIActivityViewController`, document type bridging.
- **backgroundtasks** — v1.1 offline downloads (`BGTaskScheduler`, background `URLSession`).
- **corehaptics** — play/pause and download-complete haptics from the design system.
- **hig** — accessibility, contrast, layout decisions.
- **simulator-utils** — screenshots when validating UI changes.
- **apple-docs-index** — when unsure which framework owns an API.

Defer until needed (do not preemptively pull in):

- **guide-swiftui-animations** — only when adding non-trivial motion.
- **guide-swiftui-performance-audit** — only on a measured performance issue.
- **widgetkit** — v1.6 now-playing widget.
- **appintents** — v1.4+ Siri shortcuts.
- **tipkit** — onboarding hints, optional polish.
- **usernotifications** — only if download-complete notifications ship.
- **combine** — only at framework boundaries that hand back publishers.
- **ios-ui-craft** / **ios-design-consultant** — visual polish phase.

Skip (not relevant to this project):

- **ios-liquid-glass** — iOS 26+ only; project targets iOS 17.
- **storekit** — no in-app purchase planned.
- **healthkit**, **eventkit**, **mapkit**, **photosui**, **guide-swiftui-charts** — no use case.
- **apple-aso** — app is personal-use, not on the App Store.
- **guide-macos-spm-packaging** — iOS only.
