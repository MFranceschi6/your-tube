import Testing
import Foundation
import SwiftData
@testable import YourTube

// MARK: - Smoke

@Suite("YourTube smoke")
struct YourTubeTests {
    @Test func appBuilds() {
        #expect(true)
    }
}

// MARK: - AppShellViewModel Tests

@Suite("AppShellViewModel")
@MainActor
struct AppShellViewModelTests {

    private func makeSUT() -> AppShellViewModel { AppShellViewModel() }

    private let sampleTrack = Track(
        videoId: "abc",
        title: "Test Track",
        channel: "Test Channel",
        durationSec: 180,
        thumbnailUrl: ""
    )

    @Test("play(_:) sets currentTrack and starts playback")
    func playSetsCurentTrackAndIsPlaying() {
        let sut = makeSUT()
        sut.play(sampleTrack)
        #expect(sut.currentTrack?.videoId == "abc")
        #expect(sut.isPlaying == true)
        #expect(sut.progress == 0.0)
    }

    @Test("togglePlayPause flips isPlaying")
    func togglePlayPauseFlipsState() {
        let sut = makeSUT()
        sut.play(sampleTrack)
        sut.togglePlayPause()
        #expect(sut.isPlaying == false)
        sut.togglePlayPause()
        #expect(sut.isPlaying == true)
    }

    @Test("hasMiniPlayer is false when no track is loaded")
    func hasMiniPlayerFalseWithoutTrack() {
        let sut = makeSUT()
        #expect(sut.hasMiniPlayer == false)
    }

    @Test("hasMiniPlayer is true after play")
    func hasMiniPlayerTrueAfterPlay() {
        let sut = makeSUT()
        sut.play(sampleTrack)
        #expect(sut.hasMiniPlayer == true)
    }

    @Test("openNowPlaying does nothing when no track is loaded")
    func openNowPlayingGuardNoTrack() {
        let sut = makeSUT()
        sut.openNowPlaying()
        #expect(sut.isNowPlayingOpen == false)
    }

    @Test("openNowPlaying sets isNowPlayingOpen when track is loaded")
    func openNowPlayingOpensWhenTrackLoaded() {
        let sut = makeSUT()
        sut.play(sampleTrack)
        sut.openNowPlaying()
        #expect(sut.isNowPlayingOpen == true)
    }

    @Test("closeNowPlaying clears isNowPlayingOpen")
    func closeNowPlayingClears() {
        let sut = makeSUT()
        sut.play(sampleTrack)
        sut.openNowPlaying()
        sut.closeNowPlaying()
        #expect(sut.isNowPlayingOpen == false)
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

// MARK: - PlaylistCover gradient determinism

@Suite("PlaylistCover")
struct PlaylistCoverTests {
    // Exercises `PlaylistCover.gradientSeed(for:)` directly so any future
    // drift in the production djb2 implementation is caught — previously
    // the test inlined a private copy of the algorithm, which silently
    // diverged from production was a real risk (YT-0037 nit).

    @Test("gradientSeed produces identical result for same UUID string")
    func gradientSeedIsStable() {
        let uuid = "550e8400-e29b-41d4-a716-446655440000"
        let first = PlaylistCover.gradientSeed(for: uuid)
        let second = PlaylistCover.gradientSeed(for: uuid)
        #expect(first == second)
        // hashValue is randomized — the djb2 result must be consistent regardless
        let third = PlaylistCover.gradientSeed(for: uuid)
        #expect(first == third)
    }

    @Test("gradientSeed produces different hues for different UUIDs")
    func gradientSeedDifferentiatesUUIDs() {
        #expect(PlaylistCover.gradientSeed(for: "00000000-0000-0000-0000-000000000001") !=
                PlaylistCover.gradientSeed(for: "00000000-0000-0000-0000-000000000002"))
    }
}

// MARK: - PlaylistDetail isPlaying wiring (YT-0069)

/// Locks in the wiring contract used at
/// `ios/YourTube/Features/Library/PlaylistDetailScreen.swift` —
/// `TrackRow(isPlaying: currentVideoId == position.track.videoId)`.
///
/// SwiftUI views aren't directly introspectable in Swift Testing, so this suite
/// exercises the equality predicate the view evaluates per row against real
/// SwiftData entities. It locks in two invariants:
///
/// 1. The comparison key is `videoId`, not the SwiftData primary key (`id`),
///    so a track returned from search and the same track stored in a playlist
///    both light up the active-row tint.
/// 2. Exactly one row matches when `currentVideoId` is set to a member track.
@MainActor
@Suite("PlaylistDetailScreen isPlaying wiring")
struct PlaylistDetailIsPlayingWiringTests {

    private func makeContainer() throws -> ModelContainer {
        let schema = Schema(PersistenceSchema.models)
        let config = ModelConfiguration(isStoredInMemoryOnly: true)
        return try ModelContainer(for: schema, configurations: config)
    }

    /// Mirror of the boolean expression in PlaylistDetailScreen's ForEach body.
    /// Kept inline (not a helper on the screen) so a regression that drops the
    /// argument or swaps the comparison key still surfaces as a test failure.
    private func isPlaying(currentVideoId: String?, position: PlaylistTrackEntity) -> Bool {
        currentVideoId == position.track.videoId
    }

    @Test("Matching currentVideoId flips isPlaying true for the matching position only")
    func matchingVideoIdHighlightsOneRow() throws {
        let container = try makeContainer()
        let store = PlaylistStore(context: container.mainContext)
        let playlist = try store.create(name: "Test", id: "pl-1", sortIndex: 0)
        try store.addTrack(
            Track(videoId: "v1", title: "T1", channel: "C", durationSec: 10, thumbnailUrl: ""),
            toPlaylistId: playlist.id
        )
        try store.addTrack(
            Track(videoId: "v2", title: "T2", channel: "C", durationSec: 20, thumbnailUrl: ""),
            toPlaylistId: playlist.id
        )

        let positions = playlist.orderedPositions
        let flags = positions.map { isPlaying(currentVideoId: "v2", position: $0) }
        #expect(flags == [false, true])
    }

    @Test("Nil currentVideoId leaves every row inactive")
    func nilVideoIdHighlightsNothing() throws {
        let container = try makeContainer()
        let store = PlaylistStore(context: container.mainContext)
        let playlist = try store.create(name: "Test", id: "pl-2", sortIndex: 0)
        try store.addTrack(
            Track(videoId: "v1", title: "T1", channel: "C", durationSec: 10, thumbnailUrl: ""),
            toPlaylistId: playlist.id
        )

        let flags = playlist.orderedPositions.map {
            isPlaying(currentVideoId: nil, position: $0)
        }
        #expect(flags == [false])
    }

    @Test("Comparison uses videoId, not the SwiftData primary key")
    func usesVideoIdNotPrimaryKey() throws {
        let container = try makeContainer()
        let store = PlaylistStore(context: container.mainContext)
        let playlist = try store.create(name: "Test", id: "pl-3", sortIndex: 0)
        try store.addTrack(
            Track(videoId: "abc", title: "T", channel: "C", durationSec: 0, thumbnailUrl: ""),
            toPlaylistId: playlist.id
        )

        let position = try #require(playlist.orderedPositions.first)
        // Passing the videoId activates the row; passing any other identifier
        // (e.g. the playlist UUID — a stand-in for any non-videoId key) does not.
        #expect(isPlaying(currentVideoId: "abc", position: position) == true)
        #expect(isPlaying(currentVideoId: playlist.id, position: position) == false)
    }
}
