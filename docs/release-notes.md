# Release Notes

## v0.1.2 — YouTube extractor hotfix (2026-09-03)

### What changed since v0.1.1

Android-only hotfix. Playback had stopped working on every track: YouTube started gating the `googlevideo.com` URLs returned to the `ANDROID_VR` InnerTube client behind a Proof-of-Origin token (yt-dlp upstream: "Since 2026.08.17, ALL formats ... are 403'd with version 1.65.10"). Symptoms reproduced with curl on 2026-09-03: `HEAD` → 403, and any GET → 403 after ~1 MB cumulative per URL, i.e. roughly one minute of audio.

- **InnerTube `/player` client swapped to `VISIONOS` 1.02** (`InnerTubePlayerClient.kt`), yt-dlp's current default JS-less client. No PO token, no signature solver, pre-signed URLs, `HEAD` 200 and full byte-range service verified. Same swap applied to iOS source (not part of this release).
- **Docs**: `docs/android-extraction.md` records the new pin, the VISIONOS quirks (no progressive `formats` array, un-ranged GETs throttled) and a rotation-monitoring note for the "player OK but googlevideo 403" failure mode.
- Perf-trace marks for the autoplay advance path in `DefaultPlayerController.kt` (already in the working tree; no behaviour change).

### Distribution

- Hosted APK: `https://mfranceschi6.github.io/your-tube/android/yourtube-0.1.2.apk`
- Feed: `https://mfranceschi6.github.io/your-tube/android/update.json`
- `versionCode` 3, `minimumSupportedVersionCode` stays 1.
- Signing keystore: same lineage as v0.1.0 / v0.1.1 (cert SHA-256 `93a9d3ca29742fb78ea1755b54edf6bd3eb97528faf4629e376a1e207c4eb42d`). Upgrades install in-place.

### Validation gating

- `cd android && ./gradlew :core:network:testDebugUnitTest` — 87 tests, 0 failures.
- `cd android && ./gradlew :app:assembleRelease` — BUILD SUCCESSFUL; V2 signer cert SHA-256 matches lineage; APK SHA-256 `792443473148df5da425295ffc142a8cc9d69658e53f29ef1fa8fb9cf23f98b8`.
- Manual smoke on Pixel_8 API 35 emulator (debug build with the same extractor change): search → play → past the 1-minute mark → seek → skip. PASS (Matteo, 2026-09-03).
- iOS: extractor suites (33 tests) pass on iPhone 16 / iOS 26.4; iOS build not released.

## v0.1.1 — first hosted update smoke release (2026-05-15)

### What changed since v0.1.0

This is the first build distributed through the self-hosted update channel introduced in YT-0251. Same MVP scope as v0.1.0; gameplay-equivalent. Adds the in-app update prompt wired to `https://mfranceschi6.github.io/your-tube/android/update.json`.

Android-only highlights landed in the 2026-05-14 → 2026-05-15 closure batches:

- **Settings → Appearance → Theme** (YT-0316): System / Light / Dark picker, persisted to DataStore, applied via Compose recomposition (`ComponentActivity` — not `AppCompatActivity`; AMOLED row gated on Dark).
- **Search → recent search history MRU** (YT-0319): persisted recents in DataStore, 20-entry cap, long-press chip removal with `Reject` haptic + `Removed from recent searches` Snackbar + Undo (YT-0325 fixed the chip pointer-absorption regression that surfaced in smoke; YT-0326 dropped the curated `lofi`/`focus`/`ambient` fallback strip so the idle row is recents-only).
- **Recently played → day-grouped LazyColumn** (YT-0321): sticky day headers (`Today` / `Yesterday` / `dd MMM yyyy`), 0.5-threshold swipe-to-remove with `errorContainer` background, long-press `HistoryRowSheet`, relative timestamp slot on every row.
- **Snackbar host hoisted above MiniPlayer** (YT-0328): global `SnackbarHostState` lives in `AppShell`, rendered above `PlayerOverlay` with `+miniPlayerHeight + 8.dp` bottom inset. Action-bearing snackbars now pass explicit `SnackbarDuration.Short` so Undo auto-dismisses (M3 defaults `actionLabel != null` to `Indefinite`).
- **Hosted update channel** (YT-0251): app reads `update.json` on cold-start, surfaces `UpdateAvailableBanner` when `versionCode < feed.versionCode`, and renders a non-dismissible `UpdateRequiredOverlay` when `versionCode < feed.minimumSupportedVersionCode`. Browser handoff is intentional — `REQUEST_INSTALL_PACKAGES` is NOT in the manifest.

### Distribution

- Hosted APK: `https://mfranceschi6.github.io/your-tube/android/yourtube-0.1.1.apk`
- Feed: `https://mfranceschi6.github.io/your-tube/android/update.json`
- Signing keystore: same lineage as v0.1.0 (cert SHA-256 `93a9d3ca29742fb78ea1755b54edf6bd3eb97528faf4629e376a1e207c4eb42d`). Upgrades from v0.1.0 install in-place without uninstall.

### Validation gating

- `cd android && ./gradlew :app:assembleRelease` — BUILD SUCCESSFUL.
- `cd android && ./gradlew testDebugUnitTest` — BUILD SUCCESSFUL (436 tasks, 0 failures).
- Manual 2-APK hosted smoke (YT-0251 four-step procedure): PASS on Pixel_8 API 35 emulator.
  - Install v0.1.0 → cold-start → `UpdateAvailableBanner` shows v0.1.1 ✓
  - Tap Update → browser opens apkUrl → Package Installer upgrade succeeds ✓
  - Post-upgrade Settings → no banner, no error (`UpToDate`) ✓
  - `minimumSupportedVersionCode > installed` → `UpdateRequiredOverlay` non-dismissible (back press intercepted) ✓
- iOS: not part of this release; iOS still on YT-0033 smoke.

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
