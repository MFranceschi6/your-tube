import Foundation
import SwiftData

// MARK: - TrackEntity

/// Persisted track record. Tracks are shared across playlists through ``PlaylistTrackEntity``.
///
/// `videoId` is the YouTube video identifier and serves as the unique key.
/// Cascade-delete of the join row (``PlaylistTrackEntity``) does not delete the track;
/// tracks are deduped by `videoId` and shared across playlists.
@Model
final class TrackEntity {

    // MARK: Identity & metadata

    /// YouTube video identifier, e.g. `"dQw4w9WgXcQ"`. Unique across the store.
    @Attribute(.unique) var videoId: String
    var title: String
    var channel: String
    /// Duration in whole seconds. 0 for live streams / unknown.
    var durationSec: Int
    /// HTTPS thumbnail URL; prefer `mqdefault` quality.
    var thumbnailUrl: String

    // MARK: Relationship

    /// All join rows that reference this track (back-pointer; not cascade-deleted).
    @Relationship var playlistPositions: [PlaylistTrackEntity] = []

    // MARK: Init

    init(
        videoId: String,
        title: String,
        channel: String,
        durationSec: Int,
        thumbnailUrl: String
    ) {
        self.videoId = videoId
        self.title = title
        self.channel = channel
        self.durationSec = durationSec
        self.thumbnailUrl = thumbnailUrl
    }
}

// MARK: - Mapping

extension TrackEntity {
    /// Convert a transient ``Track`` DTO to a persisted entity.
    convenience init(track: Track) {
        self.init(
            videoId: track.videoId,
            title: track.title,
            channel: track.channel,
            durationSec: track.durationSec,
            thumbnailUrl: track.thumbnailUrl
        )
    }

    /// Produce a transient ``Track`` DTO from this entity.
    func asTrack() -> Track {
        Track(
            videoId: videoId,
            title: title,
            channel: channel,
            durationSec: durationSec,
            thumbnailUrl: thumbnailUrl
        )
    }
}
