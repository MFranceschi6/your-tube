import Foundation
import SwiftData

// MARK: - PlaylistImportError

/// User-facing errors surfaced by ``PlaylistImportService``.
///
/// Each case provides ``alertTitle`` / ``userMessage`` suitable for an `Alert`.
/// Internal diagnostic detail is kept in associated values for logging only —
/// never log it together with PII.
enum PlaylistImportError: Error, Equatable {
    /// The file could not be read from disk.
    case fileRead(String)
    /// The JSON payload was malformed or did not match the expected schema.
    case invalidSchema(String)
    /// The file targets a `schemaVersion` newer than this build understands.
    case unsupportedSchemaVersion(Int)
    /// SwiftData rejected the upsert.
    case persistenceFailed(String)

    /// Title shown in the import-failure alert.
    var alertTitle: String {
        switch self {
        case .unsupportedSchemaVersion: return "Update Required"
        default: return "Import Failed"
        }
    }

    /// Body shown in the import-failure alert.
    var userMessage: String {
        switch self {
        case .fileRead:
            return "We couldn't read that playlist file. Make sure it hasn't been moved or deleted, then try again."
        case .invalidSchema:
            return "This file isn't a valid YourTube playlist."
        case .unsupportedSchemaVersion:
            return "This playlist was made with a newer version of YourTube. Please update the app to import it."
        case .persistenceFailed:
            return "Something went wrong saving the imported playlist."
        }
    }
}

// MARK: - PlaylistImportOutcome

/// Outcome of a successful ``PlaylistImportService/importPlaylist(from:into:)`` call.
struct PlaylistImportOutcome: Equatable {
    /// Display name of the imported playlist (after upsert).
    let name: String
    /// Stable playlist id.
    let id: String
    /// Number of tracks in the imported playlist.
    let trackCount: Int
}

// MARK: - PlaylistImportService

/// Reads a `.ytplaylist.json` URL produced by ``PlaylistExportService`` (or any
/// platform implementing the cross-platform export contract), decodes through
/// ``PlaylistCodec``, and upserts via ``PlaylistStore``.
///
/// Conflict resolution follows `docs/api-contracts.md` (last-write-wins on
/// `updatedAt`); ``PlaylistStore/importPlaylist(_:)`` is the source of truth
/// for the upsert rules.
enum PlaylistImportService {

    /// Import a playlist file at ``url`` into the SwiftData-backed store.
    ///
    /// - Parameters:
    ///   - url: A file URL pointing at a `.ytplaylist.json` document. URLs that
    ///     come from outside the app sandbox (Files, AirDrop) are wrapped in a
    ///     security-scoped resource access for the duration of the read.
    ///   - store: The ``PlaylistStore`` to upsert into.
    /// - Returns: ``PlaylistImportOutcome`` describing the imported playlist.
    /// - Throws: ``PlaylistImportError`` on any failure.
    @discardableResult
    static func importPlaylist(
        from url: URL,
        into store: PlaylistStore
    ) throws -> PlaylistImportOutcome {
        let needsScope = url.isFileURL && url.startAccessingSecurityScopedResource()
        defer { if needsScope { url.stopAccessingSecurityScopedResource() } }

        let data: Data
        do {
            data = try Data(contentsOf: url)
        } catch {
            throw PlaylistImportError.fileRead(error.localizedDescription)
        }

        return try importPlaylist(from: data, into: store)
    }

    /// Import a playlist payload from raw JSON bytes. Surface for tests.
    @discardableResult
    static func importPlaylist(
        from data: Data,
        into store: PlaylistStore
    ) throws -> PlaylistImportOutcome {
        // 1. Decode through the shared codec.
        let payload: PlaylistPayload
        do {
            payload = try PlaylistCodec.decode(data)
        } catch PlaylistCodecError.unsupportedSchemaVersion(let version) {
            throw PlaylistImportError.unsupportedSchemaVersion(version)
        } catch PlaylistCodecError.decodingFailed(let detail) {
            throw PlaylistImportError.invalidSchema(detail)
        } catch {
            throw PlaylistImportError.invalidSchema(error.localizedDescription)
        }

        // 2. Upsert (last-write-wins on updatedAt).
        do {
            let entity = try store.importPlaylist(payload)
            return PlaylistImportOutcome(
                name: entity.name,
                id: entity.id,
                trackCount: entity.orderedPositions.count
            )
        } catch {
            throw PlaylistImportError.persistenceFailed(error.localizedDescription)
        }
    }
}
