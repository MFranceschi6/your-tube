import Foundation
import AVFoundation
import MediaPlayer
import UIKit

// MARK: - AVPlayerWrapping

/// Thin protocol over `AVPlayer` so tests can substitute a fake without
/// subclassing AVPlayer (which is not safe per Apple documentation).
@MainActor
protocol AVPlayerWrapping: AnyObject {
    var currentItem: AVPlayerItem? { get }
    var rate: Float { get }
    func play()
    func pause()
    func seek(to time: CMTime, toleranceBefore: CMTime, toleranceAfter: CMTime)
    func replaceCurrentItem(with item: AVPlayerItem?)
    func addPeriodicTimeObserver(
        forInterval interval: CMTime,
        queue: DispatchQueue?,
        using block: @escaping @Sendable (CMTime) -> Void
    ) -> Any
    func removeTimeObserver(_ observer: Any)
}

// MARK: AVPlayer + AVPlayerWrapping

extension AVPlayer: AVPlayerWrapping {}

// MARK: - AVPlayerAudioEngine

/// `AVPlayer`-backed audio engine with `MPNowPlayingInfoCenter` and
/// `MPRemoteCommandCenter` support.
///
/// `play(track:url:)` accepts an optional resolved stream URL. When the URL is
/// non-nil the engine builds an `AVPlayerItem`, hands it to the underlying
/// player via `replaceCurrentItem(with:)`, and starts playback so
/// `timeControlStatus` advances out of `.waitingToPlayAtSpecifiedRate`. When
/// the URL is `nil` (tests / previews), the engine only updates transport and
/// now-playing metadata.
@MainActor
@Observable
final class AVPlayerAudioEngine: AudioEngineProtocol {

    // MARK: - Observed state

    private(set) var currentTrack: Track?
    private(set) var isPlaying: Bool = false
    private(set) var currentTime: TimeInterval = 0
    private(set) var duration: TimeInterval = 0

    // MARK: - Private

    private let player: any AVPlayerWrapping
    /// Injectable Now Playing info center. Defaults to the live
    /// `MPNowPlayingInfoCenter` singleton so production behaviour is unchanged;
    /// tests substitute a `FakeNowPlayingInfoCenter` to assert metadata
    /// without polluting global state across runs.
    private let infoCenter: any NowPlayingInfoCenterProtocol
    // nonisolated(unsafe) lets deinit (which runs off the main actor in Swift 5.10)
    // safely access these references for cleanup without a concurrency diagnostic.
    nonisolated(unsafe) private var rateObserver: NSKeyValueObservation?
    nonisolated(unsafe) private var timeObserver: Any?
    nonisolated(unsafe) private var interruptionObserver: NSObjectProtocol?

    /// Tracks (command, registration token) pairs so `deinit` can call
    /// `command.removeTarget(token)` for every registered handler.
    nonisolated(unsafe) private var remoteCommandEntries: [(MPRemoteCommand, Any)] = []

    /// Cached artwork to avoid a re-fetch on every `updateNowPlayingInfo()` call.
    private var cachedArtwork: MPMediaItemArtwork?
    private var cachedArtworkVideoId: String?

    /// Monotonically increasing token bumped by `prepare(track:)` whenever
    /// the underlying `AVPlayerItem` is replaced (or cleared). Captured by
    /// the periodic time observer so a stale tick scheduled for a previous
    /// item cannot overwrite `currentTime` — that was YT-0049's published-
    /// state regression: the scrubber kept the previous track's elapsed time
    /// until the new resolve completed because a stale observer tick was
    /// landing after `prepare(track:)` had already zeroed it.
    private var currentItemGeneration: Int = 0

    // MARK: - Shuffle / Repeat mirrored state (YT-0027 Q10)

    /// Last shuffle state pushed to `nowPlayingInfo`. Owned by the coordinator;
    /// the engine reflects it.
    private var shuffleEnabled: Bool = false
    /// Last repeat mode pushed to `nowPlayingInfo`.
    private var repeatMode: RepeatMode = .off

    /// Optional remote-initiated handlers. Populated by the coordinator so
    /// changes to shuffle/repeat coming from the lock screen / Control Center
    /// flow back into the app's state. The engine never imports
    /// `PlayerCoordinator` to keep the dependency direction one-way.
    var onRemoteShuffleChange: ((Bool) -> Void)?
    var onRemoteRepeatChange: ((RepeatMode) -> Void)?
    var onRemoteSkipNext: (() -> Void)?
    var onRemoteSkipPrevious: (() -> Void)?

