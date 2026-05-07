import SwiftUI

// MARK: - QueueView

/// Pushed destination for the full queue. YT-0027 Q6 mandates a real
/// `List` with native edit mode (`.onMove`, `.onDelete`, `.swipeActions`)
/// rather than a collapsible panel.
///
/// Reads the queue + current index from the shell view-model, mutates via
/// the ``AppShellViewModel``'s `move(from:to:)` / `remove(at:)` proxies so
/// the underlying ``PlayerCoordinator`` stays in charge of state.
struct QueueView: View {
    @Bindable var shell: AppShellViewModel

    @State private var editMode: EditMode = .inactive

    var body: some View {
        List {
            ForEach(Array(shell.queue.enumerated()), id: \.element.videoId) { index, track in
                row(for: track, at: index)
                    .listRowBackground(Color.clear)
                    .listRowSeparatorTint(Color.white.opacity(0.08))
                    .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                        Button(role: .destructive) {
                            shell.remove(at: index)
                        } label: {
                            Label("Remove", systemImage: "trash")
                        }
                    }
            }
            .onMove { source, destination in
                guard let from = source.first else { return }
                shell.move(from: from, to: destination)
            }
            .onDelete { indices in
                for i in indices.sorted(by: >) {
                    shell.remove(at: i)
                }
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .background(NowPlayingBackground())
        .environment(\.editMode, $editMode)
        .navigationTitle("Up Next")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button(editMode.isEditing ? "Done" : "Edit") {
                    withAnimation { editMode = editMode.isEditing ? .inactive : .active }
                }
            }
        }
        .toolbarColorScheme(.dark, for: .navigationBar)
    }

    private func row(for track: Track, at index: Int) -> some View {
        let isCurrent = (index == shell.currentIndex)
        return HStack(spacing: 12) {
            RoundedRectangle(cornerRadius: 8, style: Tokens.cornerStyle)
                .fill(Theme.accent.opacity(0.18))
                .frame(width: 44, height: 44)
                .overlay(
                    Image(systemName: isCurrent ? "speaker.wave.2.fill" : "music.note")
                        .font(.system(size: 16, weight: .medium))
                        .foregroundStyle(isCurrent ? Color.accentColor : Theme.accent)
                )
            VStack(alignment: .leading, spacing: 2) {
                Text(track.title)
                    .font(.system(size: 15, weight: .medium))
                    .foregroundStyle(isCurrent ? Color.accentColor : Color.white)
                    .lineLimit(1)
                Text(track.channel)
                    .font(.system(size: 13))
                    .foregroundStyle(Color.white.opacity(0.55))
                    .lineLimit(1)
            }
            Spacer()
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(isCurrent ? "Now playing: " : "")\(track.title) by \(track.channel)")
    }
}
