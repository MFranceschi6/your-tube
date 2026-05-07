import Foundation
import AVFoundation
import YouTubeKit

// Resolve a fresh YouTube stream URL (DASH audio mp4a OR HLS master) and play it
// through AVPlayer locally on macOS, logging every state transition with monotonic
// timestamps so we can measure "time to first sample" and confirm whether AVPlayer
// streams progressively or stalls waiting for full asset metadata.

enum Mode: String { case hls, dash, auto }

struct Args {
    var videoId: String = "dQw4w9WgXcQ"
    var mode: Mode = .auto
    var maxSeconds: Double = 25
    var quality: Int = 128_000   // bps ceiling for DASH audio mp4a (only used in dash mode)
    var printHLSURL: Bool = false
    var userAgent: String? = nil
}

func parseArgs() -> Args {
    var a = Args()
    var it = CommandLine.arguments.dropFirst().makeIterator()
    while let arg = it.next() {
        switch arg {
        case "--id":
            if let v = it.next() { a.videoId = v }
        case "--mode":
            if let v = it.next(), let m = Mode(rawValue: v) { a.mode = m }
        case "--max":
            if let v = it.next(), let s = Double(v) { a.maxSeconds = s }
        case "--quality":
            if let v = it.next(), let q = Int(v) { a.quality = q }
        case "--print-hls-url":
            a.printHLSURL = true
        case "--user-agent":
            if let v = it.next() { a.userAgent = v }
        case "-h", "--help":
            print("""
            yt-play — load a YouTubeKit-resolved URL into AVPlayer and dump KVO timeline.

            USAGE:
                swift run yt-play --id VIDEO_ID [--mode hls|dash|auto] [--max SECONDS] [--quality BPS]

            MODES:
              dash : pick best audio-only mp4a from `streams` ≤ --quality (bps).
              hls  : pick first HLS livestream URL.
              auto : try dash, fall back to hls on any error.
            """)
            exit(0)
        default:
            FileHandle.standardError.write(Data("unknown arg: \(arg)\n".utf8))
            exit(2)
        }
    }
    return a
}

let start = Date()
func ts() -> String { String(format: "%6.3fs", Date().timeIntervalSince(start)) }
func log(_ msg: String) { print("[\(ts())] \(msg)") }

func describeError(_ error: Error) -> String {
    if let kit = error as? YouTubeKitError { return "YouTubeKitError.\(kit.rawValue)" }
    let ns = error as NSError
    return "\(type(of: error)) [\(ns.domain) #\(ns.code)] \(ns.localizedDescription)"
}

// Pick best audio-only mp4a from streams (mirrors LiveYouTubeService logic).
func bps(_ s: YouTubeKit.Stream) -> Int { s.averageBitrate ?? s.bitrate ?? 0 }
func bpsMax(_ s: YouTubeKit.Stream) -> Int { s.averageBitrate ?? s.bitrate ?? Int.max }

func pickDashAudio(_ streams: [YouTubeKit.Stream], qualityBps: Int) -> YouTubeKit.Stream? {
    let audioOnly = streams.filterAudioOnly().filter { $0.isNativelyPlayable }
    let candidates = audioOnly.filter { bps($0) <= qualityBps }
    if let best = candidates.max(by: { bps($0) < bps($1) }) { return best }
    return audioOnly.min(by: { bpsMax($0) < bpsMax($1) })
}

func resolve(args: Args) async throws -> (url: URL, kind: String) {
    let yt = YouTube(videoID: args.videoId)
    switch args.mode {
    case .dash:
        let streams = try await yt.streams
        guard let s = pickDashAudio(streams, qualityBps: args.quality) else {
            throw NSError(domain: "yt-play", code: 1, userInfo: [NSLocalizedDescriptionKey: "no DASH audio candidate"])
        }
        let mirror = Mirror(reflecting: s.itag).children.first { $0.label == "itag" }
        let itag = (mirror?.value as? Int).map(String.init) ?? "?"
        log("resolved DASH itag=\(itag) avgBitrate=\(s.averageBitrate ?? -1) ext=\(s.fileExtension) audioCodec=\(String(describing: s.audioCodec))")
        return (s.url, "dash-mp4a")
    case .hls:
        let lives = try await yt.livestreams
        guard let url = lives.first(where: { $0.streamType == .hls })?.url else {
            throw NSError(domain: "yt-play", code: 2, userInfo: [NSLocalizedDescriptionKey: "no HLS livestream URL"])
        }
        log("resolved HLS master playlist")
        return (url, "hls")
    case .auto:
        do {
            let streams = try await yt.streams
            if let s = pickDashAudio(streams, qualityBps: args.quality) {
                let mirror = Mirror(reflecting: s.itag).children.first { $0.label == "itag" }
                let itag = (mirror?.value as? Int).map(String.init) ?? "?"
                log("resolved DASH (auto) itag=\(itag)")
                return (s.url, "dash-mp4a")
            }
        } catch {
            log("DASH path failed: \(describeError(error)) — falling back to HLS")
        }
        let lives = try await yt.livestreams
        guard let url = lives.first(where: { $0.streamType == .hls })?.url else {
            throw NSError(domain: "yt-play", code: 3, userInfo: [NSLocalizedDescriptionKey: "no fallback HLS"])
        }
        log("resolved HLS (auto fallback)")
        return (url, "hls")
    }
}

