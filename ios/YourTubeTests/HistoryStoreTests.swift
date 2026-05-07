import Testing
import Foundation
import SwiftData
@testable import YourTube

// MARK: - Helpers

/// Build a fully isolated in-memory ``ModelContainer`` for each test. Mirrors
/// the helper used by ``PlaylistStoreTests`` so the schema stays in lockstep.
@MainActor
private func makeContainer() throws -> ModelContainer {
    let schema = Schema(PersistenceSchema.models)
    let config = ModelConfiguration(isStoredInMemoryOnly: true)
    return try ModelContainer(for: schema, configurations: config)
}

private func makeTrack(
    videoId: String = "vid-001",
    title: String = "Track",
    channel: String = "Channel",
    durationSec: Int = 180
) -> Track {
    Track(
        videoId: videoId,
        title: title,
        channel: channel,
        durationSec: durationSec,
        thumbnailUrl: "https://example.com/\(videoId).jpg"
    )
}

// MARK: - HistoryStoreTests

@MainActor
@Suite("HistoryStore")
struct HistoryStoreTests {

    // MARK: Append

    @Test("append — persists track metadata snapshot and timestamp")
    func appendPersistsSnapshot() throws {
        let container = try makeContainer()
        let store = HistoryStore(context: container.mainContext)
        let track = makeTrack(videoId: "abc", title: "Hello", channel: "World", durationSec: 240)
        let when = Date(timeIntervalSinceReferenceDate: 1_000)

        let entry = try store.append(track, playedAt: when)

        #expect(entry.videoId == "abc")
        #expect(entry.title == "Hello")
        #expect(entry.channel == "World")
        #expect(entry.durationSec == 240)
        #expect(entry.thumbnailUrl == "https://example.com/abc.jpg")
        #expect(entry.playedAt == when)
    }

    @Test("append — repeated plays of the same track upsert into a single entry, latest playedAt wins")
    func appendRepeatedPlaysAreDeduped() throws {
        let container = try makeContainer()
        let store = HistoryStore(context: container.mainContext)
        let track = makeTrack(videoId: "abc")

        try store.append(track, playedAt: Date(timeIntervalSinceReferenceDate: 0))
        try store.append(track, playedAt: Date(timeIntervalSinceReferenceDate: 60))
        try store.append(track, playedAt: Date(timeIntervalSinceReferenceDate: 120))

        let all = try store.fetch()
        #expect(all.count == 1)
        #expect(all.first?.videoId == "abc")
        #expect(all.first?.playedAt == Date(timeIntervalSinceReferenceDate: 120))
    }

    @Test("appendUpsertsByVideoId — second append for the same videoId refreshes playedAt instead of inserting")
    func appendUpsertsByVideoId() throws {
        let container = try makeContainer()
        let store = HistoryStore(context: container.mainContext)
        let track = makeTrack(videoId: "dup")
        let t1 = Date(timeIntervalSinceReferenceDate: 1_000)
        let t2 = Date(timeIntervalSinceReferenceDate: 2_000)

        let first = try store.append(track, playedAt: t1)
        let second = try store.append(track, playedAt: t2)

        // Same persistent identity: the upsert returns the existing entry, not a new one.
        #expect(first.persistentModelID == second.persistentModelID)

        let all = try store.fetch()
        #expect(all.count == 1)
        #expect(all.first?.videoId == "dup")
        #expect(all.first?.playedAt == t2)
    }

    @Test("append — upsert refreshes title / channel / thumbnail snapshot on replay")
    func appendUpsertRefreshesMetadataSnapshot() throws {
        let container = try makeContainer()
        let store = HistoryStore(context: container.mainContext)

        try store.append(
            makeTrack(videoId: "x", title: "Old Title", channel: "Old Channel", durationSec: 100),
            playedAt: Date(timeIntervalSinceReferenceDate: 0)
        )
        try store.append(
            Track(
                videoId: "x",
                title: "New Title",
                channel: "New Channel",
                durationSec: 222,
                thumbnailUrl: "https://example.com/x-new.jpg"
            ),
            playedAt: Date(timeIntervalSinceReferenceDate: 60)
        )

        let all = try store.fetch()
        #expect(all.count == 1)
        let only = try #require(all.first)
        #expect(only.title == "New Title")
        #expect(only.channel == "New Channel")
        #expect(only.durationSec == 222)
        #expect(only.thumbnailUrl == "https://example.com/x-new.jpg")
    }

    // MARK: Ordering

    @Test("fetch — returns entries newest-first")
    func fetchReturnsNewestFirst() throws {
        let container = try makeContainer()
        let store = HistoryStore(context: container.mainContext)

        let oldest = Date(timeIntervalSinceReferenceDate: 0)
        let middle = Date(timeIntervalSinceReferenceDate: 60)
        let newest = Date(timeIntervalSinceReferenceDate: 120)

        // Insert in non-monotonic order to prove the sort is data-driven.
        try store.append(makeTrack(videoId: "mid", title: "Middle"), playedAt: middle)
        try store.append(makeTrack(videoId: "old", title: "Oldest"), playedAt: oldest)
        try store.append(makeTrack(videoId: "new", title: "Newest"), playedAt: newest)

        let result = try store.fetch()
        #expect(result.map(\.videoId) == ["new", "mid", "old"])
    }

    @Test("fetch — honours the optional fetch limit")
    func fetchHonoursLimit() throws {
        let container = try makeContainer()
        let store = HistoryStore(context: container.mainContext)

        for i in 0..<10 {
            try store.append(
                makeTrack(videoId: "t\(i)"),
                playedAt: Date(timeIntervalSinceReferenceDate: TimeInterval(i))
            )
        }

        let limited = try store.fetch(limit: 3)
        #expect(limited.count == 3)
        // Newest first: t9, t8, t7.
        #expect(limited.map(\.videoId) == ["t9", "t8", "t7"])
    }

