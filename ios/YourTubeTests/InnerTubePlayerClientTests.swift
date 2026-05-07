import Testing
import Foundation
@testable import YourTube

// MARK: - Fixture loader

/// Anchor type used by `Bundle(for:)` to locate the test target's resource
/// bundle so the InnerTube `/player` JSON fixtures under
/// `ios/YourTubeTests/Fixtures/` can be loaded uniformly across suites.
private final class FixtureBundleAnchor {}

private func loadPlayerFixture(_ name: String) throws -> Data {
    let bundle = Bundle(for: FixtureBundleAnchor.self)
    // xcodegen merges `ios/YourTubeTests/Fixtures` into the same resource
    // group as `docs/fixtures`, so InnerTube fixtures land under the bundle's
    // `fixtures/` subdirectory at install time. Pass the subdir explicitly so
    // `Bundle.url(forResource:withExtension:)` doesn't only search the root.
    if let url = bundle.url(forResource: name, withExtension: "json", subdirectory: "player-fixtures") {
        return try Data(contentsOf: url)
    }
    if let url = bundle.url(forResource: name, withExtension: "json") {
        return try Data(contentsOf: url)
    }
    Issue.record("Missing fixture: \(name).json — make sure ios/YourTubeTests/player-fixtures/\(name).json is bundled.")
    throw NSError(domain: "FixtureMissing", code: 0)
}

// MARK: - URLProtocol fake

/// `URLProtocol` subclass that responds to `/player` POSTs with a configured
/// stub — either a body + status, or an error to inject into the URLSession
/// data task. Set the stub before constructing the session; reset to `nil`
/// after the test.
final class FakePlayerURLProtocol: URLProtocol, @unchecked Sendable {

    enum Stub {
        case body(statusCode: Int, body: Data)
        case error(NSError)
    }

    nonisolated(unsafe) static var stub: Stub?
    /// Tracks how many times `startLoading` fired — useful for asserting that
    /// the visitor cache coalesces concurrent calls into a single transport hit.
    nonisolated(unsafe) static var loadCount: Int = 0

    static func reset() {
        stub = nil
        loadCount = 0
    }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        Self.loadCount += 1
        guard let stub = Self.stub else {
            client?.urlProtocol(self, didFailWithError: NSError(domain: NSURLErrorDomain, code: NSURLErrorBadServerResponse))
            return
        }
        switch stub {
        case .body(let statusCode, let body):
            let response = HTTPURLResponse(
                url: request.url!,
                statusCode: statusCode,
                httpVersion: "HTTP/1.1",
                headerFields: ["Content-Type": "application/json"]
            )!
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: body)
            client?.urlProtocolDidFinishLoading(self)
        case .error(let error):
            client?.urlProtocol(self, didFailWithError: error)
        }
    }

    override func stopLoading() {}
}

private func fakeSession() -> URLSession {
    let config = URLSessionConfiguration.ephemeral
    config.protocolClasses = [FakePlayerURLProtocol.self]
    return URLSession(configuration: config)
}

// MARK: - Stream selection

@Suite("LivePlayerExtractor.pickAudioOnlyURL")
struct LivePlayerExtractorAudioPickTests {

    private func format(itag: Int, mime: String, avgBps: Int?) -> InnerTubePlayerResponse.AdaptiveFormat {
        InnerTubePlayerResponse.AdaptiveFormat(
            itag: itag,
            url: "https://example.invalid/itag/\(itag)",
            mimeType: mime,
            bitrate: avgBps,
            averageBitrate: avgBps
        )
    }

    @Test("Picks the highest-bitrate audio-only mp4a stream within the quality ceiling")
    func picksHighestBelowCeiling() {
        let formats = [
            format(itag: 139, mime: "audio/mp4; codecs=\"mp4a.40.5\"", avgBps:  48_000),
            format(itag: 140, mime: "audio/mp4; codecs=\"mp4a.40.2\"", avgBps: 130_000),
            format(itag: 141, mime: "audio/mp4; codecs=\"mp4a.40.2\"", avgBps: 256_000),
        ]
        let url = LivePlayerExtractor.pickAudioOnlyURL(from: formats, quality: .medium)
        // .medium = 128 kbps ceiling → 141 (256k) is over; 140 (130k) is over;
        // 139 (48k) is under. Among <= 128 kbps the only candidate is itag 139.
        #expect(url?.absoluteString.hasSuffix("/139") == true)
    }