    /// Invoked when the underlying `AVPlayerItem.status` transitions to
    /// `.failed` — typically because the resolved stream URL returned a
    /// non-2xx HTTP status (most often 403 from a stale/broken signed
    /// `googlevideo.com` URL when YouTubeKit's signature transform fails,
    /// see YT-0071). The coordinator wires this to flip
    /// `PlayerCoordinator.state` to `.error(message:)` so the UI surfaces a
    /// banner instead of parking on a forever-buffering spinner.
    ///
    /// Callback-based seam keeps `AVPlayerAudioEngine` free of any
    /// `PlayerCoordinator` import — the dependency direction stays one-way.
    /// The engine NEVER passes the stream URL into the callback because
    /// signed YouTube URLs carry short-lived tokens (`.claude/rules/security.md`).
    var onItemFailure: ((Error?) -> Void)?

    /// KVO observation on the current `AVPlayerItem.status`. Replaced (and
    /// the previous one invalidated) on every `replaceCurrentItem(with:)`
    /// so a stale observation can never fire against a freed item.
    nonisolated(unsafe) private var itemStatusObserver: NSKeyValueObservation?

    /// YT-0157: retains the HLS proxy resource-loader for the lifetime of the
    /// current `AVPlayerItem`. Bound in `play(track:url:resourceLoader:)`,
    /// cleared in `prepare(track:)` and `stop()` after the item is replaced
    /// so AVPlayer can no longer call back into a freed delegate. Same
    /// lifetime contract as `itemStatusObserver` above.
    private var currentResourceLoader: HLSProxyLoader?

    // MARK: - Perf instrumentation (PlaybackPerfTracer)

    /// Most-recent track id handed to `play(track:url:...)`. Captured up-front
    /// so KVO / time-observer callbacks (which fire without a `Track`
    /// reference) can attribute their `mark` to the correct video. Cleared
    /// on `stop()` and refreshed on every `play(...)`.
    private var perfCurrentVideoId: String?

    /// Latched flag for FIRST_AUDIO. `addPeriodicTimeObserver` keeps firing
    /// for the lifetime of the player; we want exactly one FIRST_AUDIO mark
    /// per `play(...)`. Reset in `prepare(track:)` so the next track gets a
    /// fresh latch.
    private var perfFirstAudioFired: Bool = false

    /// Latched flags for STATUS_READY_TO_PLAY / STATE_BUFFERING / STATE_READY.
    /// `AVPlayerItem.status` and `AVPlayer.timeControlStatus` can flap
    /// (especially when the network throttles); the perf trace only cares
    /// about the *first* transition into each meaningful state per track,
    /// matching the Android brief one-shot semantics.
    private var perfReadyToPlayFired: Bool = false
    private var perfBufferingFired: Bool = false
    private var perfStateReadyFired: Bool = false

    /// KVO observation on `AVPlayer.timeControlStatus`. Drives the
    /// `STATE_BUFFERING` / `STATE_READY` perf marks. Held alongside the
    /// other KVO handles so deinit invalidates it cleanly.
    nonisolated(unsafe) private var timeControlStatusObserver: NSKeyValueObservation?


    // MARK: - Init

    init(
        player: any AVPlayerWrapping = AVPlayer(),
        infoCenter: any NowPlayingInfoCenterProtocol = MPNowPlayingInfoCenter.default()
    ) {
        self.player = player
        self.infoCenter = infoCenter
        Self.activateAudioSession()
        setupRemoteCommands()
        setupInterruptionHandling()
        setupRateObserver()
        setupTimeObserver()
        setupTimeControlStatusObserver()
    }

    /// Defensive `AVAudioSession` activation (YT-0046 v3). The app already
    /// activates the session in `YourTubeApp.init()`, but if that call failed
    /// silently (e.g. early Application-Support I/O cost) the audio queue gets
    /// torn down within milliseconds of the first `play()` and long streams
    /// stop almost immediately. Re-activating here is idempotent for an
    /// already-correct session and recovers from a missed activation. Wrapped
    /// in `try?` so a transient failure does NOT crash playback. Never logs
    /// the URL or any user-identifiable data.
    private static func activateAudioSession() {
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(
            .playback,
            mode: .default,
            options: [.allowBluetooth, .allowAirPlay]
        )
        try? session.setActive(true)
    }

