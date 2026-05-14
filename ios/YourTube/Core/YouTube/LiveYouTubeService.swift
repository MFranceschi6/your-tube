import Foundation

// MARK: - HTTPDataTasking

/// Thin protocol over `URLSession` so the network layer can be replaced in tests.
protocol HTTPDataTasking: Sendable {
    func data(for request: URLRequest) async throws -> (Data, URLResponse)
}

extension URLSession: HTTPDataTasking {}

// MARK: - LiveYouTubeService

/// Production implementation of `YouTubeServiceProtocol`.
///
/// - Search: calls the YouTube InnerTube `/search` endpoint and parses video results.
/// - Stream resolution: delegates to a ``PlayerExtracting`` (default
///   ``LivePlayerExtractor`` — direct InnerTube `/player` ANDROID_VR call,
///   YT-0162). Audio-only streams are wrapped with the YT-0157 HLS proxy;
///   livestreams + muxed fallback bypass the proxy.
final class LiveYouTubeService: YouTubeServiceProtocol {

    // MARK: - Dependencies

    /// Injected to allow faking in tests. Defaults to the shared session.
    private let transport: any HTTPDataTasking
    /// Injected to allow faking in tests. Defaults to a direct-InnerTube
    /// ANDROID_VR resolver — no JS signature solver, no `__js` race.
    private let playerClient: any PlayerExtracting
    /// YT-0298 — Mix queue client (initial page + continuation pages).
    private let mixClient: MixClient

    init(
        transport: any HTTPDataTasking = URLSession.shared,
        playerClient: any PlayerExtracting = LivePlayerExtractor()
    ) {
        self.transport = transport
        self.playerClient = playerClient
        self.mixClient = MixClient(transport: transport)
    }

    // MARK: - Search

    func search(query: String, maxResults: Int = 20) async throws -> [SearchResult] {
        let rawResults: [InnerTubeSearchResult]
        do {
            rawResults = try await fetchInnerTubeSearch(query: query)
        } catch let error as YouTubeServiceError {
            throw error
        } catch {
            if isNetworkError(error) {
                throw YouTubeServiceError.networkFailure
            }
            throw YouTubeServiceError.searchFailed
        }

        return rawResults
            .prefix(maxResults)
            .map { r in
                SearchResult(
                    videoId: r.videoId,
                    title: r.title,
                    channel: r.channel,
                    durationSec: r.durationSec,
                    thumbnailUrl: r.thumbnailUrl
                )
            }
    }

    // MARK: - Stream resolution

    func resolveStreamURL(videoId: String, quality: AudioQuality) async throws -> ResolvedStream {
        // Single InnerTube /player ANDROID_VR call. Pre-signed URLs, no JS
        // signature solver, no `__js` cache race (closes YT-0071 + YT-0052).
        let resolution = try await playerClient.resolve(videoId: videoId, quality: quality)
        switch resolution.kind {
        case .audioOnly:
            return await proxiedAudioStream(for: resolution.audioURL)
        case .muxed:
            return ResolvedStream(url: resolution.audioURL, isMuxedFallback: true)
        case .livestream:
            // HLS is already segmented; AVPlayer's HLS engine plays it
            // natively. The YT-0157 fmp4 proxy is unnecessary here.
            return ResolvedStream(url: resolution.audioURL, isMuxedFallback: false)
        }
    }

    /// Wraps a fragmented-mp4 audio-only googlevideo URL with the YT-0157
    /// HLS proxy so AVPlayer's HLS engine reads byterange entries from a
    /// synthesised m3u8 — sidesteps the multi-hour stall AVPlayer's non-HLS
    /// fmp4 parser hits on tracks with thousands of `moof+mdat` fragments.
    ///
    /// Falls back to the unproxied URL on any proxy-setup failure (HEAD non-2xx,
    /// missing/unparseable sidx). Short tracks (~tens of fragments) play fine
    /// without the proxy, so a fallback preserves the YT-0046 baseline rather
    /// than failing the whole resolve.
    private func proxiedAudioStream(for originURL: URL) async -> ResolvedStream {
        do {
            let proxied = try await HLSProxy.prepareProxiedPlayback(originURL: originURL)
            return ResolvedStream(
                url: proxied.playlistURL,
                isMuxedFallback: false,
                proxyLoader: proxied.loader
            )
        } catch {
            return ResolvedStream(url: originURL, isMuxedFallback: false)
        }
    }

    // MARK: - Mix queue (YT-0298)

    func getMixQueueWithContinuation(videoId: String) async -> MixQueueResult {
        await mixClient.getMixQueueWithContinuation(videoId: videoId)
    }

