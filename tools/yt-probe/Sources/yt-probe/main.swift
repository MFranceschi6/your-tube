import Foundation
import YouTubeKit

// MARK: - Args

struct Args {
    var idsFile: String = "ids.sample.txt"
    var idsFileExplicit: Bool = false
    var iterations: Int = 5
    var concurrent: Bool = false
    var jsonOut: String? = nil
    var idsInline: [String] = []
    var probeHLS: Bool = false
}

func parseArgs() -> Args {
    var a = Args()
    var it = CommandLine.arguments.dropFirst().makeIterator()
    while let arg = it.next() {
        switch arg {
        case "--ids":
            if let v = it.next() {
                a.idsFile = v
                a.idsFileExplicit = true
            }
        case "--iterations", "-n":
            if let v = it.next(), let n = Int(v) { a.iterations = n }
        case "--concurrent":
            a.concurrent = true
        case "--probe-hls":
            a.probeHLS = true
        case "--json":
            if let v = it.next() { a.jsonOut = v }
        case "--id":
            if let v = it.next() { a.idsInline.append(v) }
        case "-h", "--help":
            printUsage()
            exit(0)
        default:
            FileHandle.standardError.write(Data("unknown arg: \(arg)\n".utf8))
            printUsage()
            exit(2)
        }
    }
    return a
}

func printUsage() {
    let u = """
    yt-probe — exercise YouTubeKit against a list of videoIds, log per-attempt outcomes.

    USAGE:
        swift run yt-probe [--ids FILE] [--iterations N] [--concurrent] [--json OUT] [--id LABEL:VIDEO_ID]...

    FILE FORMAT (one entry per line, '#' comments allowed):
        label,videoId
        short,jNQXAC9IVRw
        long-vod,5qap5aO4i9A

    DEFAULTS: --ids ids.sample.txt, --iterations 5, serial mode.
    --concurrent fires all probes for one (label,videoId) at once to flush the JS cache race.
    """
    print(u)
}

// MARK: - Targets

struct Target {
    let label: String
    let videoId: String
}

func loadTargets(_ args: Args) throws -> [Target] {
    var targets: [Target] = args.idsInline.compactMap { raw in
        let parts = raw.split(separator: ":", maxSplits: 1).map(String.init)
        guard parts.count == 2 else { return nil }
        return Target(label: parts[0], videoId: parts[1])
    }
    let fm = FileManager.default
    let shouldReadFile = args.idsFileExplicit || args.idsInline.isEmpty
    if shouldReadFile {
        if fm.fileExists(atPath: args.idsFile) {
            let text = try String(contentsOfFile: args.idsFile, encoding: .utf8)
            for raw in text.split(whereSeparator: { $0.isNewline }) {
                let line = raw.trimmingCharacters(in: .whitespaces)
                if line.isEmpty || line.hasPrefix("#") { continue }
                let parts = line.split(separator: ",", maxSplits: 1).map { $0.trimmingCharacters(in: .whitespaces) }
                guard parts.count == 2 else { continue }
                targets.append(Target(label: parts[0], videoId: parts[1]))
            }
        } else if args.idsInline.isEmpty {
            FileHandle.standardError.write(Data("ids file not found: \(args.idsFile)\n".utf8))
            exit(2)
        }
    }
    return targets
}

// MARK: - Probe

struct StreamRow: Codable {
    let itag: Int?
    let fileExtension: String
    let videoCodec: String?
    let audioCodec: String?
    let bitrate: Int?
    let averageBitrate: Int?
    let isProgressive: Bool
    let isAdaptive: Bool
    let isDash: Bool
    let includesAudio: Bool
    let includesVideo: Bool
    let isNativelyPlayable: Bool
}

struct HLSProbe: Codable {
    let httpStatus: Int?
    let isM3U8: Bool
    let firstLines: [String]
    let endlistPresent: Bool?
    let segmentCount: Int?
    let error: String?
}

struct Attempt: Codable {
    let label: String
    let videoId: String
    let iteration: Int
    let startedAt: String
    let availabilityMs: Double?
    let availabilityError: String?
    let streamsMs: Double?
    let streamsCount: Int?
    let streamsError: String?
    let livestreamsMs: Double?
    let livestreamsCount: Int?
    let livestreamsError: String?
    let nativelyPlayableAudioOnly: Int?
    let topStreams: [StreamRow]
    let livestreamUrlScheme: String?
    let hlsProbe: HLSProbe?
}

