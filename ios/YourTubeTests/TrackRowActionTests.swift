import Testing
@testable import YourTube

@Suite("TrackRow overflow-menu action sets (YT-0194)")
struct TrackRowActionTests {

    @Test("Search actions match spec: play, addToQueue, addToPlaylist, share")
    func searchActionsMatchSpec() {
        #expect(TrackRowAction.searchActions == [.play, .addToQueue, .addToPlaylist, .share])
    }

    @Test("PlaylistDetail actions match spec: addToPlaylist, share, removeFromPlaylist")
    func playlistDetailActionsMatchSpec() {
        #expect(TrackRowAction.playlistDetailActions == [.addToPlaylist, .share, .removeFromPlaylist])
    }

    @Test("Both search and playlistDetail surfaces expose share (AC4 parity)")
    func bothSurfacesExposeShare() {
        #expect(TrackRowAction.searchActions.contains(.share))
        #expect(TrackRowAction.playlistDetailActions.contains(.share))
    }

    @Test("Search exposes play and addToQueue; playlistDetail does not")
    func playbackActionsSearchOnly() {
        #expect(TrackRowAction.searchActions.contains(.play))
        #expect(TrackRowAction.searchActions.contains(.addToQueue))
        #expect(!TrackRowAction.playlistDetailActions.contains(.play))
        #expect(!TrackRowAction.playlistDetailActions.contains(.addToQueue))
    }

    @Test("Remove from playlist is playlistDetail only; not in search")
    func removeAvailabilityPerSurface() {
        #expect(TrackRowAction.playlistDetailActions.contains(.removeFromPlaylist))
        #expect(!TrackRowAction.searchActions.contains(.removeFromPlaylist))
    }
}
