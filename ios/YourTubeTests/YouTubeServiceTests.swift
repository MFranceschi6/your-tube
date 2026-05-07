import Testing
import Foundation
@testable import YourTube

// MARK: - Fixtures

/// Minimal InnerTube-shaped search response with two video results.
private let searchFixtureJSON = """
{
  "contents": {
    "twoColumnSearchResultsRenderer": {
      "primaryContents": {
        "sectionListRenderer": {
          "contents": [
            {
              "itemSectionRenderer": {
                "contents": [
                  {
                    "videoRenderer": {
                      "videoId": "dQw4w9WgXcQ",
                      "title": { "runs": [{ "text": "Never Gonna Give You Up" }] },
                      "ownerText": { "runs": [{ "text": "Rick Astley" }] },
                      "lengthText": { "simpleText": "3:33" },
                      "thumbnail": {
                        "thumbnails": [
                          { "url": "https://i.ytimg.com/vi/dQw4w9WgXcQ/mqdefault.jpg", "width": 320, "height": 180 }
                        ]
                      }
                    }
                  },
                  {
                    "videoRenderer": {
                      "videoId": "jfKfPfyJRdk",
                      "title": { "runs": [{ "text": "lofi hip hop radio" }] },
                      "ownerText": { "runs": [{ "text": "Lofi Girl" }] },
                      "lengthText": { "simpleText": "0:00" },
                      "thumbnail": {
                        "thumbnails": [
                          { "url": "https://i.ytimg.com/vi/jfKfPfyJRdk/mqdefault.jpg", "width": 320, "height": 180 }
                        ]
                      }
                    }
                  }
                ]
              }
            }
          ]
        }
      }
    }
  }
}
"""

/// Fixture with a non-videoRenderer item (e.g. ad) that should be silently skipped.
private let mixedFixtureJSON = """
{
  "contents": {
    "twoColumnSearchResultsRenderer": {
      "primaryContents": {
        "sectionListRenderer": {
          "contents": [
            {
              "itemSectionRenderer": {
                "contents": [
                  { "promotedVideoRenderer": { "videoId": "ad123" } },
                  {
                    "videoRenderer": {
                      "videoId": "abc123",
                      "title": { "runs": [{ "text": "Valid Video" }] },
                      "ownerText": { "runs": [{ "text": "Channel" }] },
                      "lengthText": { "simpleText": "1:23" },
                      "thumbnail": { "thumbnails": [] }
                    }
                  }
                ]
              }
            }
          ]
        }
      }
    }
  }
}
"""

/// Empty / unexpected shape that should produce no results without crashing.
private let emptyFixtureJSON = "{}"

// MARK: - Fake HTTPDataTasking

/// Minimal transport fake that returns a pre-set `Data` payload.
/// Avoids real network calls in tests.
private struct StubTransport: HTTPDataTasking {
    private let stubbedData: Data

    init(data: Data) {
        self.stubbedData = data
    }

    func data(for request: URLRequest) async throws -> (Data, URLResponse) {
        let response = HTTPURLResponse(
            url: request.url ?? URL(string: "https://example.com")!,
            statusCode: 200,
            httpVersion: nil,
            headerFields: nil
        )!
        return (stubbedData, response)
    }
}

private struct ErrorTransport: HTTPDataTasking {
    func data(for request: URLRequest) async throws -> (Data, URLResponse) {
        throw NSError(domain: NSURLErrorDomain, code: NSURLErrorNotConnectedToInternet)
    }
}

// MARK: - Fake service for stream resolution logic

/// A test double for `YouTubeServiceProtocol` that returns pre-set results.
/// Used in downstream consumer tests (e.g. SearchViewModel) — included here for completeness.
final class FakeYouTubeService: YouTubeServiceProtocol, @unchecked Sendable {
    var searchResult: Result<[SearchResult], YouTubeServiceError> = .success([])
    var streamResult: Result<ResolvedStream, YouTubeServiceError> = .failure(.noStreamFound)

    func search(query: String, maxResults: Int) async throws -> [SearchResult] {
        switch searchResult {
        case .success(let r): return r
        case .failure(let e): throw e
        }
    }

    func resolveStreamURL(videoId: String, quality: AudioQuality) async throws -> ResolvedStream {
        switch streamResult {
        case .success(let r): return r
        case .failure(let e): throw e
        }
    }
}

// MARK: - AudioQuality tests

@Suite("AudioQuality")
struct AudioQualityTests {

