# YT-0027 — SwiftUI Implementation Spec

> Companion to `decision-log.md`. This file is the technical contract — view hierarchy, animations, hit-targets, file layout. Open `mockup.html` side-by-side.

## File layout (suggested)

```
ios/YourTube/Features/NowPlaying/
  NowPlayingView.swift           ← root, owns drag-to-dismiss + matchedGeometry
  NowPlayingArtworkView.swift    ← square artwork, pause-scale spring
  NowPlayingScrubber.swift       ← custom DragGesture scrubber + time labels
  NowPlayingTransportRow.swift   ← shuffle / back / play / fwd / repeat
  NowPlayingActionRow.swift      ← AirPlay / queue / share / add
  NowPlayingUpNextPreview.swift  ← inline Up Next + "See all" → QueueView
  QueueView.swift                ← pushed NavigationStack destination
  AirPlayRoutePicker.swift       ← UIViewRepresentable wrapping AVRoutePickerView
ios/YourTube/Features/NowPlaying/Background/
  NowPlayingBackground.swift     ← .solid / .radial / .extracted (flagged)
```

## View hierarchy (top-down)

```
NowPlayingView
├── NowPlayingBackground            (solid #1C1C1E for v1)
├── DragHandle                      (36×5pt rounded, top: 60pt, drives offset)
├── TopBar
│   ├── DismissButton               (chevron.down, 22pt glyph, 44pt hit)
│   ├── ContextLabel                ("FROM QUEUE · {source}", caption2/secondary)
│   └── EllipsisMenu                (Menu with Add to playlist, Share, etc)
├── NowPlayingArtworkView           (matchedGeometryEffect("artwork", in: ns))
├── TitleBlock
│   ├── Title                       (.system(size: 22, weight: .semibold))
│   ├── Channel                     (.system(size: 15) .secondary)
│   └── AddToPlaylistButton         (plus, 22pt, 44pt hit)
├── NowPlayingScrubber
├── NowPlayingTransportRow
├── VolumeSlider                    (MPVolumeView via UIViewRepresentable, hides MPVolumeView's own AirPlay button)
├── NowPlayingActionRow
└── NowPlayingUpNextPreview         (conditional, when queue.count > 0)
```

## Presentation (Q1)

```swift
.fullScreenCover(isPresented: $showNowPlaying) {
    NowPlayingView(namespace: heroNS)
        .background(.clear)
        .presentationBackground(.clear)  // iOS 16.4+; lets your bg show through
}
```

The drag-to-dismiss is custom — bind a `@State var dragOffset: CGFloat = 0` to `.offset(y:)` on the root, drive via `DragGesture().onChanged / onEnded`. Commit dismiss when `translation.height > 120 || velocity > 800`. Otherwise spring back to 0.

`matchedGeometryEffect`: artwork view in MiniPlayer has `.matchedGeometryEffect(id: "artwork", in: ns, isSource: !showNowPlaying)`; same on Now Playing artwork with `isSource: showNowPlaying`. The namespace lives in the parent (the tab/root view).

## Artwork (Q2)

```swift
struct NowPlayingArtworkView: View {
    let url: URL?
    let isPlaying: Bool
    let isScrubbing: Bool

    var scale: CGFloat {
        if isScrubbing { return 0.92 }
        return isPlaying ? 1.0 : 0.85
    }
    var radius: CGFloat { isPlaying ? 12 : 20 }

    var body: some View {
        AsyncImage(url: url) { phase in
            switch phase {
            case .success(let img): img.resizable().scaledToFill()
            default: Color(.secondarySystemFill)
            }
        }
        .aspectRatio(1, contentMode: .fit)
        .clipShape(RoundedRectangle(cornerRadius: radius, style: .continuous))
        .scaleEffect(scale, anchor: .center)
        .animation(.spring(response: 0.45, dampingFraction: 0.78), value: scale)
        .animation(.easeInOut(duration: 0.25), value: radius)
        .padding(.horizontal, 24)
    }
}
```

Reduce-motion variant: replace the `.spring` with `.linear(duration: 0)` (instant) when `accessibilityReduceMotion` is true; keep the radius transition (information, not motion).

## Scrubber (Q4)

Don't use `Slider`. Build with a `GeometryReader` + `DragGesture`:

```swift
struct NowPlayingScrubber: View {
    @Binding var elapsed: TimeInterval
    let duration: TimeInterval
    @State private var isDragging = false
    @State private var dragValue: TimeInterval = 0
    let onCommit: (TimeInterval) -> Void  // calls AVPlayer.seek

    private var displayed: TimeInterval { isDragging ? dragValue : elapsed }
    private var progress: CGFloat { duration > 0 ? CGFloat(displayed / duration) : 0 }

    var body: some View {
        VStack(spacing: 6) {
            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    Capsule().fill(Color(.tertiarySystemFill))
                    Capsule().fill(Color.white).frame(width: geo.size.width * progress)
                    Circle().fill(Color.white)
                        .frame(width: isDragging ? 18 : 12, height: isDragging ? 18 : 12)
                        .offset(x: geo.size.width * progress - (isDragging ? 9 : 6))
                        .shadow(radius: 1, y: 1)
                }
                .frame(height: isDragging ? 8 : 4)
                .frame(maxHeight: .infinity, alignment: .center)
                .contentShape(Rectangle())
                .gesture(
                    DragGesture(minimumDistance: 0)
                        .onChanged { v in
                            if !isDragging { isDragging = true; dragValue = elapsed }
                            let p = max(0, min(1, v.location.x / geo.size.width))
                            dragValue = duration * Double(p)
                        }
                        .onEnded { _ in
                            onCommit(dragValue)   // ← seek fires HERE only
                            isDragging = false
                        }
                )
                .animation(.easeInOut(duration: 0.18), value: isDragging)
            }
            .frame(height: 20)

            HStack {
                Text(timeString(displayed))
                Spacer()
                Text("-" + timeString(max(0, duration - displayed)))
            }
            .monospacedDigit()
            .font(.system(size: isDragging ? 14 : 12, weight: .medium))
            .foregroundStyle(isDragging ? .primary : .tertiary)
            .animation(.easeInOut(duration: 0.18), value: isDragging)
        }
        .sensoryFeedback(.selection, trigger: Int(dragValue / 10))   // tick every 10s
        .sensoryFeedback(.impact(weight: .medium), trigger: isDragging) { old, new in
            old == true && new == false   // fires only on release
        }
    }
}
```

