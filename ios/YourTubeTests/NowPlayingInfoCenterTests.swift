import Testing
import Foundation
import AVFoundation
import MediaPlayer
@testable import YourTube

// MARK: - NowPlayingInfoCenterTests

/// Verifies the YT-0027 Q10 contract for `MPNowPlayingInfoCenter` updates:
/// the engine writes title / artist / duration / elapsed / rate on every
/// state change, and clears them on `stop()`. Uses a `FakeNowPlayingInfoCenter`
/// so assertions don't depend on the global singleton across test runs.
@Suite("AVPlayerAudioEngine – MPNowPlayingInfoCenter mirroring")
@MainActor
struct NowPlayingInfoCenterTests {

    private func makeSUT() -> (
        sut: AVPlayerAudioEngine,
        player: FakeAVPlayer,
        info: FakeNowPlayingInfoCenter
    ) {
        let player = FakeAVPlayer()
        let info = FakeNowPlayingInfoCenter()
        let sut = AVPlayerAudioEngine(player: player, infoCenter: info)
        return (sut, player, info)
    }

    private let sample = Track(
        videoId: "abc123",
        title: "Test Track",
        channel: "Test Channel",
        durationSec: 180,
        thumbnailUrl: ""
    )

    // MARK: play(track:)

    @Test("play(track:) writes title and artist into nowPlayingInfo")
    func playWritesTitleArtist() {
        let (sut, _, info) = makeSUT()
        sut.play(track: sample)
        #expect(info.nowPlayingInfo?[MPMediaItemPropertyTitle] as? String == sample.title)
        #expect(info.nowPlayingInfo?[MPMediaItemPropertyArtist] as? String == sample.channel)
    }

    @Test("play(track:) writes duration and rate=1.0")
    func playWritesDurationAndRate() {
        let (sut, _, info) = makeSUT()
        sut.play(track: sample)
        #expect(info.nowPlayingInfo?[MPMediaItemPropertyPlaybackDuration] as? TimeInterval == 180)
        #expect(info.nowPlayingInfo?[MPNowPlayingInfoPropertyPlaybackRate] as? Double == 1.0)
    }

    // MARK: pause / resume

    @Test("pause() writes rate=0.0 to nowPlayingInfo")
    func pauseWritesRateZero() {
        let (sut, _, info) = makeSUT()
        sut.play(track: sample)
        sut.pause()
        #expect(info.nowPlayingInfo?[MPNowPlayingInfoPropertyPlaybackRate] as? Double == 0.0)
    }

    @Test("resume() writes rate=1.0 to nowPlayingInfo")
    func resumeWritesRateOne() {
        let (sut, _, info) = makeSUT()
        sut.play(track: sample)
        sut.pause()
        sut.resume()
        #expect(info.nowPlayingInfo?[MPNowPlayingInfoPropertyPlaybackRate] as? Double == 1.0)
    }

    // MARK: seek

    @Test("seek(to:) writes elapsed time into nowPlayingInfo")
    func seekWritesElapsed() {
        let (sut, _, info) = makeSUT()
        sut.play(track: sample)
        sut.seek(to: 42.5)
        #expect(info.nowPlayingInfo?[MPNowPlayingInfoPropertyElapsedPlaybackTime] as? TimeInterval == 42.5)
    }

    // MARK: stop

    @Test("stop() clears nowPlayingInfo")
    func stopClearsInfo() {
        let (sut, _, info) = makeSUT()
        sut.play(track: sample)
        sut.stop()
        #expect(info.nowPlayingInfo == nil)
    }

    // MARK: Q10 — media-type + shuffle/repeat command-center mirroring

    @Test("play(track:) writes media-type=audio so lock screen renders audio chrome")
    func playWritesMediaTypeAudio() {
        let (sut, _, info) = makeSUT()
        sut.play(track: sample)
        let raw = info.nowPlayingInfo?[MPNowPlayingInfoPropertyMediaType] as? UInt
        #expect(raw == MPNowPlayingInfoMediaType.audio.rawValue)
    }

    @Test("setShuffleMode toggles refresh nowPlayingInfo so the lock-screen scrubber stays current")
    func shuffleToggleRefreshesInfo() {
        let (sut, _, info) = makeSUT()
        sut.play(track: sample)
        let before = info.setCount
        sut.setShuffleMode(true)
        #expect(info.setCount > before)
        let afterOn = info.setCount
        sut.setShuffleMode(false)
        #expect(info.setCount > afterOn)
    }

    @Test("setRepeatMode cycles refresh nowPlayingInfo so the lock-screen scrubber stays current")
    func repeatCycleRefreshesInfo() {
        let (sut, _, info) = makeSUT()
        sut.play(track: sample)
        let before = info.setCount
        sut.setRepeatMode(.all)
        #expect(info.setCount > before)
        let afterAll = info.setCount
        sut.setRepeatMode(.one)
        #expect(info.setCount > afterAll)
        let afterOne = info.setCount
        sut.setRepeatMode(.off)
        #expect(info.setCount > afterOne)
    }

    // MARK: write-counter sanity

    @Test("transitions push at least one update each — covers Q10 mirror cadence")
    func everyTransitionUpdates() {
        let (sut, _, info) = makeSUT()
        let baseline = info.setCount
        sut.play(track: sample)
        let afterPlay = info.setCount
        sut.pause()
        let afterPause = info.setCount
        sut.resume()
        let afterResume = info.setCount
        sut.seek(to: 30)
        let afterSeek = info.setCount

        // Each transition must flush at least one update so the lock screen
        // never lags behind the in-app state. We don't pin to an exact count
        // because internal helpers (artwork load, time observer) may layer
        // additional updates on top.
        #expect(afterPlay > baseline)
        #expect(afterPause > afterPlay)
        #expect(afterResume > afterPause)
        #expect(afterSeek > afterResume)
    }
}
