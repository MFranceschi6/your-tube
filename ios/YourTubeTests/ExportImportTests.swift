import Testing
import Foundation
import SwiftData
@testable import YourTube

// MARK: - Helpers

@MainActor
private func makeContainer() throws -> ModelContainer {
    let schema = Schema(PersistenceSchema.models)
    let config = ModelConfiguration(isStoredInMemoryOnly: true)
    return try ModelContainer(for: schema, configurations: config)
}

private func makeTrack(videoId: String, title: String = "T") -> Track {
    Track(
        videoId: videoId,
        title: title,
        channel: "Channel",
        durationSec: 180,
        thumbnailUrl: "https://example.com/thumb.jpg"
    )
}

private func makePayload(
    id: String = "11111111-1111-4111-8111-111111111111",
    name: String = "Round Trip",
    createdAt: Date = Date(timeIntervalSinceReferenceDate: 1_000),
    updatedAt: Date = Date(timeIntervalSinceReferenceDate: 2_000),
    tracks: [Track] = [
        Track(
            videoId: "abc123",
            title: "Test Track",
            channel: "Test Channel",
            durationSec: 180,
            thumbnailUrl: "https://i.ytimg.com/vi/abc123/mqdefault.jpg"
        )
    ]
) -> PlaylistPayload {
    PlaylistPayload(
        schemaVersion: 1,
        id: id,
        name: name,
        createdAt: createdAt,
        updatedAt: updatedAt,
        tracks: tracks
    )
}

/// Per-test scratch directory under the system temp dir. Cleaned up by the OS;
/// we create a unique subfolder so parallel tests cannot stomp on each other.
private func makeScratchDirectory(_ name: String = UUID().uuidString) throws -> URL {
    let url = FileManager.default.temporaryDirectory
        .appendingPathComponent("ytplaylist-tests-\(name)", isDirectory: true)
    try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
    return url
}

// MARK: - Static fixture (frozen wire format)

/// Frozen `.ytplaylist.json` payload — proves the on-disk schema hasn't
/// drifted from the v1 contract documented in docs/api-contracts.md. Any
/// breaking change to keys, types, or date formatting must bump
/// `PlaylistCodec.supportedSchemaVersion` and update this fixture in lockstep.
private let frozenFixtureJSON = """
{
  "createdAt" : "2026-01-01T00:00:00Z",
  "id" : "11111111-1111-4111-8111-111111111111",
  "name" : "Sample Fixture",
  "schemaVersion" : 1,
  "tracks" : [
    {
      "channel" : "Test Channel",
      "durationSec" : 213,
      "thumbnailUrl" : "https://i.ytimg.com/vi/dQw4w9WgXcQ/mqdefault.jpg",
      "title" : "Never Gonna Give You Up",
      "videoId" : "dQw4w9WgXcQ"
    },
    {
      "channel" : "Lofi Girl",
      "durationSec" : 0,
      "thumbnailUrl" : "https://i.ytimg.com/vi/jfKfPfyJRdk/mqdefault.jpg",
      "title" : "lofi hip hop radio",
      "videoId" : "jfKfPfyJRdk"
    }
  ],
  "updatedAt" : "2026-01-02T00:00:00Z"
}
"""

// MARK: - Export ↔ Import round trip

@Suite("ExportImport — round trip")
struct ExportImportRoundTripTests {

    @Test("write → read → decode round-trips to an equal payload")
    func roundTripThroughTemporaryFile() throws {
        let dir = try makeScratchDirectory()
        let original = makePayload()

        let url = try PlaylistExportService.writeTemporaryFile(for: original, in: dir)

        // The file must exist where we said it would.
        #expect(FileManager.default.fileExists(atPath: url.path))
        #expect(url.lastPathComponent == "Round Trip.ytplaylist.json")

        let raw = try Data(contentsOf: url)
        let decoded = try PlaylistCodec.decode(raw)
        #expect(decoded == original)
    }

    @Test("frozen fixture decodes into a known payload")
    func frozenFixtureDecodes() throws {
        let data = try #require(frozenFixtureJSON.data(using: .utf8))
        let decoded = try PlaylistCodec.decode(data)

        #expect(decoded.schemaVersion == 1)
        #expect(decoded.id == "11111111-1111-4111-8111-111111111111")
        #expect(decoded.name == "Sample Fixture")
        #expect(decoded.tracks.count == 2)
        #expect(decoded.tracks[0].videoId == "dQw4w9WgXcQ")
        #expect(decoded.tracks[0].durationSec == 213)
        #expect(decoded.tracks[1].videoId == "jfKfPfyJRdk")
        #expect(decoded.tracks[1].durationSec == 0)
    }

    @Test("frozen fixture survives a write → read → decode loop unchanged")
    func frozenFixtureRoundTripsOnDisk() throws {
        let data = try #require(frozenFixtureJSON.data(using: .utf8))
        let original = try PlaylistCodec.decode(data)

        let dir = try makeScratchDirectory()
        let url = try PlaylistExportService.writeTemporaryFile(for: original, in: dir)
        #expect(url.lastPathComponent == "Sample Fixture.ytplaylist.json")

        let written = try Data(contentsOf: url)
        let reloaded = try PlaylistCodec.decode(written)
        #expect(reloaded == original)
    }

