import Testing
import Foundation
@testable import YourTube

// MARK: - parseAtomHeader

@Suite("HLSProxy.parseAtomHeader")
struct HLSProxyAtomHeaderTests {

    @Test("Standard 32-bit size + ASCII type")
    func standardHeader() {
        // size = 42, type = "moov"
        let data = Data([0x00, 0x00, 0x00, 0x2A, 0x6D, 0x6F, 0x6F, 0x76])
        let h = HLSProxy.parseAtomHeader(data)
        #expect(h?.size == 42)
        #expect(h?.type == "moov")
        #expect(h?.headerLength == 8)
    }

    @Test("Large extended 64-bit size when leading uint32 == 1")
    func extendedHeader() {
        // size32 = 1, type = "moov", size64 = 0x1_00000000 (4 GiB).
        let data = Data([
            0x00, 0x00, 0x00, 0x01,
            0x6D, 0x6F, 0x6F, 0x76,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00,
        ])
        let h = HLSProxy.parseAtomHeader(data)
        #expect(h?.size == 0x1_00000000)
        #expect(h?.type == "moov")
        #expect(h?.headerLength == 16)
    }

    @Test("Returns nil for buffer shorter than 8 bytes")
    func shortBuffer() {
        #expect(HLSProxy.parseAtomHeader(Data([0x00, 0x00])) == nil)
    }

    @Test("Returns nil when leading uint32 == 1 but buffer < 16 bytes")
    func extendedSizeShortBuffer() {
        // size32 = 1, type = "moov", but only 12 bytes total → can't read 64-bit size.
        let data = Data([
            0x00, 0x00, 0x00, 0x01,
            0x6D, 0x6F, 0x6F, 0x76,
            0x00, 0x00, 0x00, 0x01,
        ])
        #expect(HLSProxy.parseAtomHeader(data) == nil)
    }

    @Test("Returns nil when type bytes are not ASCII-decodable")
    func nonAsciiType() {
        let data = Data([0x00, 0x00, 0x00, 0x10, 0xFF, 0xFE, 0xFD, 0xFC])
        #expect(HLSProxy.parseAtomHeader(data) == nil)
    }
}

// MARK: - parseSidx

@Suite("HLSProxy.parseSidx")
struct HLSProxySidxTests {

