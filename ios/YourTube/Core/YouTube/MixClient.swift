import Foundation

// MARK: - MixQueueResult

/// Paged Mix queue result.
///
/// `items` is the ordered list of tracks parsed from the current page (initial
/// fetch or continuation page). `nextToken` is the continuation token to feed
/// into `getMixContinuation(token:)` for the next page; `nil` means the server
/// did not return a continuation and the consumer should stop paginating.
///
/// See `docs/mix-queue.md §Continuation / Pagination` for the full contract.
struct MixQueueResult: Sendable {
    let items: [SearchResult]
    let nextToken: String?

    static let empty = MixQueueResult(items: [], nextToken: nil)
}

// MARK: - MixClient

/// Fetches the initial Mix queue page and continuation pages from the InnerTube
/// `/next` endpoint. Both operations live here to keep all `/next`-for-Mix
/// concerns in one file (mirroring `RelatedVideoClient` on Android).
///
/// `getMixQueueWithContinuation(videoId:)` is the preferred entry-point for callers
/// that need to extend the Mix beyond the initial page (YT-0295 / YT-0298).
/// `getMixContinuation(token:)` walks subsequent pages.
///
/// All public methods return `MixQueueResult.empty` on HTTP failure, parse error,
/// or a blank token — they never throw.
final class MixClient: @unchecked Sendable {

    private let transport: any HTTPDataTasking

    init(transport: any HTTPDataTasking = URLSession.shared) {
        self.transport = transport
    }

    // MARK: - Initial fetch

    /// Same wire call as the legacy `getMixQueue` but also surfaces the first
    /// continuation token (if any) so the consumer can lazy-load the next Mix
    /// page via `getMixContinuation(token:)`.
    ///
    /// The first entry in `items` is the seed track itself (indices 0) —
    /// consumers should skip it (already at queue index 0 from `playNow`) and
    /// append `items.dropFirst()` to the playback queue.
    func getMixQueueWithContinuation(videoId: String) async -> MixQueueResult {
        guard !videoId.isEmpty else { return .empty }
        let body = buildMixBody(videoId: videoId)
        guard let data = await post(body: body) else { return .empty }
        return parseMixQueue(data) ?? .empty
    }

    // MARK: - Continuation fetch

    /// Fetches the next Mix page given a `token` from a previous
    /// `getMixQueueWithContinuation` or `getMixContinuation` call.
    ///
    /// The request body carries only `{ context, continuation }` — `videoId`
    /// and `playlistId` are NOT re-sent; the token encodes which Mix is being
    /// walked. Returns `MixQueueResult.empty` on blank token, HTTP failure, or
    /// parse error.
    func getMixContinuation(token: String) async -> MixQueueResult {
        guard !token.trimmingCharacters(in: .whitespaces).isEmpty else { return .empty }
        let body = buildContinuationBody(token: token)
        guard let data = await post(body: body) else { return .empty }
        return parseMixContinuation(data) ?? .empty
    }

    // MARK: - HTTP

