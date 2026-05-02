import SwiftUI

struct ContentView: View {
    var body: some View {
        TabView {
            placeholder(name: "Search")
                .tabItem { Label("Search", systemImage: "magnifyingglass") }
                .accessibilityIdentifier("tab.search")

            placeholder(name: "Library")
                .tabItem { Label("Library", systemImage: "music.note.list") }
                .accessibilityIdentifier("tab.library")

            placeholder(name: "History")
                .tabItem { Label("History", systemImage: "clock") }
                .accessibilityIdentifier("tab.history")

            placeholder(name: "Player")
                .tabItem { Label("Player", systemImage: "play.circle") }
                .accessibilityIdentifier("tab.player")

            placeholder(name: "Settings")
                .tabItem { Label("Settings", systemImage: "gear") }
                .accessibilityIdentifier("tab.settings")
        }
    }

    private func placeholder(name: String) -> some View {
        let label = "YourTube — \(name)"
        return Text(label)
            .font(.title2)
            .accessibilityIdentifier("placeholder.\(name.lowercased())")
            .accessibilityLabel(label)
    }
}

#Preview {
    ContentView()
}
