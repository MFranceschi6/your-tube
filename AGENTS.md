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

## Planning vault

- Local Obsidian vault: `obsidian-vault/`.
- The vault is intentionally gitignored and must not be committed.
- Tracked planning conventions live in `docs/obsidian-planning.md`.
- Use the `obsidian-project-management` skill when creating, triaging, reviewing, or maintaining Obsidian tasks, Bases, dashboards, or task templates.
- Do not create or update task notes unless the user explicitly asks for task preparation, triage, review, or maintenance.
- Shared product behavior discovered during planning still belongs in tracked `docs/`, not only in the local vault.
- When a request names an Obsidian task ID or clearly asks an agent to work from the vault, open that task note first and route the working personality from `platform`, `area`, `agent_profile`, and `review_profile` as defined in `docs/obsidian-planning.md`.
- When an agent starts work on a task note, confirm every `depends_on` task is `done`, update the note to `status: in-progress`, set or confirm `agent_profile` and `review_profile`, and refresh `updated`. When implementation is ready, move it to `status: review`; reviewers move it to `done`, `in-progress`, or `blocked` based on the outcome.
- Use `depends_on` for hard task prerequisites. A task with incomplete dependencies should normally be `status: blocked`; `links` are only contextual.
- Current task notes should carry MVP roadmap metadata: `milestone: MVP`, `epic`, `phase`, `blocked_reason`, `validation_command`, and minimal tags `task` and `mvp`.
- If a task is too large for one focused implementation and review, split it before implementation using `parent_id`, `child_tasks`, and `split_reason`; keep each child independently validatable.
- Prefer platform parity waves over task-by-task ping-pong: finish a small iOS checkpoint first, then use the corresponding Android tasks as the parity pass unless the user explicitly asks for simultaneous cross-platform work. iOS leads because design mockups land there first.
- Default implementation profiles are `android-engineer` for `platform: android`, `ios-engineer` for `platform: ios`, `shared-contract-engineer` for `platform: shared`, `docs-maintainer` for `platform: docs`, and `build-ops-engineer` for `platform: ops`. Default code review uses `mobile-reviewer`; planning review uses `obsidian-task-reviewer`.

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
