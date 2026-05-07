import Testing
import Foundation
import AVFoundation
import MediaPlayer
@testable import YourTube

// MARK: - FakeAVPlayer

/// Fake player for unit testing AVPlayerAudioEngine without a real AVPlayer.
@MainActor
final class FakeAVPlayer: AVPlayerWrapping {
    private(set) var currentItem: AVPlayerItem?
    private(set) var rate: Float = 0
    private(set) var didPlay = false
    private(set) var didPause = false
    private(set) var lastSeekTime: CMTime?
    private(set) var replaceItemCallCount = 0
    /// Counts pause calls so tests can distinguish "pause was triggered N times"
    /// from "pause has fired at least once" (YT-0049).
    private(set) var pauseCallCount = 0

    func play() {
        rate = 1
        didPlay = true
    }

    func pause() {
        rate = 0
        didPause = true
        pauseCallCount += 1
    }

    func seek(to time: CMTime, toleranceBefore: CMTime, toleranceAfter: CMTime) {
        lastSeekTime = time
    }

    func replaceCurrentItem(with item: AVPlayerItem?) {
        currentItem = item
        replaceItemCallCount += 1
    }

    /// Captured periodic time observer block. Tests that need to simulate a
    /// stale tick (YT-0049 reviewer follow-up) can call this directly to
    /// reproduce the published-state regression where a queued tick from the
    /// previous AVPlayer item lands after `prepare(track:)` has zeroed
    /// `currentTime` and would otherwise clobber it.
    private(set) var capturedTimeObserverBlock: (@Sendable (CMTime) -> Void)?

    func addPeriodicTimeObserver(
        forInterval interval: CMTime,
        queue: DispatchQueue?,
        using block: @escaping @Sendable (CMTime) -> Void
    ) -> Any {
        capturedTimeObserverBlock = block
        return NSObject()
    }

    func removeTimeObserver(_ observer: Any) {}
}

// MARK: - Sample fixture

private let sampleTrack = Track(
    videoId: "abc123",
    title: "Test Track",
    channel: "Test Channel",
    durationSec: 240,
    thumbnailUrl: ""
)

// MARK: - AudioEngineTests

@Suite("AVPlayerAudioEngine")
@MainActor
struct AudioEngineTests {

    private func makeSUT() -> (sut: AVPlayerAudioEngine, fake: FakeAVPlayer) {
        let fake = FakeAVPlayer()
        let sut = AVPlayerAudioEngine(player: fake)
        return (sut, fake)
    }

    // MARK: play(track:)

    @Test("play(track:) sets currentTrack")
    func playSetsCurentTrack() {
        let (sut, _) = makeSUT()
        sut.play(track: sampleTrack)
        #expect(sut.currentTrack?.videoId == "abc123")
    }

    @Test("play(track:) sets isPlaying to true")
    func playSetsIsPlaying() {
        let (sut, _) = makeSUT()
        sut.play(track: sampleTrack)
        #expect(sut.isPlaying == true)
    }

    @Test("play(track:) resets currentTime to 0")
    func playResetsCurrentTime() {
        let (sut, _) = makeSUT()
        sut.play(track: sampleTrack)
        #expect(sut.currentTime == 0)
    }

    @Test("play(track:) sets duration from track.durationSec")
    func playSetsDuration() {
        let (sut, _) = makeSUT()
        sut.play(track: sampleTrack)
        #expect(sut.duration == 240)
    }

    // MARK: pause()

    @Test("pause() sets isPlaying to false")
    func pauseSetsIsPlayingFalse() {
        let (sut, _) = makeSUT()
        sut.play(track: sampleTrack)
        sut.pause()
        #expect(sut.isPlaying == false)
    }

    @Test("pause() calls player.pause()")
    func pauseCallsPlayerPause() {
        let (sut, fake) = makeSUT()
        sut.play(track: sampleTrack)
        sut.pause()
        #expect(fake.didPause == true)
    }

    // MARK: resume()

