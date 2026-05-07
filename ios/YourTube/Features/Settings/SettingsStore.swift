import Foundation

// MARK: - AudioQuality

/// User-facing audio quality preference for the Settings screen.
/// This is a UI enum; stream resolution uses `AudioQuality` from `YouTubeServiceProtocol.swift`.
enum AudioQualityPreference: String, CaseIterable, Identifiable {
    case auto = "auto"
    case high = "high"      // 320 kbps equivalent
    case medium = "medium"  // 128 kbps equivalent
    case low = "low"        // 64 kbps equivalent

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .auto:   return "Automatic"
        case .high:   return "High (best quality)"
        case .medium: return "Medium"
        case .low:    return "Low (saves data)"
        }
    }
}

extension AudioQualityPreference {
    /// Maps the UI preference to the service-level ``AudioQuality``.
    /// `.auto` resolves to `.high` (best effort by default).
    func toServiceQuality() -> AudioQuality {
        switch self {
        case .auto:   return .high
        case .high:   return .high
        case .medium: return .medium
        case .low:    return .low
        }
    }
}

// MARK: - SettingsKeys

/// AppStorage keys for user-facing settings.
enum SettingsKeys {
    static let audioQuality = "audioQuality"
}

// MARK: - SettingsStore

/// Utility namespace for settings side-effects (cache clearing, version lookup).
/// Preference reads and writes use @AppStorage in the view layer directly.
struct SettingsStore {

    /// Removes temporary files scoped to `Caches/YourTube/{extractor,artwork,export}`.
    /// Only the three known YourTube subdirectories are touched; system-managed caches
    /// (URLSession, WebKit, YouTubeKit) and sibling app directories are left intact.
    static func clearCache() throws {
        let base = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        let ourCache = base.appendingPathComponent("YourTube", isDirectory: true)
        let subdirs = ["extractor", "artwork", "export"]
        for subdir in subdirs {
            let dir = ourCache.appendingPathComponent(subdir, isDirectory: true)
            guard FileManager.default.fileExists(atPath: dir.path) else { continue }
            let contents = try FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil)
            for url in contents {
                try? FileManager.default.removeItem(at: url)
            }
        }
    }

    /// Human-readable app version string, e.g. "1.0 (42)".
    static var appVersion: String {
        let version = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0"
        let build   = Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "1"
        return "\(version) (\(build))"
    }
}
