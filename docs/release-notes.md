# Release Notes

## v0.1.0 — MVP

### Scope

Personal-use only. YourTube streams audio from public YouTube videos to its owner's own devices for offline-of-mind background listening. It is **not** distributed through the App Store or Google Play, and **no public rollout is planned** for this release.

### Known risks

- **YouTube extractor breakage**: stream URL extraction depends on third-party libraries and undocumented YouTube internals (YouTubeKit on iOS, NewPipe-derived logic on Android). YouTube can change response shapes, cipher functions, or playability gates at any time. When that happens, playback can fail silently until the extractor is updated. There is no upstream SLA. Treat the app as a hobby project that may stop working without warning until manually patched.
- **Background audio on iOS** is exercised by the iPhone 16 simulator suite; real-device confirmation is pending the user's YT-0033 manual smoke (background-task budgets behave differently on hardware). On Android, OEM background-kill policies can still terminate playback in long sessions. Capture incidents in a follow-up task rather than treating as a regression.
- **Personal data only**: no analytics, no telemetry, no third-party tracking SDKs. Sign-in is not part of MVP.

### Distribution

- Android: install the debug `.apk` produced by `cd android && ./gradlew :app:assembleDebug`.
- iOS: build the `YourTube` scheme in Xcode against the iPhone 16 simulator or sideload onto a personal device with a free developer profile.

No store rollout, no TestFlight public link, no Play Console internal testing track for v0.1.0.

### Validation gating

- Android automated: `cd android && ./gradlew :app:assembleDebug :app:testDebugUnitTest` — both BUILD SUCCESSFUL.
- iOS automated: `cd ios && xcodebuild -project YourTube.xcodeproj -scheme YourTube -destination 'platform=iOS Simulator,name=iPhone 16' test` — TEST SUCCEEDED, 336 tests in 56 suites.
- Cross-platform parity (`docs/fixtures/playlist-*.ytplaylist.json`) — Android↔iOS round-trip codec parity covered by `CrossPlatformParityTests` (iOS) and `CrossPlatformParityTest` (Android).
- Manual smoke: `docs/mvp-validation.md` checklist. The user runs this on physical devices before tagging `v0.1.0`.
