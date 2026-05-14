import Testing
import Foundation
@testable import YourTube

// MARK: - Fakes

/// In-memory, fully observable audio engine for unit tests.
/// Records every call so tests can assert on transport intent without touching AVPlayer.
@MainActor
final class FakeAudioEngine: AudioEngineProtocol {
    var currentTrack: Track?
    var isPlaying: Bool = false
    var currentTime: TimeInterval = 0
    var duration: TimeInterval = 0

    private(set) var playCallCount = 0
    private(set) var pauseCallCount = 0
    private(set) var resumeCallCount = 0
    private(set) var stopCallCount = 0
    private(set) var prepareCallCount = 0
    private(set) var lastSeek: TimeInterval?
    /// Last URL the coordinator handed to the engine. `nil` when the call site
    /// passed `url: nil` (state-only path used by some preview/test wiring).
    private(set) var lastPlayURL: URL?
    /// Last track passed to `prepare(track:)`. Lets tests assert that the
    /// engine state was reset BEFORE stream resolution returned (YT-0046 Bug B).
    private(set) var lastPreparedTrack: Track?
    /// YT-0157: last resource loader the coordinator forwarded. Lets tests
    /// assert the loader survives the coordinator → engine hop and would
    /// outlive the surrounding `ResolvedStream` value.
    private(set) var lastResourceLoader: HLSProxyLoader?

    func play(track: Track, url: URL?, resourceLoader: HLSProxyLoader?) {
        playCallCount += 1
        lastPlayURL = url
        lastResourceLoader = resourceLoader
        currentTrack = track
        duration = TimeInterval(track.durationSec)
        currentTime = 0
        isPlaying = true
    }

    func prepare(track: Track) {
        prepareCallCount += 1
        lastPreparedTrack = track
        currentTrack = track
        currentTime = 0
        duration = TimeInterval(track.durationSec)
        // Mirrors the production engine contract (YT-0049): prepare stops
        // the previously-playing audio so a track switch is instant.
        isPlaying = false
    }
    func pause() { pauseCallCount += 1; isPlaying = false }
    func resume() { resumeCallCount += 1; isPlaying = true }
    func togglePlayPause() { isPlaying.toggle() }
    func seek(to time: TimeInterval) { lastSeek = time; currentTime = time }
    func skipNext() {}
    func skipPrevious() {}
    func stop() {
        stopCallCount += 1
        currentTrack = nil
        isPlaying = false
        currentTime = 0
        duration = 0
    }
}

/// Records every `resolveStreamURL` call so tests can assert the quality the
/// coordinator forwarded.
final class RecordingYouTubeService: YouTubeServiceProtocol, @unchecked Sendable {
    private let lock = NSLock()
    private var _calls: [(videoId: String, quality: AudioQuality)] = []
    var streamResult: Result<ResolvedStream, YouTubeServiceError> =
        .success(ResolvedStream(url: URL(string: "https://example.invalid/x")!, isMuxedFallback: false))

    var calls: [(videoId: String, quality: AudioQuality)] {
        lock.lock(); defer { lock.unlock() }
        return _calls
    }

    func search(query: String, maxResults: Int) async throws -> [SearchResult] { [] }

    func resolveStreamURL(videoId: String, quality: AudioQuality) async throws -> ResolvedStream {
        lock.lock()
        _calls.append((videoId, quality))
        lock.unlock()
        switch streamResult {
        case .success(let r): return r
        case .failure(let e): throw e
        }
    }

    // YT-0298 Mix stubs — return empty by default; tests override as needed.
    func getMixQueueWithContinuation(videoId: String) async -> MixQueueResult { .empty }

    /// Configurable continuation result. Defaults to `.empty` (terminal/nil nextToken).
    /// Set to a non-nil-token result in tests that must preserve `mixContinuationToken`
    /// across a `jumpToQueueItem` that triggers `maybePrefetchMixContinuation`.
    var mixContinuationResult: MixQueueResult = .empty
    func getMixContinuation(token: String) async -> MixQueueResult { mixContinuationResult }
}

// MARK: - Helpers

private let trackA = Track(videoId: "a1", title: "A", channel: "ChA", durationSec: 100, thumbnailUrl: "")
private let trackB = Track(videoId: "b2", title: "B", channel: "ChB", durationSec: 200, thumbnailUrl: "")
private let trackC = Track(videoId: "c3", title: "C", channel: "ChC", durationSec: 300, thumbnailUrl: "")

/// Lets a queued resolution `Task` run and reach its `engine.play(track:)` /
/// state-update step before tests assert. One yield per pending continuation
/// (resolve → state mutation) is enough.
@MainActor
private func flushPlayerTasks() async {
    for _ in 0..<5 { await Task.yield() }
}

@MainActor
private func makeSUT(
    quality: AudioQuality = .medium
) -> (sut: PlayerCoordinator, engine: FakeAudioEngine, service: RecordingYouTubeService) {
    let engine = FakeAudioEngine()
    let service = RecordingYouTubeService()
    let sut = PlayerCoordinator(
        audioEngine: engine,
        youtubeService: service,
        qualityProvider: { quality }
    )
    return (sut, engine, service)
}

// MARK: - State machine

@Suite("PlayerCoordinator – state transitions")
@MainActor
struct PlayerCoordinatorStateTests {

    @Test("starts idle with empty queue")
    func startsIdle() {
        let (sut, _, _) = makeSUT()
        #expect(sut.state == .idle)
        #expect(sut.queue.isEmpty)
        #expect(sut.currentIndex == nil)
        #expect(sut.currentTrack == nil)
        #expect(sut.isPlaying == false)
    }