// MARK: - KVO observer

final class PlayerObserver: NSObject, @unchecked Sendable {
    let item: AVPlayerItem
    let player: AVPlayer
    var firstSampleTime: Date? = nil
    var statusObs: NSKeyValueObservation?
    var timeCtrlObs: NSKeyValueObservation?
    var bufEmptyObs: NSKeyValueObservation?
    var bufKeepUpObs: NSKeyValueObservation?
    var bufFullObs: NSKeyValueObservation?
    var loadedRangesObs: NSKeyValueObservation?
    var durationObs: NSKeyValueObservation?
    var presentationSizeObs: NSKeyValueObservation?
    var timeObserverToken: Any?

    init(item: AVPlayerItem, player: AVPlayer) {
        self.item = item
        self.player = player
        super.init()
    }

    func attach() {
        statusObs = item.observe(\.status, options: [.new, .initial]) { [weak self] item, _ in
            log("item.status -> \(item.status.rawValue) (\(self?.statusName(item.status) ?? "?"))")
            if let err = item.error { log("  item.error: \((err as NSError).localizedDescription)") }
        }
        timeCtrlObs = player.observe(\.timeControlStatus, options: [.new, .initial]) { [weak self] p, _ in
            let name = self?.timeControlName(p.timeControlStatus) ?? "?"
            log("player.timeControlStatus -> \(p.timeControlStatus.rawValue) (\(name))")
            if p.timeControlStatus == .playing && self?.firstSampleTime == nil {
                self?.firstSampleTime = Date()
                log("⏱ FIRST SAMPLE at \(ts())")
            }
        }
        bufEmptyObs = item.observe(\.isPlaybackBufferEmpty, options: [.new]) { item, _ in
            log("item.isPlaybackBufferEmpty -> \(item.isPlaybackBufferEmpty)")
        }
        bufKeepUpObs = item.observe(\.isPlaybackLikelyToKeepUp, options: [.new]) { item, _ in
            log("item.isPlaybackLikelyToKeepUp -> \(item.isPlaybackLikelyToKeepUp)")
        }
        bufFullObs = item.observe(\.isPlaybackBufferFull, options: [.new]) { item, _ in
            log("item.isPlaybackBufferFull -> \(item.isPlaybackBufferFull)")
        }
        loadedRangesObs = item.observe(\.loadedTimeRanges, options: [.new]) { item, _ in
            let ranges = item.loadedTimeRanges.map { v -> String in
                let r = v.timeRangeValue
                return String(format: "[%.1f..+%.1fs]", CMTimeGetSeconds(r.start), CMTimeGetSeconds(r.duration))
            }
            log("item.loadedTimeRanges -> \(ranges.joined(separator: ","))")
        }
        durationObs = item.observe(\.duration, options: [.new]) { item, _ in
            let d = CMTimeGetSeconds(item.duration)
            log("item.duration -> \(d.isFinite ? String(format: "%.1fs", d) : "indefinite/live")")
        }
        let interval = CMTime(seconds: 0.5, preferredTimescale: 600)
        timeObserverToken = player.addPeriodicTimeObserver(forInterval: interval, queue: .main) { [weak self] t in
            let secs = CMTimeGetSeconds(t)
            if let item = self?.item {
                let ranges = item.loadedTimeRanges.compactMap { v -> Double? in
                    let r = v.timeRangeValue
                    let end = CMTimeGetSeconds(r.start) + CMTimeGetSeconds(r.duration)
                    return end.isFinite ? end : nil
                }
                let ahead = ranges.last.map { $0 - secs } ?? 0
                log(String(format: "tick playhead=%.2fs bufferedAhead=%.2fs", secs, ahead))
            }
        }
    }

