import SwiftUI
import MediaPlayer
import UIKit

// MARK: - SystemVolumeSlider

/// SwiftUI bridge over `MPVolumeView` for the YT-0027 Now Playing screen.
///
/// Per the handoff `swiftui-spec.md`: do **not** hand-roll a volume slider.
/// iOS owns system volume; `MPVolumeView` is the only public way to render
/// a slider that actually changes the device volume and does not provoke
/// duplicate haptics with the system volume HUD. The route picker is
/// rendered separately by `AirPlayRoutePicker` so we hide
/// `MPVolumeView`'s built-in route button to avoid duplication.
struct SystemVolumeSlider: UIViewRepresentable {
    func makeUIView(context: Context) -> MPVolumeView {
        let view = MPVolumeView(frame: .zero)
        view.showsRouteButton = false
        view.tintColor = UIColor(Theme.accent)
        view.backgroundColor = .clear
        return view
    }

    func updateUIView(_ uiView: MPVolumeView, context: Context) {
        uiView.tintColor = UIColor(Theme.accent)
    }
}
