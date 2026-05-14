// MARK: - TrackRowAction

/// Canonical per-screen overflow-menu action sets for `TrackRow`.
///
/// Both the 3-dots tap (`onMoreTap`) and the long-press `.contextMenu` must
/// surface identical items per screen (AC4 / YT-0194). Defining the sets
/// here as a testable enum prevents future per-surface drift.
enum TrackRowAction: CaseIterable, Equatable {
    case play
    case addToQueue
    case addToPlaylist
    case share
    case removeFromPlaylist
}

extension TrackRowAction {
    /// Action set for search-result rows. Remove is not offered — no owning
    /// playlist context in search results.
    static let searchActions: [TrackRowAction] = [.play, .addToQueue, .addToPlaylist, .share]

    /// Action set for playlist-detail rows. Add to Queue / Play are not
    /// offered from the detail view (tap-to-play owns that path, YT-0186).
    static let playlistDetailActions: [TrackRowAction] = [.addToPlaylist, .share, .removeFromPlaylist]
}
