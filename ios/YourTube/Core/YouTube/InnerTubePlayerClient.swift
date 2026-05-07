import Foundation

// MARK: - PlayerResolution

/// Result of resolving a videoId to a playable audio URL.
///
/// `audioURL` is pre-signed (the InnerTube `/player` response on the ANDROID_VR
/// client returns ready-to-play `googlevideo.com` URLs without needing the JS
/// signature solver YouTubeKit's `__js` cache used to wire). `kind` lets the
/// service layer decide whether to wrap the URL with the YT-0157 HLS proxy
/// (`.audioOnly` only — livestreams are self-segmented HLS, muxed fallback
/// isn't fragmented MP4).
struct PlayerResolution: Sendable, Equatable {
    let audioURL: URL
    let kind: AudioFormat

    enum AudioFormat: Sendable, Equatable {
        /// `streamingData.adaptiveFormats` audio-only mp4a/aac. Wrap with the
        /// HLS proxy for long fragmented MP4 audio (YT-0157).
        case audioOnly
        /// `streamingData.formats` progressive audio+video. Skip the proxy.
        case muxed
        /// `streamingData.hlsManifestUrl`. AVPlayer's HLS engine handles this
        /// natively. Skip the proxy.
        case livestream
    }
}

// MARK: - PlayerExtracting

/// Resolves a YouTube videoId to a playable audio stream URL.
///
/// Replaces the (now removed) `StreamExtracting` protocol that wrapped
/// `YouTubeKit.YouTube(videoID:)`. The kit dependency was removed in YT-0162
/// because its JavaScriptCore-based `n`/`sig` signature solver was the source
/// of YT-0071's intermittent ~20% HTTP 403 rate. The ANDROID_VR client sidesteps
/// the solver entirely by returning pre-signed URLs.
protocol PlayerExtracting: Sendable {
    /// Resolve `videoId` against the InnerTube `/player` endpoint. Throws
    /// ``YouTubeServiceError`` cases mapped from the response's
    /// `playabilityStatus.status` field or from network failures.
    func resolve(videoId: String, quality: AudioQuality) async throws -> PlayerResolution
}

// MARK: - LivePlayerExtractor

/// Production conformer to ``PlayerExtracting``.
///
/// POSTs to `https://www.youtube.com/youtubei/v1/player` with the InnerTube
/// `ANDROID_VR` client. ANDROID_VR is chosen because:
///
/// 1. It returns pre-signed `googlevideo.com` URLs — no JS signature solver
///    needed (the YT-0071 failure mode disappears).
/// 2. It does not require a Proof-of-Origin (PO) token, so we don't need
///    a parallel BotGuard pipeline (yt-dlp `GVS_PO_TOKEN_POLICY` is empty
///    for android_vr).
/// 3. It returns standard `audio/mp4 mp4a` adaptive formats compatible with
///    the YT-0157 HLS proxy.
///
/// Limits we accept:
///
/// - "Made for kids" videos aren't available with this client (yt-dlp note).
///   Falls through to a `playabilityStatus.status != "OK"` error which the
///   caller surfaces as `.videoUnavailable`. Not in scope for music use.
/// - `clientVersion` is PINNED to `1.65.10` — yt-dlp explicitly warns that
///   using `>1.65` may return SABR-only streams (Server-side Ads Based
///   Routing — protected, not directly fetchable). Don't bump above 1.65.x
///   without re-verifying.
///
/// All client constants are sourced from `yt-dlp/yt_dlp/extractor/youtube/_base.py`
/// (verified 2026-05-07 against upstream master). When YouTube rotates the
/// values, update this single block — the rest of the file (decoding,
/// selection, error mapping) is invariant.
final class LivePlayerExtractor: PlayerExtracting, Sendable {

    // MARK: - Constants (pin from yt-dlp upstream)

    private static let endpointURL = URL(string: "https://www.youtube.com/youtubei/v1/player?key=AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8&prettyPrint=false")!

    private static let clientName = "ANDROID_VR"
    private static let clientVersion = "1.65.10"
    private static let clientNameNumeric = "28"
    private static let androidSdkVersion = 32
    private static let osVersion = "12L"
    private static let userAgent = "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"

    // MARK: - Dependencies

    typealias VisitorDataProvider = @Sendable () async -> String?

    private let transport: any HTTPDataTasking
    private let visitorDataProvider: VisitorDataProvider

