import Testing
@testable import YourTube

// MARK: - Smoke

@Suite("YourTube smoke")
struct YourTubeTests {
    @Test func appBuilds() {
        #expect(true)
    }
}

// MARK: - PlaylistCodec tests

/// Canonical fixture JSON — byte-for-byte equivalent to docs/fixtures/playlist-valid-v1.ytplaylist.json
private let validV1JSON = """
{
  "schemaVersion": 1,
  "id": "00000000-0000-4000-8000-000000000001",
  "name": "MVP Round Trip",
  "createdAt": "2026-05-02T09:00:00Z",
  "updatedAt": "2026-05-02T09:30:00Z",
  "tracks": [
    {
      "videoId": "dQw4w9WgXcQ",
      "title": "Fixture Track One",
      "channel": "Fixture Channel",
      "durationSec": 213,
      "thumbnailUrl": "https://i.ytimg.com/vi/dQw4w9WgXcQ/mqdefault.jpg"
    },
    {
      "videoId": "jfKfPfyJRdk",
      "title": "Fixture Track Two",
      "channel": "Fixture Channel",
      "durationSec": 0,
      "thumbnailUrl": "https://i.ytimg.com/vi/jfKfPfyJRdk/mqdefault.jpg"
    }
  ]
}
"""

/// Canonical fixture JSON — byte-for-byte equivalent to docs/fixtures/playlist-future-schema.ytplaylist.json
private let futureSchemaJSON = """
{
  "schemaVersion": 999,
  "id": "00000000-0000-4000-8000-000000000002",
  "name": "Future Schema Fixture",
  "createdAt": "2026-05-02T09:00:00Z",
  "updatedAt": "2026-05-02T09:30:00Z",
  "tracks": [
    {
      "videoId": "dQw4w9WgXcQ",
      "title": "Future Fixture Track",
      "channel": "Fixture Channel",
      "durationSec": 213,
      "thumbnailUrl": "https://i.ytimg.com/vi/dQw4w9WgXcQ/mqdefault.jpg"
    }
  ]
}
"""

@Suite("PlaylistCodec")
struct PlaylistCodecTests {

    // AC4-a: Decode the valid v1 fixture → all fields match expected values.
    @Test("Decode valid v1 fixture — all fields match")
    func decodeValidV1() throws {
        let data = try #require(validV1JSON.data(using: .utf8))
        let payload = try PlaylistCodec.decode(data)

        #expect(payload.schemaVersion == 1)
        #expect(payload.id == "00000000-0000-4000-8000-000000000001")
        #expect(payload.name == "MVP Round Trip")

        // ISO8601 dates
        let formatter = ISO8601DateFormatter()
        #expect(payload.createdAt == formatter.date(from: "2026-05-02T09:00:00Z"))
        #expect(payload.updatedAt == formatter.date(from: "2026-05-02T09:30:00Z"))

        // Tracks
        #expect(payload.tracks.count == 2)
        let first = payload.tracks[0]
        #expect(first.videoId == "dQw4w9WgXcQ")
        #expect(first.title == "Fixture Track One")
        #expect(first.channel == "Fixture Channel")
        #expect(first.durationSec == 213)
        #expect(first.thumbnailUrl == "https://i.ytimg.com/vi/dQw4w9WgXcQ/mqdefault.jpg")

        let second = payload.tracks[1]
        #expect(second.videoId == "jfKfPfyJRdk")
        #expect(second.durationSec == 0)
    }

    // AC4-b: Encode a PlaylistPayload → decode back → round-trip equality.
    @Test("Encode → decode round-trip produces equal payload")
    func roundTrip() throws {
        let formatter = ISO8601DateFormatter()
        let original = PlaylistPayload(
            schemaVersion: 1,
            id: "11111111-1111-4111-8111-111111111111",
            name: "Round Trip Test",
            createdAt: try #require(formatter.date(from: "2026-01-01T00:00:00Z")),
            updatedAt: try #require(formatter.date(from: "2026-01-02T12:00:00Z")),
            tracks: [
                Track(
                    videoId: "abc123",
                    title: "Test Track",
                    channel: "Test Channel",
                    durationSec: 180,
                    thumbnailUrl: "https://i.ytimg.com/vi/abc123/mqdefault.jpg"
                )
            ]
        )

        let encoded = try PlaylistCodec.encode(original)
        let decoded = try PlaylistCodec.decode(encoded)

        #expect(decoded == original)
    }

    // AC4-c: Decode the future-schema fixture → codec returns an error, not a crash.
    @Test("Decode future-schema fixture → unsupportedSchemaVersion error")
    func rejectFutureSchema() throws {
        let data = try #require(futureSchemaJSON.data(using: .utf8))
        do {
            _ = try PlaylistCodec.decode(data)
            Issue.record("Expected PlaylistCodecError.unsupportedSchemaVersion but decode succeeded")
        } catch PlaylistCodecError.unsupportedSchemaVersion(let version) {
            #expect(version == 999)
        } catch {
            Issue.record("Expected PlaylistCodecError.unsupportedSchemaVersion but got: \(error)")
        }
    }

    // Extra: corrupted data produces decodingFailed, not a crash.
    @Test("Decode corrupted data → decodingFailed error")
    func rejectCorruptedData() throws {
        let garbage = Data("not json at all".utf8)
        do {
            _ = try PlaylistCodec.decode(garbage)
            Issue.record("Expected PlaylistCodecError.decodingFailed but decode succeeded")
        } catch PlaylistCodecError.decodingFailed {
            // Expected path — pass.
        } catch {
            Issue.record("Expected PlaylistCodecError.decodingFailed but got: \(error)")
        }
    }
}