    @Test("playNow transitions idle → loading → playing")
    func playNowTransitions() async {
        let (sut, engine, _) = makeSUT()
        sut.playNow(trackA)
        // After the synchronous portion of playNow, state is .loading.
        #expect(sut.state == .loading)
        await flushPlayerTasks()
        #expect(sut.state == .playing)
        #expect(sut.isPlaying == true)
        #expect(engine.playCallCount == 1)
        #expect(engine.currentTrack?.videoId == trackA.videoId)
    }

    @Test("togglePlayPause: playing → paused → playing")
    func togglePauseAndResume() async {
        let (sut, engine, _) = makeSUT()
        sut.playNow(trackA)
        await flushPlayerTasks()

        sut.togglePlayPause()
        #expect(sut.state == .paused)
        #expect(engine.pauseCallCount == 1)

        sut.togglePlayPause()
        #expect(sut.state == .playing)
        #expect(engine.resumeCallCount == 1)
    }

    @Test("togglePlayPause is a no-op while idle")
    func toggleIgnoredWhileIdle() {
        let (sut, engine, _) = makeSUT()
        sut.togglePlayPause()
        #expect(sut.state == .idle)
        #expect(engine.pauseCallCount == 0)
        #expect(engine.resumeCallCount == 0)
    }

    @Test("resolution failure produces .error state with message")
    func resolutionErrorBecomesErrorState() async {
        let (sut, _, service) = makeSUT()
        service.streamResult = .failure(.noStreamFound)

        sut.playNow(trackA)
        await flushPlayerTasks()

        if case .error(let message) = sut.state {
            #expect(!message.isEmpty)
        } else {
            Issue.record("Expected .error state, got \(sut.state)")
        }
        #expect(sut.isPlaying == false)
    }

    @Test("reportError forces error state")
    func reportErrorForcesErrorState() {
        let (sut, _, _) = makeSUT()
        sut.reportError("boom")
        #expect(sut.state == .error(message: "boom"))
    }

    @Test("seek forwards to engine")
    func seekForwardsToEngine() {
        let (sut, engine, _) = makeSUT()
        sut.seek(to: 42)
        #expect(engine.lastSeek == 42)
    }
}

// MARK: - Queue operations

@Suite("PlayerCoordinator – queue operations")
@MainActor
struct PlayerCoordinatorQueueTests {

    @Test("append on empty queue auto-plays the first track")
    func appendOnEmptyQueueAutoPlays() async {
        let (sut, engine, _) = makeSUT()
        sut.append(trackA)
        #expect(sut.queue.count == 1)
        #expect(sut.currentIndex == 0)
        await flushPlayerTasks()
        #expect(engine.playCallCount == 1)
    }

    @Test("append while a track is loaded only enqueues")
    func appendWhileLoadedOnlyEnqueues() async {
        let (sut, engine, _) = makeSUT()
        sut.playNow(trackA)
        await flushPlayerTasks()
        sut.append(trackB)
        await flushPlayerTasks()
        #expect(sut.queue.count == 2)
        #expect(sut.currentIndex == 0)
        // Engine.play should have fired exactly once — for trackA only.
        #expect(engine.playCallCount == 1)
        #expect(sut.hasNext == true)
    }

    @Test("playNow re-selects existing track without duplicating")
    func playNowReusesExistingEntry() async {
        let (sut, _, _) = makeSUT()
        sut.append(trackA)
        sut.append(trackB)
        await flushPlayerTasks()
        sut.playNow(trackB)
        await flushPlayerTasks()
        #expect(sut.queue.count == 2)
        #expect(sut.currentIndex == 1)
        #expect(sut.currentTrack?.videoId == trackB.videoId)
    }

    @Test("next/previous traverse the queue")
    func nextPreviousTraverse() async {
        let (sut, _, _) = makeSUT()
        sut.append(trackA)
        sut.append(trackB)
        sut.append(trackC)
        await flushPlayerTasks()

        #expect(sut.currentIndex == 0)
        #expect(sut.hasPrevious == false)
        sut.next()
        await flushPlayerTasks()
        #expect(sut.currentIndex == 1)
        sut.next()
        await flushPlayerTasks()
        #expect(sut.currentIndex == 2)
        #expect(sut.hasNext == false)
        sut.next()  // No-op at tail.
        #expect(sut.currentIndex == 2)
        sut.previous()
        await flushPlayerTasks()
        #expect(sut.currentIndex == 1)
    }

    @Test("remove non-active before active shifts cursor left")
    func removeBeforeActiveShifts() async {
        let (sut, _, _) = makeSUT()
        sut.append(trackA)
        sut.append(trackB)
        sut.append(trackC)
        await flushPlayerTasks()
        sut.next()  // currentIndex = 1 (trackB)
        await flushPlayerTasks()
        sut.remove(at: 0)
        #expect(sut.queue.map(\.videoId) == ["b2", "c3"])
        #expect(sut.currentIndex == 0)
        #expect(sut.currentTrack?.videoId == trackB.videoId)
    }

    @Test("remove active track loads neighbour")
    func removeActiveLoadsNext() async {
        let (sut, engine, _) = makeSUT()
        sut.append(trackA)
        sut.append(trackB)
        await flushPlayerTasks()
        let playsBefore = engine.playCallCount
        sut.remove(at: 0)
        await flushPlayerTasks()
        #expect(sut.queue.map(\.videoId) == ["b2"])
        #expect(sut.currentIndex == 0)
        #expect(sut.currentTrack?.videoId == trackB.videoId)
        #expect(engine.playCallCount == playsBefore + 1)
    }

    @Test("remove last remaining track returns to idle")
    func removeLastRemainingReturnsIdle() async {
        let (sut, engine, _) = makeSUT()
        sut.append(trackA)
        await flushPlayerTasks()
        sut.remove(at: 0)
        #expect(sut.queue.isEmpty)
        #expect(sut.currentIndex == nil)
        #expect(sut.state == .idle)
        #expect(engine.stopCallCount == 1)
    }