    /// Production default: lazily fetch a `visitorData` from
    /// `/youtubei/v1/visitor_id`, cached for the lifetime of the extractor.
    /// Without it, `ANDROID_VR /player` returns `LOGIN_REQUIRED` ("Sign in to
    /// confirm you're not a bot") on most public videos — verified empirically
    /// against `n61ULEU7CO0` on 2026-05-06.
    init(
        transport: any HTTPDataTasking = URLSession.shared,
        visitorDataProvider: VisitorDataProvider? = nil
    ) {
        self.transport = transport
        if let visitorDataProvider {
            self.visitorDataProvider = visitorDataProvider
        } else {
            let cache = VisitorIDCache()
            let capturedTransport = transport
            self.visitorDataProvider = { await cache.fetch(using: capturedTransport) }
        }
    }

    // MARK: - PlayerExtracting

    func resolve(videoId: String, quality: AudioQuality) async throws -> PlayerResolution {
        let response = try await fetchPlayerResponse(videoId: videoId)

        // Surface playability gates first so a user-safe error displaces any
        // partial streamingData payload that might otherwise be misinterpreted.
        try Self.validatePlayability(response.playabilityStatus)

        guard let streamingData = response.streamingData else {
            throw YouTubeServiceError.noStreamFound
        }

        // 1. Audio-only mp4a/aac — the happy path. Selection mirrors the
        //    bitrate-ceiling logic that LiveYouTubeService used to run over
        //    the kit's [Stream] (filter audio-only, prefer at-or-below the
        //    requested AudioQuality, fall back to lowest if no candidate
        //    fits the ceiling).
        if let url = Self.pickAudioOnlyURL(from: streamingData.adaptiveFormats ?? [], quality: quality) {
            return PlayerResolution(audioURL: url, kind: .audioOnly)
        }

        // 2. Progressive (muxed audio+video) fallback — for tracks where the
        //    server only returned a `formats` payload (rare for modern YouTube
        //    but happens for old uploads / age-gated previews).
        if let url = Self.pickMuxedURL(from: streamingData.formats ?? []) {
            return PlayerResolution(audioURL: url, kind: .muxed)
        }

        // 3. HLS livestream manifest — long-running content (Lofi Girl 24/7,
        //    news streams) reaches the extractor with empty adaptive/progressive
        //    arrays but a populated `hlsManifestUrl`. AVPlayer plays HLS
        //    natively so the proxy is unnecessary here.
        if let manifestString = streamingData.hlsManifestUrl, let manifest = URL(string: manifestString) {
            return PlayerResolution(audioURL: manifest, kind: .livestream)
        }

        throw YouTubeServiceError.noStreamFound
    }

    // MARK: - Networking

    private func fetchPlayerResponse(videoId: String) async throws -> InnerTubePlayerResponse {
        // Fetch the visitor data first — empirically required to avoid the
        // ANDROID_VR "Sign in to confirm you're not a bot" gate. Cached for
        // the extractor's lifetime so subsequent resolves pay zero latency.
        let visitor = await visitorDataProvider()

        var request = URLRequest(url: Self.endpointURL)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue(Self.clientNameNumeric, forHTTPHeaderField: "X-YouTube-Client-Name")
        request.setValue(Self.clientVersion, forHTTPHeaderField: "X-YouTube-Client-Version")
        request.setValue("https://www.youtube.com", forHTTPHeaderField: "Origin")
        request.setValue(Self.userAgent, forHTTPHeaderField: "User-Agent")
        if let visitor {
            request.setValue(visitor, forHTTPHeaderField: "X-Goog-Visitor-Id")
        }
        request.httpBody = try Self.encodeRequestBody(videoId: videoId, visitorData: visitor)

        let data: Data
        do {
            let (responseData, _) = try await transport.data(for: request)
            data = responseData
        } catch is CancellationError {
            // Cooperative cancellation — re-throw so the surrounding
            // `Task { @MainActor }` in `PlayerCoordinator` can short-circuit
            // without surfacing a misleading "network failure" error banner.
            throw CancellationError()
        } catch {
            throw YouTubeServiceError.networkFailure
        }

        do {
            return try JSONDecoder().decode(InnerTubePlayerResponse.self, from: data)
        } catch {
            // Malformed response shape — most likely a captcha / consent page
            // returned where we expected JSON. Surface as a generic network
            // failure so the user banner offers Retry.
            throw YouTubeServiceError.networkFailure
        }
    }

    private static func encodeRequestBody(videoId: String, visitorData: String?) throws -> Data {
        // `racyCheckOk` + `contentCheckOk` mirror the flags yt-dlp injects for
        // unauthenticated music extraction — they suppress the InnerTube-side
        // gating that would otherwise bounce many tracks back as
        // `LOGIN_REQUIRED`. `visitorData` is the bot-check ticket; without it
        // ANDROID_VR returns "Sign in to confirm you're not a bot" for most
        // public music videos.
        var client: [String: Any] = [
            "clientName": clientName,
            "clientVersion": clientVersion,
            "deviceMake": "Oculus",
            "deviceModel": "Quest 3",
            "androidSdkVersion": androidSdkVersion,
            "osName": "Android",
            "osVersion": osVersion,
            "hl": "en",
            "gl": "US",
        ]
        if let visitorData {
            client["visitorData"] = visitorData
        }
        let body: [String: Any] = [
            "context": ["client": client],
            "videoId": videoId,
            "racyCheckOk": true,
            "contentCheckOk": true,
            "playbackContext": [
                "contentPlaybackContext": [
                    "html5Preference": "HTML5_PREF_WANTS",
                ],
            ],
        ]
        return try JSONSerialization.data(withJSONObject: body)
    }