    @Test("With two candidates under the ceiling, picks the higher-bitrate one (locks comparator direction)")
    func twoCandidatesPicksHigher() {
        // Both fit under .medium (128 kbps): 64 kbps and 96 kbps. The picker
        // must return 96 kbps. Catches a future swap of `<` ↔ `>` in the
        // `max(by:)` comparator that single-candidate tests can't see.
        let formats = [
            format(itag: 139, mime: "audio/mp4; codecs=\"mp4a.40.5\"", avgBps:  64_000),
            format(itag: 140, mime: "audio/mp4; codecs=\"mp4a.40.2\"", avgBps:  96_000),
        ]
        let url = LivePlayerExtractor.pickAudioOnlyURL(from: formats, quality: .medium)
        #expect(url?.absoluteString.hasSuffix("/140") == true)
    }

    @Test("When no candidate respects the ceiling, falls back to the lowest-bitrate audio-only stream")
    func fallsBackToLowest() {
        // All above the .low ceiling (48 kbps); fallback returns lowest.
        let formats = [
            format(itag: 140, mime: "audio/mp4; codecs=\"mp4a.40.2\"", avgBps: 130_000),
            format(itag: 141, mime: "audio/mp4; codecs=\"mp4a.40.2\"", avgBps: 256_000),
        ]
        let url = LivePlayerExtractor.pickAudioOnlyURL(from: formats, quality: .low)
        #expect(url?.absoluteString.hasSuffix("/140") == true)
    }

    @Test("Skips opus / webm streams (AVPlayer can't play them outside HLS)")
    func skipsOpus() {
        let formats = [
            format(itag: 251, mime: "audio/webm; codecs=\"opus\"", avgBps: 160_000),
            format(itag: 140, mime: "audio/mp4; codecs=\"mp4a.40.2\"", avgBps: 130_000),
        ]
        let url = LivePlayerExtractor.pickAudioOnlyURL(from: formats, quality: .high)
        #expect(url?.absoluteString.hasSuffix("/140") == true)
    }

    @Test("Returns nil when no audio-only mp4a stream is present")
    func noneReturnsNil() {
        let formats = [
            format(itag: 251, mime: "audio/webm; codecs=\"opus\"", avgBps: 160_000),
        ]
        #expect(LivePlayerExtractor.pickAudioOnlyURL(from: formats, quality: .high) == nil)
    }

    @Test("Rejects audio/mp4 mp4a candidates with both bitrate fields nil")
    func rejectsBothBitratesNil() {
        // The unsized format used to win the lowest-bitrate fallback by
        // coalescing to `Int.max` against real candidates. After the YT-0162
        // reviewer pass, formats without any known bitrate are filtered up
        // front so they can never win.
        let formats = [
            format(itag: 140, mime: "audio/mp4; codecs=\"mp4a.40.2\"", avgBps: 130_000),
            // Unsized: both bitrate AND averageBitrate nil.
            InnerTubePlayerResponse.AdaptiveFormat(
                itag: 999,
                url: "https://example.invalid/itag/999",
                mimeType: "audio/mp4; codecs=\"mp4a.40.2\"",
                bitrate: nil,
                averageBitrate: nil
            ),
        ]
        let url = LivePlayerExtractor.pickAudioOnlyURL(from: formats, quality: .low)
        // Even at .low (48 kbps ceiling) where 130k overshoots and the lowest
        // fallback runs, the unsized 999 must NOT be picked. The 140 is the
        // only valid candidate after filtering.
        #expect(url?.absoluteString.hasSuffix("/140") == true)
    }
}

@Suite("LivePlayerExtractor.pickMuxedURL")
struct LivePlayerExtractorMuxedPickTests {

    private func format(itag: Int, mime: String, avgBps: Int) -> InnerTubePlayerResponse.AdaptiveFormat {
        InnerTubePlayerResponse.AdaptiveFormat(
            itag: itag,
            url: "https://example.invalid/itag/\(itag)",
            mimeType: mime,
            bitrate: avgBps,
            averageBitrate: avgBps
        )
    }

    @Test("Picks the highest-bitrate progressive mp4 stream")
    func picksHighestProgressive() {
        let formats = [
            format(itag: 18, mime: "video/mp4; codecs=\"avc1.42001E, mp4a.40.2\"", avgBps:  300_000),
            format(itag: 22, mime: "video/mp4; codecs=\"avc1.64001F, mp4a.40.2\"", avgBps: 1_500_000),
        ]
        let url = LivePlayerExtractor.pickMuxedURL(from: formats)
        #expect(url?.absoluteString.hasSuffix("/22") == true)
    }

