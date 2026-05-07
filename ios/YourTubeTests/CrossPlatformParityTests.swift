import Testing
import Foundation
@testable import YourTube

// MARK: - YT-0034 — Cross-platform playlist round trip
//
// Pairs with `android/core/data/src/test/kotlin/com/yourtube/core/data/codec/CrossPlatformParityTest.kt`.
// Both tests share the canonical fixtures under `docs/fixtures/` and the same
// expected domain payload (see `canonicalPayload` below).
//
// What this side proves:
//   - iOS decodes the Android-shaped export (`playlist-android-export-canonical.ytplaylist.json`)
//     into the same `PlaylistPayload` the Android test asserts on.
//   - The committed iOS-canonical fixture decodes into that same payload, and the codec
//     round-trips the canonical record back to itself.
//   - `playlist-future-schema.ytplaylist.json` is rejected with the documented
//     `PlaylistCodecError.unsupportedSchemaVersion` error.
//
// Fixtures stay byte-deterministic: no clocks, no UUID generation, no environment data.
//
// Note: this test must NOT change codec output. If it ever fails because the encoded bytes
// drift, file a follow-up task — the parity test exists to expose drift, not paper over it.

@Suite("YT-0034 — Cross-platform playlist round trip")
struct CrossPlatformParityTests {

    // MARK: Canonical fixture filenames (kept in lockstep with the Android test).

    private static let androidCanonicalFixture = "playlist-android-export-canonical.ytplaylist.json"
    private static let iosCanonicalFixture = "playlist-ios-export-canonical.ytplaylist.json"
    private static let futureSchemaFixture = "playlist-future-schema.ytplaylist.json"

    // MARK: Canonical domain payload — the contract both clients must agree on.

    private static let canonicalPayload: PlaylistPayload = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime]
        return PlaylistPayload(
            schemaVersion: 1,
            id: "00000000-0000-4000-8000-000000000010",
            name: "Cross Platform Round Trip",
            // Force-unwraps are safe: literals are valid ISO-8601 strings.
            createdAt: f.date(from: "2026-05-02T09:00:00Z")!,
            updatedAt: f.date(from: "2026-05-02T09:30:00Z")!,
            tracks: [
                Track(
                    videoId: "dQw4w9WgXcQ",
                    title: "Fixture Track One",
                    channel: "Fixture Channel",
                    durationSec: 213,
                    thumbnailUrl: "https://i.ytimg.com/vi/dQw4w9WgXcQ/mqdefault.jpg"
                ),
                Track(
                    videoId: "jfKfPfyJRdk",
                    title: "Fixture Track Two",
                    channel: "Fixture Channel",
                    durationSec: 0,
                    thumbnailUrl: "https://i.ytimg.com/vi/jfKfPfyJRdk/mqdefault.jpg"
                ),
            ]
        )
    }()

    // MARK: - Tests

    @Test("decodes the Android canonical export into the shared domain payload")
    func decodesAndroidCanonicalExport() throws {
        let data = try CrossPlatformParityTests.fixtureData(Self.androidCanonicalFixture)

        let decoded = try PlaylistCodec.decode(data)

        // We compare field-by-field rather than via `==` so a failure says exactly
        // which field drifted — which is the whole point of this parity test.
        let expected = Self.canonicalPayload
        #expect(decoded.schemaVersion == expected.schemaVersion)
        #expect(decoded.id == expected.id)
        #expect(decoded.name == expected.name)
        #expect(decoded.createdAt == expected.createdAt)
        #expect(decoded.updatedAt == expected.updatedAt)
        #expect(decoded.tracks.count == expected.tracks.count)
        for (lhs, rhs) in zip(decoded.tracks, expected.tracks) {
            #expect(lhs.videoId == rhs.videoId)
            #expect(lhs.title == rhs.title)
            #expect(lhs.channel == rhs.channel)
            #expect(lhs.durationSec == rhs.durationSec)
            #expect(lhs.thumbnailUrl == rhs.thumbnailUrl)
        }
        // Belt-and-braces full equality (catches anything the loop missed).
        #expect(decoded == expected)
    }

    @Test("decodes the iOS canonical fixture and round-trips through the codec")
    func iosCanonicalFixtureRoundTrips() throws {
        // Anchor the iOS canonical fixture against the codec by:
        //   1. Decoding the committed fixture (proves the file matches what iOS expects).
        //   2. Re-encoding the canonical domain payload and re-decoding it (proves the
        //      codec round-trips the same logical record).
        // We deliberately compare structurally rather than byte-by-byte so that benign
        // formatting tweaks in `JSONEncoder` (e.g. whitespace) don't make this test
        // brittle — the *parity* contract is logical equality, not bytes.

        let fixtureData = try CrossPlatformParityTests.fixtureData(Self.iosCanonicalFixture)
        let decodedFromFixture = try PlaylistCodec.decode(fixtureData)
        #expect(decodedFromFixture == Self.canonicalPayload)

        let reEncoded = try PlaylistCodec.encode(Self.canonicalPayload)
        let reDecoded = try PlaylistCodec.decode(reEncoded)
        #expect(reDecoded == Self.canonicalPayload)
        #expect(reDecoded == decodedFromFixture)
    }

    @Test("rejects the future-schema fixture with unsupportedSchemaVersion")
    func rejectsFutureSchemaFixture() throws {
        let data = try CrossPlatformParityTests.fixtureData(Self.futureSchemaFixture)

        do {
            _ = try PlaylistCodec.decode(data)
            Issue.record("Expected unsupportedSchemaVersion, got success")
        } catch let error as PlaylistCodecError {
            switch error {
            case .unsupportedSchemaVersion(let version):
                #expect(version == 999)
            default:
                Issue.record("Expected unsupportedSchemaVersion, got \(error)")
            }
        }
    }

    // MARK: - Fixture loading
    //
    // Fixtures live at `docs/fixtures/*.ytplaylist.json` so the Android JUnit
    // suite reads the same bytes. The iOS test target ships the directory as
    // a `folder`-typed Resources reference (see `ios/project.yml`), so the
    // simulator sandbox can resolve them through the test bundle.

    private final class BundleAnchor {}

    private static func fixtureData(_ filename: String) throws -> Data {
        let bundle = Bundle(for: BundleAnchor.self)
        guard let url = bundle.url(
            forResource: filename,
            withExtension: nil,
            subdirectory: "fixtures"
        ) ?? bundle.url(forResource: filename, withExtension: nil) else {
            throw FixtureError.missing(filename)
        }
        return try Data(contentsOf: url)
    }

    private enum FixtureError: Error, CustomStringConvertible {
        case missing(String)
        var description: String {
            switch self {
            case .missing(let name): return "Missing fixture \(name) in test bundle"
            }
        }
    }
}