    @Test("v0 sidx with 3 entries computes absolute offsets cumulatively")
    func v0ThreeEntries() {
        // Layout for v0 with 3 refs: 8 (header) + 4 (version+flags) + 4
        // (reference_ID) + 4 (timescale) + 4 (earliest) + 4 (firstOffset)
        // + 4 (reserved + refCount) + 12 × 3 = 68 bytes.
        var b: [UInt8] = []
        b += [0x00, 0x00, 0x00, 0x44]               // size = 68
        b += Array("sidx".utf8)
        b += [0x00, 0x00, 0x00, 0x00]               // version=0, flags=0
        b += [0x00, 0x00, 0x00, 0x01]               // reference_ID
        b += [0x00, 0x00, 0x03, 0xE8]               // timescale = 1000
        b += [0x00, 0x00, 0x00, 0x00]               // earliest_presentation_time
        b += [0x00, 0x00, 0x00, 0x00]               // first_offset = 0
        b += [0x00, 0x00, 0x00, 0x03]               // reserved + reference_count = 3
        // ref 1: type=0, size=200; duration=500; sap=0.
        b += [0x00, 0x00, 0x00, 0xC8, 0x00, 0x00, 0x01, 0xF4, 0x00, 0x00, 0x00, 0x00]
        // ref 2: size=300; duration=500.
        b += [0x00, 0x00, 0x01, 0x2C, 0x00, 0x00, 0x01, 0xF4, 0x00, 0x00, 0x00, 0x00]
        // ref 3: size=400; duration=500.
        b += [0x00, 0x00, 0x01, 0x90, 0x00, 0x00, 0x01, 0xF4, 0x00, 0x00, 0x00, 0x00]
        #expect(b.count == 68)

        let parsed = HLSProxy.parseSidx(data: Data(b), sidxFileOffset: 1000, sidxAtomSize: 68)
        #expect(parsed?.timescale == 1000)
        #expect(parsed?.entries == [
            HLSProxy.SidxEntry(absoluteOffset: 1068, size: 200, durationTicks: 500),
            HLSProxy.SidxEntry(absoluteOffset: 1268, size: 300, durationTicks: 500),
            HLSProxy.SidxEntry(absoluteOffset: 1568, size: 400, durationTicks: 500),
        ])
    }

    @Test("Skips referenceType=1 (sub-sidx pointer) but advances cursor by its size")
    func skipsSubSidxPointer() {
        // 8 + 4 + 4 + 4 + 4 + 4 + 4 + 12 × 2 = 56 bytes.
        var b: [UInt8] = []
        b += [0x00, 0x00, 0x00, 0x38]               // size = 56
        b += Array("sidx".utf8)
        b += [0x00, 0x00, 0x00, 0x00]               // version + flags
        b += [0x00, 0x00, 0x00, 0x01]               // reference_ID
        b += [0x00, 0x00, 0x03, 0xE8]               // timescale = 1000
        b += [0x00, 0x00, 0x00, 0x00]               // earliest_presentation_time
        b += [0x00, 0x00, 0x00, 0x00]               // first_offset = 0
        b += [0x00, 0x00, 0x00, 0x02]               // reserved + reference_count = 2
        // ref 1: referenceType=1, size=100, duration=500.
        b += [0x80, 0x00, 0x00, 0x64, 0x00, 0x00, 0x01, 0xF4, 0x00, 0x00, 0x00, 0x00]
        // ref 2: referenceType=0, size=200, duration=500.
        b += [0x00, 0x00, 0x00, 0xC8, 0x00, 0x00, 0x01, 0xF4, 0x00, 0x00, 0x00, 0x00]
        #expect(b.count == 56)

        let parsed = HLSProxy.parseSidx(data: Data(b), sidxFileOffset: 0, sidxAtomSize: 56)
        // Only the second ref produces an entry. Cursor for it
        // = 0 (sidxFileOffset) + 56 (sidxAtomSize) + 0 (firstOffset) + 100 (skipped ref).
        #expect(parsed?.entries == [
            HLSProxy.SidxEntry(absoluteOffset: 156, size: 200, durationTicks: 500),
        ])
    }

    @Test("Returns nil when buffer is shorter than declared atom size")
    func shortBuffer() {
        let b: [UInt8] = Array(repeating: 0, count: 30)
        #expect(HLSProxy.parseSidx(data: Data(b), sidxFileOffset: 0, sidxAtomSize: 64) == nil)
    }

    @Test("v1 sidx uses 64-bit earliest_presentation_time + first_offset")
    func v1SixtyFourBitFields() {
        // v1 layout: 8 (header) + 4 (version+flags) + 4 (refID) + 4 (timescale)
        // + 8 (earliest, 64-bit) + 8 (firstOffset, 64-bit) + 4 (reserved+refCount)
        // + 12 × 1 = 52 bytes. firstOffset = 8 forces the cursor forward by 8.
        var b: [UInt8] = []
        b += [0x00, 0x00, 0x00, 0x34]               // size = 52
        b += Array("sidx".utf8)
        b += [0x01, 0x00, 0x00, 0x00]               // version = 1, flags = 0
        b += [0x00, 0x00, 0x00, 0x01]               // reference_ID
        b += [0x00, 0x00, 0x03, 0xE8]               // timescale = 1000
        // earliest_presentation_time (8 bytes) = 0
        b += Array(repeating: 0x00, count: 8)
        // first_offset (8 bytes) = 8
        b += [0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x08]
        // reserved + reference_count = 1
        b += [0x00, 0x00, 0x00, 0x01]
        // ref 1: type=0, size=200, duration=500, sap=0
        b += [0x00, 0x00, 0x00, 0xC8, 0x00, 0x00, 0x01, 0xF4, 0x00, 0x00, 0x00, 0x00]
        #expect(b.count == 52)

        let parsed = HLSProxy.parseSidx(data: Data(b), sidxFileOffset: 1000, sidxAtomSize: 52)
        // cursor = 1000 + 52 + 8 = 1060.
        #expect(parsed?.timescale == 1000)
        #expect(parsed?.entries == [
            HLSProxy.SidxEntry(absoluteOffset: 1060, size: 200, durationTicks: 500),
        ])
    }

    @Test("reference_count == 0 returns an empty entries array (no parse failure)")
    func zeroReferences() {
        // Minimum-size v0 sidx with refCount = 0 = 32 bytes.
        var b: [UInt8] = []
        b += [0x00, 0x00, 0x00, 0x20]               // size = 32
        b += Array("sidx".utf8)
        b += [0x00, 0x00, 0x00, 0x00]               // version + flags
        b += [0x00, 0x00, 0x00, 0x01]               // reference_ID
        b += [0x00, 0x00, 0x03, 0xE8]               // timescale = 1000
        b += [0x00, 0x00, 0x00, 0x00]               // earliest
        b += [0x00, 0x00, 0x00, 0x00]               // first_offset
        b += [0x00, 0x00, 0x00, 0x00]               // reserved + refCount = 0
        #expect(b.count == 32)

        let parsed = HLSProxy.parseSidx(data: Data(b), sidxFileOffset: 0, sidxAtomSize: 32)
        #expect(parsed?.timescale == 1000)
        #expect(parsed?.entries == [])
    }
}