    @Test("resume() sets isPlaying to true")
    func resumeSetsIsPlayingTrue() {
        let (sut, _) = makeSUT()
        sut.play(track: sampleTrack)
        sut.pause()
        sut.resume()
        #expect(sut.isPlaying == true)
    }

    @Test("resume() calls player.play()")
    func resumeCallsPlayerPlay() {
        let (sut, fake) = makeSUT()
        sut.resume()
        #expect(fake.didPlay == true)
    }

    // MARK: togglePlayPause()

    @Test("togglePlayPause() from playing pauses")
    func toggleFromPlayingPauses() {
        let (sut, _) = makeSUT()
        sut.play(track: sampleTrack)
        sut.togglePlayPause()
        #expect(sut.isPlaying == false)
    }

    @Test("togglePlayPause() from paused resumes")
    func toggleFromPausedResumes() {
        let (sut, _) = makeSUT()
        sut.play(track: sampleTrack)
        sut.pause()
        sut.togglePlayPause()
        #expect(sut.isPlaying == true)
    }

    // MARK: seek(to:)

    @Test("seek(to:) updates currentTime")
    func seekUpdatesCurrentTime() {
        let (sut, _) = makeSUT()
        sut.play(track: sampleTrack)
        sut.seek(to: 42.5)
        #expect(sut.currentTime == 42.5)
    }

    @Test("seek(to:) forwards correct CMTime to player")
    func seekForwardsToPlayer() {
        let (sut, fake) = makeSUT()
        sut.seek(to: 30)
        let expected = CMTime(seconds: 30, preferredTimescale: CMTimeScale(NSEC_PER_SEC))
        #expect(fake.lastSeekTime == expected)
    }

    // MARK: stop()

    @Test("stop() clears currentTrack")
    func stopClearsCurrentTrack() {
        let (sut, _) = makeSUT()
        sut.play(track: sampleTrack)
        sut.stop()
        #expect(sut.currentTrack == nil)
    }

    @Test("stop() sets isPlaying to false")
    func stopSetsIsPlayingFalse() {
        let (sut, _) = makeSUT()
        sut.play(track: sampleTrack)
        sut.stop()
        #expect(sut.isPlaying == false)
    }

    @Test("stop() resets currentTime and duration to 0")
    func stopResetsTimeAndDuration() {
        let (sut, _) = makeSUT()
        sut.play(track: sampleTrack)
        sut.seek(to: 10)
        sut.stop()
        #expect(sut.currentTime == 0)
        #expect(sut.duration == 0)
    }

    @Test("stop() calls player.replaceCurrentItem(with: nil)")
    func stopReplacesCurrentItemWithNil() {
        let (sut, fake) = makeSUT()
        sut.play(track: sampleTrack)
        sut.stop()
        #expect(fake.replaceItemCallCount >= 1)
        #expect(fake.currentItem == nil)
    }

    // MARK: handleInterruption(_:)

    @Test("interruption .began sets isPlaying to false")
    func interruptionBeganPauses() {
        let (sut, _) = makeSUT()
        sut.play(track: sampleTrack)

        let notification = Notification(
            name: AVAudioSession.interruptionNotification,
            object: nil,
            userInfo: [
                AVAudioSessionInterruptionTypeKey: AVAudioSession.InterruptionType.began.rawValue
            ]
        )
        sut.handleInterruption(notification)
        #expect(sut.isPlaying == false)
    }

    @Test("interruption .ended with shouldResume resumes playback")
    func interruptionEndedResumesWhenShouldResume() {
        let (sut, fake) = makeSUT()
        sut.play(track: sampleTrack)
        sut.pause()

        let notification = Notification(
            name: AVAudioSession.interruptionNotification,
            object: nil,
            userInfo: [
                AVAudioSessionInterruptionTypeKey: AVAudioSession.InterruptionType.ended.rawValue,
                AVAudioSessionInterruptionOptionKey: AVAudioSession.InterruptionOptions.shouldResume.rawValue,
            ]
        )
        sut.handleInterruption(notification)
        #expect(sut.isPlaying == true)
        #expect(fake.didPlay == true)
    }