func itagNumber(of itag: ITag) -> Int? {
    let mirror = Mirror(reflecting: itag)
    for child in mirror.children where child.label == "itag" {
        return child.value as? Int
    }
    return nil
}

func describeError(_ error: Error) -> String {
    if let kit = error as? YouTubeKitError {
        return "YouTubeKitError.\(kit.rawValue)"
    }
    let ns = error as NSError
    return "\(type(of: error)) [\(ns.domain) #\(ns.code)] \(ns.localizedDescription)"
}

func now() -> String {
    let f = ISO8601DateFormatter()
    f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
    return f.string(from: Date())
}

func probeHLS(url: URL) async -> HLSProbe {
    var req = URLRequest(url: url)
    req.httpMethod = "GET"
    req.setValue("Mozilla/5.0", forHTTPHeaderField: "User-Agent")
    do {
        let (data, response) = try await URLSession.shared.data(for: req)
        let http = response as? HTTPURLResponse
        let body = String(data: data.prefix(8 * 1024), encoding: .utf8) ?? ""
        let lines = body.split(whereSeparator: { $0.isNewline }).map(String.init)
        let isM3U8 = lines.first == "#EXTM3U"
        let endlist = body.contains("#EXT-X-ENDLIST")
        let segCount = lines.filter { $0.hasPrefix("#EXTINF") }.count
        return HLSProbe(
            httpStatus: http?.statusCode,
            isM3U8: isM3U8,
            firstLines: Array(lines.prefix(8)),
            endlistPresent: endlist,
            segmentCount: segCount,
            error: nil
        )
    } catch {
        return HLSProbe(httpStatus: nil, isM3U8: false, firstLines: [], endlistPresent: nil, segmentCount: nil, error: describeError(error))
    }
}

func probe(_ target: Target, iteration: Int, doHLSProbe: Bool) async -> Attempt {
    let started = now()
    let yt = YouTube(videoID: target.videoId)

    var availabilityMs: Double? = nil
    var availabilityError: String? = nil
    let availClock = Date()
    do {
        try await yt.checkAvailability()
        availabilityMs = Date().timeIntervalSince(availClock) * 1000
    } catch {
        availabilityMs = Date().timeIntervalSince(availClock) * 1000
        availabilityError = describeError(error)
    }

    var streamsMs: Double? = nil
    var streamsCount: Int? = nil
    var streamsError: String? = nil
    var topStreams: [StreamRow] = []
    var nativelyPlayableAudioOnly: Int? = nil

    let streamClock = Date()
    do {
        let streams = try await yt.streams
        streamsMs = Date().timeIntervalSince(streamClock) * 1000
        streamsCount = streams.count
        nativelyPlayableAudioOnly = streams.filter { !$0.includesVideoTrack && $0.includesAudioTrack && $0.isNativelyPlayable }.count
        topStreams = streams.prefix(20).map { s in
            StreamRow(
                itag: itagNumber(of: s.itag),
                fileExtension: String(describing: s.fileExtension),
                videoCodec: s.videoCodec.map { String(describing: $0) },
                audioCodec: s.audioCodec.map { String(describing: $0) },
                bitrate: s.bitrate,
                averageBitrate: s.averageBitrate,
                isProgressive: s.isProgressive,
                isAdaptive: s.isAdaptive,
                isDash: s.isDash,
                includesAudio: s.includesAudioTrack,
                includesVideo: s.includesVideoTrack,
                isNativelyPlayable: s.isNativelyPlayable
            )
        }
    } catch {
        streamsMs = Date().timeIntervalSince(streamClock) * 1000
        streamsError = describeError(error)
    }

    var livestreamsMs: Double? = nil
    var livestreamsCount: Int? = nil
    var livestreamsError: String? = nil
    var livestreamUrlScheme: String? = nil
    var firstHLS: URL? = nil
    let liveClock = Date()
    do {
        let lives = try await yt.livestreams
        livestreamsMs = Date().timeIntervalSince(liveClock) * 1000
        livestreamsCount = lives.count
        livestreamUrlScheme = lives.first?.url.scheme
        firstHLS = lives.first(where: { $0.streamType == .hls })?.url
    } catch {
        livestreamsMs = Date().timeIntervalSince(liveClock) * 1000
        livestreamsError = describeError(error)
    }

    var hlsProbeResult: HLSProbe? = nil
    if doHLSProbe, let url = firstHLS {
        hlsProbeResult = await probeHLS(url: url)
    }

    return Attempt(
        label: target.label,
        videoId: target.videoId,
        iteration: iteration,
        startedAt: started,
        availabilityMs: availabilityMs,
        availabilityError: availabilityError,
        streamsMs: streamsMs,
        streamsCount: streamsCount,
        streamsError: streamsError,
        livestreamsMs: livestreamsMs,
        livestreamsCount: livestreamsCount,
        livestreamsError: livestreamsError,
        nativelyPlayableAudioOnly: nativelyPlayableAudioOnly,
        topStreams: topStreams,
        livestreamUrlScheme: livestreamUrlScheme,
        hlsProbe: hlsProbeResult
    )
}