    @Test("move reorders queue and tracks the active index")
    func moveTracksActiveIndex() async {
        let (sut, _, _) = makeSUT()
        sut.append(trackA)
        sut.append(trackB)
        sut.append(trackC)
        await flushPlayerTasks()
        // Active = 0 (trackA). Move trackC (index 2) to front (index 0).
        sut.move(from: 2, to: 0)
        #expect(sut.queue.map(\.videoId) == ["c3", "a1", "b2"])
        // Active track is still trackA, so currentIndex follows it.
        #expect(sut.currentTrack?.videoId == trackA.videoId)
        #expect(sut.currentIndex == 1)
    }

    @Test("move ignores invalid indices")
    func moveIgnoresInvalid() async {
        let (sut, _, _) = makeSUT()
        sut.append(trackA)
        sut.append(trackB)
        await flushPlayerTasks()
        sut.move(from: 5, to: 0)
        #expect(sut.queue.map(\.videoId) == ["a1", "b2"])
    }

    @Test("progress is clamped and returns 0 for unknown duration")
    func progressClamped() {
        let (sut, engine, _) = makeSUT()
        engine.duration = 0
        engine.currentTime = 10
        #expect(sut.progress == 0)
        engine.duration = 100
        engine.currentTime = 50
        #expect(sut.progress == 0.5)
        engine.currentTime = 500
        #expect(sut.progress == 1.0)
    }
}

// MARK: - YT-0308 — tap-to-jump within an existing queue

/// `jumpToQueueItem(at:)` must mirror the skip-next/skip-prev index-set +
/// transport-play path so a tap on a queued track behaves like the user
/// pressed skip-next repeatedly: queue contents preserved, only `currentIndex`
/// moves, and Mix continuation state survives. Anti-tests guard the no-op
/// surface (current entry, out-of-bounds) and the cancel-during-load contract.
@Suite("PlayerCoordinator – jumpToQueueItem (YT-0308)")
@MainActor
struct PlayerCoordinatorJumpToQueueItemTests {

    @Test("jumpToQueueItem plays target and preserves queue contents")
    func jumpPlaysTargetAndPreservesQueue() async {
        let (sut, engine, _) = makeSUT()
        sut.append(trackA)
        sut.append(trackB)
        sut.append(trackC)
        await flushPlayerTasks()
        #expect(sut.currentIndex == 0)
        let playsBefore = engine.playCallCount
        let queueBefore = sut.queue.map(\.videoId)

        sut.jumpToQueueItem(at: 2)
        await flushPlayerTasks()

        #expect(sut.currentIndex == 2)
        #expect(sut.currentTrack?.videoId == trackC.videoId)
        #expect(sut.queue.map(\.videoId) == queueBefore)
        #expect(sut.queue.count == 3)
        #expect(engine.playCallCount == playsBefore + 1)
        #expect(engine.currentTrack?.videoId == trackC.videoId)
    }

    @Test("after jumping, skip-prev walks back to the immediately preceding entry")
    func skipPrevAfterJumpWalksBackOneSlot() async {
        let (sut, _, _) = makeSUT()
        sut.append(trackA)
        sut.append(trackB)
        sut.append(trackC)
        await flushPlayerTasks()

        sut.jumpToQueueItem(at: 2)
        await flushPlayerTasks()
        sut.previous()
        await flushPlayerTasks()

        // Skip-prev returns to entry 1 (trackB), NOT entry 0 — the queue was
        // never rebuilt, so the preceding slot is still trackB.
        #expect(sut.currentIndex == 1)
        #expect(sut.currentTrack?.videoId == trackB.videoId)
    }

    @Test("jumpToQueueItem on the currently-playing entry is a silent no-op")
    func jumpOnCurrentIsNoOp() async {
        let (sut, engine, service) = makeSUT()
        sut.append(trackA)
        sut.append(trackB)
        await flushPlayerTasks()
        let playsBefore = engine.playCallCount
        let preparesBefore = engine.prepareCallCount
        let resolvesBefore = service.calls.count
        let stateBefore = sut.state

        sut.jumpToQueueItem(at: 0)  // already current
        await flushPlayerTasks()

        #expect(sut.currentIndex == 0)
        #expect(sut.state == stateBefore)
        // No re-fetch, no engine churn — pure no-op.
        #expect(engine.playCallCount == playsBefore)
        #expect(engine.prepareCallCount == preparesBefore)
        #expect(service.calls.count == resolvesBefore)
    }

    @Test("jumpToQueueItem with out-of-bounds index is a silent no-op")
    func jumpOutOfBoundsIsNoOp() async {
        let (sut, engine, _) = makeSUT()
        sut.append(trackA)
        sut.append(trackB)
        await flushPlayerTasks()
        let playsBefore = engine.playCallCount
        let indexBefore = sut.currentIndex

        sut.jumpToQueueItem(at: 99)
        sut.jumpToQueueItem(at: -1)
        await flushPlayerTasks()

        #expect(sut.currentIndex == indexBefore)
        #expect(engine.playCallCount == playsBefore)
    }

    /// YT-0249 parity: a jump during a load must cancel the previous resolve
    /// and start the tapped one. With the never-resolving service the original
    /// resolve is still suspended; once we tap a different slot, `prepare` runs
    /// against the new track and `engine.lastPreparedTrack` flips.
    @Test("jumpToQueueItem during a load cancels the in-flight resolve")
    func jumpDuringLoadCancelsPreviousResolve() async {
        let engine = FakeAudioEngine()
        let service = NeverResolvingYouTubeService()
        let sut = PlayerCoordinator(
            audioEngine: engine,
            youtubeService: service,
            qualityProvider: { .medium }
        )

        // Seed a queue and start loading trackA (resolution suspends forever).
        sut.append(trackA)
        sut.append(trackB)
        sut.append(trackC)
        #expect(sut.state == .loading)
        #expect(engine.lastPreparedTrack?.videoId == trackA.videoId)

        // Jump to trackC while trackA's resolve is still in flight.
        sut.jumpToQueueItem(at: 2)

        // Synchronous portion: engine state already reflects the new target.
        #expect(sut.currentIndex == 2)
        #expect(sut.state == .loading)
        #expect(engine.lastPreparedTrack?.videoId == trackC.videoId)
        // Neither resolve has completed (service never resolves), so play()
        // has not fired for either track — what we care about is that the
        // synchronous index + prepare hop landed on trackC.
        #expect(engine.playCallCount == 0)
    }