// MARK: - makeM3U8

@Suite("HLSProxy.makeM3U8")
struct HLSProxyM3U8Tests {

    @Test("Three contiguous entries: header + EXT-X-MAP + EXTINF + BYTERANGE per entry")
    func threeContiguousEntries() {
        let entries = [
            HLSProxy.SidxEntry(absoluteOffset: 1000, size: 100, durationTicks: 500),
            HLSProxy.SidxEntry(absoluteOffset: 1100, size: 150, durationTicks: 500),
            HLSProxy.SidxEntry(absoluteOffset: 1250, size: 200, durationTicks: 500),
        ]
        let m3u8 = HLSProxy.makeM3U8(
            initSize: 1000,
            segmentURI: "yt-prefetch://yt/audio.mp4",
            mapURI: "yt-prefetch://yt/audio.mp4",
            entries: entries,
            timescale: 1000
        )
        let lines = m3u8.split(separator: "\n").map(String.init)

        #expect(lines.first == "#EXTM3U")
        #expect(lines.contains("#EXT-X-VERSION:7"))
        #expect(lines.contains("#EXT-X-PLAYLIST-TYPE:VOD"))
        #expect(lines.contains("#EXT-X-MAP:URI=\"yt-prefetch://yt/audio.mp4\",BYTERANGE=\"1000@0\""))
        // First entry has no contiguous predecessor → byterange must include @offset.
        #expect(lines.contains("#EXT-X-BYTERANGE:100@1000"))
        // Second entry follows directly (1000 + 100 == 1100) → no @offset.
        #expect(lines.contains("#EXT-X-BYTERANGE:150"))
        // Third entry follows directly (1100 + 150 == 1250) → no @offset.
        #expect(lines.contains("#EXT-X-BYTERANGE:200"))
        // EXTINF formatted with 3 decimals (500/1000 = 0.500).
        #expect(lines.filter { $0 == "#EXTINF:0.500," }.count == 3)
        // Segment URI repeated three times.
        #expect(lines.filter { $0 == "yt-prefetch://yt/audio.mp4" }.count == 3)
        #expect(lines.last == "#EXT-X-ENDLIST")
    }