    deinit {
        rateObserver?.invalidate()
        itemStatusObserver?.invalidate()
        timeControlStatusObserver?.invalidate()
        for (command, token) in remoteCommandEntries {
            command.removeTarget(token)
        }
        if let obs = timeObserver {
            // removeTimeObserver is called on the concrete AVPlayer via Obj-C to
            // avoid a main-actor isolation error in the nonisolated deinit context.
            (player as? AVPlayer)?.removeTimeObserver(obs)
        }
        if let obs = interruptionObserver {
            NotificationCenter.default.removeObserver(obs)
        }
    }

    // MARK: - AudioEngineProtocol

    func play(track: Track, url: URL?, resourceLoader: HLSProxyLoader?) {
        currentTrack = track
        isPlaying = true
        currentTime = 0
        duration = TimeInterval(track.durationSec)
        // Ad-hoc perf instrumentation: lock in the videoId so the
        // (background-queue-fired) periodic time observer and the (KVO-fired)
        // status / timeControlStatus callbacks can label their marks even
        // though they don't carry a `Track`. `prepare(track:)` already
        // assigned this — re-assigning here is defensive in case a caller
        // skipped `prepare` (tests, previews).
        perfCurrentVideoId = track.videoId
        // YT-0157: bind the proxy loader to the asset's resourceLoader so
        // AVPlayer's HLS engine routes m3u8 + segment requests through our
        // delegate, and retain the loader on the engine so it outlives the
        // surrounding `ResolvedStream` value type. Cleared on prepare/stop.
        currentResourceLoader = resourceLoader
        // Hand the resolved stream URL to AVPlayer. Without this step the
        // underlying player parks at `timeControlStatus = .waitingToPlayAtSpecifiedRate`
        // with `AVPlayerWaitingWithNoItemToPlayReason` and no audio is audible.
        // We never log `url` — signed YouTube stream URLs carry short-lived
        // tokens (per .claude/rules/ios.md security guidance).
        //
        // YT-0046 v2: the v1 fix preloaded both `playable` and `duration`, but
        // naming `duration` as an *automatically loaded* key makes AVFoundation
        // wait for a full duration probe before the item flips to ready-to-play.
        // On multi-hour YouTube CDN streams that probe stalls long enough that
        // the user never hears audio. We now only require `playable` and
        // explicitly opt out of precise timing via
        // `AVURLAssetPreferPreciseDurationAndTimingKey: false`, which lets the
        // framework start streaming as soon as the URL is reachable. Duration
        // resolves later in the background and surfaces through the existing
        // engine pathway.
        if let url {
            let asset = AVURLAsset(
                url: url,
                options: [AVURLAssetPreferPreciseDurationAndTimingKey: false]
            )
            if let loader = resourceLoader {
                asset.resourceLoader.setDelegate(loader, queue: loader.queue)
            }
            let item = AVPlayerItem(
                asset: asset,
                automaticallyLoadedAssetKeys: ["playable"]
            )
            // YT-0070: invalidate any prior status observation BEFORE handing
            // the new item to the player so a stale callback can't fire
            // against the freed item, and install a fresh KVO observation
            // on the new item's `.status`. When AVPlayerItem flips to
            // `.failed` (e.g. CDN 403 from a stale signed URL — the YT-0071
            // failure mode), the engine forwards `item.error` through
            // `onItemFailure` so the coordinator surfaces a `.error(message:)`
            // state. Without this hook AVPlayer parks on
            // `AVPlayerWaitingWhileEvaluatingBufferingRateReason` forever
            // and the user sees a silent broken state.
            itemStatusObserver?.invalidate()
            itemStatusObserver = item.observe(\.status, options: [.new]) { [weak self] item, _ in
                Task { @MainActor [weak self] in
                    guard let self else { return }
                    switch item.status {
                    case .readyToPlay:
                        // Ad-hoc perf instrumentation — first time this item
                        // reports ready-to-play. Latched so flapping never
                        // produces multiple STATUS_READY_TO_PLAY marks per
                        // track.
                        if !self.perfReadyToPlayFired {
                            self.perfReadyToPlayFired = true
                            let videoId = self.perfCurrentVideoId ?? "?"
                            PlaybackPerfTracer.shared.mark("STATUS_READY_TO_PLAY", videoId: videoId)
                        }
                    case .failed:
                        // Surface the AVPlayerItem error to the coordinator. We
                        // pass the error along (without the URL) so the
                        // coordinator can build a user-safe message; the
                        // localized description from CoreMedia / CFNetwork is
                        // safe to render but the URL never leaves the engine.
                        let videoId = self.perfCurrentVideoId ?? "?"
                        let message = (item.error as NSError?)?.localizedDescription ?? "unknown"
                        PlaybackPerfTracer.shared.mark(
                            "FAIL",
                            videoId: videoId,
                            context: "stage=avplayerItem error=\(message)"
                        )
                        self.onItemFailure?(item.error)
                    case .unknown:
                        break
                    @unknown default:
                        break
                    }
                }
            }
            player.replaceCurrentItem(with: item)
            player.play()
        }
        updateNowPlayingInfo()
        loadArtworkIfNeeded(for: track)
    }