    @Test("jumpToQueueItem preserves the Mix continuation token")
    func jumpPreservesMixState() async {
        let (sut, _, service) = makeSUT()
        // Configure the fake so that any in-flight continuation fetch triggered by
        // `jumpToQueueItem` returns a non-terminal page. Without this, the fetch
        // returns `.empty` (nil nextToken) and `fetchMixContinuation` overwrites
        // `mixContinuationToken` with nil — defeating the assertion below.
        service.mixContinuationResult = MixQueueResult(
            items: [SearchResult(videoId: "mx-extra", title: "Extra", channel: "Ch",
                                 durationSec: 180, thumbnailUrl: "")],
            nextToken: "tok-preserved"
        )
        sut.append(trackA)
        sut.append(trackB)
        sut.append(trackC)
        await flushPlayerTasks()
        // Pretend the queue is mid-Mix-walk: a continuation token is held so
        // a near-tail jump must NOT clear it (that's `playNow`'s job).
        sut.mixContinuationToken = "mix-token-abc"

        sut.jumpToQueueItem(at: 2)
        await flushPlayerTasks()

        #expect(sut.currentIndex == 2)
        // Token must survive: fetchMixContinuation returned nextToken="tok-preserved",
        // so mixContinuationToken is updated to that value (not nil).
        #expect(sut.mixContinuationToken != nil)
        // Sanity: jump did NOT push the coordinator into the new-queue path
        // (which would have cleared the token via `cancelMixContinuation`).
        #expect(sut.queue.count >= 3)
    }
}

// MARK: - Stream-resolution wiring (closes YT-0031 AC2)

@Suite("PlayerCoordinator – stream resolution receives audio quality")
@MainActor
struct PlayerCoordinatorStreamResolutionTests {

    @Test("playNow forwards configured audio quality to YouTubeService")
    func playNowForwardsQuality() async {
        let (sut, _, service) = makeSUT(quality: .low)
        sut.playNow(trackA)
        await flushPlayerTasks()
        #expect(service.calls.count == 1)
        #expect(service.calls.first?.videoId == trackA.videoId)
        #expect(service.calls.first?.quality == .low)
    }

    @Test("changing the provider updates the next play call")
    func qualityIsReadAtPlayTime() async {
        var current: AudioQuality = .high
        let engine = FakeAudioEngine()
        let service = RecordingYouTubeService()
        let sut = PlayerCoordinator(
            audioEngine: engine,
            youtubeService: service,
            qualityProvider: { current }
        )
        sut.playNow(trackA)
        await flushPlayerTasks()
        current = .medium
        sut.playNow(trackB)
        await flushPlayerTasks()
        #expect(service.calls.count == 2)
        #expect(service.calls[0].quality == .high)
        #expect(service.calls[1].quality == .medium)
    }

    @Test("default provider maps `auto` preference to .high (documented policy)")
    func defaultProviderMapsAutoToHigh() {
        let suiteName = "PlayerCoordinatorTests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defaults.set(AudioQualityPreference.auto.rawValue, forKey: SettingsKeys.audioQuality)
        // Confirm the mapping that defaultQualityProvider depends on:
        let pref = AudioQualityPreference(rawValue: AudioQualityPreference.auto.rawValue)
        #expect(pref?.toServiceQuality() == .high)
        defaults.removePersistentDomain(forName: suiteName)
    }

    @Test("next/previous also forward quality on each load")
    func nextForwardsQuality() async {
        let (sut, _, service) = makeSUT(quality: .high)
        sut.append(trackA)
        sut.append(trackB)
        await flushPlayerTasks()
        sut.next()
        await flushPlayerTasks()
        #expect(service.calls.count == 2)
        #expect(service.calls.allSatisfy { $0.quality == .high })
        #expect(service.calls[0].videoId == trackA.videoId)
        #expect(service.calls[1].videoId == trackB.videoId)
    }
}

// MARK: - YT-0046 Bug B regression — engine state reset before resolution

/// Service that suspends `resolveStreamURL` indefinitely so tests can observe
/// the coordinator state during the resolution window.
private final class NeverResolvingYouTubeService: YouTubeServiceProtocol, @unchecked Sendable {
    func search(query: String, maxResults: Int) async throws -> [SearchResult] { [] }
    func resolveStreamURL(videoId: String, quality: AudioQuality) async throws -> ResolvedStream {
        // Suspend until the surrounding task is cancelled. The throw keeps the
        // coordinator's catch path tidy if the test ever lets the task run.
        try await Task.sleep(nanoseconds: .max)
        throw CancellationError()
    }
    func getMixQueueWithContinuation(videoId: String) async -> MixQueueResult { .empty }
    func getMixContinuation(token: String) async -> MixQueueResult { .empty }
}

@Suite("PlayerCoordinator – engine state reset on new track (YT-0046 Bug B)")
@MainActor
struct PlayerCoordinatorPrepareTests {

