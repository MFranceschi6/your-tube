import AVFoundation
import Foundation

/// Pure helpers + AVAssetResourceLoaderDelegate for the YT-0157 HLS proxy:
/// ISO/IEC 14496-12 atom-header and `sidx` segment-index parsing, HLS m3u8
/// synthesis, and the `AVURLAsset` factory that stitches them together so
/// AVPlayer treats long fragmented-mp4 audio as HLS (avoiding the catastrophic
/// stall on tracks with thousands of `moof+mdat` fragments).
///
/// The byte-walking primitives are pure data-in / data-out so they can be
/// unit-tested without standing up AVFoundation. The delegate's path-dispatch
/// is also extracted into a pure ``HLSProxy/route(_:)`` so behaviour is
/// verifiable without constructing `AVAssetResourceLoadingRequest` fakes.
enum HLSProxy {

    // MARK: - Public types

    struct AtomHeader: Equatable {
        let size: Int
        let type: String
        let headerLength: Int
    }

    struct SidxEntry: Equatable {
        let absoluteOffset: Int
        let size: Int
        let durationTicks: UInt32
    }

    struct SidxParseResult: Equatable {
        let timescale: UInt32
        let entries: [SidxEntry]
    }

    // MARK: - Atom header

    /// Parses the leading 8 (or 16) bytes of an MP4 atom. Standard atoms carry
    /// `size` as a 32-bit big-endian integer; large atoms encode `size = 1`
    /// then a 64-bit big-endian extended size at offset 8 (large-size box per
    /// ISO/IEC 14496-12 §4.2). Returns `nil` when the buffer is too short.
    static func parseAtomHeader(_ data: Data) -> AtomHeader? {
        guard data.count >= 8 else { return nil }
        let bytes = Array(data.prefix(16))
        let s32 = u32(bytes, 0)
        guard let type = String(bytes: bytes[4..<8], encoding: .ascii) else { return nil }
        if s32 == 1 {
            guard bytes.count >= 16 else { return nil }
            let s64 = u64(bytes, 8)
            return AtomHeader(size: Int(s64), type: type, headerLength: 16)
        }
        return AtomHeader(size: Int(s32), type: type, headerLength: 8)
    }

    // MARK: - Sidx parser

    /// Parses one `sidx` atom whose bytes start at index 0 of `data`. The
    /// returned `absoluteOffset` values are relative to the original file —
    /// `sidxFileOffset` + `sidxAtomSize` + `first_offset` is the first
    /// fragment's start, and each subsequent reference advances by its
    /// `referencedSize`. References with `referenceType == 1` (sub-sidx
    /// pointers, ISO/IEC 14496-12 §8.16.3) are skipped per the prototype's
    /// behaviour but still advance the cursor.
    ///
    /// Returns `nil` when the buffer is shorter than `sidxAtomSize`, when the
    /// fixed sidx prelude does not fit, or when the per-reference table runs
    /// past the buffer.
    static func parseSidx(data: Data, sidxFileOffset: Int, sidxAtomSize: Int) -> SidxParseResult? {
        guard data.count >= sidxAtomSize, sidxAtomSize >= 32 else { return nil }
        let bytes = Array(data.prefix(sidxAtomSize))

        // Skip the 8-byte atom header (size + "sidx").
        var p = 8
        let version = bytes[p]; p += 1
        p += 3 // flags
        p += 4 // reference_ID
        let timescale = u32(bytes, p); p += 4

        let firstOffset: UInt64
        if version == 0 {
            p += 4 // earliest_presentation_time
            firstOffset = UInt64(u32(bytes, p)); p += 4
        } else {
            guard p + 16 <= bytes.count else { return nil }
            p += 8 // earliest_presentation_time
            firstOffset = u64(bytes, p); p += 8
        }

        guard p + 4 <= bytes.count else { return nil }
        p += 2 // reserved
        let referenceCount = Int(u16(bytes, p)); p += 2
        guard p + referenceCount * 12 <= bytes.count else { return nil }

        var entries: [SidxEntry] = []
        entries.reserveCapacity(referenceCount)
        var cursor = sidxFileOffset + sidxAtomSize + Int(firstOffset)
        for _ in 0..<referenceCount {
            let raw1 = u32(bytes, p); p += 4
            let referenceType = (raw1 >> 31) & 1
            let referencedSize = Int(raw1 & 0x7FFFFFFF)
            let subsegmentDuration = u32(bytes, p); p += 4
            p += 4 // SAP info
            if referenceType == 0 {
                entries.append(SidxEntry(
                    absoluteOffset: cursor,
                    size: referencedSize,
                    durationTicks: subsegmentDuration
                ))
            }
            cursor += referencedSize
        }
        return SidxParseResult(timescale: timescale, entries: entries)
    }

