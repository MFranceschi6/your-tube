---
name: mobile-native-development
description: Use when implementing, reviewing, testing, or planning native Android and iOS app features in this repository.
---

# Native Mobile Development Skill

Use this skill for Android, iOS, or cross-platform feature parity work.

## Decision flow

1. Determine target platform:
   - Android only
   - iOS only
   - Both native apps
2. Inspect existing project structure before proposing new files.
3. Prefer existing architecture, dependencies, naming, and test utilities.
4. Keep changes platform-idiomatic.
5. Preserve API and product behavior parity when both platforms are touched.

## Android checklist

- Kotlin is idiomatic and null-safe.
- Compose UI is stateless where practical.
- ViewModel owns screen state.
- Coroutines are lifecycle-aware.
- Flow collection is lifecycle-aware.
- No blocking work on main thread.
- Unit tests cover logic and state transitions.

## iOS checklist

- Swift code is idiomatic and readable.
- SwiftUI views remain small; no networking or persistence in views.
- Async work uses Swift Concurrency (async/await, actors). Combine only at framework boundaries.
- State ownership is clear; `@Observable` for screen state, `@State` for view-local UI state only.
- SwiftData is the persistence layer; one `ModelContainer` at app root.
- Swift Testing (`@Test`, `#expect`) for new unit tests. XCUITest for end-to-end.
- AVPlayer / `MPNowPlayingInfoCenter` / `MPRemoteCommandCenter` are main-thread bound — wrap in a `@MainActor` service.
- For background audio: `UIBackgroundModes = [audio]`, activate `AVAudioSession` `.playback`, handle interruption + route-change notifications.
- See [.claude/rules/ios.md](../../rules/ios.md) for full project iOS rules.

### iOS skill routing

Pick the most specific skill for the task; do not invoke skills that are not relevant.

| Task                                            | Skill                                                 |
| ----------------------------------------------- | ----------------------------------------------------- |
| Any iOS work — start here                       | `ios-dev`                                             |
| Build / clean up a SwiftUI view                 | `guide-swiftui-view-refactor`, `guide-swiftui-ui-patterns` |
| SwiftUI API lookup                              | `swiftui`                                             |
| Persistence (models, queries, migrations)       | `guide-swiftdata`, `swiftdata`                        |
| async/await, actors, cancellation               | `guide-swift-concurrency`, `swift-concurrency`        |
| Writing or migrating tests                      | `guide-swift-testing`, `swift-testing`                |
| End-to-end UI smoke                             | `xcuitest`                                            |
| `UIActivityViewController`, UTType import       | `uikit`                                               |
| v1.1 offline downloads (`BGTaskScheduler`, bg URLSession) | `backgroundtasks`                           |
| Haptics on play/pause / download complete       | `corehaptics`                                         |
| Accessibility / contrast / layout decisions     | `hig`                                                 |
| Validating UI via simulator screenshots         | `simulator-utils`                                     |
| Unsure which framework owns an API              | `apple-docs-index`                                    |
| Visual polish phase                             | `ios-ui-craft`, `ios-design-consultant`               |
| v1.6 now-playing widget                         | `widgetkit`                                           |
| v1.4+ Siri Shortcuts                            | `appintents`                                          |

**Skip** for this project: `ios-liquid-glass` (iOS 26+; we target iOS 17), `storekit`, `healthkit`, `eventkit`, `mapkit`, `photosui`, `guide-swiftui-charts`, `apple-aso`, `guide-macos-spm-packaging`.

## Shared parity checklist

When implementing both platforms:
- Same empty/loading/error/success states.
- Same validation rules.
- Same analytics events if applicable.
- Same accessibility intent.
- Same API error mapping.
- Same edge cases.

## Output format

When completing work, summarize:
1. What changed
2. Files changed
3. Tests run
4. Tests not run and why
5. Follow-up risks