    @Test("low < medium < high ordering")
    func ordering() {
        #expect(AudioQuality.low < AudioQuality.medium)
        #expect(AudioQuality.medium < AudioQuality.high)
        #expect(!(AudioQuality.high < AudioQuality.low))
    }

    @Test("rawValue matches expected kbps")
    func rawValues() {
        #expect(AudioQuality.low.rawValue == 48)
        #expect(AudioQuality.medium.rawValue == 128)
        #expect(AudioQuality.high.rawValue == 256)
    }
}

// MARK: - Search fixture decoding

@Suite("YouTubeService search fixture decoding")
struct YouTubeServiceSearchTests {

    @Test("Fixture decoding returns two results with correct fields")
    func decodesFixtureResults() async throws {
        let data = try #require(searchFixtureJSON.data(using: .utf8))
        let session = StubTransport(data: data)
        let service = LiveYouTubeService(transport: session)

        let results = try await service.search(query: "test", maxResults: 20)

        #expect(results.count == 2)

        let first = results[0]
        #expect(first.videoId == "dQw4w9WgXcQ")
        #expect(first.title == "Never Gonna Give You Up")
        #expect(first.channel == "Rick Astley")
        #expect(first.durationSec == 213)  // 3*60 + 33
        #expect(first.thumbnailUrl == "https://i.ytimg.com/vi/dQw4w9WgXcQ/mqdefault.jpg")

        let second = results[1]
        #expect(second.videoId == "jfKfPfyJRdk")
        #expect(second.durationSec == 0)  // "0:00" → live / unknown
    }

    @Test("maxResults caps number of returned results")
    func maxResultsCap() async throws {
        let data = try #require(searchFixtureJSON.data(using: .utf8))
        let session = StubTransport(data: data)
        let service = LiveYouTubeService(transport: session)

        let results = try await service.search(query: "test", maxResults: 1)

        #expect(results.count == 1)
        #expect(results[0].videoId == "dQw4w9WgXcQ")
    }

    @Test("Non-video renderer items are silently skipped")
    func skipsNonVideoRenderers() async throws {
        let data = try #require(mixedFixtureJSON.data(using: .utf8))
        let session = StubTransport(data: data)
        let service = LiveYouTubeService(transport: session)

        let results = try await service.search(query: "test", maxResults: 20)

        #expect(results.count == 1)
        #expect(results[0].videoId == "abc123")
    }

    @Test("Empty / unexpected JSON shape returns empty array without crash")
    func emptyResponseReturnsEmpty() async throws {
        let data = try #require(emptyFixtureJSON.data(using: .utf8))
        let session = StubTransport(data: data)
        let service = LiveYouTubeService(transport: session)

        let results = try await service.search(query: "test", maxResults: 20)

        #expect(results.isEmpty)
    }

    @Test("Network error maps to YouTubeServiceError.networkFailure")
    func networkErrorMapsCorrectly() async throws {
        let service = LiveYouTubeService(transport: ErrorTransport())

        do {
            _ = try await service.search(query: "test", maxResults: 20)
            Issue.record("Expected networkFailure to be thrown")
        } catch YouTubeServiceError.networkFailure {
            // Expected path.
        } catch {
            Issue.record("Expected YouTubeServiceError.networkFailure but got: \(error)")
        }
    }
}

// MARK: - Duration parsing

@Suite("Duration parsing")
struct DurationParsingTests {

    // We access the internal parse logic via the fixture round-trip since
    // `InnerTubeSearchResult` is private to the module. These tests exercise
    // the behavior through public API.

    @Test("M:SS format parses correctly")
    func minuteSecondFormat() async throws {
        // 3:33 → 213 s
        let data = try #require(searchFixtureJSON.data(using: .utf8))
        let session = StubTransport(data: data)
        let service = LiveYouTubeService(transport: session)
        let results = try await service.search(query: "q", maxResults: 20)
        #expect(results[0].durationSec == 213)
    }

    @Test("0:00 live-stream indicator parses to 0")
    func zeroForLive() async throws {
        let data = try #require(searchFixtureJSON.data(using: .utf8))
        let session = StubTransport(data: data)
        let service = LiveYouTubeService(transport: session)
        let results = try await service.search(query: "q", maxResults: 20)
        #expect(results[1].durationSec == 0)
    }

    @Test("1:23 format parses to 83 seconds")
    func oneMinute23Seconds() async throws {
        let data = try #require(mixedFixtureJSON.data(using: .utf8))
        let session = StubTransport(data: data)
        let service = LiveYouTubeService(transport: session)
        let results = try await service.search(query: "q", maxResults: 20)
        #expect(results[0].durationSec == 83)
    }
}