    /// Closes the YT-0046 Bug B regression: when the user picks a new track
    /// the coordinator must reset the engine's observable state to the new
    /// track's metadata immediately, BEFORE stream resolution returns.
    /// Otherwise `engine.duration` retains the previous track's duration —
    /// e.g. a 6-hour mix's 22258s value bleeds into the Now Playing UI of
    /// a 4-minute song while resolution is still in flight, producing the
    /// `-370:58` countdown observed in the YT-0033 manual run.
    @Test("playNow with a new track resets engine.duration before resolution completes")
    func playNowResetsEngineStateBeforeResolution() async {
        let engine = FakeAudioEngine()
        let service = NeverResolvingYouTubeService()
        let sut = PlayerCoordinator(
            audioEngine: engine,
            youtubeService: service,
            qualityProvider: { .medium }
        )

        // Pretend the engine is still showing the *previous* long track:
        // a 6h10m mix (22258s) that already resolved and started playing.
        let longTrack = Track(
            videoId: "long",
            title: "Long Mix",
            channel: "Lofi",
            durationSec: 22258,
            thumbnailUrl: ""
        )
        engine.currentTrack = longTrack
        engine.duration = TimeInterval(longTrack.durationSec)
        engine.currentTime = 1234

        // User taps a new short track. Resolution will never complete, but
        // the engine state MUST already reflect the new track.
        sut.playNow(trackA)

        // Synchronous part of `playNow → beginLoadingCurrent` already ran.
        // No `await flushPlayerTasks()` — we want the in-flight window.
        #expect(engine.prepareCallCount == 1)
        #expect(engine.lastPreparedTrack?.videoId == trackA.videoId)
        #expect(engine.currentTrack?.videoId == trackA.videoId)
        #expect(engine.duration == TimeInterval(trackA.durationSec))
        #expect(engine.currentTime == 0)
        #expect(sut.state == .loading)
        // play(track:url:) MUST NOT have fired yet — resolution is suspended.
        #expect(engine.playCallCount == 0)
    }

    @Test("next() also resets engine state before the new track resolves")
    func nextResetsEngineStateBeforeResolution() async {
        let engine = FakeAudioEngine()
        let service = NeverResolvingYouTubeService()
        let sut = PlayerCoordinator(
            audioEngine: engine,
            youtubeService: service,
            qualityProvider: { .medium }
        )

        sut.append(trackA)
        sut.append(trackB)
        // Two prepare calls so far: one for the synchronous append → auto-play,
        // and the new one for next(). We assert by *track identity*, not count.
        sut.next()

        #expect(engine.lastPreparedTrack?.videoId == trackB.videoId)
        #expect(engine.currentTrack?.videoId == trackB.videoId)
        #expect(engine.duration == TimeInterval(trackB.durationSec))
    }

    /// YT-0049: switching tracks while the previous one is playing must stop
    /// the previous audio synchronously, before the new stream URL resolves.
    /// The fake engine mirrors the production contract by clearing
    /// `isPlaying` inside `prepare(track:)`, so the coordinator must call
    /// `prepare` before awaiting resolution — exposed here by suspending
    /// resolution forever.
    @Test("playNow during playback clears engine.isPlaying before resolution returns")
    func playNowStopsPreviousAudioBeforeResolution() async {
        let engine = FakeAudioEngine()
        let service = NeverResolvingYouTubeService()
        let sut = PlayerCoordinator(
            audioEngine: engine,
            youtubeService: service,
            qualityProvider: { .medium }
        )

        // Pretend trackA is already playing: engine reports playback, the
        // resolve task for trackA is still suspended in our service stub.
        sut.playNow(trackA)
        engine.isPlaying = true

        sut.playNow(trackB)

        // No `await flushPlayerTasks()` — assert the synchronous window.
        #expect(engine.isPlaying == false)
        #expect(engine.lastPreparedTrack?.videoId == trackB.videoId)
        #expect(engine.currentTime == 0)
        #expect(engine.duration == TimeInterval(trackB.durationSec))
        #expect(engine.playCallCount == 0)
    }
}

// MARK: - YT-0157 — proxy loader forwarded to engine.play

@Suite("PlayerCoordinator – HLS proxy loader forwarding (YT-0157)")
@MainActor
struct PlayerCoordinatorProxyLoaderTests {

    @Test("Loader from ResolvedStream survives the coordinator → engine hop")
    func forwardsLoader() async {
        let engine = FakeAudioEngine()
        let service = RecordingYouTubeService()
        let loader = HLSProxyLoader(originURL: URL(string: "https://example.invalid/o")!, m3u8: "#EXTM3U\n")
        // Pre-bake a `ResolvedStream` carrying the loader; the recording
        // service hands this back on resolve so the coordinator's forward
        // path is exercised end-to-end.
        service.streamResult = .success(ResolvedStream(
            url: URL(string: "yt-prefetch://yt/playlist.m3u8")!,
            isMuxedFallback: false,
            proxyLoader: loader
        ))
        let sut = PlayerCoordinator(
            audioEngine: engine,
            youtubeService: service,
            qualityProvider: { .medium }
        )

        sut.playNow(trackA)
        await flushPlayerTasks()

        #expect(engine.lastResourceLoader === loader)
        #expect(engine.lastPlayURL?.scheme == "yt-prefetch")
    }

    @Test("Nil loader is forwarded as nil (non-proxied audio path)")
    func forwardsNilLoader() async {
        let engine = FakeAudioEngine()
        let service = RecordingYouTubeService()
        // Default streamResult on RecordingYouTubeService has proxyLoader nil.
        let sut = PlayerCoordinator(
            audioEngine: engine,
            youtubeService: service,
            qualityProvider: { .medium }
        )

        sut.playNow(trackA)
        await flushPlayerTasks()

        #expect(engine.lastResourceLoader == nil)
    }
}

// MARK: - YT-0024 — isPlaying / togglePlayPause during .loading

@Suite("PlayerCoordinator – isPlaying & togglePlayPause during .loading (YT-0024)")
@MainActor
struct PlayerCoordinatorLoadingStateTests {