    // MARK: - HLS m3u8 synthesis

    /// Synthesises an HLS-fmp4 VOD playlist that:
    /// - Declares the init segment (`ftyp` + `moov` + `sidx`) via
    ///   `#EXT-X-MAP` + a `BYTERANGE="<initSize>@0"` attribute.
    /// - Emits one `#EXTINF` + `#EXT-X-BYTERANGE` pair per `SidxEntry`.
    /// - Points every entry at the same `segmentURI` — HLS-fmp4 convention
    ///   requires the EXT-X-MAP and segments to share the underlying URI;
    ///   the byterange table is what distinguishes init from media.
    ///
    /// Consecutive entries (where the new `absoluteOffset` equals the prior
    /// entry's `absoluteOffset + size`) drop the `@offset` half of the
    /// byterange line so AVPlayer's HLS engine continues reading sequentially
    /// without re-issuing a Range header.
    /// Locale used to format `EXTINF` durations. RFC 8216 §4.3.2.1 requires
    /// `.` as the decimal separator; pinning to POSIX prevents devices in
    /// `it_IT`/`de_DE`/etc. from emitting `0,500`, which AVPlayer's HLS
    /// parser silently rejects (the whole playlist fails to load). Exposed
    /// as a function parameter so tests can pass a comma-locale and prove
    /// the pinning actually works.
    static let extinfLocale = Locale(identifier: "en_US_POSIX")

    static func makeM3U8(
        initSize: Int,
        segmentURI: String,
        mapURI: String,
        entries: [SidxEntry],
        timescale: UInt32,
        locale: Locale = extinfLocale
    ) -> String {
        var lines: [String] = [
            "#EXTM3U",
            "#EXT-X-VERSION:7",
            "#EXT-X-TARGETDURATION:5",
            "#EXT-X-PLAYLIST-TYPE:VOD",
            "#EXT-X-MAP:URI=\"\(mapURI)\",BYTERANGE=\"\(initSize)@0\"",
            "#EXT-X-MEDIA-SEQUENCE:0",
        ]
        var prevOffset = -1
        for e in entries {
            let dur = Double(e.durationTicks) / Double(timescale)
            lines.append(String(format: "#EXTINF:%.3f,", locale: locale, dur))
            if prevOffset == e.absoluteOffset {
                lines.append("#EXT-X-BYTERANGE:\(e.size)")
            } else {
                lines.append("#EXT-X-BYTERANGE:\(e.size)@\(e.absoluteOffset)")
            }
            lines.append(segmentURI)
            prevOffset = e.absoluteOffset + e.size
        }
        lines.append("#EXT-X-ENDLIST")
        return lines.joined(separator: "\n") + "\n"
    }

    // MARK: - Big-endian byte readers

    private static func u16(_ b: [UInt8], _ o: Int) -> UInt16 {
        (UInt16(b[o]) << 8) | UInt16(b[o + 1])
    }

    private static func u32(_ b: [UInt8], _ o: Int) -> UInt32 {
        (UInt32(b[o]) << 24)
        | (UInt32(b[o + 1]) << 16)
        | (UInt32(b[o + 2]) << 8)
        | UInt32(b[o + 3])
    }

    private static func u64(_ b: [UInt8], _ o: Int) -> UInt64 {
        (UInt64(u32(b, o)) << 32) | UInt64(u32(b, o + 4))
    }

    // MARK: - Custom-URL contract

    /// Custom URL scheme for the proxy. Triple-slash form
    /// (`yt-prefetch://yt/...`) — empty-host URLs (`yt-prefetch://playlist.m3u8`)
    /// parse with empty path and break the loader's path-suffix dispatch.
    static let scheme = "yt-prefetch"
    static let host = "yt"
    static let playlistFilename = "playlist.m3u8"
    static let segmentFilename = "audio.mp4"

    static var playlistURL: URL { URL(string: "\(scheme)://\(host)/\(playlistFilename)")! }
    static var segmentURI: String { "\(scheme)://\(host)/\(segmentFilename)" }

    enum RouteDecision: Equatable {
        case playlist
        case segment
        case ignore
    }