    // MARK: - Stream selection

    /// Picks the best audio-only mp4a stream at or below the requested quality
    /// ceiling. Returns the lowest-bitrate audio-only stream when no candidate
    /// matches the ceiling so the caller still gets something playable on a
    /// constrained network. Mirrors the YouTubeKit-era logic in
    /// `LiveYouTubeService.swift:142-159`.
    static func pickAudioOnlyURL(from formats: [InnerTubePlayerResponse.AdaptiveFormat], quality: AudioQuality) -> URL? {
        // Reject formats with neither bitrate field — they would otherwise
        // coalesce to `Int.max` in the lowest-bitrate fallback below and win
        // over real candidates, causing AVPlayer to load an unknown-bitrate
        // stream of unknown decodability. Per reviewer pass on YT-0162.
        let audioOnly = formats.filter { $0.isAudioOnlyMp4a && $0.hasKnownBitrate }
        let qualityBps = quality.rawValue * 1_000

        let candidates = audioOnly.filter { format in
            // `hasKnownBitrate` guarantees at least one of the two is set.
            let bps = format.averageBitrate ?? format.bitrate ?? Int.max
            return bps <= qualityBps
        }

        if let best = candidates.max(by: { lhs, rhs in
            let a = lhs.averageBitrate ?? lhs.bitrate ?? 0
            let b = rhs.averageBitrate ?? rhs.bitrate ?? 0
            return a < b
        }) {
            return URL(string: best.url ?? "")
        }

        // Below the ceiling: pick the lowest-bitrate available so the user
        // gets audio (matching the YouTubeKit-era fallback shape).
        if let lowest = audioOnly.min(by: { lhs, rhs in
            let a = lhs.averageBitrate ?? lhs.bitrate ?? Int.max
            let b = rhs.averageBitrate ?? rhs.bitrate ?? Int.max
            return a < b
        }) {
            return URL(string: lowest.url ?? "")
        }

        return nil
    }

    /// Picks the highest-bitrate progressive (muxed audio+video) mp4 stream.
    /// Used when the server returned no audio-only adaptive formats — rare for
    /// modern uploads but occasionally seen on older or partially-restricted
    /// videos.
    static func pickMuxedURL(from formats: [InnerTubePlayerResponse.AdaptiveFormat]) -> URL? {
        let muxed = formats.filter { $0.isMuxedMp4 }
        guard let best = muxed.max(by: { lhs, rhs in
            let a = lhs.averageBitrate ?? lhs.bitrate ?? 0
            let b = rhs.averageBitrate ?? rhs.bitrate ?? 0
            return a < b
        }) else { return nil }
        return URL(string: best.url ?? "")
    }

    // MARK: - Playability gating

    /// Maps `playabilityStatus.status` to ``YouTubeServiceError``. Treats any
    /// non-`OK` status as user-facing and unrecoverable (the user's only
    /// option is to pick a different track). Specific subreasons that we
    /// expect to see on this client:
    ///
    /// - `LOGIN_REQUIRED` — age-gated content. ANDROID_VR doesn't carry
    ///   account credentials so this is a hard wall for music videos behind
    ///   the age gate. Surface as `.videoUnavailable`.
    /// - `UNPLAYABLE` — geo-blocked, members-only, etc.
    /// - `LIVE_STREAM_OFFLINE` — past livestream that hasn't been archived.
    /// - `ERROR` — generic content removal / takedown.
    static func validatePlayability(_ status: InnerTubePlayerResponse.PlayabilityStatus?) throws {
        guard let status, let raw = status.status else { return }
        switch raw {
        case "OK":
            return
        case "LOGIN_REQUIRED", "AGE_VERIFICATION_REQUIRED", "CONTENT_CHECK_REQUIRED",
             "UNPLAYABLE", "ERROR", "LIVE_STREAM_OFFLINE":
            throw YouTubeServiceError.videoUnavailable
        default:
            // Unknown subreason — surface as networkFailure so the existing
            // YT-0070 retry banner gives the user a way out.
            throw YouTubeServiceError.networkFailure
        }
    }
}

// MARK: - InnerTube response Codable