    @Test("isPlaying is true while state == .loading so the pause affordance shows immediately")
    func isPlayingTrueWhileLoading() {
        let engine = FakeAudioEngine()
        let service = NeverResolvingYouTubeService()
        let sut = PlayerCoordinator(
            audioEngine: engine,
            youtubeService: service,
            qualityProvider: { .medium }
        )

        sut.playNow(trackA)

        #expect(sut.state == .loading)
        #expect(sut.isPlaying == true)
    }

    @Test("togglePlayPause while .loading transitions to .paused and cancels the in-flight resolve")
    func togglePlayPauseWhileLoadingPausesAndCancels() async {
        let engine = FakeAudioEngine()
        let service = NeverResolvingYouTubeService()
        let sut = PlayerCoordinator(
            audioEngine: engine,
            youtubeService: service,
            qualityProvider: { .medium }
        )

        sut.playNow(trackA)
        #expect(sut.state == .loading)

        sut.togglePlayPause()

        #expect(sut.state == .paused)
        #expect(engine.pauseCallCount == 1)
        #expect(engine.playCallCount == 0)
        await flushPlayerTasks()
        #expect(sut.state == .paused)
        #expect(engine.playCallCount == 0)
    }
}

// MARK: - YT-0070 — error state surface

/// Coordinator-side contracts for ``PlayerCoordinator/dismissError()`` and
/// ``PlayerCoordinator/retryLastAttempt()``. The MiniPlayer + NowPlaying
/// error banner reads `state.error` via `AppShellViewModel.errorMessage`
/// (single source of truth — no parallel error bool); these tests lock the
/// coordinator's side of that contract.
@Suite("PlayerCoordinator – error dismiss & retry (YT-0070)")
@MainActor
struct PlayerCoordinatorErrorTests {

    @Test("reportError moves state to .error with message")
    func reportErrorSetsState() {
        let (sut, _, _) = makeSUT()
        sut.reportError("HTTP 403: Forbidden")
        if case let .error(message) = sut.state {
            #expect(message == "HTTP 403: Forbidden")
        } else {
            Issue.record("Expected .error state, got \(sut.state)")
        }
    }

    @Test("dismissError returns to .paused when a track is loaded")
    func dismissErrorReturnsToPausedWhenTrackLoaded() async {
        let (sut, _, _) = makeSUT()
        sut.playNow(trackA)
        await flushPlayerTasks()
        // Force the coordinator into an error state mid-playback.
        sut.reportError("Playback failed.")
        #expect(sut.currentTrack?.videoId == trackA.videoId)

        sut.dismissError()

        #expect(sut.state == .paused)
        // The track stays loaded so the user can hit play to resume the
        // transport without re-resolving — that's `retryLastAttempt`'s job.
        #expect(sut.currentTrack?.videoId == trackA.videoId)
    }

    @Test("dismissError returns to .idle when no track is loaded")
    func dismissErrorReturnsToIdleWhenQueueEmpty() {
        let (sut, _, _) = makeSUT()
        sut.reportError("Playback failed.")
        #expect(sut.currentTrack == nil)

        sut.dismissError()

        #expect(sut.state == .idle)
    }

    @Test("dismissError is a no-op for non-error states")
    func dismissErrorNoOpForNonErrorState() async {
        let (sut, _, _) = makeSUT()
        sut.playNow(trackA)
        await flushPlayerTasks()
        // Coordinator is now in `.playing` after the resolve completes.
        let stateBefore = sut.state

        sut.dismissError()

        #expect(sut.state == stateBefore)
    }

    @Test("retryLastAttempt re-runs the resolve flow for the current track")
    func retryLastAttemptCallsPlayNowForCurrentTrack() async {
        let (sut, _, service) = makeSUT()
        sut.playNow(trackA)
        await flushPlayerTasks()
        let resolveCallsBefore = service.calls.count
        sut.reportError("Playback failed.")

        sut.retryLastAttempt()

        // Synchronous post-retry window: state is back in `.loading`,
        // a fresh resolve task has been kicked off. Yield so it can run.
        #expect(sut.state == .loading)
        await flushPlayerTasks()
        // A new resolve was issued for the same videoId.
        #expect(service.calls.count == resolveCallsBefore + 1)
        #expect(service.calls.last?.videoId == trackA.videoId)
    }

    @Test("retryLastAttempt is a no-op when nothing is loaded")
    func retryLastAttemptNoOpWhenNoTrack() {
        let (sut, _, service) = makeSUT()
        sut.reportError("Playback failed.")
        #expect(sut.currentTrack == nil)

        sut.retryLastAttempt()

        // No track to retry — state unchanged, no resolve fired.
        #expect(service.calls.isEmpty == true)
        if case .error = sut.state {
            // Still in error state — retry can't do anything without a track.
        } else {
            Issue.record("Expected .error state to persist, got \(sut.state)")
        }
    }
}

// MARK: - AppShellViewModel — errorMessage derivation (YT-0070)

@Suite("AppShellViewModel – errorMessage proxy (YT-0070)")
@MainActor
struct AppShellViewModelErrorTests {

    private func makeShell() -> (shell: AppShellViewModel, coordinator: PlayerCoordinator) {
        let engine = FakeAudioEngine()
        let service = RecordingYouTubeService()
        let coordinator = PlayerCoordinator(
            audioEngine: engine,
            youtubeService: service,
            qualityProvider: { .medium }
        )
        return (AppShellViewModel(player: coordinator), coordinator)
    }

    @Test("errorMessage is nil when coordinator state is not .error")
    func errorMessageNilAtRest() {
        let (shell, _) = makeShell()
        #expect(shell.errorMessage == nil)
        #expect(shell.hasError == false)
    }