    func detach() {
        statusObs?.invalidate()
        timeCtrlObs?.invalidate()
        bufEmptyObs?.invalidate()
        bufKeepUpObs?.invalidate()
        bufFullObs?.invalidate()
        loadedRangesObs?.invalidate()
        durationObs?.invalidate()
        presentationSizeObs?.invalidate()
        if let token = timeObserverToken { player.removeTimeObserver(token) }
    }

    func statusName(_ s: AVPlayerItem.Status) -> String {
        switch s { case .unknown: return "unknown"; case .readyToPlay: return "readyToPlay"; case .failed: return "failed"; @unknown default: return "?" }
    }
    func timeControlName(_ s: AVPlayer.TimeControlStatus) -> String {
        switch s { case .paused: return "paused"; case .waitingToPlayAtSpecifiedRate: return "waiting"; case .playing: return "playing"; @unknown default: return "?" }
    }
}

// MARK: - main

let args = parseArgs()

if args.printHLSURL {
    let yt = YouTube(videoID: args.videoId)
    let lives = try await yt.livestreams
    guard let url = lives.first(where: { $0.streamType == .hls })?.url else {
        FileHandle.standardError.write(Data("no HLS URL\n".utf8))
        exit(1)
    }
    print(url.absoluteString)
    exit(0)
}

log("starting yt-play id=\(args.videoId) mode=\(args.mode.rawValue) max=\(args.maxSeconds)s")

let resolved: (url: URL, kind: String)
do {
    resolved = try await resolve(args: args)
} catch {
    log("resolve failed: \(describeError(error))")
    exit(1)
}
log("kind=\(resolved.kind) host=\(resolved.url.host ?? "?")")
do {
    let s = resolved.url.absoluteString
    // Match both path-style (HLS) and query-style (DASH) parameter encoding.
    let source = s.range(of: #"[/?&]source[=/][a-z_]+"#, options: .regularExpression).map { String(s[$0]) } ?? "?"
    let plType = s.range(of: #"[/?&]playlist_type[=/][A-Z]+"#, options: .regularExpression).map { String(s[$0]) } ?? "(none)"
    let nParam = s.range(of: #"[/?&]n[=/][A-Za-z0-9_-]+"#, options: .regularExpression).map { String(s[$0]) } ?? "(none)"
    let isLiveBcast = s.contains("source=yt_live_broadcast") || s.contains("/source/yt_live_broadcast")
    log("url-pattern: source=\(source) playlist_type=\(plType) n=\(nParam) live=\(isLiveBcast)")
}

var assetOptions: [String: Any] = ["AVURLAssetPreferPreciseDurationAndTimingKey": false]
if let ua = args.userAgent {
    assetOptions["AVURLAssetHTTPHeaderFieldsKey"] = ["User-Agent": ua]
    log("setting User-Agent: \(ua)")
}
let asset = AVURLAsset(url: resolved.url, options: assetOptions)
let item = AVPlayerItem(asset: asset, automaticallyLoadedAssetKeys: ["playable"])
let player = AVPlayer(playerItem: item)
let observer = PlayerObserver(item: item, player: player)
observer.attach()

log("calling player.play()")
player.play()

let deadline = Date().addingTimeInterval(args.maxSeconds)
while Date() < deadline {
    try? await Task.sleep(nanoseconds: 200_000_000)
}

if let first = observer.firstSampleTime {
    let latency = first.timeIntervalSince(start)
    log(String(format: "SUMMARY: first-sample latency = %.2fs (kind=\(resolved.kind))", latency))
} else {
    log("SUMMARY: NEVER REACHED .playing in \(args.maxSeconds)s (kind=\(resolved.kind))")
}

if let log = item.accessLog() {
    print("--- AVPlayerItem accessLog (\(log.events.count) events) ---")
    for e in log.events.suffix(5) {
        print(String(format: "  uri=\(e.uri ?? "?") indicatedBitrate=%.0f observedBitrate=%.0f durationWatched=%.2fs numStalls=\(e.numberOfStalls) playbackStartDate=\(String(describing: e.playbackStartDate))",
                     e.indicatedBitrate, e.observedBitrate, e.durationWatched))
    }
}
if let elog = item.errorLog() {
    print("--- AVPlayerItem errorLog (\(elog.events.count) events) ---")
    for e in elog.events.suffix(5) {
        print("  comment=\(e.errorComment ?? "?") code=\(e.errorStatusCode) domain=\(e.errorDomain)")
    }
}

observer.detach()
player.pause()
