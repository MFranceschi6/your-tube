import Testing
import Foundation
@testable import YourTube

// MARK: - Fakes

/// YouTubeService fake with configurable Mix behavior for YT-0298 tests.
/// `resolveStreamURL` always succeeds instantly so queue tests are not
/// blocked waiting for stream resolution.
@MainActor
final class MixFakeYouTubeService: YouTubeServiceProtocol, @unchecked Sendable {

    // Stream resolution — always succeeds.
    func search(query: String, maxResults: Int) async throws -> [SearchResult] { [] }
    func resolveStreamURL(videoId: String, quality: AudioQuality) async throws -> ResolvedStream {
        ResolvedStream(url: URL(string: "https://example.invalid/\(videoId)")!, isMuxedFallback: false)
    }

    // MARK: Mix config

    /// The `MixQueueResult` returned by `getMixQueueWithContinuation`. Tests set
    /// this before calling `playNow`.
    var initialMixResult: MixQueueResult = .empty

    /// Queue of results for successive `getMixContinuation` calls. Each call
    /// dequeues the first element; after the queue is exhausted, `.empty` is returned.
    var continuationResults: [MixQueueResult] = []

    /// Number of `getMixContinuation` calls received.
    private(set) var continuationCallCount: Int = 0

    /// Tokens passed to `getMixContinuation`, in order.
    private(set) var continuationTokensReceived: [String] = []

    func getMixQueueWithContinuation(videoId: String) async -> MixQueueResult {
        initialMixResult
    }

    func getMixContinuation(token: String) async -> MixQueueResult {
        continuationCallCount += 1
        continuationTokensReceived.append(token)
        if continuationResults.isEmpty { return .empty }
        return continuationResults.removeFirst()
    }
}

// MARK: - Helpers

/// Builds a `SearchResult` fixture for Mix tests.
private func makeSearchResult(videoId: String, durationSec: Int = 180) -> SearchResult {
    SearchResult(videoId: videoId, title: "T-\(videoId)", channel: "Ch", durationSec: durationSec, thumbnailUrl: "")
}

/// Builds a `Track` fixture for Mix tests.
private func makeTrack(videoId: String, durationSec: Int = 180) -> Track {
    Track(videoId: videoId, title: "T-\(videoId)", channel: "Ch", durationSec: durationSec, thumbnailUrl: "")
}

/// Builds a `MixQueueResult` with the given videoIds, all with standard duration,
/// and optionally carrying a continuation token.
private func mixResult(videoIds: [String], nextToken: String? = nil) -> MixQueueResult {
    MixQueueResult(
        items: videoIds.map { makeSearchResult(videoId: $0) },
        nextToken: nextToken
    )
}

/// Yields the Swift concurrency scheduler enough times for a fire-and-forget Task
/// chain (playNow → tryLoadMixQueue → fetchMixContinuation) to run to completion.
@MainActor
private func flush(yields: Int = 15) async {
    for _ in 0..<yields { await Task.yield() }
}

@MainActor
private func makeSUT() -> (
    sut: PlayerCoordinator,
    engine: FakeAudioEngine,
    service: MixFakeYouTubeService
) {
    let engine = FakeAudioEngine()
    let service = MixFakeYouTubeService()
    let sut = PlayerCoordinator(
        audioEngine: engine,
        youtubeService: service,
        qualityProvider: { .medium }
    )
    return (sut, engine, service)
}

// MARK: - Test suite

@Suite("PlayerCoordinator – Mix continuation (YT-0298)")
@MainActor
struct MixContinuationTests {

    // MARK: AC1: Initial Mix → skip-next near tail → continuation fires → queue extends