    // YT-0049: when the user picks a new track the previous stream's audio
    // must stop and the scrubber must reset to 0 INSTANTLY, before the new
    // stream URL resolves. We pause the underlying player and clear its
    // current item synchronously here, so AVPlayer can no longer pump samples
    // from the old track or advance the periodic time observer on the old
    // duration. The new audio is still loaded later in `play(track:url:)`
    // once `resolveStreamURL` returns. This also closes YT-0046 Bug B: the
    // engine's `duration` would otherwise hold the *previous* track's value
    // until `play(track:url:)` ran, briefly rendering a 6h duration against a
    // 4-minute song's title.
    //
    // YT-0049 reviewer follow-up (2026-05-06): bumps `currentItemGeneration`
    // before clearing the AVPlayer item so any queued periodic-time-observer
    // ticks for the *previous* item that have been scheduled on the main
    // actor but not yet run cannot clobber `currentTime` back to a non-zero
    // value (or NaN). The published `currentTime` must read 0 by the time
    // SwiftUI's next render runs — see `setupTimeObserver()` for the guard.
    func prepare(track: Track) {
        currentItemGeneration &+= 1
        // YT-0070: drop the previous item's status observation BEFORE we
        // clear `currentItem` so a final `.failed` callback for the old
        // track cannot mis-fire against the new track's resolve window.
        itemStatusObserver?.invalidate()
        itemStatusObserver = nil
        // YT-0157: drop the previous proxy loader alongside the item so
        // AVPlayer's resourceLoader can no longer dispatch into a delegate
        // that we no longer want servicing requests.
        currentResourceLoader = nil
        player.pause()
        player.replaceCurrentItem(with: nil)
        isPlaying = false
        currentTrack = track
        // Order: zero `currentTime` AFTER `replaceCurrentItem(with: nil)` so
        // any synchronous KVO side-effect that touches the value lands first;
        // the assignment below is the value SwiftUI reads on the next render.
        currentTime = 0
        duration = TimeInterval(track.durationSec)
        // Ad-hoc perf instrumentation — reset the per-track latches so the
        // next `play(track:url:...)` re-emits STATUS_READY_TO_PLAY /
        // STATE_BUFFERING / STATE_READY / FIRST_AUDIO. PREPARE itself is
        // marked here because this is the synchronous engine-side hook that
        // immediately follows the coordinator's `.loading` transition.
        perfCurrentVideoId = track.videoId
        perfFirstAudioFired = false
        perfReadyToPlayFired = false
        perfBufferingFired = false
        perfStateReadyFired = false
        PlaybackPerfTracer.shared.mark("PREPARE", videoId: track.videoId)
        updateNowPlayingInfo()
        loadArtworkIfNeeded(for: track)
    }

    func pause() {
        player.pause()
        isPlaying = false
        updateNowPlayingInfo()
    }

    func resume() {
        player.play()
        isPlaying = true
        updateNowPlayingInfo()
    }

    func togglePlayPause() {
        if isPlaying { pause() } else { resume() }
    }

    func seek(to time: TimeInterval) {
        let cmTime = CMTime(seconds: time, preferredTimescale: CMTimeScale(NSEC_PER_SEC))
        player.seek(to: cmTime, toleranceBefore: .zero, toleranceAfter: .zero)
        currentTime = time
        updateNowPlayingInfo()
    }

