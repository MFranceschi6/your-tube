import Foundation
import MediaPlayer

// MARK: - RepeatMode

/// Three-state repeat selector mirrored to `MPRemoteCommandCenter.changeRepeatModeCommand`
/// per YT-0027 Q10. Cycle order is `off → all → one → off` to match the
/// behaviour users expect from Music / Podcasts.
enum RepeatMode: String, CaseIterable, Sendable {
    case off
    case all
    case one

    /// Next mode in the cycle.
    var next: RepeatMode {
        switch self {
        case .off: return .all
        case .all: return .one
        case .one: return .off
        }
    }

    /// VoiceOver-facing label used by `accessibilityLabel`.
    var label: String {
        switch self {
        case .off: return "off"
        case .all: return "all"
        case .one: return "one"
        }
    }

    /// Mapping to `MPRepeatType` for the `MPRemoteCommandCenter.changeRepeatModeCommand`
    /// `currentRepeatType`. Used by the audio engine to mirror state to the
    /// lock screen.
    var mpRepeatType: MPRepeatType {
        switch self {
        case .off: return .off
        case .all: return .all
        case .one: return .one
        }
    }
}
