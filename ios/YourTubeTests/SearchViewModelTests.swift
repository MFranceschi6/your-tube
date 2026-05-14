import Testing
import Foundation
@testable import YourTube

// MARK: - Fakes

/// Hand-written fake `YouTubeServiceProtocol` for SearchViewModel tests.
/// Stores per-call recorded queries and lets each test choose the result it
/// wants by setting `searchResult`.
final class FakeSearchService: YouTubeServiceProtocol, @unchecked Sendable {
    private let lock = NSLock()
    private var _calls: [(query: String, maxResults: Int)] = []

    /// Configurable per-test outcome. Defaults to a single non-empty result.
    var searchResult: Result<[SearchResult], YouTubeServiceError> = .success([
        SearchResult(videoId: "v1", title: "Result 1", channel: "Ch1", durationSec: 100, thumbnailUrl: "")
    ])

    var calls: [(query: String, maxResults: Int)] {
        lock.lock(); defer { lock.unlock() }
        return _calls
    }

    func search(query: String, maxResults: Int) async throws -> [SearchResult] {
        lock.lock()
        _calls.append((query, maxResults))
        lock.unlock()

        switch searchResult {
        case .success(let r): return r
        case .failure(let e): throw e
        }
    }

    func resolveStreamURL(videoId: String, quality: AudioQuality) async throws -> ResolvedStream {
        ResolvedStream(url: URL(string: "https://example.invalid/x")!, isMuxedFallback: false)
    }
    // YT-0298 Mix stubs — not exercised by SearchViewModel tests.
    func getMixQueueWithContinuation(videoId: String) async -> MixQueueResult { .empty }
    func getMixContinuation(token: String) async -> MixQueueResult { .empty }
}

// MARK: - Helpers

/// Yields enough times to let the in-flight `Task` continuation reach the
/// state mutation step. One yield per pending continuation is plenty.
@MainActor
private func flushSearchTasks() async {
    for _ in 0..<5 { await Task.yield() }
}

@MainActor
private func makeSUT(
    result: Result<[SearchResult], YouTubeServiceError> = .success([
        SearchResult(videoId: "v1", title: "Result 1", channel: "Ch1", durationSec: 100, thumbnailUrl: "")
    ])
) -> (sut: SearchViewModel, service: FakeSearchService) {
    let service = FakeSearchService()
    service.searchResult = result
    let sut = SearchViewModel(youtubeService: service)
    return (sut, service)
}

// MARK: - State machine

@Suite("SearchViewModel – state transitions")
@MainActor
struct SearchViewModelStateTests {

    @Test("starts idle with empty query")
    func startsIdle() {
        let (sut, _) = makeSUT()
        #expect(sut.state == .idle)
        #expect(sut.query.isEmpty)
        #expect(sut.lastQuery == nil)
    }

    @Test("submit transitions idle → loading → results")
    func submitToResults() async {
        let results = [
            SearchResult(videoId: "a", title: "A", channel: "ChA", durationSec: 10, thumbnailUrl: ""),
            SearchResult(videoId: "b", title: "B", channel: "ChB", durationSec: 20, thumbnailUrl: "")
        ]
        let (sut, service) = makeSUT(result: .success(results))
        sut.query = "lofi"
        sut.submit()
        // After the synchronous portion, state is .loading.
        #expect(sut.state == .loading)
        await flushSearchTasks()
        #expect(sut.state == .results(results))
        #expect(sut.lastQuery == "lofi")
        #expect(service.calls.count == 1)
        #expect(service.calls.first?.query == "lofi")
    }

    @Test("submit with empty results transitions to empty state")
    func submitToEmpty() async {
        let (sut, _) = makeSUT(result: .success([]))
        sut.submit("xyz")
        await flushSearchTasks()
        #expect(sut.state == .empty(query: "xyz"))
        #expect(sut.lastQuery == "xyz")
    }

    @Test("YouTubeServiceError surfaces user-safe error message")
    func submitToError() async {
        let (sut, _) = makeSUT(result: .failure(.networkFailure))
        sut.submit("lofi")
        await flushSearchTasks()
        if case .error(let message) = sut.state {
            #expect(!message.isEmpty)
            // Network failure is the user-safe error string.
            #expect(message == YouTubeServiceError.networkFailure.errorDescription)
        } else {
            Issue.record("Expected .error state, got \(sut.state)")
        }
    }

    @Test("whitespace-only submit returns to idle without calling the service")
    func whitespaceSubmitClears() async {
        let (sut, service) = makeSUT()
        sut.query = "lofi"
        sut.submit()
        await flushSearchTasks()
        // Now submit empty.
        sut.submit("   ")
        await flushSearchTasks()
        #expect(sut.state == .idle)
        #expect(sut.query.isEmpty)
        #expect(sut.lastQuery == nil)
        // Only the first (real) call should have hit the service.
        #expect(service.calls.count == 1)
    }

    @Test("clear() cancels in-flight search and resets state")
    func clearResetsState() async {
        let (sut, _) = makeSUT()
        sut.submit("lofi")
        sut.clear()
        await flushSearchTasks()
        #expect(sut.state == .idle)
        #expect(sut.query.isEmpty)
        #expect(sut.lastQuery == nil)
    }