    /// Dispatches an asset-loader request URL onto the loader's three branches.
    /// Pure: tested without AVFoundation fakes.
    static func route(_ url: URL) -> RouteDecision {
        guard url.scheme == scheme else { return .ignore }
        if url.path.hasSuffix(playlistFilename) { return .playlist }
        if url.path.hasSuffix(segmentFilename) { return .segment }
        return .ignore
    }

    // MARK: - Errors

    enum ProxyError: Error, Equatable {
        case noContentLength
        case missingFtyp
        case missingMoov
        case missingSidx
        case sidxParseFailed
    }

    // MARK: - Init-atom walker

    /// Result of `findInitAtoms`: the sidx atom location plus its already-fetched bytes.
    struct InitAtoms: Equatable {
        let sidxOffset: Int
        let sidxSize: Int
        let sidxData: Data
    }

    /// Cap on how far into the file we walk looking for `sidx`. fmp4 init
    /// regions are tiny (<<1 MB); anything past this is almost certainly
    /// non-fmp4 and we should bail rather than range-fetch endlessly.
    static let atomScanLimit: Int = 1_000_000

    /// Walks atom headers from byte 0 until it finds `sidx`, then range-fetches
    /// the full sidx atom. Throws `.missingFtyp` / `.missingMoov` / `.missingSidx`
    /// when the layout is not fmp4-compatible.
    ///
    /// `fetchRange` is the byte-fetcher seam — production binds it to
    /// `URLSession.shared` Range requests; tests can swap it for an in-memory
    /// fake.
    static func findInitAtoms(
        contentLength: Int,
        fetchRange: (ClosedRange<Int>) async throws -> Data
    ) async throws -> InitAtoms {
        var off = 0
        var sawFtyp = false
        var sawMoov = false
        let limit = min(contentLength, atomScanLimit)
        while off < limit {
            // The 16-byte head read must fit inside the file (and inside the
            // scan cap). Bail when the next header would straddle the cap or
            // EOF — we can't safely interpret a truncated atom header.
            guard off + 16 <= limit else { throw ProxyError.missingSidx }
            let head = try await fetchRange(off...(off + 15))
            guard let h = parseAtomHeader(head) else { throw ProxyError.missingSidx }
            switch h.type {
            case "ftyp": sawFtyp = true
            case "moov": sawMoov = true
            case "sidx":
                guard sawFtyp else { throw ProxyError.missingFtyp }
                guard sawMoov else { throw ProxyError.missingMoov }
                // The sidx atom must fit inside the actual file (not just the
                // scan cap). Otherwise the range fetch returns a truncated
                // tail and parseSidx silently fails downstream.
                guard h.size > 0, off + h.size <= contentLength else {
                    throw ProxyError.missingSidx
                }
                let sidxData = try await fetchRange(off...(off + h.size - 1))
                return InitAtoms(sidxOffset: off, sidxSize: h.size, sidxData: sidxData)
            default: break
            }
            guard h.size > 0 else { throw ProxyError.missingSidx }
            off += h.size
        }
        throw ProxyError.missingSidx
    }

    // MARK: - Playback prep

    /// Bundle of the playlist URL the engine should hand to AVPlayer plus the
    /// resource-loader delegate that satisfies its requests. The loader is the
    /// single retention point for the proxy — drop it and AVPlayer immediately
    /// fails the asset.
    struct ProxiedPlayback: Sendable {
        let playlistURL: URL
        let loader: HLSProxyLoader
    }

