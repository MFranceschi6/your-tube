# YT-0027 — System Parity (Lock Screen, Control Center, Dynamic Island)

> "Award-worthy" means **what's on screen is what's on the lock screen**. Don't ship the Now Playing UI without the system-integration layer below.

## `MPNowPlayingInfoCenter`

Update on every track change AND on every play / pause / seek / rate change. The lock-screen scrubber tracks elapsed time only if you keep `nowPlayingInfo` fresh.

### Required keys

```swift
import MediaPlayer

func updateNowPlayingInfo(track: Track, elapsed: TimeInterval, rate: Float) {
    var info: [String: Any] = [
        MPMediaItemPropertyTitle:                track.title,
        MPMediaItemPropertyArtist:               track.channel,
        MPMediaItemPropertyPlaybackDuration:     track.durationSec,
        MPNowPlayingInfoPropertyElapsedPlaybackTime: elapsed,
        MPNowPlayingInfoPropertyPlaybackRate:    rate,             // 1.0 playing, 0.0 paused
        MPNowPlayingInfoPropertyMediaType:       MPNowPlayingInfoMediaType.audio.rawValue,
    ]
    if let artworkURL = track.artworkURL {
        info[MPMediaItemPropertyArtwork] = MPMediaItemArtwork(boundsSize: CGSize(width: 600, height: 600)) { size in
            // Resolve image at requested size (use a cache!)
            return ImageCache.shared.image(for: artworkURL, size: size) ?? UIImage()
        }
    }
    MPNowPlayingInfoCenter.default().nowPlayingInfo = info
}
```

**Critical:** the artwork is a closure, not a fixed `UIImage`. The system asks for the size it needs (control center vs lock screen vs Dynamic Island). Pre-rendering at one size produces a blurry artwork on Dynamic Island.

**Cache the resolved images.** The closure can be called many times per second; do not re-decode the image each call.

## `MPRemoteCommandCenter`

Set up once at app launch (not in the Now Playing view — that view may not exist yet when the user uses lock-screen controls).

```swift
let cc = MPRemoteCommandCenter.shared()

cc.playCommand.addTarget { _ in player.play(); return .success }
cc.pauseCommand.addTarget { _ in player.pause(); return .success }
cc.togglePlayPauseCommand.addTarget { _ in player.toggle(); return .success }
cc.nextTrackCommand.addTarget { _ in queue.next(); return .success }
cc.previousTrackCommand.addTarget { _ in queue.previousOrRestart(); return .success }

cc.changePlaybackPositionCommand.addTarget { event in
    guard let event = event as? MPChangePlaybackPositionCommandEvent else { return .commandFailed }
    player.seek(to: event.positionTime)
    return .success
}

// THE DIFFERENTIATORS — most apps skip these
cc.changeShuffleModeCommand.addTarget { event in
    guard let event = event as? MPChangeShuffleModeCommandEvent else { return .commandFailed }
    queue.shuffleMode = event.shuffleType.toAppShuffleMode()
    return .success
}
cc.changeShuffleModeCommand.isEnabled = true

cc.changeRepeatModeCommand.addTarget { event in
    guard let event = event as? MPChangeRepeatModeCommandEvent else { return .commandFailed }
    queue.repeatMode = event.repeatType.toAppRepeatMode()
    return .success
}
cc.changeRepeatModeCommand.isEnabled = true
```

Surface the *current* shuffle/repeat state back to the lock screen:

```swift
MPNowPlayingInfoCenter.default().nowPlayingInfo?[MPNowPlayingInfoPropertyDefaultPlaybackRate] = 1.0
// In the info dict:
//   MPNowPlayingInfoPropertyShuffleMode (NSNumber wrapping MPNowPlayingInfoShuffleMode)
//   MPNowPlayingInfoPropertyRepeatMode  (NSNumber wrapping MPNowPlayingInfoRepeatMode)
```

## Audio session

Required for background playback + lock-screen controls:

```swift
do {
    try AVAudioSession.sharedInstance().setCategory(.playback, mode: .default, options: [])
    try AVAudioSession.sharedInstance().setActive(true)
} catch { /* log */ }
```

Add `UIBackgroundModes` → `audio` to `Info.plist`. Without this the player stops on screen lock.

## Live Activity / Dynamic Island

This is what makes the app feel award-worthy. ActivityKit, iOS 16.1+.

### Activity attributes

```swift
import ActivityKit

struct NowPlayingAttributes: ActivityAttributes {
    public struct ContentState: Codable, Hashable {
        var title: String
        var channel: String
        var artworkURL: URL?
        var elapsed: TimeInterval
        var duration: TimeInterval
        var isPlaying: Bool
    }
    var trackId: String  // immutable for the lifetime of the activity
}
```

### Widget views (in your widget extension)

**Lock screen / banner view**