The artwork shrinks during scrub via the parent passing `isScrubbing` down.

## Transport row (Q5)

```swift
HStack(spacing: 0) {
    transportButton(symbol: "shuffle", glyphSize: 20, hit: 44, isOn: shuffleOn)
    Spacer()
    transportButton(symbol: "backward.fill", glyphSize: 26, hit: 56)
    Spacer()
    PlayPauseButton(isPlaying: isPlaying)   // 72pt circle
    Spacer()
    transportButton(symbol: "forward.fill", glyphSize: 26, hit: 56)
    Spacer()
    transportButton(symbol: "repeat", glyphSize: 20, hit: 44, isOn: repeatOn)
}
.padding(.horizontal, 8)
```

Each button uses `.contentShape(Rectangle())` to expand the hit area to `hit × hit` regardless of glyph size.

## Volume (system, not custom)

Wrap `MPVolumeView` in a `UIViewRepresentable`. Do *not* hand-roll a volume slider — iOS controls system volume, and the route picker integrates with it. Hide `MPVolumeView`'s built-in AirPlay button (`showsRouteButton = false`); use a separate `AVRoutePickerView` in the action row.

## Action row (Q7)

```swift
HStack {
    AirPlayRoutePicker()        // wraps AVRoutePickerView
    Spacer()
    Button { showQueue = true } label: { Image(systemName: "list.bullet") }
    Spacer()
    ShareLink(item: shareURL)   // square.and.arrow.up
    Spacer()
    Button { showAddToPlaylist = true } label: { Image(systemName: "text.badge.plus") }
}
.font(.system(size: 22))
.foregroundStyle(.primary)
.padding(.horizontal, 32)
```

`AirPlayRoutePicker`:

```swift
struct AirPlayRoutePicker: UIViewRepresentable {
    func makeUIView(context: Context) -> AVRoutePickerView {
        let v = AVRoutePickerView()
        v.tintColor = UIColor(named: "AccentColor")
        v.activeTintColor = UIColor(named: "AccentColor")
        v.prioritizesVideoDevices = false
        return v
    }
    func updateUIView(_ uiView: AVRoutePickerView, context: Context) {}
}
```

## Up Next + queue (Q6)

The Now Playing screen has an **inline preview** of next 3 tracks (existing `NowPlaying.jsx` panel pattern, but without the hide/show toggle — always visible when queue isn't empty). The "See all" button pushes:

```swift
NavigationLink(value: QueueDestination.full) {
    Text("See all").foregroundStyle(.tint)
}
```

In a `NavigationStack` rooted at the Now Playing screen. The full `QueueView` is a `List` with `.environment(\.editMode, ...)`, swipe actions, drag-reorder via `.onMove`.

## Token mapping (CSS → SwiftUI)

| `colors_and_type.css` | SwiftUI |
|---|---|
| `--color-accent` | `Color.accentColor` (set `.tint(Color("AccentColor"))` at root; AccentColor in asset catalog = #8B5CF6 dark / #7C3AED light) |
| `--color-bg` | `Color(.systemBackground)` (auto-adapts) |
| `--color-surface` | `Color(.secondarySystemBackground)` |
| `--color-fg-secondary` | `.foregroundStyle(.secondary)` |
| `--color-fg-tertiary` | `.foregroundStyle(.tertiary)` |
| `--scrubber-track-height: 4px` | literal `4` (pt = px on this side) |
| `--radius-md: 12px` | `RoundedRectangle(cornerRadius: 12, style: .continuous)` |
| `--font-sans` | system default — don't import Geist on iOS, use SF Pro |

## What the existing `NowPlaying.jsx` gets wrong (on purpose, for the mockup)

The existing component in `ui_kits/app/NowPlaying.jsx` is the *old* mockup. The production iOS screen differs:

1. **Background:** mockup uses `linear-gradient(160deg, #2C1B4E ...)` — production uses solid `#1C1C1E` (Q3).
2. **Scrubber thumb:** mockup uses accent purple — production uses **white** for better contrast on extracted-color BG when v1.1 lands.
3. **Play button:** mockup uses 64pt accent-purple circle — production uses **72pt white circle** (Q5).
4. **Queue:** mockup is collapsible panel — production is inline preview + pushed destination (Q6).
5. **AirPlay:** missing in mockup — production puts it as the leftmost icon in the action row (Q7).

Don't port the `NowPlaying.jsx` styling to SwiftUI 1:1. Use it for layout reference only. The `mockup.html` in this folder is the new source of truth.
