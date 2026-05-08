# Release Notes

## v0.1.0 — MVP

### Scope

Personal-use only. YourTube streams audio from public YouTube videos to its owner's own devices for offline-of-mind background listening. It is **not** distributed through the App Store or Google Play, and **no public rollout is planned** for this release.

### Known risks

- **YouTube extractor breakage**: stream URL extraction depends on third-party libraries and undocumented YouTube internals (YouTubeKit on iOS, NewPipe-derived logic on Android). YouTube can change response shapes, cipher functions, or playability gates at any time. When that happens, playback can fail silently until the extractor is updated. There is no upstream SLA. Treat the app as a hobby project that may stop working without warning until manually patched.
- **Background audio on iOS** is exercised by the iPhone 16 simulator suite; real-device confirmation is pending the user's YT-0033 manual smoke (background-task budgets behave differently on hardware). On Android, OEM background-kill policies can still terminate playback in long sessions. Capture incidents in a follow-up task rather than treating as a regression.
- **Personal data only**: no analytics, no telemetry, no third-party tracking SDKs. Sign-in is not part of MVP.

### Known v0.1.0 limitations (Android)

These are filed as backlog tasks and will land in the v0.1.x patch series. None block personal-use scope.

- **Playback latency is higher than iOS** on cold-start (NewPipe extractor + Media3 ExoPlayer overhead vs YouTubeKit + AVPlayer). Documented as a known platform difference; track post-MVP if it impacts use.
- **Drag-handle reorder and long-press contextual menu inside `PlaylistDetail`** are not implemented (tracked under YT-0063a v2 Q3 / Q6). The two-row simultaneous swipe-to-remove crash (YT-0184) was fixed pre-MVP.

The following pre-MVP issues are now resolved and no longer apply: YT-0182 (auto-advance through queue), YT-0183 (lock-screen / notification skip-next button hidden when queue has a next item), YT-0184 (PlaylistDetail multi-swipe crash), YT-0185 (in-app/MediaSession state desync after pause-from-notification), YT-0155 (tap a playlist track to start playback), YT-0153 (Library / search overflow-menu anchoring), YT-0156 (Share entry on Library playlist row).

### Distribution

- Android: install the debug `.apk` produced by `cd android && ./gradlew :app:assembleDebug`.
- iOS: build the `YourTube` scheme in Xcode against the iPhone 16 simulator or sideload onto a personal device with a free developer profile.

No store rollout, no TestFlight public link, no Play Console internal testing track for v0.1.0.

### Validation gating

- Android automated: `cd android && ./gradlew :app:assembleDebug :app:testDebugUnitTest` — both BUILD SUCCESSFUL.
- iOS automated: `cd ios && xcodebuild -project YourTube.xcodeproj -scheme YourTube -destination 'platform=iOS Simulator,name=iPhone 16' test` — TEST SUCCEEDED, 336 tests in 56 suites.
- Cross-platform parity (`docs/fixtures/playlist-*.ytplaylist.json`) — Android↔iOS round-trip codec parity covered by `CrossPlatformParityTests` (iOS) and `CrossPlatformParityTest` (Android).
- Manual smoke: `docs/mvp-validation.md` checklist. The user runs this on physical devices before tagging `v0.1.0`.