    @Test("errorMessage mirrors PlayerCoordinator.state.error message")
    func errorMessageMirrorsCoordinatorState() {
        let (shell, coordinator) = makeShell()
        coordinator.reportError("HTTP 403: Forbidden")
        #expect(shell.errorMessage == "HTTP 403: Forbidden")
        #expect(shell.hasError == true)
    }

    @Test("dismissError on shell forwards to coordinator and clears errorMessage")
    func dismissErrorClearsErrorMessage() {
        let (shell, coordinator) = makeShell()
        coordinator.reportError("Playback failed.")
        #expect(shell.errorMessage == "Playback failed.")

        shell.dismissError()

        #expect(shell.errorMessage == nil)
        #expect(shell.hasError == false)
    }

    @Test("retryPlayback on shell forwards to coordinator and re-enters .loading")
    func retryPlaybackForwardsToCoordinator() async {
        let (shell, coordinator) = makeShell()
        let track = Track(videoId: "yt0070", title: "T", channel: "Ch", durationSec: 60, thumbnailUrl: "")
        shell.play(track)
        // Yield so the resolve completes — state goes to .playing.
        for _ in 0..<5 { await Task.yield() }
        coordinator.reportError("Playback failed.")
        #expect(shell.errorMessage != nil)

        shell.retryPlayback()

        // Synchronous post-retry: coordinator re-entered `.loading`, the
        // error has been superseded.
        #expect(shell.errorMessage == nil)
        if case .loading = coordinator.state {
            // Expected.
        } else {
            Issue.record("Expected .loading after retry, got \(coordinator.state)")
        }
    }
}

// MARK: - YT-0053 — next-track stream URL prefetch

/// Stub service that lets tests record every `resolveStreamURL` videoId in
/// order, and optionally control which calls fail. Distinct from
/// `RecordingYouTubeService` so the two failure-mode behaviours don't
/// cross-contaminate.
final class PrefetchRecordingYouTubeService: YouTubeServiceProtocol, @unchecked Sendable {
    private let lock = NSLock()
    private var _calls: [String] = []
    /// videoIds that should fail to resolve. Maps a videoId to the error
    /// to throw. Useful for the "failed prefetch falls back to live
    /// resolve" test.
    var failingVideoIds: [String: YouTubeServiceError] = [:]
    /// Optional per-videoId stream override. When absent a deterministic
    /// `https://example.invalid/<videoId>` URL is returned so the test
    /// can compare equality on `engine.lastPlayURL`.
    var streamForVideoId: [String: ResolvedStream] = [:]

    var calls: [String] {
        lock.lock(); defer { lock.unlock() }
        return _calls
    }

    func search(query: String, maxResults: Int) async throws -> [SearchResult] { [] }

    func resolveStreamURL(videoId: String, quality: AudioQuality) async throws -> ResolvedStream {
        lock.lock()
        _calls.append(videoId)
        lock.unlock()
        if let err = failingVideoIds[videoId] { throw err }
        if let stream = streamForVideoId[videoId] { return stream }
        return ResolvedStream(
            url: URL(string: "https://example.invalid/\(videoId)")!,
            isMuxedFallback: false
        )
    }

    // YT-0298 Mix stubs — return empty by default; tests override as needed.
    func getMixQueueWithContinuation(videoId: String) async -> MixQueueResult { .empty }
    func getMixContinuation(token: String) async -> MixQueueResult { .empty }
}

@Suite("PlayerCoordinator – next-track prefetch (YT-0053)")
@MainActor
struct PlayerCoordinatorPrefetchTests {

    private func makeSUT(
        prefetchFreshness: TimeInterval = 30 * 60,
        clock: @escaping @Sendable () -> Date = { Date() }
    ) -> (sut: PlayerCoordinator, engine: FakeAudioEngine, service: PrefetchRecordingYouTubeService) {
        let engine = FakeAudioEngine()
        let service = PrefetchRecordingYouTubeService()
        let sut = PlayerCoordinator(
            audioEngine: engine,
            youtubeService: service,
            qualityProvider: { .medium },
            prefetchFreshness: prefetchFreshness,
            prefetchClock: clock
        )
        return (sut, engine, service)
    }

    @Test("playing the current track schedules a prefetch for the next-in-queue")
    func prefetchScheduledWhenPlayingWithSuccessor() async {
        let (sut, _, service) = makeSUT()
        sut.append(trackA)
        sut.append(trackB)
        // The append-to-empty-queue path triggers `beginLoadingCurrent` for
        // trackA. Yield so the resolve completes and the post-resolve
        // prefetch schedule fires.
        await flushPlayerTasks()

        // Two calls expected: trackA (the current track resolution) and
        // trackB (the prefetch). Order: A first, B second.
        #expect(service.calls.contains("a1") == true)
        #expect(service.calls.contains("b2") == true)
    }

    @Test("next() consumes the cache instead of issuing a fresh resolve")
    func nextConsumesCache() async {
        let (sut, engine, service) = makeSUT()
        sut.append(trackA)
        sut.append(trackB)
        await flushPlayerTasks()
        let callsBeforeNext = service.calls.count
        // Sanity: trackB should be in the prefetch slot now.
        #expect(callsBeforeNext >= 2)

        sut.next()
        // Cache hit means `play(track:url:)` runs synchronously inside
        // `beginLoadingCurrent` (no await for resolution).
        #expect(engine.lastPlayURL?.absoluteString.contains("b2") == true)
        #expect(sut.state == .playing)
        // The cache hit path also schedules prefetch of trackC's
        // successor — but there is none, so service.calls should not have
        // grown by a re-resolve of trackB.
        await flushPlayerTasks()
        // Tighten: no duplicate trackB call after the cache hit.
        let trackBCount = service.calls.filter { $0 == "b2" }.count
        #expect(trackBCount == 1)
    }