    /// Queue management is owned by YT-0024.
    func skipNext() {}

    /// Queue management is owned by YT-0024.
    func skipPrevious() {}

    func stop() {
        // YT-0070: drop the status observation alongside the item.
        itemStatusObserver?.invalidate()
        itemStatusObserver = nil
        // YT-0157: drop the proxy loader so the resourceLoader chain has
        // nothing to dispatch into after the item is cleared.
        currentResourceLoader = nil
        player.pause()
        player.replaceCurrentItem(with: nil)
        isPlaying = false
        currentTrack = nil
        currentTime = 0
        duration = 0
        // Ad-hoc perf instrumentation — drop the videoId latch so a stray
        // late KVO callback can't mis-attribute to a freed track.
        perfCurrentVideoId = nil
        perfFirstAudioFired = false
        perfReadyToPlayFired = false
        perfBufferingFired = false
        perfStateReadyFired = false
        infoCenter.nowPlayingInfo = nil
    }

    // MARK: - Shuffle / Repeat (YT-0027 Q10)

    /// Pushes `enabled` into the lock-screen / Control Center state via
    /// `MPRemoteCommandCenter.changeShuffleModeCommand.currentShuffleType` and
    /// mirrors it into `MPNowPlayingInfoPropertyShuffleMode` so the lock-screen
    /// state badge reads the correct value (handoff Q10 / system-parity.md:75).
    func setShuffleMode(_ enabled: Bool) {
        shuffleEnabled = enabled
        let command = MPRemoteCommandCenter.shared().changeShuffleModeCommand
        command.currentShuffleType = enabled ? .items : .off
        updateNowPlayingInfo()
    }

    /// Pushes `mode` into the lock-screen / Control Center state via
    /// `MPRemoteCommandCenter.changeRepeatModeCommand.currentRepeatType` and
    /// mirrors it into `MPNowPlayingInfoPropertyRepeatMode` so the lock-screen
    /// state badge reads the correct value (handoff Q10 / system-parity.md:75).
    func setRepeatMode(_ mode: RepeatMode) {
        repeatMode = mode
        let command = MPRemoteCommandCenter.shared().changeRepeatModeCommand
        command.currentRepeatType = mode.mpRepeatType
        updateNowPlayingInfo()
    }

    // MARK: - Private setup

    private func setupRateObserver() {
        guard let avPlayer = player as? AVPlayer else { return }
        rateObserver = avPlayer.observe(\.rate, options: [.new]) { [weak self] _, change in
            Task { @MainActor [weak self] in
                guard let self else { return }
                let newRate = change.newValue ?? 0
                self.isPlaying = newRate != 0
                self.updateNowPlayingInfo()
            }
        }
    }

    /// Registers a handler on `command` and stores the (command, token) pair so
    /// the handler can be removed cleanly in `deinit`.
    private func register(
        _ command: MPRemoteCommand,
        handler: @escaping (MPRemoteCommandEvent) -> MPRemoteCommandHandlerStatus
    ) {
        let token = command.addTarget(handler: handler)
        remoteCommandEntries.append((command, token))
    }

    private func setupRemoteCommands() {
        let center = MPRemoteCommandCenter.shared()

        register(center.playCommand) { [weak self] _ in
            self?.resume()
            return .success
        }
        register(center.pauseCommand) { [weak self] _ in
            self?.pause()
            return .success
        }
        register(center.togglePlayPauseCommand) { [weak self] _ in
            self?.togglePlayPause()
            return .success
        }
        // Forward to the coordinator if a callback is wired (YT-0027); fall
        // back to .noSuchContent so the lock-screen UI stays accurate when
        // there is no queue follow-up registered.
        register(center.nextTrackCommand) { [weak self] _ in
            guard let cb = self?.onRemoteSkipNext else { return .noSuchContent }
            cb()
            return .success
        }
        register(center.previousTrackCommand) { [weak self] _ in
            guard let cb = self?.onRemoteSkipPrevious else { return .noSuchContent }
            cb()
            return .success
        }
        register(center.changePlaybackPositionCommand) { [weak self] event in
            guard let e = event as? MPChangePlaybackPositionCommandEvent else {
                return .commandFailed
            }
            self?.seek(to: e.positionTime)
            return .success
        }
        // YT-0027 Q10: shuffle / repeat mirrored both ways. The engine accepts
        // remote-initiated changes and forwards them to whoever owns app state
        // (PlayerCoordinator does in production wiring).
        register(center.changeShuffleModeCommand) { [weak self] event in
            guard let e = event as? MPChangeShuffleModeCommandEvent else {
                return .commandFailed
            }
            let enabled = e.shuffleType != .off
            self?.shuffleEnabled = enabled
            self?.onRemoteShuffleChange?(enabled)
            return .success
        }
        register(center.changeRepeatModeCommand) { [weak self] event in
            guard let e = event as? MPChangeRepeatModeCommandEvent else {
                return .commandFailed
            }
            let mode: RepeatMode
            switch e.repeatType {
            case .off: mode = .off
            case .one: mode = .one
            case .all: mode = .all
            @unknown default: mode = .off
            }
            self?.repeatMode = mode
            self?.onRemoteRepeatChange?(mode)
            return .success
        }
    }