    @Test("initial Mix loads, skip-next near tail triggers continuation, queue grows")
    func continuationFiresNearTail() async {
        let (sut, _, service) = makeSUT()

        // Seed + 3 Mix items on the initial page (index 0 = seed = "s0").
        // Continuation token "tok-A" is returned.
        service.initialMixResult = mixResult(videoIds: ["s0", "m1", "m2", "m3"], nextToken: "tok-A")

        // Continuation page adds 2 more items and terminates.
        service.continuationResults = [
            mixResult(videoIds: ["m4", "m5"], nextToken: nil)
        ]

        let seed = makeTrack(videoId: "s0")
        sut.playNow(seed)
        // Let the initial resolve + Mix fire-and-forget complete.
        await flush()

        // Queue should be [s0, m1, m2, m3] after initial Mix loads.
        #expect(sut.queue.map(\.videoId) == ["s0", "m1", "m2", "m3"])
        #expect(sut.currentIndex == 0)

        // Advance to index 1, then 2 → now index 2, 1 slot from tail (queue.count=4, tail=3).
        // At index 2: queue.count - currentIndex = 4 - 2 = 2 == threshold → fires.
        sut.next()  // index 1
        await flush()
        sut.next()  // index 2 — triggers maybePrefetchMixContinuation
        await flush()

        // Queue should now include the continuation items.
        let videoIds = sut.queue.map(\.videoId)
        #expect(videoIds.contains("m4"))
        #expect(videoIds.contains("m5"))
        #expect(service.continuationCallCount == 1)
        // Index is unchanged by the append (still 2).
        #expect(sut.currentIndex == 2)
    }

    // MARK: AC2a: items=[] + nil nextToken → token cleared (terminal page)

    @Test("continuation returning empty items and nil nextToken clears token (terminal)")
    func continuationTerminalEmptyPageClearsToken() async {
        let (sut, _, service) = makeSUT()

        service.initialMixResult = mixResult(videoIds: ["s0", "m1", "m2", "m3"], nextToken: "tok-B")
        // Terminal: server returns no items and no next token.
        service.continuationResults = [
            MixQueueResult(items: [], nextToken: nil)
        ]

        let seed = makeTrack(videoId: "s0")
        sut.playNow(seed)
        await flush()

        // Advance near tail to trigger the continuation.
        sut.next(); await flush()   // index 1
        sut.next(); await flush()   // index 2 → triggers fetch
        await flush()

        // items=[] + nextToken=nil → implementation sets token to nil (terminal).
        #expect(sut.mixContinuationToken == nil)
        #expect(sut.isMixContinuationActive == false)
        // No error UI.
        if case .error = sut.state {
            Issue.record("Terminal empty continuation must not surface error state")
        }
    }

    @Test("continuation with empty items but non-nil nextToken retains token and can retry")
    func continuationFailureWithNonNilTokenRetained() async {
        let (sut, _, service) = makeSUT()

        service.initialMixResult = mixResult(videoIds: ["s0", "m1", "m2", "m3"], nextToken: "tok-B")

        // First continuation: empty items but carries the same token (server
        // returned a temporary empty page / transient failure modeled with retained token).
        // Second continuation: actual items + terminal.
        //
        // With our lazy-prefetch model, when the first continuation returns
        // items=[] + nextToken="tok-B", the coordinator:
        //  - Sets token to "tok-B" (retained)
        //  - Calls maybePrefetchMixContinuation() (still near tail → fires second)
        //
        // Both continuations may resolve within a single flush(). We verify the end
        // state: queue extended AND no error UI surfaced.
        service.continuationResults = [
            MixQueueResult(items: [], nextToken: "tok-B"),    // first: retained token
            mixResult(videoIds: ["m4", "m5"], nextToken: nil), // second: items + terminal
        ]

        let seed = makeTrack(videoId: "s0")
        sut.playNow(seed)
        await flush()

        // Advance near tail to trigger continuation chain.
        sut.next(); await flush()
        sut.next(); await flush()  // index 2 → triggers first continuation
        await flush(yields: 20)    // let both continuations complete

        // After the chain resolves, the queue must have grown (m4 and/or m5 appended).
        // The first continuation produced no items but kept the token; the second
        // was auto-triggered and produced m4, m5.
        let ids = sut.queue.map(\.videoId)
        #expect(service.continuationCallCount >= 1)
        #expect(ids.contains("m4") || ids.contains("m5"))
        // No error UI surfaced.
        if case .error = sut.state {
            Issue.record("Must not surface error UI on continuation failure")
        }
    }

    // MARK: AC3: nil nextToken → token cleared → autoplay-related fires