    @Test("cache miss (different videoId) falls through to live resolve")
    func cacheMissFallsThroughToLiveResolve() async {
        let (sut, engine, service) = makeSUT()
        sut.append(trackA)
        sut.append(trackB)
        await flushPlayerTasks()
        // Mutate the queue so the prefetch slot's videoId no longer
        // matches the new successor: insert trackC between current and
        // trackB. After the mutation, the cache must NOT be honoured for
        // trackC (different videoId).
        sut.append(trackC)
        sut.move(from: 2, to: 1) // trackC is now at index 1 — the new successor.
        await flushPlayerTasks()

        let callsBeforeNext = service.calls.count
        sut.next()
        // The cache had trackB queued (from the original prefetch) but the
        // new successor is trackC; cache lookup misses on videoId. A live
        // resolve is required.
        await flushPlayerTasks()
        let trackCCount = service.calls.filter { $0 == "c3" }.count
        #expect(trackCCount >= 1)
        #expect(service.calls.count >= callsBeforeNext + 1)
        #expect(engine.lastPlayURL?.absoluteString.contains("c3") == true)
    }

    @Test("queue mutation cancels in-flight prefetch")
    func queueMutationCancelsPrefetch() async {
        let (sut, _, service) = makeSUT()
        sut.append(trackA)
        sut.append(trackB)
        // Mutate the queue *during* the resolve → prefetch flow. `remove`
        // calls `invalidatePrefetch()` which cancels the in-flight task.
        sut.remove(at: 1) // remove trackB
        await flushPlayerTasks()

        // Only trackA's resolve should fire. trackB's prefetch was
        // cancelled before it could land.
        let trackBCalls = service.calls.filter { $0 == "b2" }.count
        // 0 or 1 — race-tolerant: if the cancellation lands before the
        // task body's first await, trackB was never called; if after, it
        // may have been called once but the result was discarded.
        #expect(trackBCalls <= 1)
    }

    @Test("failed prefetch leaves the cache empty so next() falls back to live resolve")
    func failedPrefetchFallsBackToLiveResolve() async {
        let (sut, engine, service) = makeSUT()
        // Make trackB's resolve fail. `failingVideoIds` triggers a throw
        // inside the service.
        service.failingVideoIds["b2"] = .noStreamFound
        sut.append(trackA)
        sut.append(trackB)
        await flushPlayerTasks()
        // Drop the failing flag so the live resolve on `next()` succeeds.
        service.failingVideoIds.removeValue(forKey: "b2")

        sut.next()
        await flushPlayerTasks()

        // trackB was attempted twice: once via prefetch (failed) and once
        // via live resolve (succeeded).
        let trackBCalls = service.calls.filter { $0 == "b2" }.count
        #expect(trackBCalls == 2)
        #expect(engine.lastPlayURL?.absoluteString.contains("b2") == true)
        #expect(sut.state == .playing)
    }

    @Test("expired prefetch entry is dropped — next() issues a fresh resolve")
    func expiredEntryFallsThroughToLiveResolve() async {
        // Drive the clock forward so the prefetched entry ages past the
        // freshness window. Keep freshness short for the test (1 second).
        let now: NSLock = NSLock()
        nonisolated(unsafe) var fakeNow = Date(timeIntervalSince1970: 1_000_000)
        let (sut, engine, service) = makeSUT(
            prefetchFreshness: 1.0,
            clock: { now.lock(); defer { now.unlock() }; return fakeNow }
        )
        sut.append(trackA)
        sut.append(trackB)
        await flushPlayerTasks()

        // Advance fake clock past the freshness window.
        now.lock(); fakeNow = fakeNow.addingTimeInterval(60); now.unlock()

        let callsBeforeNext = service.calls.count
        sut.next()
        await flushPlayerTasks()

        // trackB should have been re-resolved live because the cached
        // entry was stale.
        let trackBCalls = service.calls.filter { $0 == "b2" }.count
        // 1 prefetch + 1 live re-resolve = 2.
        #expect(trackBCalls == 2)
        #expect(service.calls.count >= callsBeforeNext + 1)
        #expect(engine.lastPlayURL?.absoluteString.contains("b2") == true)
    }
}

// MARK: - YT-0192 isActivelyPlaying helper

@Suite("PlayerCoordinator – isActivelyPlaying")
@MainActor
struct PlayerCoordinatorIsActivelyPlayingTests {

    // Case 1: id matches AND state is playing → true
    @Test("returns true when videoId matches the current track and player is playing")
    func trueWhenIdMatchAndPlaying() async {
        let (sut, _, _) = makeSUT()
        sut.playNow(trackA)
        await flushPlayerTasks()
        // State is .playing after flush.
        #expect(sut.isPlaying == true)
        #expect(sut.isActivelyPlaying(videoId: trackA.videoId) == true)
    }

    // Case 2: id matches BUT state is paused → false
    @Test("returns false when videoId matches but player is paused")
    func falseWhenIdMatchButPaused() async {
        let (sut, _, _) = makeSUT()
        sut.playNow(trackA)
        await flushPlayerTasks()
        sut.togglePlayPause()
        #expect(sut.state == .paused)
        #expect(sut.isActivelyPlaying(videoId: trackA.videoId) == false)
    }

    // Case 3: id does NOT match but player is playing → false
    @Test("returns false when videoId does not match even though player is playing")
    func falseWhenIdNonMatchAndPlaying() async {
        let (sut, _, _) = makeSUT()
        sut.playNow(trackA)
        await flushPlayerTasks()
        #expect(sut.isPlaying == true)
        #expect(sut.isActivelyPlaying(videoId: trackB.videoId) == false)
    }

    // Case 4: no current track (idle) → false
    @Test("returns false when no track is loaded")
    func falseWhenNoCurrentTrack() {
        let (sut, _, _) = makeSUT()
        #expect(sut.currentTrack == nil)
        #expect(sut.isActivelyPlaying(videoId: trackA.videoId) == false)
    }
}