    func getMixContinuation(token: String) async -> MixQueueResult {
        await mixClient.getMixContinuation(token: token)
    }

    // MARK: - Private: InnerTube search

    private func fetchInnerTubeSearch(query: String) async throws -> [InnerTubeSearchResult] {
        guard var components = URLComponents(string: "https://www.youtube.com/youtubei/v1/search") else {
            throw YouTubeServiceError.searchFailed
        }
        components.queryItems = [
            URLQueryItem(name: "key", value: "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"),
            URLQueryItem(name: "prettyPrint", value: "false"),
        ]
        guard let url = components.url else {
            throw YouTubeServiceError.searchFailed
        }

        let body: [String: Any] = [
            "context": [
                "client": [
                    "clientName": "WEB",
                    "clientVersion": "2.20260114.08.00",
                ]
            ],
            "query": query,
        ]

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("Mozilla/5.0", forHTTPHeaderField: "User-Agent")
        request.setValue("en-US,en", forHTTPHeaderField: "Accept-Language")
        request.httpBody = try JSONSerialization.data(withJSONObject: body)

        let data: Data
        do {
            let (responseData, _) = try await transport.data(for: request)
            data = responseData
        } catch {
            throw YouTubeServiceError.networkFailure
        }

        return parseSearchResponse(data)
    }

    /// Walks the InnerTube search JSON response and extracts `videoRenderer` items.
    private func parseSearchResponse(_ data: Data) -> [InnerTubeSearchResult] {
        guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return []
        }

        // Path: contents > twoColumnSearchResultsRenderer > primaryContents >
        //       sectionListRenderer > contents[] > itemSectionRenderer > contents[] > videoRenderer
        guard
            let contents = json["contents"] as? [String: Any],
            let twoColumn = contents["twoColumnSearchResultsRenderer"] as? [String: Any],
            let primary = twoColumn["primaryContents"] as? [String: Any],
            let sectionList = primary["sectionListRenderer"] as? [String: Any],
            let sections = sectionList["contents"] as? [[String: Any]]
        else { return [] }

        var results: [InnerTubeSearchResult] = []

        for section in sections {
            guard
                let itemSection = section["itemSectionRenderer"] as? [String: Any],
                let items = itemSection["contents"] as? [[String: Any]]
            else { continue }

            for item in items {
                guard let renderer = item["videoRenderer"] as? [String: Any] else { continue }
                if let result = InnerTubeSearchResult(renderer: renderer) {
                    results.append(result)
                }
            }
        }

        return results
    }

    // MARK: - Private: helpers

    private func isNetworkError(_ error: Error) -> Bool {
        let nsError = error as NSError
        return nsError.domain == NSURLErrorDomain
    }
}

// MARK: - Internal DTO for InnerTube search results

private struct InnerTubeSearchResult {
    let videoId: String
    let title: String
    let channel: String
    let durationSec: Int
    let thumbnailUrl: String

    init?(renderer: [String: Any]) {
        guard let videoId = renderer["videoId"] as? String else { return nil }

        self.videoId = videoId

        // Title
        let titleRuns = (renderer["title"] as? [String: Any])?["runs"] as? [[String: Any]]
        self.title = titleRuns?.compactMap { $0["text"] as? String }.joined() ?? ""

        // Channel
        let ownerText = (renderer["ownerText"] as? [String: Any])?["runs"] as? [[String: Any]]
        self.channel = ownerText?.compactMap { $0["text"] as? String }.first ?? ""

        // Duration in seconds
        let lengthText = (renderer["lengthText"] as? [String: Any])?["simpleText"] as? String
        self.durationSec = InnerTubeSearchResult.parseDuration(lengthText)

        // Thumbnail — prefer mqdefault, fall back to last available
        let thumbs = (renderer["thumbnail"] as? [String: Any])?["thumbnails"] as? [[String: Any]] ?? []
        let mqDefault = "https://i.ytimg.com/vi/\(videoId)/mqdefault.jpg"
        self.thumbnailUrl = thumbs.last?["url"] as? String ?? mqDefault
    }

    /// Parses "H:MM:SS" or "M:SS" into total seconds. Returns 0 for live/unknown.
    static func parseDuration(_ text: String?) -> Int {
        guard let text else { return 0 }
        let parts = text.components(separatedBy: ":").compactMap { Int($0) }
        switch parts.count {
        case 2: return parts[0] * 60 + parts[1]
        case 3: return parts[0] * 3600 + parts[1] * 60 + parts[2]
        default: return 0
        }
    }
}