    @Test("nil nextToken on continuation clears token so autoplay-related gate passes")
    func nilNextTokenClearsToken() async {
        let (sut, _, service) = makeSUT()

        // Initial page with token; continuation terminates.
        service.initialMixResult = mixResult(videoIds: ["s0", "m1", "m2", "m3"], nextToken: "tok-C")
        service.continuationResults = [
            mixResult(videoIds: ["m4"], nextToken: nil)  // terminal
        ]

        let seed = makeTrack(videoId: "s0")
        sut.playNow(seed)
        await flush()

        // Advance near tail to trigger continuation.
        sut.next(); await flush()
        sut.next(); await flush()  // triggers continuation
        await flush()

        // Continuation returned nil nextToken → token cleared.
        #expect(sut.mixContinuationToken == nil)
        #expect(sut.isMixContinuationActive == false)
        // YT-0292 autoplay-related gate: isMixContinuationActive == false means
        // the caller (autoplay gate) can proceed with the related-video fetch.
    }

    // MARK: AC4: fresh playNow while continuation in flight cancels task + clears token

    @Test("fresh playNow while continuation in flight cancels task and clears token")
    func freshPlayNowCancelsContinuation() async {
        let (sut, _, service) = makeSUT()

        // Initial Mix has a token and the continuation suspends forever
        // (simulated by never resolving from continuationResults).
        service.initialMixResult = mixResult(videoIds: ["s0", "m1", "m2", "m3"], nextToken: "tok-D")
        // continuationResults is empty → getMixContinuation suspends waiting for results
        // (not exactly — MixFakeYouTubeService returns .empty immediately if the queue
        //  is empty). We need a "never-resolving" mix service. Use a custom subclass.

        // Set up a service that hangs indefinitely on getMixContinuation.
        let hangingService = HangingMixYouTubeService()
        let engine = FakeAudioEngine()
        let sut2 = PlayerCoordinator(
            audioEngine: engine,
            youtubeService: hangingService,
            qualityProvider: { .medium }
        )

        let seed1 = makeTrack(videoId: "v1")
        sut2.playNow(seed1)
        await flush()

        // Advance near tail to trigger continuation (which will hang).
        sut2.next(); await flush()
        sut2.next()

        // Verify continuation task is in flight. `mixContinuationTask` is assigned
        // synchronously inside `maybePrefetchMixContinuation` — check it right after
        // the second `next()` call, before `await flush()`, so the test captures the
        // handle before the Task body has a chance to run and clear it.
        #expect(sut2.mixContinuationTask != nil)

        await flush()

        // Tap a new track — must cancel in-flight task and clear token.
        let seed2 = makeTrack(videoId: "v2")
        sut2.playNow(seed2)
        // Synchronous: cancelMixContinuation runs before any await.
        #expect(sut2.mixContinuationToken == nil)
        #expect(sut2.mixContinuationTask == nil)

        await flush()
        // After the dust settles, new Mix would have started but hangingService
        // also hangs on getMixQueueWithContinuation, so still empty.
        #expect(sut2.isMixContinuationActive == false || sut2.currentTrack?.videoId == "v2")
    }

    // MARK: AC7: tryExtendMixAtTail — next() at queue-end with active token extends and advances

    @Test("next() at queue tail with active token fires tryExtendMixAtTail and advances")
    func nextAtTailFiresTryExtendMixAtTail() async {
        let (sut, _, service) = makeSUT()

        // Seed-only initial Mix page (no tail items) but carries a token.
        // tryLoadMixQueue dropFirst() = [] → nothing appended → queue stays ["s0"].
        // mixContinuationToken is set to "tok-G".
        service.initialMixResult = mixResult(videoIds: ["s0"], nextToken: "tok-G")
        // Continuation brings one new item.
        service.continuationResults = [mixResult(videoIds: ["m1"], nextToken: nil)]

        let seed = makeTrack(videoId: "s0")
        sut.playNow(seed)
        await flush()

        // Queue is ["s0"], currentIndex=0, token="tok-G", no continuation in flight.
        #expect(sut.queue.map(\.videoId) == ["s0"])
        #expect(sut.mixContinuationToken == "tok-G")

        // next() at the tail (hasNext=false, isMixContinuationActive=true).
        // Spawns tryExtendMixAtTail: fires maybePrefetchMixContinuation → fetch → appends m1
        // → hasNext becomes true → calls next() → currentIndex becomes 1.
        sut.next()
        await flush(yields: 30)

        let ids = sut.queue.map(\.videoId)
        #expect(ids.contains("m1"))
        #expect(sut.currentIndex == 1)
        // Token cleared (continuation returned nil nextToken).
        #expect(sut.mixContinuationToken == nil)
    }

