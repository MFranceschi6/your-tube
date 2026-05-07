import Foundation
import SwiftData

// MARK: - PlaylistTrackEntity

/// Ordered join table row connecting a ``PlaylistEntity`` to a ``TrackEntity``.
///
/// `position` is a zero-based integer index within the playlist. After any
/// reorder, the caller (``PlaylistStore``) must renormalise positions to be
/// contiguous from 0 so that sorting remains stable.
@Model
final class PlaylistTrackEntity {

    // MARK: Stored properties

    /// Zero-based ordinal within the owning playlist.
    var position: Int

    /// The owning playlist (inverse of ``PlaylistEntity/trackPositions``).
    var playlist: PlaylistEntity?

    /// The referenced track.
    var track: TrackEntity

    // MARK: Init

    init(track: TrackEntity, position: Int) {
        self.track = track
        self.position = position
    }
}
