import Foundation

// MARK: - PlaylistCodecError

/// Errors surfaced by ``PlaylistCodec``.
enum PlaylistCodecError: Error, Equatable {
    /// The file carries a `schemaVersion` higher than this build supports.
    /// The associated value is the version found in the file.
    case unsupportedSchemaVersion(Int)
    /// The raw data could not be decoded as a ``PlaylistPayload``.
    case decodingFailed(String)
    /// The payload could not be serialised to JSON.
    case encodingFailed(String)
}

// MARK: - PlaylistCodec

/// Encodes and decodes ``PlaylistPayload`` values to/from JSON.
///
/// Schema versioning contract (from docs/api-contracts.md):
/// - Files with `schemaVersion > supportedSchemaVersion` are **rejected** with
///   ``PlaylistCodecError/unsupportedSchemaVersion(_:)``.
/// - Files with `schemaVersion < supportedSchemaVersion` are accepted (migrate up — no-op for v1).
enum PlaylistCodec {

    /// The highest `schemaVersion` this build can import.
    static let supportedSchemaVersion = 1

    // MARK: Decode

    /// Decodes a ``PlaylistPayload`` from raw JSON data.
    ///
    /// - Parameter data: Raw bytes of a `.ytplaylist.json` file.
    /// - Returns: A decoded ``PlaylistPayload``.
    /// - Throws: ``PlaylistCodecError/unsupportedSchemaVersion(_:)`` when the file's
    ///   `schemaVersion` exceeds ``supportedSchemaVersion``;
    ///   ``PlaylistCodecError/decodingFailed(_:)`` for any other problem.
    static func decode(_ data: Data) throws -> PlaylistPayload {
        // First-pass: check schemaVersion before full decode.
        let version = try firstPassVersion(data)
        guard version <= supportedSchemaVersion else {
            throw PlaylistCodecError.unsupportedSchemaVersion(version)
        }
        do {
            return try makeDecoder().decode(PlaylistPayload.self, from: data)
        } catch let error as PlaylistCodecError {
            throw error
        } catch {
            throw PlaylistCodecError.decodingFailed(error.localizedDescription)
        }
    }

    // MARK: Encode

    /// Encodes a ``PlaylistPayload`` to JSON data.
    ///
    /// - Parameter payload: The playlist to encode.
    /// - Returns: Pretty-printed UTF-8 JSON bytes.
    /// - Throws: ``PlaylistCodecError/encodingFailed(_:)`` if serialisation fails.
    static func encode(_ payload: PlaylistPayload) throws -> Data {
        do {
            return try makeEncoder().encode(payload)
        } catch {
            throw PlaylistCodecError.encodingFailed(error.localizedDescription)
        }
    }

    // MARK: Private helpers

    private static func makeDecoder() -> JSONDecoder {
        let d = JSONDecoder()
        d.dateDecodingStrategy = .iso8601
        return d
    }

    private static func makeEncoder() -> JSONEncoder {
        let e = JSONEncoder()
        e.dateEncodingStrategy = .iso8601
        e.outputFormatting = [.prettyPrinted, .sortedKeys]
        return e
    }

    /// Lightweight first-pass decode that reads only `schemaVersion`.
    private static func firstPassVersion(_ data: Data) throws -> Int {
        struct VersionProbe: Decodable { let schemaVersion: Int }
        do {
            return try JSONDecoder().decode(VersionProbe.self, from: data).schemaVersion
        } catch {
            throw PlaylistCodecError.decodingFailed(error.localizedDescription)
        }
    }
}

// MARK: - SharingService (placeholder)

/// Placeholder. Real export/import via UIActivityViewController + UTType lands in a later task.
enum SharingService {}