// MARK: - Run

func runSerial(_ targets: [Target], iterations: Int, doHLSProbe: Bool) async -> [Attempt] {
    var out: [Attempt] = []
    for t in targets {
        for i in 1...iterations {
            print("→ [serial] \(t.label) \(t.videoId) iter \(i)/\(iterations)")
            let a = await probe(t, iteration: i, doHLSProbe: doHLSProbe)
            printShort(a)
            out.append(a)
        }
    }
    return out
}

func runConcurrent(_ targets: [Target], iterations: Int, doHLSProbe: Bool) async -> [Attempt] {
    var out: [Attempt] = []
    for t in targets {
        print("→ [concurrent×\(iterations)] \(t.label) \(t.videoId)")
        let attempts = await withTaskGroup(of: Attempt.self) { group -> [Attempt] in
            for i in 1...iterations {
                group.addTask { await probe(t, iteration: i, doHLSProbe: doHLSProbe) }
            }
            var local: [Attempt] = []
            for await a in group { local.append(a) }
            return local
        }
        for a in attempts.sorted(by: { $0.iteration < $1.iteration }) {
            printShort(a)
            out.append(a)
        }
    }
    return out
}

func printShort(_ a: Attempt) {
    let s = a.streamsCount.map(String.init) ?? "—"
    let l = a.livestreamsCount.map(String.init) ?? "—"
    let np = a.nativelyPlayableAudioOnly.map(String.init) ?? "—"
    let avail = a.availabilityError ?? "ok"
    let strErr = a.streamsError ?? "ok"
    let liveErr = a.livestreamsError ?? "ok"
    print("   iter \(a.iteration): avail=\(avail) | streams=\(s) (\(strErr)) | livestreams=\(l) (\(liveErr)) | audio-only-playable=\(np)")
    if let h = a.hlsProbe {
        let live = (h.endlistPresent == false) ? "live(no-endlist)" : "vod(endlist)"
        let status = h.httpStatus.map(String.init) ?? "—"
        let err = h.error ?? "ok"
        print("     hls: http=\(status) m3u8=\(h.isM3U8) \(live) seg=\(h.segmentCount ?? 0) (\(err))")
    }
}

// MARK: - Summary

