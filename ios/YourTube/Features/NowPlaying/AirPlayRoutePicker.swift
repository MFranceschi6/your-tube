import SwiftUI
import AVKit

// MARK: - AirPlayRoutePicker

/// SwiftUI bridge over `AVRoutePickerView`. Per YT-0027 Q7 the AirPlay
/// affordance must be the system-provided picker — never a custom button —
/// so users get the OS-managed route sheet and we don't have to maintain
/// an output-routing UI ourselves.
///
/// Tinted at the screen root via `.tint(...)`; the bridge applies the
/// resolved accent color so both the inactive and active states match the
/// design system.
struct AirPlayRoutePicker: UIViewRepresentable {
    /// Tint colour passed in from the host view. Resolved by SwiftUI before
    /// the bridge is created so the inactive glyph matches the design-system
    /// purple (#8B5CF6) without an asset catalog entry.
    let tintColor: UIColor

    func makeUIView(context: Context) -> AVRoutePickerView {
        let view = AVRoutePickerView()
        view.tintColor = tintColor
        view.activeTintColor = tintColor
        view.prioritizesVideoDevices = false
        view.backgroundColor = .clear
        return view
    }

    func updateUIView(_ uiView: AVRoutePickerView, context: Context) {
        uiView.tintColor = tintColor
        uiView.activeTintColor = tintColor
    }
}
