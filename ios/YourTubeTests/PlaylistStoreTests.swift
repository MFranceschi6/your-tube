import Testing
import Foundation
import SwiftData
@testable import YourTube

// MARK: - Helpers

/// Build a fully isolated in-memory ModelContainer for each test.
/// Must run on MainActor because `ModelContainer.mainContext` is MainActor-bound.
@MainActor
private func makeContainer() throws -> ModelContainer {
    let schema = Schema(PersistenceSchema.models)
    let config = ModelConfiguration(isStoredInMemoryOnly: true)
    return try ModelContainer(for: schema, configurations: config)
}

/// A short track fixture.
private func makeTrack(
    videoId: String = "vid-001",
    title: String = "Test Track",
    channel: String = "Test Channel",
    durationSec: Int = 180,
    thumbnailUrl: String = "https://example.com/thumb.jpg"
) -> Track {
    Track(
        videoId: videoId,
        title: title,
        channel: channel,
        durationSec: durationSec,
        thumbnailUrl: thumbnailUrl
    )
}

// MARK: - PlaylistStoreTests

@MainActor
@Suite("PlaylistStore")
struct PlaylistStoreTests {

    // MARK: Create

    @Test("create — inserts a playlist with correct metadata")
    func createPlaylist() throws {
        let container = try makeContainer()
        let store = PlaylistStore(context: container.mainContext)
        let now = Date(timeIntervalSinceReferenceDate: 0)

        let playlist = try store.create(name: "My Playlist", now: now)

        #expect(playlist.name == "My Playlist")
        #expect(playlist.createdAt == now)
        #expect(playlist.updatedAt == now)
        #expect(playlist.trackPositions.isEmpty)
    }

    @Test("create — uses provided id")
    func createPlaylistWithId() throws {
        let container = try makeContainer()
        let store = PlaylistStore(context: container.mainContext)
        let id = "fixed-id-123"

        let playlist = try store.create(name: "Named", id: id)

        #expect(playlist.id == id)
    }

    // MARK: fetchAll / fetch

    @Test("fetchAll — returns all playlists")
    func fetchAll() throws {
        let container = try makeContainer()
        let store = PlaylistStore(context: container.mainContext)

        try store.create(name: "A")
        try store.create(name: "B")
        try store.create(name: "C")

        let all = try store.fetchAll()
        #expect(all.count == 3)
    }

    @Test("fetch — throws notFound for unknown id")
    func fetchUnknownId() throws {
        let container = try makeContainer()
        let store = PlaylistStore(context: container.mainContext)

        do {
            _ = try store.fetch(id: "nonexistent")
            Issue.record("Expected notFound error")
        } catch PlaylistStoreError.notFound(let id) {
            #expect(id == "nonexistent")
        }
    }

    // MARK: Rename

    @Test("rename — updates name and updatedAt")
    func renamePlaylist() throws {
        let container = try makeContainer()
        let store = PlaylistStore(context: container.mainContext)
        let created = Date(timeIntervalSinceReferenceDate: 1_000)
        let renamed = Date(timeIntervalSinceReferenceDate: 2_000)

        let playlist = try store.create(name: "Original", now: created)
        try store.rename(id: playlist.id, to: "Renamed", now: renamed)

        let fetched = try store.fetch(id: playlist.id)
        #expect(fetched.name == "Renamed")
        #expect(fetched.updatedAt == renamed)
    }

    @Test("rename — throws notFound for unknown id")
    func renameUnknownId() throws {
        let container = try makeContainer()
        let store = PlaylistStore(context: container.mainContext)

        do {
            try store.rename(id: "ghost", to: "New Name")
            Issue.record("Expected notFound error")
        } catch PlaylistStoreError.notFound(let id) {
            #expect(id == "ghost")
        }
    }

    // MARK: Delete

    @Test("delete — removes playlist from store")
    func deletePlaylist() throws {
        let container = try makeContainer()
        let store = PlaylistStore(context: container.mainContext)

        let playlist = try store.create(name: "To Delete")
        let id = playlist.id
        try store.delete(id: id)

        do {
            _ = try store.fetch(id: id)
            Issue.record("Expected notFound after delete")
        } catch PlaylistStoreError.notFound {
            // Expected.
        }
    }