    @Test("re-encoding the frozen fixture decodes back to the same payload")
    func frozenFixtureReEncodeIsStable() throws {
        let data = try #require(frozenFixtureJSON.data(using: .utf8))
        let payload = try PlaylistCodec.decode(data)
        let reEncoded = try PlaylistCodec.encode(payload)
        let reDecoded = try PlaylistCodec.decode(reEncoded)
        #expect(reDecoded == payload)
    }

    @Test("filename sanitises path-hostile characters")
    func sanitisesFilename() throws {
        let dir = try makeScratchDirectory()
        let payload = makePayload(name: "Lo/fi: Vol*1?")

        let url = try PlaylistExportService.writeTemporaryFile(for: payload, in: dir)

        // Slashes/colons/asterisks/question marks must be stripped before the suffix.
        #expect(!url.lastPathComponent.contains("/"))
        #expect(!url.lastPathComponent.contains(":"))
        #expect(!url.lastPathComponent.contains("*"))
        #expect(!url.lastPathComponent.contains("?"))
        #expect(url.lastPathComponent.hasSuffix(".ytplaylist.json"))
    }

    @Test("blank name falls back to default filename")
    func blankNameFallsBack() throws {
        let dir = try makeScratchDirectory()
        let payload = makePayload(name: "   ")

        let url = try PlaylistExportService.writeTemporaryFile(for: payload, in: dir)
        #expect(url.lastPathComponent == "Playlist.ytplaylist.json")
    }
}

// MARK: - Import error paths

@MainActor
@Suite("PlaylistImportService — errors")
struct PlaylistImportErrorTests {

    @Test("invalid JSON → invalidSchema error")
    func invalidJSON() throws {
        let container = try makeContainer()
        let store = PlaylistStore(context: container.mainContext)
        let garbage = Data("not json".utf8)

        do {
            _ = try PlaylistImportService.importPlaylist(from: garbage, into: store)
            Issue.record("Expected invalidSchema, got success")
        } catch let error as PlaylistImportError {
            switch error {
            case .invalidSchema:
                break  // expected
            default:
                Issue.record("Expected invalidSchema, got \(error)")
            }
        }
    }

    @Test("future schemaVersion → unsupportedSchemaVersion error with version surfaced")
    func unsupportedSchemaVersion() throws {
        let container = try makeContainer()
        let store = PlaylistStore(context: container.mainContext)
        let json = """
        {
          "schemaVersion": 999,
          "id": "00000000-0000-4000-8000-000000000002",
          "name": "Future",
          "createdAt": "2026-05-02T09:00:00Z",
          "updatedAt": "2026-05-02T09:30:00Z",
          "tracks": []
        }
        """.data(using: .utf8)!

        do {
            _ = try PlaylistImportService.importPlaylist(from: json, into: store)
            Issue.record("Expected unsupportedSchemaVersion, got success")
        } catch let error as PlaylistImportError {
            switch error {
            case .unsupportedSchemaVersion(let v):
                #expect(v == 999)
            default:
                Issue.record("Expected unsupportedSchemaVersion, got \(error)")
            }
        }
    }

    @Test("error user-facing strings are non-empty")
    func userFacingStrings() {
        let cases: [PlaylistImportError] = [
            .fileRead("x"),
            .invalidSchema("x"),
            .unsupportedSchemaVersion(999),
            .persistenceFailed("x"),
        ]
        for error in cases {
            #expect(!error.alertTitle.isEmpty)
            #expect(!error.userMessage.isEmpty)
        }
        // Schema-version case carries the dedicated "Update Required" title.
        #expect(PlaylistImportError.unsupportedSchemaVersion(2).alertTitle == "Update Required")
    }
}

// MARK: - Import conflict resolution (last-write-wins)

@MainActor
@Suite("PlaylistImportService — conflict resolution")
struct PlaylistImportConflictTests {

    @Test("new id → inserts new playlist")
    func insertsNewPlaylist() throws {
        let container = try makeContainer()
        let store = PlaylistStore(context: container.mainContext)
        let payload = makePayload(
            id: "fresh-id",
            name: "Imported",
            tracks: [makeTrack(videoId: "v1"), makeTrack(videoId: "v2")]
        )

        let outcome = try PlaylistImportService.importPlaylist(
            from: PlaylistCodec.encode(payload),
            into: store
        )

        #expect(outcome.id == "fresh-id")
        #expect(outcome.name == "Imported")
        #expect(outcome.trackCount == 2)
    }