    @Test("Non-contiguous entries always include @offset on the BYTERANGE line")
    func gappedEntriesAlwaysIncludeOffset() {
        let entries = [
            HLSProxy.SidxEntry(absoluteOffset: 1000, size: 100, durationTicks: 500),
            // Gap: prev end = 1100, this offset = 5000.
            HLSProxy.SidxEntry(absoluteOffset: 5000, size: 200, durationTicks: 500),
        ]
        let m3u8 = HLSProxy.makeM3U8(
            initSize: 1000,
            segmentURI: "x://x",
            mapURI: "x://x",
            entries: entries,
            timescale: 1000
        )
        #expect(m3u8.contains("#EXT-X-BYTERANGE:100@1000"))
        #expect(m3u8.contains("#EXT-X-BYTERANGE:200@5000"))
    }

    @Test("Single entry always carries the @offset half of the BYTERANGE line")
    func singleEntryIncludesOffset() {
        let entries = [
            HLSProxy.SidxEntry(absoluteOffset: 2000, size: 300, durationTicks: 1000),
        ]
        let m3u8 = HLSProxy.makeM3U8(
            initSize: 100, segmentURI: "x://x", mapURI: "x://x", entries: entries, timescale: 1000
        )
        #expect(m3u8.contains("#EXT-X-BYTERANGE:300@2000"))
    }

    @Test("Empty entries still produces a valid header + ENDLIST envelope")
    func emptyEntries() {
        let m3u8 = HLSProxy.makeM3U8(
            initSize: 100, segmentURI: "x://x", mapURI: "x://x", entries: [], timescale: 1000
        )
        #expect(m3u8.hasPrefix("#EXTM3U\n"))
        #expect(m3u8.contains("#EXT-X-MAP:URI=\"x://x\",BYTERANGE=\"100@0\""))
        #expect(m3u8.hasSuffix("#EXT-X-ENDLIST\n"))
        // No EXTINF / BYTERANGE / segment URI when there are no entries.
        #expect(!m3u8.contains("#EXTINF"))
        #expect(!m3u8.contains("#EXT-X-BYTERANGE"))
    }

    @Test("EXTINF stays decimal-period under a comma-locale (proves locale pinning)")
    func localeOverrideStillEmitsPeriod() {
        // Force the bug surface: pass a comma-locale explicitly. The default
        // production path pins to en_US_POSIX, so a test that just asserts
        // current output passes vacuously. Calling with `de_DE` lets us
        // verify the format-string call site itself respects the locale
        // argument — confidence that swapping in POSIX in production is
        // load-bearing, not theatre.
        let m3u8DE = HLSProxy.makeM3U8(
            initSize: 100,
            segmentURI: "x://x",
            mapURI: "x://x",
            entries: [HLSProxy.SidxEntry(absoluteOffset: 200, size: 50, durationTicks: 500)],
            timescale: 1000,
            locale: Locale(identifier: "de_DE")
        )
        // de_DE → comma decimal. Confirms the formatter actually honours the
        // locale arg (so passing POSIX in production is what's preventing the
        // crash on real Italian/German devices).
        #expect(m3u8DE.contains("#EXTINF:0,500,"))
        #expect(!m3u8DE.contains("#EXTINF:0.500,"))

        // Default call (POSIX) emits the spec-compliant period.
        let m3u8POSIX = HLSProxy.makeM3U8(
            initSize: 100,
            segmentURI: "x://x",
            mapURI: "x://x",
            entries: [HLSProxy.SidxEntry(absoluteOffset: 200, size: 50, durationTicks: 500)],
            timescale: 1000
        )
        #expect(m3u8POSIX.contains("#EXTINF:0.500,"))
        #expect(!m3u8POSIX.contains("#EXTINF:0,500,"))
    }
}

// MARK: - route

@Suite("HLSProxy.route")
struct HLSProxyRouteTests {

    @Test("yt-prefetch playlist URL routes to .playlist")
    func playlistRoute() {
        #expect(HLSProxy.route(URL(string: "yt-prefetch://yt/playlist.m3u8")!) == .playlist)
    }

    @Test("yt-prefetch segment URL routes to .segment")
    func segmentRoute() {
        #expect(HLSProxy.route(URL(string: "yt-prefetch://yt/audio.mp4")!) == .segment)
    }