// MARK: - Stream resolution logic

@Suite("Stream resolution — FakeYouTubeService")
struct StreamResolutionTests {

    @Test("FakeYouTubeService returns pre-set search results")
    func fakeSearchResults() async throws {
        let expected = SearchResult(
            videoId: "abc",
            title: "Test",
            channel: "Ch",
            durationSec: 60,
            thumbnailUrl: "https://example.com/t.jpg"
        )
        let fake = FakeYouTubeService()
        fake.searchResult = .success([expected])

        let results = try await fake.search(query: "q", maxResults: 5)
        #expect(results.count == 1)
        #expect(results[0] == expected)
    }

    @Test("FakeYouTubeService propagates search error")
    func fakeSearchError() async throws {
        let fake = FakeYouTubeService()
        fake.searchResult = .failure(.searchFailed)

        do {
            _ = try await fake.search(query: "q", maxResults: 5)
            Issue.record("Expected searchFailed")
        } catch YouTubeServiceError.searchFailed {
            // Expected.
        }
    }

    @Test("FakeYouTubeService returns pre-set audio-only stream")
    func fakeAudioOnlyStream() async throws {
        let url = URL(string: "https://example.com/audio.mp4")!
        let fake = FakeYouTubeService()
        fake.streamResult = .success(ResolvedStream(url: url, isMuxedFallback: false))

        let resolved = try await fake.resolveStreamURL(videoId: "vid", quality: .medium)
        #expect(resolved.url == url)
        #expect(resolved.isMuxedFallback == false)
    }

    @Test("FakeYouTubeService returns muxed fallback stream when requested")
    func fakeMuxedFallbackStream() async throws {
        let url = URL(string: "https://example.com/muxed.mp4")!
        let fake = FakeYouTubeService()
        fake.streamResult = .success(ResolvedStream(url: url, isMuxedFallback: true))

        let resolved = try await fake.resolveStreamURL(videoId: "vid", quality: .low)
        #expect(resolved.isMuxedFallback == true)
    }

    @Test("FakeYouTubeService propagates noStreamFound error")
    func fakeNoStreamFound() async throws {
        let fake = FakeYouTubeService()
        fake.streamResult = .failure(.noStreamFound)

        do {
            _ = try await fake.resolveStreamURL(videoId: "vid", quality: .medium)
            Issue.record("Expected noStreamFound")
        } catch YouTubeServiceError.noStreamFound {
            // Expected.
        }
    }
}

// MARK: - YouTubeServiceError

@Suite("YouTubeServiceError")
struct YouTubeServiceErrorTests {

    @Test("All cases have non-nil localised descriptions")
    func localisedDescriptions() {
        let cases: [YouTubeServiceError] = [.networkFailure, .videoUnavailable, .noStreamFound, .searchFailed]
        for error in cases {
            #expect(error.errorDescription != nil)
        }
    }

    @Test("Equatable conformance works")
    func equatable() {
        #expect(YouTubeServiceError.networkFailure == YouTubeServiceError.networkFailure)
        #expect(YouTubeServiceError.noStreamFound != YouTubeServiceError.searchFailed)
    }
}

// MARK: - ResolvedStream

@Suite("ResolvedStream")
struct ResolvedStreamTests {

    @Test("isMuxedFallback false for audio-only")
    func audioOnly() {
        let stream = ResolvedStream(url: URL(string: "https://example.com/a.mp4")!, isMuxedFallback: false)
        #expect(!stream.isMuxedFallback)
    }

    @Test("isMuxedFallback true for progressive fallback")
    func muxed() {
        let stream = ResolvedStream(url: URL(string: "https://example.com/v.mp4")!, isMuxedFallback: true)
        #expect(stream.isMuxedFallback)
    }
}

// MARK: - PlayerExtracting fake (YT-0162 — replaces StreamExtracting)

/// In-memory `PlayerExtracting` for `LiveYouTubeService` resolution tests.
/// Returns the configured payload or throws the configured error per resolve.
private final class FakePlayerExtractor: PlayerExtracting, @unchecked Sendable {
    var result: Result<PlayerResolution, Error> = .failure(YouTubeServiceError.noStreamFound)
    private(set) var calls: [(videoId: String, quality: AudioQuality)] = []

    func resolve(videoId: String, quality: AudioQuality) async throws -> PlayerResolution {
        calls.append((videoId, quality))
        return try result.get()
    }
}

