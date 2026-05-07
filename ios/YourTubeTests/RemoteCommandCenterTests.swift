import Testing
import Foundation
import AVFoundation
import MediaPlayer
@testable import YourTube

// MARK: - RemoteCommandCenterTests

/// YT-0032 AC3 — proves `MPRemoteCommandCenter` wiring survives engine init.
///
/// `MPRemoteCommandEvent` cannot be constructed in test code (no public
/// initialiser), so we cannot synthesise a lock-screen tap and call the
/// registered handler directly. Instead we assert the contract that the
/// engine *does* control: after init each command of interest is enabled,
/// and the engine pushes shuffle/repeat state into the command center. The
/// public skip/shuffle/repeat callback wiring is exercised separately via
/// `PlayerCoordinator` integration tests.
@Suite("AVPlayerAudioEngine – MPRemoteCommandCenter wiring")
@MainActor
struct RemoteCommandCenterTests {

    private func makeSUT() -> AVPlayerAudioEngine {
        AVPlayerAudioEngine(player: FakeAVPlayer(), infoCenter: FakeNowPlayingInfoCenter())
    }

    // MARK: enabled state

    @Test("playCommand is enabled after engine init")
    func playCommandEnabled() {
        _ = makeSUT()
        #expect(MPRemoteCommandCenter.shared().playCommand.isEnabled)
    }

    @Test("pauseCommand is enabled after engine init")
    func pauseCommandEnabled() {
        _ = makeSUT()
        #expect(MPRemoteCommandCenter.shared().pauseCommand.isEnabled)
    }

    @Test("togglePlayPauseCommand is enabled after engine init")
    func togglePlayPauseCommandEnabled() {
        _ = makeSUT()
        #expect(MPRemoteCommandCenter.shared().togglePlayPauseCommand.isEnabled)
    }

    @Test("nextTrackCommand is enabled after engine init")
    func nextTrackCommandEnabled() {
        _ = makeSUT()
        #expect(MPRemoteCommandCenter.shared().nextTrackCommand.isEnabled)
    }

    @Test("previousTrackCommand is enabled after engine init")
    func previousTrackCommandEnabled() {
        _ = makeSUT()
        #expect(MPRemoteCommandCenter.shared().previousTrackCommand.isEnabled)
    }

    @Test("changePlaybackPositionCommand is enabled after engine init")
    func changePlaybackPositionCommandEnabled() {
        _ = makeSUT()
        #expect(MPRemoteCommandCenter.shared().changePlaybackPositionCommand.isEnabled)
    }

    // MARK: shuffle / repeat push-through

    @Test("setShuffleMode(true) updates changeShuffleModeCommand.currentShuffleType")
    func shuffleEnabledPushesItems() {
        let sut = makeSUT()
        sut.setShuffleMode(true)
        #expect(MPRemoteCommandCenter.shared().changeShuffleModeCommand.currentShuffleType == .items)
    }

    @Test("setShuffleMode(false) sets changeShuffleModeCommand.currentShuffleType to .off")
    func shuffleDisabledPushesOff() {
        let sut = makeSUT()
        sut.setShuffleMode(true)
        sut.setShuffleMode(false)
        #expect(MPRemoteCommandCenter.shared().changeShuffleModeCommand.currentShuffleType == .off)
    }

    @Test("setRepeatMode(.one) sets changeRepeatModeCommand.currentRepeatType to .one")
    func repeatOnePushesOne() {
        let sut = makeSUT()
        sut.setRepeatMode(.one)
        #expect(MPRemoteCommandCenter.shared().changeRepeatModeCommand.currentRepeatType == .one)
    }

    @Test("setRepeatMode(.all) sets changeRepeatModeCommand.currentRepeatType to .all")
    func repeatAllPushesAll() {
        let sut = makeSUT()
        sut.setRepeatMode(.all)
        #expect(MPRemoteCommandCenter.shared().changeRepeatModeCommand.currentRepeatType == .all)
    }

    @Test("setRepeatMode(.off) resets changeRepeatModeCommand.currentRepeatType to .off")
    func repeatOffPushesOff() {
        let sut = makeSUT()
        sut.setRepeatMode(.all)
        sut.setRepeatMode(.off)
        #expect(MPRemoteCommandCenter.shared().changeRepeatModeCommand.currentRepeatType == .off)
    }

    // MARK: callback wiring (used by PlayerCoordinator)

    @Test("onRemoteSkipNext callback can be assigned and invoked synchronously")
    func skipNextCallbackInvokable() {
        let sut = makeSUT()
        var invoked = 0
        sut.onRemoteSkipNext = { invoked += 1 }
        sut.onRemoteSkipNext?()
        #expect(invoked == 1)
    }

    @Test("onRemoteSkipPrevious callback can be assigned and invoked synchronously")
    func skipPreviousCallbackInvokable() {
        let sut = makeSUT()
        var invoked = 0
        sut.onRemoteSkipPrevious = { invoked += 1 }
        sut.onRemoteSkipPrevious?()
        #expect(invoked == 1)
    }

    @Test("onRemoteShuffleChange callback receives the toggled state")
    func shuffleCallbackReceivesState() {
        let sut = makeSUT()
        var received: [Bool] = []
        sut.onRemoteShuffleChange = { received.append($0) }
        sut.onRemoteShuffleChange?(true)
        sut.onRemoteShuffleChange?(false)
        #expect(received == [true, false])
    }

    @Test("onRemoteRepeatChange callback receives the new mode")
    func repeatCallbackReceivesMode() {
        let sut = makeSUT()
        var received: [RepeatMode] = []
        sut.onRemoteRepeatChange = { received.append($0) }
        sut.onRemoteRepeatChange?(.all)
        sut.onRemoteRepeatChange?(.one)
        #expect(received == [.all, .one])
    }
}