    private func setupInterruptionHandling() {
        interruptionObserver = NotificationCenter.default.addObserver(
            forName: AVAudioSession.interruptionNotification,
            object: nil,
            queue: .main
        ) { [weak self] notification in
            Task { @MainActor [weak self] in
                self?.handleInterruption(notification)
            }
        }
    }

    // Internal so AudioEngineTests can call it directly.
    func handleInterruption(_ notification: Notification) {
        guard
            let info = notification.userInfo,
            let typeValue = info[AVAudioSessionInterruptionTypeKey] as? UInt,
            let type = AVAudioSession.InterruptionType(rawValue: typeValue)
        else { return }

        switch type {
        case .began:
            // System paused us — keep state in sync. The system handles the
            // actual session deactivation; we just mirror UI state. `pause()`
            // would also be valid but we already block on `isPlaying`.
            isPlaying = false
            updateNowPlayingInfo()
        case .ended:
            if let optionsValue = info[AVAudioSessionInterruptionOptionKey] as? UInt {
                let options = AVAudioSession.InterruptionOptions(rawValue: optionsValue)
                if options.contains(.shouldResume) {
                    // YT-0046 v3: the system deactivates our audio session
                    // during the interruption. Re-activating before `resume()`
                    // ensures the audio queue is alive when AVPlayer pumps the
                    // first sample, otherwise `AQME Default-InputOutput`
                    // tears down again within milliseconds.
                    Self.activateAudioSession()
                    resume()
                }
            }
        @unknown default:
            break
        }
    }

    private func setupTimeObserver() {
        // 100 ms granularity is fine-grained enough that FIRST_AUDIO lands
        // within ~50 ms of actual audio output (vs ~250 ms with the previous
        // 0.5 s tick) without measurably hurting scroll perf — the callback
        // body is a few statements. Keeping the same interval for the
        // scrubber update is fine because SwiftUI batches @Observable
        // re-renders.
        let interval = CMTime(seconds: 0.1, preferredTimescale: CMTimeScale(NSEC_PER_SEC))
        timeObserver = player.addPeriodicTimeObserver(
            forInterval: interval,
            queue: .main
        ) { [weak self] time in
            Task { @MainActor [weak self] in
                guard let self else { return }
                // YT-0049 reviewer follow-up: drop ticks when no item is
                // loaded (defensive — `replaceCurrentItem(with: nil)` may
                // emit a final tick), and drop ticks with non-finite seconds
                // (CMTime can be `.invalid` immediately after a clear,
                // producing NaN). Either case would clobber the freshly-
                // zeroed `currentTime` and surface a stale or NaN value to
                // the SwiftUI scrubber.
                guard self.player.currentItem != nil else { return }
                let seconds = time.seconds
                guard seconds.isFinite else { return }
                self.currentTime = seconds
                self.updateNowPlayingInfo()
                // Ad-hoc perf instrumentation — FIRST_AUDIO fires the first
                // tick where AVPlayer reports `currentTime > 0` AND the
                // player rate is > 0 (i.e. samples are actually being
                // pumped, not just the item being prepared). Latched per
                // `prepare(track:)` so subsequent ticks for the same track
                // do NOT re-emit. There is no public AVFoundation API for
                // "first audio frame to the speaker"; this is the closest
                // observable proxy.
                if !self.perfFirstAudioFired,
                   seconds > 0,
                   self.player.rate > 0 {
                    self.perfFirstAudioFired = true
                    let videoId = self.perfCurrentVideoId ?? "?"
                    PlaybackPerfTracer.shared.mark(
                        "FIRST_AUDIO",
                        videoId: videoId,
                        context: "currentTime=\(String(format: "%.3f", seconds))"
                    )
                }
            }
        }
    }