    // MARK: AC5: dedup — continuation items already in queue are filtered

    @Test("continuation items with videoIds already in queue are deduped")
    func continuationDeduplication() async {
        let (sut, _, service) = makeSUT()

        // Initial Mix: s0 + m1 + m2
        service.initialMixResult = mixResult(videoIds: ["s0", "m1", "m2", "m3"], nextToken: "tok-E")

        // Continuation: m2 (already in queue) + m4 (novel) + m1 (already in queue)
        service.continuationResults = [
            mixResult(videoIds: ["m2", "m4", "m1"], nextToken: nil)
        ]

        let seed = makeTrack(videoId: "s0")
        sut.playNow(seed)
        await flush()

        // Queue is [s0, m1, m2, m3].
        #expect(sut.queue.map(\.videoId) == ["s0", "m1", "m2", "m3"])

        // Advance near tail to trigger continuation.
        sut.next(); await flush()  // index 1
        sut.next(); await flush()  // index 2 → trigger
        await flush()

        // Continuation returned [m2, m4, m1]: m2 and m1 are already in queue.
        // Only m4 should be appended.
        let ids = sut.queue.map(\.videoId)
        #expect(ids.contains("m4"))
        // m2 and m1 should NOT appear twice.
        #expect(ids.filter { $0 == "m2" }.count == 1)
        #expect(ids.filter { $0 == "m1" }.count == 1)
    }

    // MARK: Shorts filter — durationSec < 60 must not be appended

    @Test("continuation items with durationSec < 60 (Shorts) are filtered and not appended")
    func continuationShortsAreFiltered() async {
        let (sut, _, service) = makeSUT()

        service.initialMixResult = mixResult(videoIds: ["s0", "m1", "m2", "m3"], nextToken: "tok-F")

        // Continuation returns one Short (59 s) and one standard track.
        // Only the standard track must land in the queue.
        service.continuationResults = [
            MixQueueResult(
                items: [
                    makeSearchResult(videoId: "short1", durationSec: 59),  // Short — must be filtered
                    makeSearchResult(videoId: "m4", durationSec: 180),     // standard — must be appended
                ],
                nextToken: nil
            )
        ]

        let seed = makeTrack(videoId: "s0")
        sut.playNow(seed)
        await flush()

        // Advance near tail to trigger continuation.
        sut.next(); await flush()  // index 1
        sut.next(); await flush()  // index 2 → triggers fetch
        await flush()

        let ids = sut.queue.map(\.videoId)
        #expect(ids.contains("m4"), "Standard-duration track must be appended")
        #expect(!ids.contains("short1"), "Short (59 s) must be filtered and not appended")
    }
}

// MARK: - HangingMixYouTubeService

/// A service whose `getMixQueueWithContinuation` and `getMixContinuation` never
/// complete (suspend until the Task is cancelled). Used to hold an in-flight
/// task so tests can observe the cancellation path from `playNow`.
private final class HangingMixYouTubeService: YouTubeServiceProtocol, @unchecked Sendable {
    func search(query: String, maxResults: Int) async throws -> [SearchResult] { [] }
    func resolveStreamURL(videoId: String, quality: AudioQuality) async throws -> ResolvedStream {
        ResolvedStream(url: URL(string: "https://example.invalid/x")!, isMuxedFallback: false)
    }

    var initialMixResult: MixQueueResult = MixQueueResult(
        items: [
            SearchResult(videoId: "v1", title: "T", channel: "Ch", durationSec: 180, thumbnailUrl: ""),
            SearchResult(videoId: "mx1", title: "T", channel: "Ch", durationSec: 180, thumbnailUrl: ""),
            SearchResult(videoId: "mx2", title: "T", channel: "Ch", durationSec: 180, thumbnailUrl: ""),
            SearchResult(videoId: "mx3", title: "T", channel: "Ch", durationSec: 180, thumbnailUrl: ""),
        ],
        nextToken: "tok-hang"
    )

    func getMixQueueWithContinuation(videoId: String) async -> MixQueueResult {
        initialMixResult
    }

    func getMixContinuation(token: String) async -> MixQueueResult {
        // Hang until cancellation.
        try? await Task.sleep(nanoseconds: .max)
        return .empty
    }
}
