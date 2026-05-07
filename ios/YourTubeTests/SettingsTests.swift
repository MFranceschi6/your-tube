import Testing
import Foundation
@testable import YourTube

// MARK: - SettingsTests

@Suite("Settings")
struct SettingsTests {

    @Test("AudioQualityPreference.auto is default rawValue")
    func audioQualityDefaultRawValue() {
        #expect(AudioQualityPreference.auto.rawValue == "auto")
    }

    @Test("AudioQualityPreference roundtrips from rawValue")
    func audioQualityRoundtrip() {
        for quality in AudioQualityPreference.allCases {
            let recovered = AudioQualityPreference(rawValue: quality.rawValue)
            #expect(recovered == quality)
        }
    }

    @Test("AudioQualityPreference.allCases has 4 elements")
    func audioQualityAllCases() {
        #expect(AudioQualityPreference.allCases.count == 4)
    }

    @Test("SettingsStore.appVersion is non-empty")
    func appVersionNonEmpty() {
        #expect(!SettingsStore.appVersion.isEmpty)
    }

    @Test("AudioQualityPreference displayName is non-empty for all cases")
    func audioQualityDisplayNames() {
        for quality in AudioQualityPreference.allCases {
            #expect(!quality.displayName.isEmpty)
        }
    }

    // MARK: - Nit 1: UserDefaults persistence

    @Test("AudioQualityPreference persists via UserDefaults")
    func audioQualityPreferencePersists() {
        let suiteName = "test.settings.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defaults.set(AudioQualityPreference.low.rawValue, forKey: SettingsKeys.audioQuality)
        let recovered = AudioQualityPreference(rawValue: defaults.string(forKey: SettingsKeys.audioQuality) ?? "")
        #expect(recovered == .low)
        defaults.removeSuite(named: suiteName)
    }

    // MARK: - Nit 2: AudioQualityPreference → AudioQuality mapping

    @Test("AudioQualityPreference.toServiceQuality maps auto to high")
    func mappingAutoToHigh() {
        #expect(AudioQualityPreference.auto.toServiceQuality() == .high)
    }

    @Test("AudioQualityPreference.toServiceQuality maps all explicit cases")
    func mappingExplicitCases() {
        #expect(AudioQualityPreference.high.toServiceQuality() == .high)
        #expect(AudioQualityPreference.medium.toServiceQuality() == .medium)
        #expect(AudioQualityPreference.low.toServiceQuality() == .low)
    }

    // MARK: - Nit 3: clearCache scope

    @Test("clearCache only removes files under Caches/YourTube/")
    func clearCacheScoped() throws {
        let caches = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        let ours = caches.appendingPathComponent("YourTube/extractor", isDirectory: true)
        let sibling = caches.appendingPathComponent("SiblingApp", isDirectory: true)

        try FileManager.default.createDirectory(at: ours, withIntermediateDirectories: true)
        let testFile = ours.appendingPathComponent("test.tmp")
        try "test".write(to: testFile, atomically: true, encoding: .utf8)

        try FileManager.default.createDirectory(at: sibling, withIntermediateDirectories: true)
        let siblingFile = sibling.appendingPathComponent("preserve.txt")
        try "keep".write(to: siblingFile, atomically: true, encoding: .utf8)

        try SettingsStore.clearCache()

        // Our file is gone
        #expect(!FileManager.default.fileExists(atPath: testFile.path))
        // Sibling survives
        #expect(FileManager.default.fileExists(atPath: siblingFile.path))

        // Cleanup
        try? FileManager.default.removeItem(at: sibling)
    }
}