```swift
@ViewBuilder
func lockScreenView(_ context: ActivityViewContext<NowPlayingAttributes>) -> some View {
    HStack(spacing: 12) {
        ArtworkThumb(url: context.state.artworkURL, size: 56)
            .clipShape(RoundedRectangle(cornerRadius: 9, style: .continuous))  // ~16% of 56
        VStack(alignment: .leading, spacing: 2) {
            Text(context.state.title).font(.headline).lineLimit(1)
            Text(context.state.channel).font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
            ProgressView(value: context.state.elapsed, total: context.state.duration)
                .tint(.accentColor)
        }
        Image(systemName: context.state.isPlaying ? "pause.fill" : "play.fill")
    }
    .padding()
    .activityBackgroundTint(.black)
    .activitySystemActionForegroundColor(.white)
}
```

**Dynamic Island**

```swift
DynamicIsland {
    // Expanded
    DynamicIslandExpandedRegion(.leading) {
        ArtworkThumb(url: context.state.artworkURL, size: 44)
            .clipShape(RoundedRectangle(cornerRadius: 7, style: .continuous))
    }
    DynamicIslandExpandedRegion(.trailing) {
        AudioWaveformBars(isPlaying: context.state.isPlaying)
            .frame(width: 40, height: 24)
    }
    DynamicIslandExpandedRegion(.bottom) {
        VStack(alignment: .leading) {
            Text(context.state.title).font(.headline).lineLimit(1)
            Text(context.state.channel).font(.caption).foregroundStyle(.secondary)
            ProgressView(value: context.state.elapsed, total: context.state.duration).tint(.accentColor)
            HStack(spacing: 16) {
                Button(intent: SkipBackIntent()) { Image(systemName: "backward.fill") }
                Button(intent: TogglePlayIntent()) { Image(systemName: context.state.isPlaying ? "pause.fill" : "play.fill") }
                Button(intent: SkipFwdIntent()) { Image(systemName: "forward.fill") }
            }
        }
    }
} compactLeading: {
    ArtworkThumb(url: context.state.artworkURL, size: 22)
        .clipShape(RoundedRectangle(cornerRadius: 4, style: .continuous))  // ~16% of 22
} compactTrailing: {
    AudioWaveformBars(isPlaying: context.state.isPlaying).frame(width: 18, height: 14)
} minimal: {
    AudioWaveformBars(isPlaying: context.state.isPlaying)
}
```

### The waveform — the Apple-tier touch

```swift
struct AudioWaveformBars: View {
    let isPlaying: Bool
    @State private var phase: CGFloat = 0

    var body: some View {
        HStack(spacing: 2) {
            ForEach(0..<2) { i in
                Capsule().fill(Color.accentColor)
                    .frame(width: 3, height: barHeight(i))
            }
        }
        .onAppear { if isPlaying { animate() } }
        .onChange(of: isPlaying) { _, new in if new { animate() } }
    }

    func barHeight(_ i: Int) -> CGFloat {
        guard isPlaying else { return 6 }   // bars freeze flat on pause
        let base: CGFloat = i == 0 ? 14 : 18
        return base + sin(phase + CGFloat(i)) * 4
    }
    func animate() {
        withAnimation(.linear(duration: 0.6).repeatForever(autoreverses: false)) {
            phase += .pi * 4
        }
    }
}
```

The bars **must** freeze flat when `isPlaying = false`. Animating bars on a paused track is the visual lie that breaks the magic.

### Corner radius parity

Every artwork thumbnail across surfaces uses `cornerRadius ≈ 0.16 × side`:

| Surface | Side | Radius |
|---|---|---|
| Now Playing screen | 320 | ~50 (use 12pt to match design system; *or* go proportional — pick one and document) |
| MiniPlayer | 48 | 8 (already in tokens, `--miniplayer-thumb-radius`) |
| Lock screen widget | 56 | 9 |
| Dynamic Island expanded | 44 | 7 |
| Dynamic Island compact | 22 | 4 |

Pick the proportional formula or pin to the design tokens — but be **consistent**. Inconsistent corner radii across surfaces is a fingerprint of an unpolished app.

## Update cadence

- On every `play / pause / seek` → update `MPNowPlayingInfoCenter` AND `LiveActivity.update(...)`.
- On track change → update both with the new track AND issue a brief `LiveActivity.end(.dismiss(), dismissalPolicy: .immediate)` only if the source playlist ended; otherwise keep activity alive across track changes.
- On AVPlayer time observer → throttle Live Activity updates to **once per 2 seconds** (ActivityKit budgets updates; spamming gets you throttled).

## What gets surfaced where

| Element on Now Playing | `MPNowPlayingInfoCenter` | Lock screen widget | Dynamic Island compact | Dynamic Island expanded |
|---|---|---|---|---|
| Title | ✓ | ✓ | — | ✓ |
| Channel | ✓ | ✓ | — | ✓ |
| Artwork | ✓ | ✓ | leading | leading |
| Elapsed / duration | ✓ | progress bar | — | progress bar |
| Play / pause | ✓ | ✓ | — | ✓ |
| Skip fwd / back | ✓ | ✓ | — | ✓ |
| Shuffle state | ✓ (mode key) | — | — | optional |
| Repeat state | ✓ (mode key) | — | — | optional |
| Audio waveform | — | — | trailing | trailing |