    @Test("https:// URL falls through to .ignore so native loaders take over")
    func httpsIgnored() {
        #expect(HLSProxy.route(URL(string: "https://example.com/audio.mp4")!) == .ignore)
    }

    @Test("Custom-scheme but unknown filename routes to .ignore")
    func unknownPathIgnored() {
        #expect(HLSProxy.route(URL(string: "yt-prefetch://yt/strange.foo")!) == .ignore)
    }
}

// MARK: - findInitAtoms

@Suite("HLSProxy.findInitAtoms")
struct HLSProxyFindInitAtomsTests {

    /// Builds a synthetic file with: ftyp(24) + moov(40) + sidx(56) + mdat tail.
    /// The tail is irrelevant — the walker stops at sidx.
    private func makeInitBlob() -> (data: Data, sidxOffset: Int, sidxSize: Int) {
        var b: [UInt8] = []
        // ftyp atom — size 24, type "ftyp", arbitrary payload.
        b += [0x00, 0x00, 0x00, 0x18]
        b += Array("ftyp".utf8)
        b += Array(repeating: 0xAA, count: 16)
        // moov atom — size 40, type "moov", arbitrary payload.
        b += [0x00, 0x00, 0x00, 0x28]
        b += Array("moov".utf8)
        b += Array(repeating: 0xBB, count: 32)
        let sidxOffset = b.count
        // sidx atom — minimal v0 with a single ref so parseSidx would succeed.
        // Size = 8 (header) + 4 (version+flags) + 4 (refID) + 4 (timescale)
        // + 4 (earliest) + 4 (firstOffset) + 4 (reserved+refCount) + 12 = 44.
        b += [0x00, 0x00, 0x00, 0x2C]               // size = 44
        b += Array("sidx".utf8)
        b += [0x00, 0x00, 0x00, 0x00]               // version=0, flags=0
        b += [0x00, 0x00, 0x00, 0x01]               // reference_ID
        b += [0x00, 0x00, 0x03, 0xE8]               // timescale = 1000
        b += [0x00, 0x00, 0x00, 0x00]               // earliest_presentation_time
        b += [0x00, 0x00, 0x00, 0x00]               // first_offset = 0
        b += [0x00, 0x00, 0x00, 0x01]               // reserved + reference_count = 1
        b += [0x00, 0x00, 0x00, 0xC8, 0x00, 0x00, 0x01, 0xF4, 0x00, 0x00, 0x00, 0x00]
        let sidxSize = b.count - sidxOffset
        // Tail bytes — never read by the walker.
        b += Array(repeating: 0xCC, count: 100)
        return (Data(b), sidxOffset, sidxSize)
    }

    @Test("Walker locates sidx after ftyp + moov and range-fetches the full atom")
    func locatesSidx() async throws {
        let blob = makeInitBlob()
        let initAtoms = try await HLSProxy.findInitAtoms(contentLength: blob.data.count) { range in
            let lo = range.lowerBound
            let hi = min(range.upperBound, blob.data.count - 1)
            return blob.data.subdata(in: lo..<(hi + 1))
        }
        #expect(initAtoms.sidxOffset == blob.sidxOffset)
        #expect(initAtoms.sidxSize == blob.sidxSize)
        #expect(initAtoms.sidxData.count == blob.sidxSize)
    }

