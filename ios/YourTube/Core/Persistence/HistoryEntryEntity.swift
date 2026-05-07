import Foundation
import SwiftData

// MARK: - HistoryEntryEntity

/// One "recently played" track. Entries are deduped per ``videoId`` by
/// ``HistoryStore/append(_:playedAt:)``: replaying a track refreshes the
/// existing entry's ``playedAt`` and metadata snapshot rather than inserting a
/// new row.
///
/// History entries are value-level records and are deleted independently
/// (e.g. via ``HistoryStore/trim(to:)``).
@Model
final class HistoryEntryEntity {

    // MARK: Stored properties

    /// YouTube video identifier of the track that was played.
    var videoId: String
    /// Snapshot of the track title at play time.
    var title: String
    /// Snapshot of the channel name at play time.
    var channel: String
    /// Duration in whole seconds (0 for live / unknown).
    var durationSec: Int
    /// Thumbnail URL snapshot at play time.
    var thumbnailUrl: String
    /// Wall-clock time when the play event was recorded.
    var playedAt: Date

    // MARK: Init

    init(
        videoId: String,
        title: String,
        channel: String,
        durationSec: Int,
        thumbnailUrl: String,
        playedAt: Date
    ) {
        self.videoId = videoId
        self.title = title
        self.channel = channel
        self.durationSec = durationSec
        self.thumbnailUrl = thumbnailUrl
        self.playedAt = playedAt
    }
}

// MARK: - Mapping

extension HistoryEntryEntity {
    /// Record a play event for the given track DTO.
    convenience init(track: Track, playedAt: Date = .now) {
        self.init(
            videoId: track.videoId,
            title: track.title,
            channel: track.channel,
            durationSec: track.durationSec,
            thumbnailUrl: track.thumbnailUrl,
            playedAt: playedAt
        )
    }

    /// Produce a transient ``Track`` DTO from the snapshot stored in this entry.
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