/// Minimal projection of the InnerTube `/player` response shape we care about.
/// Other fields (`videoDetails`, `microformat`, `playerConfig`, etc.) are
/// intentionally absent — extending this struct is a follow-up if a feature
/// needs them.
struct InnerTubePlayerResponse: Decodable, Sendable {

    let playabilityStatus: PlayabilityStatus?
    let streamingData: StreamingData?

    struct PlayabilityStatus: Decodable, Sendable {
        let status: String?
        let reason: String?
    }

    struct StreamingData: Decodable, Sendable {
        let formats: [AdaptiveFormat]?
        let adaptiveFormats: [AdaptiveFormat]?
        let hlsManifestUrl: String?
    }

    /// Both `formats` (progressive) and `adaptiveFormats` (DASH) entries share
    /// the same JSON shape. We treat them via one struct and discriminate at
    /// pick-time using the `mimeType`.
    struct AdaptiveFormat: Decodable, Equatable, Sendable {
        let itag: Int?
        let url: String?
        let mimeType: String?
        let bitrate: Int?
        let averageBitrate: Int?

        /// True for `audio/mp4` adaptive formats with `mp4a` codec — the
        /// ANDROID_VR audio-only path. AVPlayer can decode these natively.
        var isAudioOnlyMp4a: Bool {
            guard let mimeType = mimeType?.lowercased() else { return false }
            return mimeType.hasPrefix("audio/mp4") && mimeType.contains("mp4a")
        }

        /// True for progressive `video/mp4` formats with both audio AND video
        /// codecs (avc1 + mp4a) — used as muxed fallback when no audio-only
        /// format is available (rare on modern uploads).
        ///
        /// This substring match (`hasPrefix("video/mp4") && contains("avc1") && contains("mp4a")`)
        /// is **narrower than YouTubeKit's parsed-codec match**: the kit
        /// decoded the full codec list, so any `video/mp4` carrying an mp4a
        /// audio track + ANY video codec was eligible. We explicitly require
        /// `avc1` because that's what the InnerTube `/player formats` array
        /// returns for itag 18/22 (the standard progressive entries) and we
        /// don't want to accept progressive streams encoded with codecs
        /// AVPlayer may not have a hardware decoder for. If a future video
        /// returns a non-avc1 progressive stream that AVPlayer COULD decode,
        /// extend this predicate explicitly rather than relaxing the substring.
        var isMuxedMp4: Bool {
            guard let mimeType = mimeType?.lowercased() else { return false }
            return mimeType.hasPrefix("video/mp4") && mimeType.contains("mp4a") && mimeType.contains("avc1")
        }

        /// True when at least one of `bitrate` / `averageBitrate` is non-nil.
        /// The audio-only picker rejects formats failing this check so an
        /// unsized format can't coalesce to `Int.max` in the lowest-bitrate
        /// fallback and win over real candidates.
        var hasKnownBitrate: Bool {
            bitrate != nil || averageBitrate != nil
        }
    }
}

// MARK: - VisitorIDCache

/// Lazily fetches a `visitorData` token from `/youtubei/v1/visitor_id` and
/// caches it for the lifetime of the actor (typically the
/// `LivePlayerExtractor` instance, which is created once at app startup).
///
/// Cooperative once-only: concurrent callers during the in-flight first fetch
/// share the same `Task` instead of duplicating the request. Failure (network
/// error, malformed response) is silently swallowed — the player request
/// proceeds without `visitorData`, which produces a clear `LOGIN_REQUIRED`
/// from the server that the existing error mapping surfaces to the user.
actor VisitorIDCache {
    private var cached: String?
    private var inFlight: Task<String?, Never>?

    private static let endpoint = URL(string: "https://www.youtube.com/youtubei/v1/visitor_id?key=AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8&prettyPrint=false")!

    func fetch(using transport: any HTTPDataTasking) async -> String? {
        if let cached { return cached }
        if let inFlight { return await inFlight.value }
        let task = Task<String?, Never> {
            await Self.performFetch(transport: transport)
        }
        inFlight = task
        let value = await task.value
        if let value { cached = value }
        inFlight = nil
        return value
    }

    private static func performFetch(transport: any HTTPDataTasking) async -> String? {
        var req = URLRequest(url: endpoint)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        let body: [String: Any] = [
            "context": [
                "client": [
                    "clientName": "WEB",
                    "clientVersion": "2.20260114.08.00",
                    "hl": "en",
                    "gl": "US",
                ],
            ],
        ]
        guard let httpBody = try? JSONSerialization.data(withJSONObject: body) else { return nil }
        req.httpBody = httpBody
        guard let (data, _) = try? await transport.data(for: req) else { return nil }
        guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let context = json["responseContext"] as? [String: Any],
              let visitor = context["visitorData"] as? String,
              !visitor.isEmpty else { return nil }
        return visitor
    }
}
