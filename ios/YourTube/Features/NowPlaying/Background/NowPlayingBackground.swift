import SwiftUI

// MARK: - NowPlayingBackground

/// Solid `#1C1C1E` Now Playing background per YT-0027 Q3. Extracted-color
/// treatment is staged for v1.1 behind a feature flag and intentionally not
/// implemented here — see the handoff `decision-log.md` Q3 rationale for
/// why "do it well or don't ship it".
///
/// Defined as its own view so the v1.1 swap is a one-file change.
struct NowPlayingBackground: View {
    var body: some View {
        // The shipped value is locked to #1C1C1E (the iOS systemGray6 in
        // dark mode) regardless of trait collection. Now Playing is a dark
        // surface in both Light and Dark modes per the handoff mockup.
        Color(red: 28.0 / 255.0, green: 28.0 / 255.0, blue: 30.0 / 255.0)
            .ignoresSafeArea()
    }
}

#Preview("NowPlayingBackground") {
    NowPlayingBackground()
}
