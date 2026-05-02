# Project: Native Mobile App

This repository contains two native clients:

- `android/`: Kotlin, Gradle, Jetpack Compose.
- `ios/`: Swift, SwiftUI, XCTest/XCUITest.

## Default behavior

Before changing code:
1. Inspect the relevant platform directory.
2. Explain the intended change briefly.
3. Prefer small, reviewable diffs.
4. Do not change both Android and iOS unless the task explicitly requires parity.
5. After edits, run the smallest relevant validation command.

## Architecture principles

- Keep platform code idiomatic. Do not force Android patterns into iOS or iOS patterns into Android.
- Shared product behavior should be documented in `docs/`.
- UI should follow the platform design system and accessibility conventions.
- Network/API contracts should be treated as shared product contracts.
- Never hardcode secrets, tokens, API keys, bundle identifiers, signing credentials, or provisioning data.

## Android commands

From `android/`:

- Build debug app: `./gradlew :app:assembleDebug`
- Unit tests: `./gradlew testDebugUnitTest`
- Instrumented tests: `./gradlew connectedDebugAndroidTest`
- Lint: `./gradlew lintDebug`
- Format/check if configured: `./gradlew ktlintCheck detekt`

## iOS commands

From `ios/`:

- List schemes: `xcodebuild -list`
- Build: `xcodebuild -scheme MyApp -destination 'platform=iOS Simulator,name=iPhone 16' build`
- Unit tests: `xcodebuild -scheme MyApp -destination 'platform=iOS Simulator,name=iPhone 16' test`
- Format/check if configured: `swiftformat . --lint` and `swiftlint`

## Testing expectations

- New ViewModels, reducers, repositories, mappers, and business rules need unit tests.
- UI changes need at least accessibility labels/identifiers where useful.
- Bug fixes should include a regression test when practical.

## Git rules

- Never rewrite git history unless explicitly asked.
- Never run destructive commands such as `rm -rf`, `git reset --hard`, or deleting signing files without asking.
- Keep generated files out of commits unless the project already tracks them.