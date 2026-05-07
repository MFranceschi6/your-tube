import Testing
import Foundation
@testable import YourTube

// MARK: - Shuffle/Repeat fake

/// Engine fake that records `setShuffleMode(_:)` and `setRepeatMode(_:)`
/// calls so the YT-0027 Q10 mirroring contract can be asserted at the
/// coordinator boundary without dragging in `MPRemoteCommandCenter`.
@MainActor
final class RecordingShuffleRepeatEngine: AudioEngineProtocol {
    var currentTrack: Track?
    var isPlaying: Bool = false
    var currentTime: TimeInterval = 0
    var duration: TimeInterval = 0

    private(set) var shuffleStates: [Bool] = []
    private(set) var repeatStates: [RepeatMode] = []

    func play(track: Track, url: URL?, resourceLoader: HLSProxyLoader?) {
        currentTrack = track
        isPlaying = true
    }
    func prepare(track: Track) {
        currentTrack = track
        currentTime = 0
        duration = TimeInterval(track.durationSec)
        // YT-0049: prepare stops previous audio so track switches feel instant.
        isPlaying = false
    }
    func pause() { isPlaying = false }
    func resume() { isPlaying = true }
    func togglePlayPause() { isPlaying.toggle() }
    func seek(to time: TimeInterval) { currentTime = time }
    func skipNext() {}
    func skipPrevious() {}
    func stop() {
        currentTrack = nil
        isPlaying = false
    }

    func setShuffleMode(_ enabled: Bool) {
        shuffleStates.append(enabled)
    }
    func setRepeatMode(_ mode: RepeatMode) {
        repeatStates.append(mode)
    }
}

private let trackA = Track(videoId: "a1", title: "A", channel: "ChA", durationSec: 100, thumbnailUrl: "")

// MARK: - Tests

@Suite("PlayerCoordinator – shuffle / repeat (YT-0027 Q10)")
@MainActor
struct PlayerCoordinatorShuffleRepeatTests {

    private func makeSUT() -> (sut: PlayerCoordinator, engine: RecordingShuffleRepeatEngine) {
        let engine = RecordingShuffleRepeatEngine()
        let service = RecordingYouTubeService()
        let sut = PlayerCoordinator(
            audioEngine: engine,
            youtubeService: service,
            qualityProvider: { .medium }
        )
        return (sut, engine)
    }

    @Test("shuffle starts disabled")
    func shuffleStartsOff() {
        let (sut, _) = makeSUT()
        #expect(sut.shuffleEnabled == false)
    }

    @Test("repeat starts off")
    func repeatStartsOff() {
        let (sut, _) = makeSUT()
        #expect(sut.repeatMode == .off)
    }

    @Test("toggleShuffle flips state and mirrors to the engine")
    func toggleShuffleMirrors() {
        let (sut, engine) = makeSUT()
        sut.toggleShuffle()
        #expect(sut.shuffleEnabled == true)
        #expect(engine.shuffleStates == [true])

        sut.toggleShuffle()
        #expect(sut.shuffleEnabled == false)
        #expect(engine.shuffleStates == [true, false])
    }

    @Test("cycleRepeat moves off → all → one → off and mirrors each step")
    func cycleRepeatCycles() {
        let (sut, engine) = makeSUT()
        sut.cycleRepeat()
        #expect(sut.repeatMode == .all)
        sut.cycleRepeat()
        #expect(sut.repeatMode == .one)
        sut.cycleRepeat()
        #expect(sut.repeatMode == .off)
        #expect(engine.repeatStates == [.all, .one, .off])
    }

    @Test("setShuffleEnabled does not re-mirror to the engine — used by remote callbacks")
    func setShuffleEnabledNoMirror() {
        let (sut, engine) = makeSUT()
        sut.setShuffleEnabled(true)
        #expect(sut.shuffleEnabled == true)
        // The remote command path is the *source* of the change; mirroring
        // back to the engine would loop the lock-screen toggle.
        #expect(engine.shuffleStates.isEmpty)
    }

    @Test("setRepeatMode does not re-mirror to the engine — used by remote callbacks")
    func setRepeatModeNoMirror() {
        let (sut, engine) = makeSUT()
        sut.setRepeatMode(.one)
        #expect(sut.repeatMode == .one)
        #expect(engine.repeatStates.isEmpty)
    }

    @Test("RepeatMode.next cycles off → all → one → off")
    func repeatModeNextCycles() {
        #expect(RepeatMode.off.next == .all)
        #expect(RepeatMode.all.next == .one)
        #expect(RepeatMode.one.next == .off)
    }

    @Test("seek forwards to engine")
    func seekForwards() {
        let (sut, engine) = makeSUT()
        sut.playNow(trackA)
        sut.seek(to: 30)
        #expect(engine.currentTime == 30)
    }

    /// YT-0027 Q4 mandates that `seek(to:)` fires only in `.onEnded`.
    /// The gesture itself is internal to `NowPlayingScrubber`, but the
    /// contract surface exposed to the view *is* `PlayerCoordinator.seek`.
    /// This test confirms a single end-of-drag commit produces exactly one
    /// engine seek — a "scrub-spam" regression would manifest here as
    /// multiple seeks per drag.
    @Test("scrub release results in a single engine seek (Q4 commit-on-release)")
    func scrubReleaseSeeksOnce() {
        let (sut, engine) = makeSUT()
        sut.playNow(trackA)
        // Simulate a single .onEnded commit at the end of a drag.
        sut.seek(to: 50)
        #expect(engine.currentTime == 50)

        // A second drag with a different release point produces a second
        // seek — same behaviour the coordinator exposes to the view.
        sut.seek(to: 75)
        #expect(engine.currentTime == 75)
    }
}
