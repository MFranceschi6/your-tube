# YourTube

Native audio client for YouTube content. Android (Kotlin · Jetpack Compose) and iOS (Swift · SwiftUI).

<p align="center">
  <img src="design-system/handoff/app-icon/glyph.svg" alt="YourTube app icon" width="128" height="128">
</p>

## What it is

YourTube streams audio from public YouTube videos to its owner's own devices for background listening — search, queue, playlists, lock-screen controls. Two native clients, one shared product surface. No accounts, no telemetry, no third-party trackers.

## Who it's for

Personal use only. YourTube is **not** distributed through the App Store or Google Play, and **no public rollout is planned**. The stream extractor depends on undocumented YouTube internals and can break without warning until patched — treat the app as a hobby project that may stop working until manually updated.

## Install

### Android

Download the signed release APK from the hosted update channel:

- **v0.1.1 (latest):** [yourtube-0.1.1.apk](https://mfranceschi6.github.io/your-tube/android/yourtube-0.1.1.apk)
- v0.1.0: [yourtube-0.1.0.apk](https://mfranceschi6.github.io/your-tube/android/yourtube-0.1.0.apk)

On the device, open the link in a browser, accept the install prompt, and grant "Install unknown apps" for the browser if prompted. Upgrades from a prior YourTube install land in-place — no uninstall required.

The in-app update prompt is wired to the same channel; see [`docs/update-channel.md`](docs/update-channel.md) for the feed schema.

### iOS

Not yet shipped. Smoke validation on a physical device is pending. Build locally from source in the meantime.

## Build from source

Both clients build with their platform's native toolchain. Commands and prerequisites live in the platform directories and the [`docs/`](docs/) folder. The short version:

- Android: `cd android && ./gradlew :app:assembleDebug` (Android Studio Ladybug or newer, JDK 17, Android SDK 35).
- iOS: open `ios/YourTube.xcodeproj` and build the `YourTube` scheme against an iPhone 16 simulator (Xcode 16+).

See [`docs/mvp-validation.md`](docs/mvp-validation.md) for the manual smoke checklist and [`docs/release-notes.md`](docs/release-notes.md) for the per-version changelog.

## Project layout

| Path | Purpose |
| --- | --- |
| [`android/`](android/) | Kotlin / Compose client, Gradle multi-module. |
| [`ios/`](ios/) | SwiftUI client, Xcode project. |
| [`design-system/`](design-system/) | Shared tokens, icon handoff, reference screenshots. |
| [`docs/`](docs/) | Product docs, API contracts, release notes, validation procedures. |

## Status

- Android: v0.1.1 hosted release shipped 2026-05-15. Self-updating via [`update.json`](https://mfranceschi6.github.io/your-tube/android/update.json).
- iOS: MVP feature-complete, smoke pending.

## Links

- Landing: <https://mfranceschi6.github.io/your-tube/>
- Release notes: [`docs/release-notes.md`](docs/release-notes.md)
- Update channel: [`docs/update-channel.md`](docs/update-channel.md)
- API contracts: [`docs/api-contracts.md`](docs/api-contracts.md)

## License

Released under the [MIT License](LICENSE). Forking, modifying, and redistributing are all permitted as long as the copyright notice is preserved. The license does not change the project's "personal use, no store rollout" stance — it just removes legal friction for anyone who wants to learn from or build on the code.