    @Test("interruption .ended without shouldResume stays paused")
    func interruptionEndedWithoutShouldResumeStaysPaused() {
        let (sut, _) = makeSUT()
        sut.play(track: sampleTrack)
        sut.pause()

        let notification = Notification(
            name: AVAudioSession.interruptionNotification,
            object: nil,
            userInfo: [
                AVAudioSessionInterruptionTypeKey: AVAudioSession.InterruptionType.ended.rawValue,
                AVAudioSessionInterruptionOptionKey: UInt(0),
            ]
        )
        sut.handleInterruption(notification)
        #expect(sut.isPlaying == false)
    }

    // MARK: MPNowPlayingInfoCenter

    @Test("play(track:) sets nowPlayingInfo title and artist")
    func playUpdatesNowPlayingInfoTitle() {
        let (sut, _) = makeSUT()
        sut.play(track: sampleTrack)
        let info = MPNowPlayingInfoCenter.default().nowPlayingInfo
        #expect(info?[MPMediaItemPropertyTitle] as? String == sampleTrack.title)
        #expect(info?[MPMediaItemPropertyArtist] as? String == sampleTrack.channel)
    }

    @Test("play(track:) sets nowPlayingInfo playback rate to 1")
    func playSetNowPlayingRate() {
        let (sut, _) = makeSUT()
        sut.play(track: sampleTrack)
        let info = MPNowPlayingInfoCenter.default().nowPlayingInfo
        #expect(info?[MPNowPlayingInfoPropertyPlaybackRate] as? Double == 1.0)
    }

    @Test("pause() sets nowPlayingInfo playback rate to 0")
    func pauseSetsNowPlayingRateToZero() {
        let (sut, _) = makeSUT()
        sut.play(track: sampleTrack)
        sut.pause()
        let info = MPNowPlayingInfoCenter.default().nowPlayingInfo
        #expect(info?[MPNowPlayingInfoPropertyPlaybackRate] as? Double == 0.0)
    }

    // MARK: play(track:url:) — YT-0044 stream URL wiring

    /// Closes the YT-0044 regression: the coordinator now hands the resolved
    /// stream URL to the engine, and the engine must load it into AVPlayer via
    /// `replaceCurrentItem(with:)` and start playback. Without this step
    /// AVPlayer parks at `AVPlayerWaitingWithNoItemToPlayReason` and no audio
    /// is ever audible.
    @Test("play(track:url:) loads the resolved URL into the underlying player and starts playback")
    func playWithURLLoadsItemAndPlays() {
        let (sut, fake) = makeSUT()
        let streamURL = URL(string: "https://example.invalid/stream.m4a")!

        sut.play(track: sampleTrack, url: streamURL)

        #expect(fake.replaceItemCallCount == 1)
        #expect(fake.currentItem?.asset is AVURLAsset)
        let asset = fake.currentItem?.asset as? AVURLAsset
        #expect(asset?.url == streamURL)
        #expect(fake.didPlay == true)
    }

    /// YT-0046 v2 regression: the v1 fix preloaded both `playable` and
    /// `duration`, but naming `duration` as an automatically loaded key forces
    /// AVFoundation to wait for a full duration probe before flipping the item
    /// to ready-to-play. On multi-hour YouTube CDN streams the probe stalls
    /// long enough that the user never hears audio. The engine now only loads
    /// `playable` and constructs the asset with
    /// `AVURLAssetPreferPreciseDurationAndTimingKey: false` so playback starts
    /// as soon as the URL is reachable; duration resolves later.
    @Test("play(track:url:) builds asset without duration probe for progressive streaming (YT-0046 v2)")
    func playWithURLBuildsAssetWithoutDurationProbe() throws {
        let (sut, fake) = makeSUT()
        let streamURL = URL(string: "https://example.invalid/long-mix.m4a")!

        sut.play(track: sampleTrack, url: streamURL)

        let item = try #require(fake.currentItem)
        let loadedKeys = item.automaticallyLoadedAssetKeys
        #expect(loadedKeys.contains("playable"))
        // Critical: `duration` must NOT be in the automatically-loaded list.
        // Otherwise AVFoundation blocks the item's ready-to-play transition on
        // a full duration probe — fatal for multi-hour HTTPS streams.
        #expect(!loadedKeys.contains("duration"))
        // Sanity: the underlying asset is still the URL we handed in.
        let asset = try #require(item.asset as? AVURLAsset)
        #expect(asset.url == streamURL)
    }