    @Test("Skips non-mp4 progressive entries")
    func skipsNonMp4() {
        let formats = [
            format(itag: 43, mime: "video/webm; codecs=\"vp8, vorbis\"", avgBps: 500_000),
        ]
        #expect(LivePlayerExtractor.pickMuxedURL(from: formats) == nil)
    }
}

// MARK: - Playability gating

@Suite("LivePlayerExtractor.validatePlayability")
struct LivePlayerExtractorPlayabilityTests {

    @Test("OK status passes through without throwing")
    func okPasses() throws {
        let status = InnerTubePlayerResponse.PlayabilityStatus(status: "OK", reason: nil)
        try LivePlayerExtractor.validatePlayability(status)
    }

    @Test("LOGIN_REQUIRED maps to videoUnavailable")
    func loginRequiredMapsToUnavailable() {
        let status = InnerTubePlayerResponse.PlayabilityStatus(status: "LOGIN_REQUIRED", reason: "Sign in")
        #expect(throws: YouTubeServiceError.videoUnavailable) {
            try LivePlayerExtractor.validatePlayability(status)
        }
    }

    @Test("UNPLAYABLE / ERROR / LIVE_STREAM_OFFLINE all map to videoUnavailable")
    func unplayableVariants() {
        for raw in ["UNPLAYABLE", "ERROR", "LIVE_STREAM_OFFLINE", "AGE_VERIFICATION_REQUIRED", "CONTENT_CHECK_REQUIRED"] {
            let status = InnerTubePlayerResponse.PlayabilityStatus(status: raw, reason: nil)
            #expect(throws: YouTubeServiceError.videoUnavailable) {
                try LivePlayerExtractor.validatePlayability(status)
            }
        }
    }

    @Test("Unknown status defensively maps to networkFailure")
    func unknownDefensive() {
        let status = InnerTubePlayerResponse.PlayabilityStatus(status: "UNKNOWN_NEW_STATUS", reason: nil)
        #expect(throws: YouTubeServiceError.networkFailure) {
            try LivePlayerExtractor.validatePlayability(status)
        }
    }

    @Test("Nil status passes through (caller decides what to do with empty streamingData)")
    func nilStatusPasses() throws {
        try LivePlayerExtractor.validatePlayability(nil)
    }
}

// MARK: - End-to-end resolve via FakePlayerURLProtocol + JSON fixtures

@Suite("LivePlayerExtractor.resolve (URLProtocol fake + JSON fixtures)")
struct LivePlayerExtractorResolveTests {

    @Test("Audio-only adaptive format → .audioOnly resolution")
    func audioOnlyHappyPath() async throws {
        FakePlayerURLProtocol.reset()
        FakePlayerURLProtocol.stub = .body(statusCode: 200, body: try loadPlayerFixture("innertube-player-android-vr-audio-only-ok"))
        defer { FakePlayerURLProtocol.reset() }

        let extractor = LivePlayerExtractor(transport: fakeSession(), visitorDataProvider: { nil })
        let resolution = try await extractor.resolve(videoId: "abc", quality: .high)

        #expect(resolution.kind == .audioOnly)
        #expect(resolution.audioURL.absoluteString.contains("itag=140"))
    }

    @Test("Empty adaptiveFormats + hlsManifestUrl → .livestream resolution")
    func livestreamPath() async throws {
        FakePlayerURLProtocol.reset()
        FakePlayerURLProtocol.stub = .body(statusCode: 200, body: try loadPlayerFixture("innertube-player-android-vr-livestream"))
        defer { FakePlayerURLProtocol.reset() }

        let extractor = LivePlayerExtractor(transport: fakeSession(), visitorDataProvider: { nil })
        let resolution = try await extractor.resolve(videoId: "live", quality: .medium)

        #expect(resolution.kind == .livestream)
        #expect(resolution.audioURL.absoluteString == "https://manifest.googlevideo.com/hls/manifest.m3u8")
    }

    @Test("Only progressive formats → .muxed fallback")
    func muxedFallback() async throws {
        FakePlayerURLProtocol.reset()
        FakePlayerURLProtocol.stub = .body(statusCode: 200, body: try loadPlayerFixture("innertube-player-android-vr-muxed-only"))
        defer { FakePlayerURLProtocol.reset() }

        let extractor = LivePlayerExtractor(transport: fakeSession(), visitorDataProvider: { nil })
        let resolution = try await extractor.resolve(videoId: "old", quality: .medium)

        #expect(resolution.kind == .muxed)
        #expect(resolution.audioURL.absoluteString == "https://example.invalid/muxed.mp4")
    }