    /// Wires KVO on `AVPlayer.timeControlStatus` so the perf trace can
    /// distinguish "the player is waiting for the buffer" (`.waitingToPlay…`)
    /// from "the player is actively pumping samples" (`.playing`). Both
    /// states map to a single `mark` per track via the latches on
    /// `perfBufferingFired` / `perfStateReadyFired` — flapping (which
    /// happens on slow networks) does not produce duplicate lines.
    ///
    /// `timeControlStatus` is only available on the concrete `AVPlayer`
    /// type, not the protocol — so the observer is a no-op when the engine
    /// runs against the test fake. That's fine because the perf instrumentation
    /// is verified via `PlaybackPerfTracerTests` against the tracer directly.
    private func setupTimeControlStatusObserver() {
        guard let avPlayer = player as? AVPlayer else { return }
        timeControlStatusObserver = avPlayer.observe(\.timeControlStatus, options: [.new]) { [weak self] avPlayer, _ in
            Task { @MainActor [weak self] in
                guard let self else { return }
                let videoId = self.perfCurrentVideoId ?? "?"
                switch avPlayer.timeControlStatus {
                case .waitingToPlayAtSpecifiedRate:
                    if !self.perfBufferingFired {
                        self.perfBufferingFired = true
                        let reason = avPlayer.reasonForWaitingToPlay?.rawValue ?? "?"
                        PlaybackPerfTracer.shared.mark(
                            "STATE_BUFFERING",
                            videoId: videoId,
                            context: "reason=\(reason)"
                        )
                    }
                case .playing:
                    if !self.perfStateReadyFired {
                        self.perfStateReadyFired = true
                        PlaybackPerfTracer.shared.mark("STATE_READY", videoId: videoId)
                    }
                case .paused:
                    break
                @unknown default:
                    break
                }
            }
        }
    }

    // MARK: - Artwork loading

    /// Loads artwork from `track.thumbnailUrl` in a detached task and caches the
    /// result. A re-fetch is skipped when `videoId` matches the last loaded artwork.
    private func loadArtworkIfNeeded(for track: Track) {
        guard track.videoId != cachedArtworkVideoId else { return }
        Task.detached { [weak self] in
            guard let self, let url = URL(string: track.thumbnailUrl) else { return }
            guard
                let (data, _) = try? await URLSession.shared.data(from: url),
                let image = UIImage(data: data)
            else { return }
            let artwork = MPMediaItemArtwork(boundsSize: image.size) { _ in image }
            await MainActor.run {
                self.cachedArtwork = artwork
                self.cachedArtworkVideoId = track.videoId
                self.updateNowPlayingInfo()
            }
        }
    }

    // MARK: - Now Playing

    private func updateNowPlayingInfo() {
        guard let track = currentTrack else {
            infoCenter.nowPlayingInfo = nil
            return
        }
        let playbackDuration = duration > 0 ? duration : TimeInterval(track.durationSec)
        // Shuffle / repeat state is surfaced to the lock screen via
        // `MPRemoteCommandCenter.change{Shuffle,Repeat}ModeCommand.current{Shuffle,Repeat}Type`
        // (set in `setShuffleMode(_:)` / `setRepeatMode(_:)`); MediaPlayer does
        // not expose `MPNowPlayingInfoProperty{Shuffle,Repeat}Mode` constants
        // in the public SDK, so the info dict carries only the media-type key.
        var info: [String: Any] = [
            MPMediaItemPropertyTitle: track.title,
            MPMediaItemPropertyArtist: track.channel,
            MPMediaItemPropertyPlaybackDuration: playbackDuration,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: currentTime,
            MPNowPlayingInfoPropertyPlaybackRate: isPlaying ? 1.0 : 0.0,
            MPNowPlayingInfoPropertyMediaType: MPNowPlayingInfoMediaType.audio.rawValue,
        ]
        if let artwork = cachedArtwork {
            info[MPMediaItemPropertyArtwork] = artwork
        }
        infoCenter.nowPlayingInfo = info
    }
}