    @Test("delete — throws notFound for unknown id")
    func deleteUnknownId() throws {
        let container = try makeContainer()
        let store = PlaylistStore(context: container.mainContext)

        do {
            try store.delete(id: "ghost")
            Issue.record("Expected notFound error")
        } catch PlaylistStoreError.notFound(let id) {
            #expect(id == "ghost")
        }
    }

    // MARK: addTrack

    @Test("addTrack — appends track at correct position")
    func addTrack() throws {
        let container = try makeContainer()
        let store = PlaylistStore(context: container.mainContext)

        let playlist = try store.create(name: "P")
        let t1 = makeTrack(videoId: "v1", title: "Track 1")
        let t2 = makeTrack(videoId: "v2", title: "Track 2")

        try store.addTrack(t1, toPlaylistId: playlist.id)
        try store.addTrack(t2, toPlaylistId: playlist.id)

        let fetched = try store.fetch(id: playlist.id)
        let ordered = fetched.orderedTracks
        #expect(ordered.count == 2)
        #expect(ordered[0].videoId == "v1")
        #expect(ordered[1].videoId == "v2")
    }

    @Test("addTrack — throws duplicateTrack when videoId already in playlist")
    func addDuplicateTrack() throws {
        let container = try makeContainer()
        let store = PlaylistStore(context: container.mainContext)

        let playlist = try store.create(name: "P")
        let track = makeTrack(videoId: "dup")
        try store.addTrack(track, toPlaylistId: playlist.id)

        do {
            try store.addTrack(track, toPlaylistId: playlist.id)
            Issue.record("Expected duplicateTrack error")
        } catch PlaylistStoreError.duplicateTrack(let vid) {
            #expect(vid == "dup")
        }
    }

    // MARK: removeTrack

    @Test("removeTrack — removes track and renormalises positions")
    func removeTrack() throws {
        let container = try makeContainer()
        let store = PlaylistStore(context: container.mainContext)

        let playlist = try store.create(name: "P")
        try store.addTrack(makeTrack(videoId: "v1"), toPlaylistId: playlist.id)
        try store.addTrack(makeTrack(videoId: "v2"), toPlaylistId: playlist.id)
        try store.addTrack(makeTrack(videoId: "v3"), toPlaylistId: playlist.id)

        try store.removeTrack(videoId: "v2", fromPlaylistId: playlist.id)

        let fetched = try store.fetch(id: playlist.id)
        let ordered = fetched.orderedTracks
        #expect(ordered.count == 2)
        #expect(ordered[0].videoId == "v1")
        #expect(ordered[1].videoId == "v3")
        // Positions must be contiguous 0, 1.
        let positions = fetched.orderedPositions.map(\.position)
        #expect(positions == [0, 1])
    }

    @Test("removeTrack — throws notFound for videoId not in playlist")
    func removeAbsentTrack() throws {
        let container = try makeContainer()
        let store = PlaylistStore(context: container.mainContext)

        let playlist = try store.create(name: "P")

        do {
            try store.removeTrack(videoId: "absent", fromPlaylistId: playlist.id)
            Issue.record("Expected notFound error")
        } catch PlaylistStoreError.notFound(let vid) {
            #expect(vid == "absent")
        }
    }

    // MARK: reorderTrack

    @Test("reorderTrack — moves track forward in list")
    func reorderForward() throws {
        let container = try makeContainer()
        let store = PlaylistStore(context: container.mainContext)

        let playlist = try store.create(name: "P")
        for i in 1...4 {
            try store.addTrack(makeTrack(videoId: "v\(i)"), toPlaylistId: playlist.id)
        }

        // Move v1 (index 0) to index 2 → expected order: v2, v3, v1, v4
        try store.reorderTrack(inPlaylistId: playlist.id, fromIndex: 0, toIndex: 2)

        let ordered = try store.fetch(id: playlist.id).orderedTracks
        #expect(ordered.map(\.videoId) == ["v2", "v3", "v1", "v4"])
    }

    @Test("reorderTrack — moves track backward in list")
    func reorderBackward() throws {
        let container = try makeContainer()
        let store = PlaylistStore(context: container.mainContext)

        let playlist = try store.create(name: "P")
        for i in 1...4 {
            try store.addTrack(makeTrack(videoId: "v\(i)"), toPlaylistId: playlist.id)
        }

        // Move v4 (index 3) to index 1 → expected order: v1, v4, v2, v3
        try store.reorderTrack(inPlaylistId: playlist.id, fromIndex: 3, toIndex: 1)

        let ordered = try store.fetch(id: playlist.id).orderedTracks
        #expect(ordered.map(\.videoId) == ["v1", "v4", "v2", "v3"])
    }