    @Test("Throws .missingSidx when the blob has no sidx atom within the scan window")
    func missingSidxThrows() async {
        // ftyp + moov + free (no sidx) — walker should run off the end.
        var b: [UInt8] = []
        b += [0x00, 0x00, 0x00, 0x18] + Array("ftyp".utf8) + Array(repeating: 0, count: 16)
        b += [0x00, 0x00, 0x00, 0x18] + Array("moov".utf8) + Array(repeating: 0, count: 16)
        b += [0x00, 0x00, 0x00, 0x18] + Array("free".utf8) + Array(repeating: 0, count: 16)
        let data = Data(b)
        await #expect(throws: HLSProxy.ProxyError.missingSidx) {
            _ = try await HLSProxy.findInitAtoms(contentLength: data.count) { range in
                data.subdata(in: range.lowerBound..<(min(range.upperBound, data.count - 1) + 1))
            }
        }
    }

    @Test("Throws .missingMoov when sidx appears before moov")
    func sidxWithoutMoov() async {
        // ftyp(24) + sidx(at 24).
        var b: [UInt8] = []
        b += [0x00, 0x00, 0x00, 0x18] + Array("ftyp".utf8) + Array(repeating: 0, count: 16)
        b += [0x00, 0x00, 0x00, 0x10] + Array("sidx".utf8) + Array(repeating: 0, count: 8)
        let data = Data(b)
        await #expect(throws: HLSProxy.ProxyError.missingMoov) {
            _ = try await HLSProxy.findInitAtoms(contentLength: data.count) { range in
                data.subdata(in: range.lowerBound..<(min(range.upperBound, data.count - 1) + 1))
            }
        }
    }

    @Test("Throws .missingFtyp when sidx appears before ftyp")
    func sidxWithoutFtyp() async {
        // moov(24) + sidx(at 24) — no ftyp ever seen.
        var b: [UInt8] = []
        b += [0x00, 0x00, 0x00, 0x18] + Array("moov".utf8) + Array(repeating: 0, count: 16)
        b += [0x00, 0x00, 0x00, 0x10] + Array("sidx".utf8) + Array(repeating: 0, count: 8)
        let data = Data(b)
        await #expect(throws: HLSProxy.ProxyError.missingFtyp) {
            _ = try await HLSProxy.findInitAtoms(contentLength: data.count) { range in
                data.subdata(in: range.lowerBound..<(min(range.upperBound, data.count - 1) + 1))
            }
        }
    }

    @Test("Refuses to walk past contentLength when next header would straddle EOF")
    func walkerStopsBeforeTruncatedHeader() async {
        // ftyp(24) atom, then declared content length cuts off so the next
        // header read (at offset 24) would straddle EOF. Walker must throw
        // .missingSidx rather than asking the fetcher for bytes past the file.
        let b: [UInt8] = [0x00, 0x00, 0x00, 0x18] + Array("ftyp".utf8) + Array(repeating: 0, count: 16)
        let data = Data(b)
        // Pretend the file is only 30 bytes (so off=24 + 16-byte head straddles).
        await #expect(throws: HLSProxy.ProxyError.missingSidx) {
            _ = try await HLSProxy.findInitAtoms(contentLength: 30) { range in
                data.subdata(in: range.lowerBound..<(min(range.upperBound, data.count - 1) + 1))
            }
        }
    }
}

// MARK: - playlistSlice

@Suite("HLSProxy.playlistSlice")
struct HLSProxyPlaylistSliceTests {

    private let body = Data("#EXTM3U\n#EXT-X-VERSION:7\n".utf8)
    // body.count == 24

    @Test("Mid-body slice returns the requested length")
    func midBodySlice() {
        let slice = HLSProxy.playlistSlice(body: body, offset: 0, length: 7)
        #expect(slice.flatMap { String(data: $0, encoding: .utf8) } == "#EXTM3U")
    }

    @Test("Length past tail truncates to the body's last byte")
    func tailTruncation() {
        let slice = HLSProxy.playlistSlice(body: body, offset: 8, length: 1000)
        #expect(slice?.count == body.count - 8)
    }

    @Test("Offset at body.count returns nil so loader signals no-data via finishLoading")
    func offsetAtTailReturnsNil() {
        #expect(HLSProxy.playlistSlice(body: body, offset: body.count, length: 1) == nil)
    }

    @Test("Offset past body.count returns nil")
    func offsetPastTailReturnsNil() {
        #expect(HLSProxy.playlistSlice(body: body, offset: body.count + 1, length: 1) == nil)
    }

    @Test("Negative offset returns nil")
    func negativeOffsetReturnsNil() {
        #expect(HLSProxy.playlistSlice(body: body, offset: -1, length: 5) == nil)
    }
}