    @Test("submit() with explicit override updates the query")
    func submitWithOverride() async {
        let (sut, _) = makeSUT()
        sut.submit("jazz cafe")
        await flushSearchTasks()
        #expect(sut.query == "jazz cafe")
        #expect(sut.lastQuery == "jazz cafe")
    }

    @Test("retry() re-issues the last query")
    func retryReissuesLastQuery() async {
        let (sut, service) = makeSUT(result: .failure(.networkFailure))
        sut.submit("lofi")
        await flushSearchTasks()
        // Now flip to success and retry.
        let okResult = SearchResult(videoId: "ok", title: "OK", channel: "Ch", durationSec: 0, thumbnailUrl: "")
        service.searchResult = .success([okResult])
        sut.retry()
        await flushSearchTasks()
        #expect(sut.state == .results([okResult]))
        #expect(service.calls.count == 2)
        #expect(service.calls.last?.query == "lofi")
    }

    @Test("retry() with no prior query is a no-op")
    func retryNoOpWithoutLastQuery() async {
        let (sut, service) = makeSUT()
        sut.retry()
        await flushSearchTasks()
        #expect(sut.state == .idle)
        #expect(service.calls.isEmpty)
    }

    @Test("submit forwards configured maxResults")
    func submitForwardsMaxResults() async {
        let service = FakeSearchService()
        let sut = SearchViewModel(youtubeService: service, maxResults: 7)
        sut.submit("lofi")
        await flushSearchTasks()
        #expect(service.calls.first?.maxResults == 7)
    }
}

// MARK: - Mapping

@Suite("SearchViewModel – SearchResult → Track mapping")
struct SearchViewModelMappingTests {

    @Test("track(from:) preserves all fields")
    func mappingIsLossless() {
        let result = SearchResult(
            videoId: "vid1",
            title: "Title",
            channel: "Channel",
            durationSec: 240,
            thumbnailUrl: "https://i.ytimg.com/vi/vid1/mqdefault.jpg"
        )
        let track = SearchViewModel.track(from: result)
        #expect(track.videoId == result.videoId)
        #expect(track.title == result.title)
        #expect(track.channel == result.channel)
        #expect(track.durationSec == result.durationSec)
        #expect(track.thumbnailUrl == result.thumbnailUrl)
    }
}

// MARK: - Action wiring (queue / play)

@Suite("SearchViewModel – action wiring through AppShellViewModel")
@MainActor
struct SearchViewModelActionWiringTests {

    /// Lightweight in-memory engine for action tests. Mirrors the helper in
    /// PlayerCoordinatorTests but kept private to this file.
    private final class StubEngine: AudioEngineProtocol {
        var currentTrack: Track?
        var isPlaying: Bool = false
        var currentTime: TimeInterval = 0
        var duration: TimeInterval = 0
        var playCalls: [Track] = []
        func play(track: Track, url: URL?, resourceLoader: HLSProxyLoader?) {
            playCalls.append(track)
            currentTrack = track
            isPlaying = true
        }
        func prepare(track: Track) {
            currentTrack = track
            currentTime = 0
            duration = TimeInterval(track.durationSec)
            // YT-0049: mirror engine contract — prepare stops previous audio.
            isPlaying = false
        }
        func pause() { isPlaying = false }
        func resume() { isPlaying = true }
        func togglePlayPause() { isPlaying.toggle() }
        func seek(to time: TimeInterval) { currentTime = time }
        func skipNext() {}
        func skipPrevious() {}
        func stop() {
            currentTrack = nil
            isPlaying = false
        }
    }

    @Test("AppShellViewModel.play forwards the search-derived track to the coordinator")
    func playForwardsTrack() async {
        let engine = StubEngine()
        let service = FakeSearchService()
        let coordinator = PlayerCoordinator(
            audioEngine: engine,
            youtubeService: service,
            qualityProvider: { .medium }
        )
        let shell = AppShellViewModel(player: coordinator)

        let result = SearchResult(videoId: "play-x", title: "X", channel: "Ch", durationSec: 30, thumbnailUrl: "")
        let track = SearchViewModel.track(from: result)
        shell.play(track)
        // Yield to let the resolution Task hand the track to the engine.
        for _ in 0..<5 { await Task.yield() }

        #expect(shell.currentTrack?.videoId == "play-x")
        #expect(engine.playCalls.first?.videoId == "play-x")
    }

    @Test("AppShellViewModel.appendToQueue enqueues without disturbing current track")
    func appendDoesNotDisturbCurrent() async {
        let engine = StubEngine()
        let service = FakeSearchService()
        let coordinator = PlayerCoordinator(
            audioEngine: engine,
            youtubeService: service,
            qualityProvider: { .medium }
        )
        let shell = AppShellViewModel(player: coordinator)

        let first = Track(videoId: "first", title: "First", channel: "Ch", durationSec: 30, thumbnailUrl: "")
        let queued = Track(videoId: "queued", title: "Queued", channel: "Ch", durationSec: 30, thumbnailUrl: "")

        shell.play(first)
        for _ in 0..<5 { await Task.yield() }

        shell.appendToQueue(queued)
        for _ in 0..<5 { await Task.yield() }

        #expect(shell.queue.map(\.videoId) == ["first", "queued"])
        #expect(shell.currentTrack?.videoId == "first")
        // Engine.play should have fired exactly once — only for the first track.
        #expect(engine.playCalls.count == 1)
    }
}