    @Test("existing id with newer updatedAt → replaces local copy")
    func newerIncomingReplaces() throws {
        let container = try makeContainer()
        let store = PlaylistStore(context: container.mainContext)
        let id = "conflict-id"
        let early = Date(timeIntervalSinceReferenceDate: 1_000)
        let later = Date(timeIntervalSinceReferenceDate: 2_000)

        try store.create(name: "Local", id: id, now: early)

        let incoming = makePayload(
            id: id,
            name: "Remote",
            createdAt: early,
            updatedAt: later,
            tracks: [makeTrack(videoId: "remote-only")]
        )
        let outcome = try PlaylistImportService.importPlaylist(
            from: PlaylistCodec.encode(incoming),
            into: store
        )

        #expect(outcome.name == "Remote")
        #expect(outcome.trackCount == 1)
        let fetched = try store.fetch(id: id)
        #expect(fetched.name == "Remote")
        #expect(fetched.orderedTracks.first?.videoId == "remote-only")
    }

    @Test("existing id with older or equal updatedAt → preserves local copy")
    func equalOrOlderIncomingPreservesLocal() throws {
        let container = try makeContainer()
        let store = PlaylistStore(context: container.mainContext)
        let id = "preserve-id"
        let stamp = Date(timeIntervalSinceReferenceDate: 5_000)

        try store.create(name: "LocalKeeper", id: id, now: stamp)

        let incoming = makePayload(
            id: id,
            name: "RemoteLoser",
            createdAt: stamp,
            updatedAt: stamp,  // not newer
            tracks: [makeTrack(videoId: "remote-only")]
        )
        let outcome = try PlaylistImportService.importPlaylist(
            from: PlaylistCodec.encode(incoming),
            into: store
        )

        // Outcome reflects the *kept* local copy (no overwrite).
        #expect(outcome.name == "LocalKeeper")
        #expect(outcome.trackCount == 0)
        let fetched = try store.fetch(id: id)
        #expect(fetched.name == "LocalKeeper")
        #expect(fetched.orderedTracks.isEmpty)
    }

    @Test("file URL round-trip — write to temp, import via URL")
    func fileURLRoundTrip() throws {
        let container = try makeContainer()
        let store = PlaylistStore(context: container.mainContext)
        let dir = try makeScratchDirectory()
        let payload = makePayload(
            id: "file-url-id",
            name: "FromFile",
            tracks: [makeTrack(videoId: "fv1")]
        )

        let url = try PlaylistExportService.writeTemporaryFile(for: payload, in: dir)
        let outcome = try PlaylistImportService.importPlaylist(from: url, into: store)

        #expect(outcome.id == "file-url-id")
        #expect(outcome.name == "FromFile")
        #expect(outcome.trackCount == 1)
    }

    /// YT-0047: Library's `.fileImporter` and the scene-root `.onOpenURL`
    /// both forward the picked URL into `PlaylistImportService.importPlaylist(from:into:)`
    /// — there must be no second parsing path. This regression test pins the
    /// shared URL contract: a freshly-exported `.ytplaylist.json` URL imports
    /// identically to its in-memory payload.
    @Test("Library importer URL contract — same path as onOpenURL")
    func libraryImporterURLContract() throws {
        let container = try makeContainer()
        let store = PlaylistStore(context: container.mainContext)
        let dir = try makeScratchDirectory()
        let payload = makePayload(
            id: "library-importer-id",
            name: "PickedFromFiles",
            tracks: [makeTrack(videoId: "lv1"), makeTrack(videoId: "lv2")]
        )

        // Exact path Library's `.fileImporter` callback would receive.
        let url = try PlaylistExportService.writeTemporaryFile(for: payload, in: dir)
        #expect(url.lastPathComponent.lowercased().hasSuffix(".ytplaylist.json"))

        let outcome = try PlaylistImportService.importPlaylist(from: url, into: store)
        #expect(outcome.id == "library-importer-id")
        #expect(outcome.name == "PickedFromFiles")
        #expect(outcome.trackCount == 2)
    }

    /// YT-0047: Library's `.fileImporter` accepts `.json` as a fallback UTType
    /// because iOS resolves the multi-segment `.ytplaylist.json` extension as
    /// plain JSON. A picked `.json` file that is NOT a playlist (e.g. some
    /// random JSON the user picked by accident) must surface a clean
    /// `.invalidSchema` error rather than crash or upsert garbage.
    @Test("Non-playlist JSON URL surfaces invalidSchema, not a crash")
    func nonPlaylistJSONFails() throws {
        let container = try makeContainer()
        let store = PlaylistStore(context: container.mainContext)
        let dir = try makeScratchDirectory()

        // Write a stray JSON file under the scratch dir using the same
        // suffix Library's pre-flight check would reject. We exercise the
        // service directly to prove it fails safely if the pre-flight is
        // ever bypassed.
        let strayURL = dir.appendingPathComponent("not-a-playlist.json")
        try Data(#"{"hello":"world"}"#.utf8).write(to: strayURL, options: .atomic)

        do {
            _ = try PlaylistImportService.importPlaylist(from: strayURL, into: store)
            Issue.record("Expected invalidSchema, got success")
        } catch let error as PlaylistImportError {
            switch error {
            case .invalidSchema:
                break  // expected — schemaVersion key missing
            default:
                Issue.record("Expected invalidSchema, got \(error)")
            }
        }
    }
}