    @Test("reorderTrack — no-op when fromIndex equals toIndex")
    func reorderSameIndex() throws {
        let container = try makeContainer()
        let store = PlaylistStore(context: container.mainContext)

        let playlist = try store.create(name: "P")
        try store.addTrack(makeTrack(videoId: "v1"), toPlaylistId: playlist.id)
        try store.addTrack(makeTrack(videoId: "v2"), toPlaylistId: playlist.id)

        try store.reorderTrack(inPlaylistId: playlist.id, fromIndex: 0, toIndex: 0)

        let ordered = try store.fetch(id: playlist.id).orderedTracks
        #expect(ordered.map(\.videoId) == ["v1", "v2"])
    }

    @Test("reorderTrack — throws indexOutOfRange for out-of-bounds indices")
    func reorderOutOfBounds() throws {
        let container = try makeContainer()
        let store = PlaylistStore(context: container.mainContext)

        let playlist = try store.create(name: "P")
        try store.addTrack(makeTrack(videoId: "v1"), toPlaylistId: playlist.id)

        do {
            try store.reorderTrack(inPlaylistId: playlist.id, fromIndex: 0, toIndex: 5)
            Issue.record("Expected indexOutOfRange error")
        } catch PlaylistStoreError.indexOutOfRange {
            // Expected.
        }
    }

    // MARK: Import upsert

    @Test("importPlaylist — creates new playlist when id is absent")
    func importCreatesNew() throws {
        let container = try makeContainer()
        let store = PlaylistStore(context: container.mainContext)

        let formatter = ISO8601DateFormatter()
        let payload = PlaylistPayload(
            schemaVersion: 1,
            id: "import-new-id",
            name: "Imported Playlist",
            createdAt: try #require(formatter.date(from: "2026-01-01T00:00:00Z")),
            updatedAt: try #require(formatter.date(from: "2026-01-02T00:00:00Z")),
            tracks: [makeTrack(videoId: "iv1"), makeTrack(videoId: "iv2")]
        )

        try store.importPlaylist(payload)

        let fetched = try store.fetch(id: "import-new-id")
        #expect(fetched.name == "Imported Playlist")
        #expect(fetched.orderedTracks.count == 2)
        #expect(fetched.orderedTracks[0].videoId == "iv1")
    }

    @Test("importPlaylist — overwrites existing when incoming updatedAt is newer")
    func importOverwritesOlder() throws {
        let container = try makeContainer()
        let store = PlaylistStore(context: container.mainContext)

        let formatter = ISO8601DateFormatter()
        let early = try #require(formatter.date(from: "2026-01-01T00:00:00Z"))
        let later = try #require(formatter.date(from: "2026-01-02T00:00:00Z"))
        let id = "upsert-id"

        try store.create(name: "Old Name", id: id, now: early)

        let payload = PlaylistPayload(
            schemaVersion: 1,
            id: id,
            name: "New Name",
            createdAt: early,
            updatedAt: later,
            tracks: [makeTrack(videoId: "new-track")]
        )
        try store.importPlaylist(payload)

        let fetched = try store.fetch(id: id)
        #expect(fetched.name == "New Name")
        #expect(fetched.updatedAt == later)
        #expect(fetched.orderedTracks.count == 1)
        #expect(fetched.orderedTracks[0].videoId == "new-track")
    }

    @Test("importPlaylist — skips overwrite when existing updatedAt is same or newer")
    func importSkipsWhenNotNewer() throws {
        let container = try makeContainer()
        let store = PlaylistStore(context: container.mainContext)

        let formatter = ISO8601DateFormatter()
        let t = try #require(formatter.date(from: "2026-01-01T12:00:00Z"))
        let id = "skip-id"

        try store.create(name: "Local Name", id: id, now: t)

        let payload = PlaylistPayload(
            schemaVersion: 1,
            id: id,
            name: "Remote Name",
            createdAt: t,
            updatedAt: t,  // Same timestamp → no overwrite.
            tracks: [makeTrack(videoId: "remote-track")]
        )
        try store.importPlaylist(payload)

        let fetched = try store.fetch(id: id)
        // Name should remain "Local Name" and no tracks should be added.
        #expect(fetched.name == "Local Name")
        #expect(fetched.orderedTracks.isEmpty)
    }
}