    /// YT-0046 v3: the engine activates `AVAudioSession` defensively in init
    /// (in addition to the App-level activation) so audio queue teardown does
    /// not happen mid-playback for long streams. A failed activation must
    /// never crash the engine — the smoke test simply asserts `init` returns
    /// a usable instance.
    @Test("init() does not crash when activating AVAudioSession (YT-0046 v3)")
    func initActivatesAudioSessionWithoutCrashing() {
        let fake = FakeAVPlayer()
        let sut = AVPlayerAudioEngine(player: fake)
        // Smoke check: the engine is in its idle initial state.
        #expect(sut.currentTrack == nil)
        #expect(sut.isPlaying == false)
        #expect(sut.duration == 0)
    }

    // MARK: prepare(track:) — YT-0049 immediate stop on track switch

    /// User-reported regression: tapping a new track in Search/Library/Queue
    /// must stop the previously-playing audio immediately. The engine's
    /// `prepare(track:)` is the synchronous step before stream resolution,
    /// so it has to pause the underlying player and clear its current item
    /// right there — otherwise AVPlayer keeps pumping samples from the old
    /// stream until `play(track:url:)` runs hundreds of ms later.
    @Test("prepare(track:) pauses the underlying player and clears its current item")
    func prepareStopsPreviousAudio() {
        let (sut, fake) = makeSUT()
        let streamURL = URL(string: "https://example.invalid/a.m4a")!
        sut.play(track: sampleTrack, url: streamURL)
        // play(track:url:) loaded an item via replaceCurrentItem.
        #expect(fake.currentItem != nil)
        let other = Track(videoId: "z9", title: "Z", channel: "ZCh", durationSec: 60, thumbnailUrl: "")

        sut.prepare(track: other)

        #expect(fake.pauseCallCount == 1)
        // Two replace calls: the play() above (#1) and prepare's clear (#2).
        #expect(fake.replaceItemCallCount == 2)
        #expect(fake.currentItem == nil)
    }

    @Test("prepare(track:) resets observable state to the new track")
    func prepareResetsObservableState() {
        let (sut, _) = makeSUT()
        sut.play(track: sampleTrack, url: URL(string: "https://example.invalid/a.m4a")!)
        let other = Track(videoId: "z9", title: "Z", channel: "ZCh", durationSec: 60, thumbnailUrl: "")

        sut.prepare(track: other)

        #expect(sut.currentTrack?.videoId == "z9")
        #expect(sut.isPlaying == false)
        #expect(sut.currentTime == 0)
        #expect(sut.duration == 60)
    }

    /// AC#3 from YT-0049: lock screen and Control Center must show the new
    /// track's metadata immediately, not the previous track's title.
    @Test("prepare(track:) updates nowPlayingInfo to the new track immediately")
    func prepareUpdatesNowPlayingInfoToNewTrack() {
        let fakePlayer = FakeAVPlayer()
        let infoCenter = FakeNowPlayingInfoCenter()
        let sut = AVPlayerAudioEngine(player: fakePlayer, infoCenter: infoCenter)
        sut.play(track: sampleTrack, url: URL(string: "https://example.invalid/a.m4a")!)
        let newTrack = Track(videoId: "z9", title: "Zen Mix", channel: "Zen", durationSec: 60, thumbnailUrl: "")

        sut.prepare(track: newTrack)

        let info = infoCenter.nowPlayingInfo
        #expect(info?[MPMediaItemPropertyTitle] as? String == "Zen Mix")
        #expect(info?[MPMediaItemPropertyArtist] as? String == "Zen")
        // No item has been loaded yet — playback rate must be 0 so the lock
        // screen does not render a fake "playing" state during the resolve
        // window.
        #expect((info?[MPNowPlayingInfoPropertyPlaybackRate] as? Double) == 0)
    }