    @Test("playabilityStatus.status == LOGIN_REQUIRED → videoUnavailable")
    func loginRequiredThrows() async throws {
        FakePlayerURLProtocol.reset()
        FakePlayerURLProtocol.stub = .body(statusCode: 200, body: try loadPlayerFixture("innertube-player-android-vr-login-required"))
        defer { FakePlayerURLProtocol.reset() }

        let extractor = LivePlayerExtractor(transport: fakeSession(), visitorDataProvider: { nil })
        await #expect(throws: YouTubeServiceError.videoUnavailable) {
            _ = try await extractor.resolve(videoId: "agegated", quality: .medium)
        }
    }

    @Test("Empty streamingData throws noStreamFound")
    func emptyStreamingDataThrows() async throws {
        FakePlayerURLProtocol.reset()
        FakePlayerURLProtocol.stub = .body(statusCode: 200, body: try loadPlayerFixture("innertube-player-android-vr-empty-streaming-data"))
        defer { FakePlayerURLProtocol.reset() }

        let extractor = LivePlayerExtractor(transport: fakeSession(), visitorDataProvider: { nil })
        await #expect(throws: YouTubeServiceError.noStreamFound) {
            _ = try await extractor.resolve(videoId: "empty", quality: .medium)
        }
    }

    @Test("URLError (no internet) maps to networkFailure")
    func networkErrorMapsToNetworkFailure() async {
        FakePlayerURLProtocol.reset()
        FakePlayerURLProtocol.stub = .error(URLError(.notConnectedToInternet) as NSError)
        defer { FakePlayerURLProtocol.reset() }

        let extractor = LivePlayerExtractor(transport: fakeSession(), visitorDataProvider: { nil })
        await #expect(throws: YouTubeServiceError.networkFailure) {
            _ = try await extractor.resolve(videoId: "x", quality: .medium)
        }
    }

    @Test("Non-JSON body (e.g. consent / captcha page) maps to networkFailure without crashing")
    func nonJSONBodyMapsToNetworkFailure() async {
        FakePlayerURLProtocol.reset()
        let html = "<html><body>You are being redirected to consent</body></html>".data(using: .utf8)!
        FakePlayerURLProtocol.stub = .body(statusCode: 200, body: html)
        defer { FakePlayerURLProtocol.reset() }

        let extractor = LivePlayerExtractor(transport: fakeSession(), visitorDataProvider: { nil })
        await #expect(throws: YouTubeServiceError.networkFailure) {
            _ = try await extractor.resolve(videoId: "consented", quality: .medium)
        }
    }
}

// MARK: - VisitorIDCache cooperative coalescing

@Suite("VisitorIDCache cooperative coalescing")
struct VisitorIDCacheTests {

    @Test("Concurrent first fetches collapse to a single transport hit")
    func concurrentFetchesCoalesce() async throws {
        FakePlayerURLProtocol.reset()
        let body = #"{"responseContext":{"visitorData":"V0_TOKEN"}}"#.data(using: .utf8)!
        FakePlayerURLProtocol.stub = .body(statusCode: 200, body: body)
        defer { FakePlayerURLProtocol.reset() }

        let cache = VisitorIDCache()
        let session = fakeSession()

        // Fan out 5 concurrent fetches before any of them completes; the
        // actor's `inFlight` slot must collapse them into one HTTP call.
        async let a = cache.fetch(using: session)
        async let b = cache.fetch(using: session)
        async let c = cache.fetch(using: session)
        async let d = cache.fetch(using: session)
        async let e = cache.fetch(using: session)
        let results = await [a, b, c, d, e]

        #expect(results.allSatisfy { $0 == "V0_TOKEN" })
        #expect(FakePlayerURLProtocol.loadCount == 1)
    }

    @Test("Subsequent fetches hit the cache (zero additional transport calls)")
    func cachedFetchesNoLongerHitTransport() async throws {
        FakePlayerURLProtocol.reset()
        let body = #"{"responseContext":{"visitorData":"V0_TOKEN"}}"#.data(using: .utf8)!
        FakePlayerURLProtocol.stub = .body(statusCode: 200, body: body)
        defer { FakePlayerURLProtocol.reset() }

        let cache = VisitorIDCache()
        let session = fakeSession()
        _ = await cache.fetch(using: session)
        _ = await cache.fetch(using: session)
        _ = await cache.fetch(using: session)

        #expect(FakePlayerURLProtocol.loadCount == 1)
    }
}