    // MARK: Trim

    @Test("trim — drops the oldest entries beyond the cap")
    func trimDropsOldestEntries() throws {
        let container = try makeContainer()
        let store = HistoryStore(context: container.mainContext)

        for i in 0..<5 {
            try store.append(
                makeTrack(videoId: "t\(i)"),
                playedAt: Date(timeIntervalSinceReferenceDate: TimeInterval(i))
            )
        }

        try store.trim(to: 2)
        let remaining = try store.fetch()
        // Newest two survive (t4, t3).
        #expect(remaining.map(\.videoId) == ["t4", "t3"])
    }

    @Test("trim — no-op when count already within cap")
    func trimIsNoOpWhenWithinLimit() throws {
        let container = try makeContainer()
        let store = HistoryStore(context: container.mainContext)
        try store.append(makeTrack(videoId: "a"))
        try store.append(makeTrack(videoId: "b"))

        try store.trim(to: 5)
        #expect(try store.fetch().count == 2)
    }

    // MARK: Clear

    @Test("clearAll — removes every entry")
    func clearAllRemovesEverything() throws {
        let container = try makeContainer()
        let store = HistoryStore(context: container.mainContext)
        try store.append(makeTrack(videoId: "a"))
        try store.append(makeTrack(videoId: "b"))
        try store.append(makeTrack(videoId: "c"))

        try store.clearAll()
        #expect(try store.fetch().isEmpty)
    }

    // MARK: Round-trip mapping

    @Test("HistoryEntryEntity.asTrack — round-trips Track snapshot fields")
    func asTrackRoundTrip() throws {
        let container = try makeContainer()
        let store = HistoryStore(context: container.mainContext)
        let original = makeTrack(
            videoId: "rt",
            title: "Round Trip",
            channel: "Mapper",
            durationSec: 321
        )
        let entry = try store.append(original)

        let mapped = entry.asTrack()
        #expect(mapped == original)
    }
}

// MARK: - Recently-played replay wiring

@Suite("HistoryStore + PlayerCoordinator wiring")
@MainActor
struct HistoryReplayTests {

    /// Replay-from-history is implemented by handing the entry's ``Track`` to
    /// ``PlayerCoordinator/playNow(_:)``. This test verifies that path drives
    /// playback exactly as a fresh search-result tap would.
    @Test("tapping a history row triggers PlayerCoordinator.playNow")
    func replayTriggersPlayNow() async throws {
        let container = try makeContainer()
        let store = HistoryStore(context: container.mainContext)
        let track = Track(
            videoId: "replay-1",
            title: "Replay Me",
            channel: "Channel",
            durationSec: 200,
            thumbnailUrl: ""
        )
        try store.append(track, playedAt: Date(timeIntervalSinceReferenceDate: 0))

        let engine = FakeAudioEngine()
        let service = RecordingYouTubeService()
        let coordinator = PlayerCoordinator(
            audioEngine: engine,
            youtubeService: service,
            qualityProvider: { .high }
        )

        // Simulate the screen tap by routing the entry's track through
        // playNow — the same closure the shell injects into the screen.
        let entry = try #require(try store.fetch().first)
        coordinator.playNow(entry.asTrack())

        // Drive the resolveTask to completion.
        for _ in 0..<5 { await Task.yield() }

        #expect(coordinator.currentTrack?.videoId == "replay-1")
        #expect(engine.playCallCount == 1)
        #expect(engine.currentTrack?.videoId == "replay-1")
        #expect(service.calls.first?.videoId == "replay-1")
    }

    /// Verifies the production hook: the coordinator's history recorder fires
    /// once playback begins, so live builds will write a ``HistoryEntryEntity``
    /// every time a track reaches the engine.
    @Test("PlayerCoordinator invokes the injected history recorder after engine.play")
    func coordinatorInvokesHistoryRecorder() async {
        let engine = FakeAudioEngine()
        let service = RecordingYouTubeService()

        // Box for the recorder to capture the played track.
        final class Box: @unchecked Sendable {
            var tracks: [Track] = []
        }
        let box = Box()

        let coordinator = PlayerCoordinator(
            audioEngine: engine,
            youtubeService: service,
            qualityProvider: { .high },
            historyRecorder: { track in box.tracks.append(track) }
        )

        let track = Track(
            videoId: "rec-1",
            title: "Record Me",
            channel: "Ch",
            durationSec: 100,
            thumbnailUrl: ""
        )
        coordinator.playNow(track)
        for _ in 0..<5 { await Task.yield() }

        #expect(box.tracks.count == 1)
        #expect(box.tracks.first?.videoId == "rec-1")
    }

    /// A failed stream resolution must NOT record a history entry — history
    /// reflects actual playback intent that the engine accepted.
    @Test("recorder is not called when stream resolution fails")
    func recorderSkippedOnResolutionFailure() async {
        let engine = FakeAudioEngine()
        let service = RecordingYouTubeService()
        service.streamResult = .failure(.noStreamFound)

        final class Box: @unchecked Sendable {
            var calls = 0
        }
        let box = Box()

        let coordinator = PlayerCoordinator(
            audioEngine: engine,
            youtubeService: service,
            qualityProvider: { .high },
            historyRecorder: { _ in box.calls += 1 }
        )

        coordinator.playNow(
            Track(videoId: "x", title: "X", channel: "Ch", durationSec: 1, thumbnailUrl: "")
        )
        for _ in 0..<5 { await Task.yield() }

        #expect(box.calls == 0)
    }
}
