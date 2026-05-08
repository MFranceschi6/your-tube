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

- **Auto-advance through queue is broken** (YT-0182, P1). When the current track ends naturally, playback stops instead of auto-starting the next queued item. Workaround: tap skip-next in the in-app Now Playing screen.
- **Lock-screen / notification skip-next button is hidden** even when the queue has a next item (YT-0183, P2). Skip-prev and play/pause work; skip-next must be done from the in-app Now Playing screen.
- **In-app player state desyncs from the MediaSession card after pause-from-notification** (YT-0185, P1). Pausing from the lock-screen card leaves the in-app `NowPlayingScreen` showing a stale transport state on resume. Workaround: tap play/pause in-app once to reconcile.
- **Two-row simultaneous swipe-to-remove on `PlaylistDetail` crashes the app** (YT-0184, P1). Workaround: remove rows one at a time. Drag-handle reorder and long-press contextual menu are also still not implemented (tracked under YT-0063a v2 Q3 / Q6).
- **Playback latency is higher than iOS** on cold-start (NewPipe extractor + Media3 ExoPlayer overhead vs YouTubeKit + AVPlayer). Documented as a known platform difference; track post-MVP if it impacts use.
- **Tap on a track inside a playlist does not start playback** (YT-0155, P1 backlog). Workaround: use the playlist's "Play" button (plays the whole list from the top) or "Shuffle".
- **3-dot overflow menu on a Library playlist row shows nothing** and the search-result overflow menu is anchored to the row left edge instead of below the dot icon (YT-0153, P2 backlog). Workaround: open `PlaylistDetail` to access rename/delete via that screen's overflow menu.
- **No "Share" entry on a Library playlist row's overflow menu** (YT-0156, P2 backlog). Workaround: share from `PlaylistDetail` overflow menu.

### Distribution

- Android: install the debug `.apk` produced by `cd android && ./gradlew :app:assembleDebug`.
- iOS: build the `YourTube` scheme in Xcode against the iPhone 16 simulator or sideload onto a personal device with a free developer profile.

No store rollout, no TestFlight public link, no Play Console internal testing track for v0.1.0.

### Validation gating

- Android automated: `cd android && ./gradlew :app:assembleDebug :app:testDebugUnitTest` — both BUILD SUCCESSFUL.
- iOS automated: `cd ios && xcodebuild -project YourTube.xcodeproj -scheme YourTube -destination 'platform=iOS Simulator,name=iPhone 16' test` — TEST SUCCEEDED, 336 tests in 56 suites.
- Cross-platform parity (`docs/fixtures/playlist-*.ytplaylist.json`) — Android↔iOS round-trip codec parity covered by `CrossPlatformParityTests` (iOS) and `CrossPlatformParityTest` (Android).
- Manual smoke: `docs/mvp-validation.md` checklist. The user runs this on physical devices before tagging `v0.1.0`.