    // MARK: YT-0049 reviewer follow-up — published-state contract

    /// Reviewer's blocker on the original YT-0049 review (2026-05-06): the
    /// engine's `currentTime` was zeroed inside `prepare(track:)` but the
    /// SwiftUI scrubber kept the previous track's elapsed time on screen
    /// because a stale periodic time observer tick from the previous
    /// AVPlayer item could land *after* `prepare(track:)` had cleared the
    /// current item, clobbering `currentTime` back to a non-zero (or NaN)
    /// value just before SwiftUI rendered.
    ///
    /// This test simulates that stale tick and asserts the *published*
    /// `currentTime` (the value SwiftUI binds to via
    /// `PlayerCoordinator.currentTime` → `AppShellViewModel.currentTime`)
    /// stays 0 across the prepare → stale-tick sequence. Distinct from the
    /// engine-state ordering test above, which only asserts the synchronous
    /// post-`prepare` value.
    @Test("prepare(track:) keeps published currentTime at 0 even if a stale time observer tick lands afterward")
    func preparePublishedTimeStaysZeroAcrossStaleObserverTick() async {
        let (sut, fake) = makeSUT()
        sut.play(track: sampleTrack, url: URL(string: "https://example.invalid/a.m4a")!)
        let other = Track(videoId: "z9", title: "Z", channel: "ZCh", durationSec: 60, thumbnailUrl: "")

        sut.prepare(track: other)
        // Synchronous post-prepare contract is already covered above; assert
        // it here too so the regression scope is unambiguous.
        #expect(sut.currentTime == 0)

        // Simulate a stale periodic-time-observer tick that was queued for
        // the *previous* item before `prepare(track:)` cleared it. The fake
        // captures the engine's installed block; firing it after the clear
        // reproduces the production race.
        if let block = fake.capturedTimeObserverBlock {
            let staleTime = CMTime(seconds: 41.5, preferredTimescale: CMTimeScale(NSEC_PER_SEC))
            block(staleTime)
            // Yield so the @MainActor Task spawned by the block runs.
            await Task.yield()
        }

        // Published `currentTime` (what SwiftUI reads) must still be 0.
        #expect(sut.currentTime == 0)
    }

    /// Companion to the stale-tick test: also assert that ticks carrying
    /// non-finite seconds (e.g. CMTime `.invalid`, which AVPlayer can emit
    /// for a fraction of a second after `replaceCurrentItem(with: nil)`)
    /// are dropped and never assigned to `currentTime`. Without this guard
    /// the scrubber renders NaN width math.
    @Test("time observer drops ticks with non-finite seconds")
    func timeObserverDropsNonFiniteTicks() async {
        let (sut, fake) = makeSUT()
        let track = Track(videoId: "p1", title: "P", channel: "Ch", durationSec: 100, thumbnailUrl: "")
        sut.play(track: track, url: URL(string: "https://example.invalid/a.m4a")!)
        // Drive currentTime to a known non-zero value via a finite tick.
        if let block = fake.capturedTimeObserverBlock {
            block(CMTime(seconds: 12.0, preferredTimescale: CMTimeScale(NSEC_PER_SEC)))
            await Task.yield()
        }
        #expect(sut.currentTime == 12.0)

        // Now fire a tick with `.invalid` (NaN seconds) — must be dropped.
        if let block = fake.capturedTimeObserverBlock {
            block(.invalid)
            await Task.yield()
        }
        // currentTime stays at the last finite value, NOT NaN.
        #expect(sut.currentTime == 12.0)
        #expect(sut.currentTime.isFinite == true)
    }
}
