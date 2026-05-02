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
            try session.setCategory(.playback, mode: .default, options: [.allowBluetoothA2DP, .allowAirPlay])
            try session.setActive(true)
        } catch {
            // Scaffold stage: log only; full interruption handling lands with AudioEngine.
            print("AVAudioSession activation failed: \(error)")
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
