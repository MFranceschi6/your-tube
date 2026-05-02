---
paths:
  - "android/**/*.kt"
  - "android/**/*.kts"
  - "android/**/*.xml"
  - "android/**/AndroidManifest.xml"
---

# Android rules

Use idiomatic Kotlin and modern Android conventions.

## Stack

- Kotlin
- Gradle Kotlin DSL
- Jetpack Compose for UI
- Coroutines and Flow for async/reactive work
- ViewModel for screen state
- Hilt or the project’s existing DI system
- Room/DataStore only if already used or explicitly requested

## Architecture principles

- Offline-first: local database is the source of truth; sync with remote.
- Unidirectional data flow: events flow down, data flows up.
- Reactive streams: expose data as Kotlin `Flow`.
- Testable by design: depend on interfaces; prefer fakes/test doubles over mocking libraries.

## Module layout

Prefer this direction unless the existing app differs:

- `app`: application wiring and navigation
- `feature/<name>/api`: public navigation keys/contracts
- `feature/<name>/impl`: Screen, ViewModel, DI (internal)
- `core/data`: repositories
- `core/database`: Room DAOs, entities
- `core/network`: Retrofit, API models
- `core/model`: pure-Kotlin domain models
- `core/common`: shared utilities
- `core/ui`: reusable Compose components
- `core/designsystem`: theme, icons, base components
- `core/datastore`: preferences storage
- `core/testing`: test utilities

Do not introduce a new architecture pattern without explaining the migration cost.

## Build configuration

- Use a Gradle version catalog (`libs.versions.toml`). Do not hardcode versions in module build files.
- Centralize repeated module setup with convention plugins in `build-logic/` (e.g. `AndroidApplication`, `AndroidLibrary`, `AndroidFeature`, `AndroidCompose`, `AndroidHilt`).

## Compose

- Stateless composables where practical.
- State hoisted to ViewModel or parent composable.
- Split each screen into a `XxxRoute` (collects state, wires nav) and a stateless `XxxScreen` (takes `uiState` + `onAction`).
- Use previews for reusable components.
- Add `contentDescription` or mark decorative images as null.
- Avoid business logic inside composables.

## Standard patterns

- **UiState**: sealed interface with `Loading` / `Success(...)` / `Error(...)` data classes/objects.
- **ViewModel**: expose `StateFlow<UiState>` built with `stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Loading)`. Handle input via a single `onAction(action: XxxAction)` entry point.
- **Repository**: define an `interface` in `core/data`; implement as `OfflineFirstXxxRepository` taking a local DAO and a network API; map between entity ↔ domain model with `toModel()` / `toEntity()` extensions.

## Testing

- ViewModel tests should use coroutine test dispatchers.
- Repository tests should fake data sources (prefer hand-written fakes over mocking libraries; reach for mocks only when a fake is impractical).
- Compose UI tests should use stable semantics/test tags only where needed.

## Validation

After Android changes, prefer the smallest relevant command:

- Kotlin logic: `cd android && ./gradlew testDebugUnitTest`
- UI/resource/build changes: `cd android && ./gradlew :app:assembleDebug`
- Broad changes: `cd android && ./gradlew lintDebug testDebugUnitTest`