// MARK: - LiveYouTubeService.resolveStreamURL (YT-0162 InnerTube /player wiring)

@Suite("LiveYouTubeService resolution branches (YT-0162)")
struct LiveYouTubeServiceResolutionTests {

    private func makeService(_ extractor: FakePlayerExtractor) -> LiveYouTubeService {
        // Search transport is unused on the resolution path; pass a quiet stub.
        let data = "{}".data(using: .utf8)!
        return LiveYouTubeService(
            transport: StubTransport(data: data),
            playerClient: extractor
        )
    }

    @Test("Livestream resolution returns the manifest URL with isMuxedFallback=false")
    func livestreamPath() async throws {
        let manifest = try #require(URL(string: "https://manifest.googlevideo.com/hls/manifest.m3u8"))
        let extractor = FakePlayerExtractor()
        extractor.result = .success(PlayerResolution(audioURL: manifest, kind: .livestream))

        let resolved = try await makeService(extractor)
            .resolveStreamURL(videoId: "live123", quality: .medium)

        #expect(resolved.url == manifest)
        // HLS is already segmented — engine treats it as audio-only.
        #expect(resolved.isMuxedFallback == false)
        // Livestreams skip the YT-0157 fmp4 proxy.
        #expect(resolved.proxyLoader == nil)
    }

    @Test("Muxed fallback resolution flags isMuxedFallback=true and skips the proxy")
    func muxedPath() async throws {
        let muxed = try #require(URL(string: "https://googlevideo.com/videoplayback?itag=18"))
        let extractor = FakePlayerExtractor()
        extractor.result = .success(PlayerResolution(audioURL: muxed, kind: .muxed))

        let resolved = try await makeService(extractor)
            .resolveStreamURL(videoId: "muxedOnly", quality: .medium)

        #expect(resolved.url == muxed)
        #expect(resolved.isMuxedFallback == true)
        #expect(resolved.proxyLoader == nil)
    }

    @Test("Audio-only resolution attempts the proxy and falls back to the unproxied URL on proxy failure")
    func audioOnlyFallsBackWhenProxyFails() async throws {
        // Proxy setup goes through a real `URLSession.shared` HEAD; the
        // `example.invalid` host is unresolvable so the proxy throws and
        // `proxiedAudioStream(for:)` falls back to the unproxied URL.
        let direct = try #require(URL(string: "https://example.invalid/audio.mp4"))
        let extractor = FakePlayerExtractor()
        extractor.result = .success(PlayerResolution(audioURL: direct, kind: .audioOnly))

        let resolved = try await makeService(extractor)
            .resolveStreamURL(videoId: "audio", quality: .medium)

        #expect(resolved.url == direct)
        #expect(resolved.isMuxedFallback == false)
        // Proxy failed → no loader.
        #expect(resolved.proxyLoader == nil)
    }

    @Test("Resolver propagates videoUnavailable from the player client")
    func videoUnavailableIsRethrown() async throws {
        let extractor = FakePlayerExtractor()
        extractor.result = .failure(YouTubeServiceError.videoUnavailable)

        do {
            _ = try await makeService(extractor)
                .resolveStreamURL(videoId: "deleted", quality: .medium)
            Issue.record("Expected videoUnavailable to be thrown")
        } catch YouTubeServiceError.videoUnavailable {
            // Expected.
        } catch {
            Issue.record("Expected videoUnavailable, got: \(error)")
        }
    }

    @Test("Resolver propagates noStreamFound from the player client")
    func noStreamFoundIsRethrown() async throws {
        let extractor = FakePlayerExtractor()
        extractor.result = .failure(YouTubeServiceError.noStreamFound)

        do {
            _ = try await makeService(extractor)
                .resolveStreamURL(videoId: "vid", quality: .medium)
            Issue.record("Expected noStreamFound to be thrown")
        } catch YouTubeServiceError.noStreamFound {
            // Expected.
        } catch {
            Issue.record("Expected noStreamFound, got: \(error)")
        }
    }

    @Test("Resolver forwards the requested quality to the player client")
    func qualityForwarded() async throws {
        let direct = try #require(URL(string: "https://example.invalid/audio.mp4"))
        let extractor = FakePlayerExtractor()
        extractor.result = .success(PlayerResolution(audioURL: direct, kind: .audioOnly))

        _ = try await makeService(extractor)
            .resolveStreamURL(videoId: "x", quality: .low)

        #expect(extractor.calls.first?.quality == .low)
        #expect(extractor.calls.first?.videoId == "x")
    }
}