    private func post(body: String) async -> Data? {
        guard
            let url = URL(string: Self.nextURL),
            let bodyData = body.data(using: .utf8)
        else { return nil }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.httpBody = bodyData
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue(Self.userAgent, forHTTPHeaderField: "User-Agent")
        request.setValue("1", forHTTPHeaderField: "X-YouTube-Client-Name")
        request.setValue(Self.clientVersion, forHTTPHeaderField: "X-YouTube-Client-Version")

        do {
            let (data, response) = try await transport.data(for: request)
            guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
                return nil
            }
            return data
        } catch {
            return nil
        }
    }

    // MARK: - Request bodies

    private func buildMixBody(videoId: String) -> String {
        // swiftlint:disable:next line_length
        return #"{"context":{"client":{"clientName":"WEB","clientVersion":"\#(Self.clientVersion)","hl":"en"}},"videoId":"\#(videoId)","playlistId":"RD\#(videoId)"}"#
    }

    private func buildContinuationBody(token: String) -> String {
        // swiftlint:disable:next line_length
        return #"{"context":{"client":{"clientName":"WEB","clientVersion":"\#(Self.clientVersion)","hl":"en"}},"continuation":"\#(token)"}"#
    }

    // MARK: - Parsers

    private func parseMixQueue(_ data: Data) -> MixQueueResult? {
        guard let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return nil
        }
        guard
            let contents = root["contents"] as? [String: Any],
            let twoCol = contents["twoColumnWatchNextResults"] as? [String: Any],
            let playlistOuter = twoCol["playlist"] as? [String: Any],
            let playlist = playlistOuter["playlist"] as? [String: Any]
        else { return nil }

        let items = parsePanelContents(playlist)
        let nextToken = extractContinuationToken(from: playlist)
        return MixQueueResult(items: items, nextToken: nextToken)
    }

    private func parseMixContinuation(_ data: Data) -> MixQueueResult? {
        guard let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return nil
        }
        // Continuation responses replace the twoColumnWatchNextResults.playlist
        // wrapper with continuationContents.playlistPanelContinuation.
        guard
            let continuation = root["continuationContents"] as? [String: Any],
            let panel = continuation["playlistPanelContinuation"] as? [String: Any]
        else { return nil }

        let items = parsePanelContents(panel)
        let nextToken = extractContinuationToken(from: panel)
        return MixQueueResult(items: items, nextToken: nextToken)
    }

    /// Extracts `SearchResult`s from a `playlistPanel` or
    /// `playlistPanelContinuation` node's `contents[]`.
    private func parsePanelContents(_ panel: [String: Any]) -> [SearchResult] {
        guard let contents = panel["contents"] as? [[String: Any]] else { return [] }
        return contents.compactMap { element in
            guard let renderer = element["playlistPanelVideoRenderer"] as? [String: Any] else {
                return nil
            }
            return mixTrack(from: renderer)
        }
    }

    /// Maps a `playlistPanelVideoRenderer` JSON object to a `SearchResult`.
    /// Returns `nil` when `videoId` is blank.
    private func mixTrack(from renderer: [String: Any]) -> SearchResult? {
        guard let videoId = renderer["videoId"] as? String, !videoId.isEmpty else { return nil }

        let title = (renderer["title"] as? [String: Any])?["simpleText"] as? String ?? ""

        let channel = ((renderer["longBylineText"] as? [String: Any])?["runs"] as? [[String: Any]])?
            .first?["text"] as? String ?? ""

        let durationText = (renderer["lengthText"] as? [String: Any])?["simpleText"] as? String
        let durationSec = parseDuration(durationText)

        let thumbnails = (renderer["thumbnail"] as? [String: Any])?["thumbnails"] as? [[String: Any]] ?? []
        let thumbnailUrl = thumbnails.last?["url"] as? String ?? ""

        return SearchResult(
            videoId: videoId,
            title: title,
            channel: channel,
            durationSec: durationSec,
            thumbnailUrl: thumbnailUrl
        )
    }

    /// Extracts the continuation token from a `playlist` / `playlistPanelContinuation`
    /// node. Tries the legacy `nextContinuationData.continuation` path first (used by
    /// Mix responses per `docs/mix-queue.md §Token location`), then falls back to the
    /// modern `continuationEndpoint.continuationCommand.token` shape defensively so a
    /// future YouTube API migration doesn't silently break pagination.
    private func extractContinuationToken(from panel: [String: Any]) -> String? {
        // Legacy path — current Mix responses (WEB client 2.20240726.00.00)
        if let continuations = panel["continuations"] as? [[String: Any]] {
            for entry in continuations {
                if let token = (entry["nextContinuationData"] as? [String: Any])?["continuation"] as? String,
                   !token.isEmpty {
                    return token
                }
                // Modern path (defensive — not yet observed on Mix panel)
                if let token = ((entry["continuationEndpoint"] as? [String: Any])?["continuationCommand"] as? [String: Any])?["token"] as? String,
                   !token.isEmpty {
                    return token
                }
            }
        }
        // Defensive: continuationItemRenderer sibling of contents[] (browse/search style)
        if let contents = panel["contents"] as? [[String: Any]],
           let last = contents.last,
           let token = ((last["continuationItemRenderer"] as? [String: Any])?["continuationEndpoint"] as? [String: Any]).flatMap({ ($0["continuationCommand"] as? [String: Any])?["token"] as? String }),
           !token.isEmpty {
            return token
        }
        return nil
    }

    // MARK: - Helpers

    /// Parses "H:MM:SS" or "M:SS" text into total seconds. Returns 0 for live or
    /// missing duration (mirroring the Android `parseDuration` logic).
    private func parseDuration(_ text: String?) -> Int {
        guard let text else { return 0 }
        let parts = text.components(separatedBy: ":").compactMap { Int($0) }
        switch parts.count {
        case 2: return parts[0] * 60 + parts[1]
        case 3: return parts[0] * 3600 + parts[1] * 60 + parts[2]
        default: return 0
        }
    }

    // MARK: - Constants

    private static let nextURL = "https://www.youtube.com/youtubei/v1/next"
    private static let clientVersion = "2.20240726.00.00"
    private static let userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
}