    /// HEAD origin → atom-walk for ftyp/moov/sidx → range-fetch sidx → parse
    /// sidx → synthesise m3u8 → return `(playlistURL, loader)`. The caller
    /// (typically `AVPlayerAudioEngine.play(track:url:resourceLoader:)`) is
    /// responsible for: (a) constructing the `AVURLAsset` with `playlistURL`,
    /// (b) binding the loader via `asset.resourceLoader.setDelegate(_, queue:)`,
    /// (c) retaining the loader for the lifetime of the resulting `AVPlayerItem`.
    static func prepareProxiedPlayback(
        originURL: URL,
        session: URLSession = .shared
    ) async throws -> ProxiedPlayback {
        var headReq = URLRequest(url: originURL)
        headReq.httpMethod = "HEAD"
        let (_, headResp) = try await session.data(for: headReq)
        guard let httpResp = headResp as? HTTPURLResponse else {
            throw ProxyError.noContentLength
        }
        // googlevideo signed URLs return 4xx HTML when expired; the body's
        // Content-Length can be a positive non-zero number (the size of the
        // error page), so the `> 0` check below is not enough on its own.
        guard (200...299).contains(httpResp.statusCode) else { throw ProxyError.noContentLength }
        let totalLen = Int(httpResp.expectedContentLength)
        guard totalLen > 0 else { throw ProxyError.noContentLength }

        let initAtoms = try await findInitAtoms(contentLength: totalLen) { range in
            var req = URLRequest(url: originURL)
            req.setValue("bytes=\(range.lowerBound)-\(range.upperBound)", forHTTPHeaderField: "Range")
            let (data, _) = try await session.data(for: req)
            return data
        }

        guard let parsed = parseSidx(
            data: initAtoms.sidxData,
            sidxFileOffset: initAtoms.sidxOffset,
            sidxAtomSize: initAtoms.sidxSize
        ) else {
            throw ProxyError.sidxParseFailed
        }

        let initSize = initAtoms.sidxOffset + initAtoms.sidxSize
        let m3u8 = makeM3U8(
            initSize: initSize,
            segmentURI: segmentURI,
            mapURI: segmentURI,
            entries: parsed.entries,
            timescale: parsed.timescale
        )

        let loader = HLSProxyLoader(originURL: originURL, m3u8: m3u8)
        return ProxiedPlayback(playlistURL: playlistURL, loader: loader)
    }
}

// MARK: - HLSProxyLoader

/// `AVAssetResourceLoaderDelegate` that:
/// - Serves the synthesised m3u8 inline when AVPlayer requests `playlist.m3u8`.
/// - Redirects every segment fetch (HTTP 302) to the original googlevideo URL,
///   so AVPlayer follows with its native HTTP loader and the same Range
///   header. Inline segment serving fails with `CoreMediaError -12881
///   "custom url not redirect"` — the engine REQUIRES a redirect for
///   custom-scheme segments. Do not change this to inline.
/// - Returns `false` for non-`yt-prefetch` schemes so AVPlayer's native
///   loaders handle file:// / http(s):// without the delegate intercepting
///   redirect targets.
final class HLSProxyLoader: NSObject, AVAssetResourceLoaderDelegate, @unchecked Sendable {

    let originURL: URL
    let m3u8Body: Data
    let queue = DispatchQueue(label: "com.matteofranceschi.yourtube.hls-proxy")

    init(originURL: URL, m3u8: String) {
        self.originURL = originURL
        self.m3u8Body = Data(m3u8.utf8)
    }

    func resourceLoader(
        _ loader: AVAssetResourceLoader,
        shouldWaitForLoadingOfRequestedResource req: AVAssetResourceLoadingRequest
    ) -> Bool {
        guard let url = req.request.url else { return false }
        switch HLSProxy.route(url) {
        case .playlist:
            servePlaylist(req)
            return true
        case .segment:
            redirectToOrigin(req, requestedURL: url)
            return true
        case .ignore:
            return false
        }
    }

    private func servePlaylist(_ req: AVAssetResourceLoadingRequest) {
        if let info = req.contentInformationRequest {
            info.contentType = "application/vnd.apple.mpegurl"
            info.contentLength = Int64(m3u8Body.count)
            info.isByteRangeAccessSupported = true
        }
        if let dr = req.dataRequest {
            if let slice = HLSProxy.playlistSlice(
                body: m3u8Body,
                offset: Int(dr.requestedOffset),
                length: dr.requestedLength
            ) {
                dr.respond(with: slice)
            }
        }
        req.finishLoading()
    }

    private func redirectToOrigin(_ req: AVAssetResourceLoadingRequest, requestedURL: URL) {
        req.redirect = URLRequest(url: originURL)
        req.response = HTTPURLResponse(
            url: requestedURL,
            statusCode: 302,
            httpVersion: "HTTP/1.1",
            headerFields: ["Location": originURL.absoluteString]
        )
        req.finishLoading()
    }
}

extension HLSProxy {
    /// Pure helper for the loader's playlist branch — extracted so the slicing
    /// logic is unit-testable without `AVAssetResourceLoadingRequest` fakes
    /// (per AC#11). Returns `nil` when the requested offset is at or past the
    /// playlist tail (AVPlayer's HLS engine may probe past EOF; respond with
    /// nothing rather than zero bytes so `finishLoading()` is the only signal).
    static func playlistSlice(body: Data, offset: Int, length: Int) -> Data? {
        guard offset >= 0, length >= 0, offset < body.count else { return nil }
        let end = min(offset + length, body.count)
        return body.subdata(in: offset..<end)
    }
}
