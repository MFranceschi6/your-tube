import SwiftUI
import SwiftData
import AVFoundation

@main
struct YourTubeApp: App {
    let modelContainer: ModelContainer

    init() {
        do {
            Self.prepareApplicationSupportDirectory()
            // Empty schema for scaffold — real @Model types land in Core/Persistence later.
            let schema = Schema(PersistenceSchema.models)
            let configuration = ModelConfiguration(schema: schema, isStoredInMemoryOnly: false)
            self.modelContainer = try ModelContainer(for: schema, configurations: [configuration])
        } catch {
            fatalError("Failed to create ModelContainer: \(error)")
        }

        Self.activateAudioSession()
        // YT-0162: YouTubeKit removed; the JS-solver pre-warm that lived here
        // (formerly YT-0052) no longer applies. Direct InnerTube /player
        // ANDROID_VR returns pre-signed URLs without a JavaScriptCore warmup.
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
        .modelContainer(modelContainer)
    }

    private static func activateAudioSession() {
        do {
            let session = AVAudioSession.sharedInstance()
            // YT-0046 v3: align with `.claude/rules/ios.md` "Background audio"
            // and the engine's defensive re-activation. `.allowBluetooth`
            // covers HFP input parity; A2DP output is implicit for `.playback`.
            try session.setCategory(.playback, mode: .default, options: [.allowBluetooth, .allowAirPlay])
            try session.setActive(true)
        } catch {
            // Logging the error type only — no PII / URLs / tokens. The engine
            // re-activates defensively in `AVPlayerAudioEngine.init()` so a
            // transient failure here is recoverable.
            print("AVAudioSession activation failed: \(error.localizedDescription)")
        }
    }

    private static func prepareApplicationSupportDirectory() {
        do {
            let url = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        } catch {
            print("Application Support directory creation failed: \(error)")
        }
    }

}
