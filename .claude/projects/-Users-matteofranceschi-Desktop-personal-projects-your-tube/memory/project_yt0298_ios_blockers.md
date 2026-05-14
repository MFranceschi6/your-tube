---
name: YT-0298 iOS mix continuation — open blockers
description: Two blocking CR items from review cycle; iOS work paused to finish Android first
type: project
---

YT-0298 is `in-progress` with two blocking review items. iOS work intentionally paused.

**CR-1** — `tryExtendMixAtTail` not wired
- `isMixContinuationActive` exists on `PlayerCoordinator` + proxied via `AppShellViewModel`, but no production call site uses it to gate autoplay-related.
- Need: add `tryExtendMixAtTail() -> Bool` in `PlayerCoordinator.swift` (mirrors Android `DefaultPlayerController.kt:274-297`), wire into `next()`/track-end path before autoplay-related, add XCTest for "in-flight task at tail → wait → queue extends → related not triggered".

**CR-2** — `continuationFailureRetainsToken` test vacuous
- Located in `ios/YourTubeTests/MixContinuationTests.swift`.
- Current test only asserts `sut.state != .error(...)` — passes trivially, checks nothing about `mixContinuationToken`.
- Replace with two concrete tests: (a) `(items:[], nextToken:nil)` → `#expect(sut.mixContinuationToken == nil)`; (b) `(items:[], nextToken:"tok-retained")` → `#expect(sut.mixContinuationToken == "tok-retained")`.

**Why:** Reuse context for `docs/mix-queue.md §Continuation`, Android ref at `DefaultPlayerController.kt`, and `MixContinuationTests.swift`.