func summarize(_ all: [Attempt]) {
    print("\n==================== SUMMARY ====================")
    let byTarget = Dictionary(grouping: all, by: { "\($0.label)/\($0.videoId)" })
    for (key, runs) in byTarget.sorted(by: { $0.key < $1.key }) {
        let n = runs.count
        let availOk = runs.filter { $0.availabilityError == nil }.count
        let streamsOk = runs.filter { $0.streamsError == nil && ($0.streamsCount ?? 0) > 0 }.count
        let streamsEmpty = runs.filter { $0.streamsError == nil && ($0.streamsCount ?? 0) == 0 }.count
        let livesOk = runs.filter { $0.livestreamsError == nil && ($0.livestreamsCount ?? 0) > 0 }.count
        let avgStreamsMs = runs.compactMap { $0.streamsMs }.reduce(0, +) / Double(max(1, runs.count))

        print("\n[\(key)] runs=\(n)")
        print("  availability ok       : \(availOk)/\(n)")
        print("  streams ok (>0)       : \(streamsOk)/\(n)")
        print("  streams empty (0)     : \(streamsEmpty)/\(n)")
        print("  livestreams ok (>0)   : \(livesOk)/\(n)")
        print("  avg streams call (ms) : \(String(format: "%.0f", avgStreamsMs))")

        let errCounts = Dictionary(grouping: runs.compactMap { $0.streamsError }, by: { $0 })
            .mapValues { $0.count }
            .sorted { $0.value > $1.value }
        if !errCounts.isEmpty {
            print("  streams errors:")
            for (err, c) in errCounts { print("    \(c)× \(err)") }
        }

        let availErrCounts = Dictionary(grouping: runs.compactMap { $0.availabilityError }, by: { $0 })
            .mapValues { $0.count }.sorted { $0.value > $1.value }
        if !availErrCounts.isEmpty {
            print("  availability errors:")
            for (err, c) in availErrCounts { print("    \(c)× \(err)") }
        }

        let allItags = runs.flatMap { $0.topStreams }
        let audioOnlyPlayable = allItags.filter { !$0.includesVideo && $0.includesAudio && $0.isNativelyPlayable }
        let audioOnlyNotPlayable = allItags.filter { !$0.includesVideo && $0.includesAudio && !$0.isNativelyPlayable }
        let muxed = allItags.filter { $0.includesAudio && $0.includesVideo }
        if !allItags.isEmpty {
            let auItags = Set(audioOnlyPlayable.compactMap { $0.itag }).sorted()
            let auMimes = Set(audioOnlyPlayable.map { $0.fileExtension }).sorted()
            let auNotItags = Set(audioOnlyNotPlayable.compactMap { $0.itag }).sorted()
            let muxItags = Set(muxed.compactMap { $0.itag }).sorted()
            print("  audio-only playable itags    : \(auItags) (\(auMimes.joined(separator: ", ")))")
            print("  audio-only NOT playable itags: \(auNotItags)")
            print("  muxed itags                  : \(muxItags)")
        }
    }

    print("\n---- HYPOTHESES ----")
    let allRuns = all
    let n = allRuns.count
    let intermittent = byTarget.filter { _, runs in
        let okSet = Set(runs.map { $0.streamsError == nil && ($0.streamsCount ?? 0) > 0 })
        return okSet.count > 1
    }
    print("H1 streams empty/throws on supposedly normal videos:")
    let zeroOnNonLive = byTarget.filter { _, runs in
        let label = runs.first?.label ?? ""
        guard !label.contains("live") else { return false }
        return runs.contains { ($0.streamsCount ?? -1) == 0 || $0.streamsError != nil }
    }
    if zeroOnNonLive.isEmpty { print("  not observed.") }
    else { for (k, _) in zeroOnNonLive { print("  ⚠  \(k)") } }

    print("H2 same videoId: success vs fail across iterations (intermittent):")
    if intermittent.isEmpty { print("  not observed (deterministic across runs).") }
    else { for (k, _) in intermittent { print("  ⚠  \(k)") } }

    print("H3 livestreams populated when streams empty (HLS-only videos):")
    let liveOnly = byTarget.filter { _, runs in
        runs.contains { ($0.streamsCount ?? -1) == 0 && ($0.livestreamsCount ?? 0) > 0 }
    }
    if liveOnly.isEmpty { print("  not observed.") }
    else { for (k, _) in liveOnly { print("  ✓  \(k)") } }

    print("H5 availability returns specific kit error mapped to videoUnavailable:")
    let availSpecific = allRuns.compactMap { $0.availabilityError }
        .filter { $0.contains("videoAge") || $0.contains("videoPrivate") || $0.contains("regionBlocked") || $0.contains("membersOnly") || $0.contains("liveStream") }
    if availSpecific.isEmpty { print("  not observed.") }
    else {
        let counts = Dictionary(grouping: availSpecific, by: { $0 }).mapValues { $0.count }
        for (e, c) in counts.sorted(by: { $0.value > $1.value }) { print("  \(c)× \(e)") }
    }

    print("\nTotal attempts: \(n)")
}

// MARK: - main

let args = parseArgs()
let targets: [Target]
do {
    targets = try loadTargets(args)
} catch {
    FileHandle.standardError.write(Data("failed to load ids: \(error)\n".utf8))
    exit(1)
}
if targets.isEmpty {
    FileHandle.standardError.write(Data("no targets to probe\n".utf8))
    exit(2)
}

print("yt-probe: \(targets.count) targets × \(args.iterations) iterations (\(args.concurrent ? "concurrent" : "serial"))")

let results = await (args.concurrent
    ? runConcurrent(targets, iterations: args.iterations, doHLSProbe: args.probeHLS)
    : runSerial(targets, iterations: args.iterations, doHLSProbe: args.probeHLS))
summarize(results)

if let out = args.jsonOut {
    let enc = JSONEncoder()
    enc.outputFormatting = [.prettyPrinted, .sortedKeys]
    do {
        let data = try enc.encode(results)
        try data.write(to: URL(fileURLWithPath: out))
        print("\nwrote JSON: \(out)")
    } catch {
        FileHandle.standardError.write(Data("failed to write json: \(error)\n".utf8))
        exit(1)
    }
}
