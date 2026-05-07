import Foundation

// MARK: - PlaylistExportError

/// Errors surfaced by ``PlaylistExportService``.
enum PlaylistExportError: Error, Equatable {
    /// The payload could not be encoded to JSON.
    case encoding(String)
    /// The temporary file could not be written.
    case fileWrite(String)
}

// MARK: - PlaylistExportService

/// Writes a ``PlaylistPayload`` to a temporary `.ytplaylist.json` file
/// suitable for handing to `UIActivityViewController` / `ShareLink`.
///
/// Files are placed in `FileManager.default.temporaryDirectory` so the OS can
/// reclaim them; nothing here ever touches the persistent playlist store.
enum PlaylistExportService {

    /// Encode ``payload`` and write it to a freshly minted temporary URL.
    ///
    /// The filename is `<sanitised name>.ytplaylist.json`. If a previous export
    /// exists at the same URL it is overwritten — the temp directory is the
    /// only location we ever reuse.
    ///
    /// - Parameters:
    ///   - payload: Playlist DTO to serialise.
    ///   - directory: Override for testing. Defaults to the system temp dir.
    /// - Returns: URL of the written temporary file.
    /// - Throws: ``PlaylistExportError`` on encode/write failure.
    static func writeTemporaryFile(
        for payload: PlaylistPayload,
        in directory: URL = FileManager.default.temporaryDirectory
    ) throws -> URL {
        let data: Data
        do {
            data = try PlaylistCodec.encode(payload)
        } catch let error as PlaylistCodecError {
            throw PlaylistExportError.encoding(String(describing: error))
        } catch {
            throw PlaylistExportError.encoding(error.localizedDescription)
        }

        let filename = sanitisedFilename(for: payload.name)
        let url = directory.appendingPathComponent("\(filename).ytplaylist.json")

        do {
            // Clean overwrite — Files / AirDrop choke on stale partial writes.
            try? FileManager.default.removeItem(at: url)
            try data.write(to: url, options: .atomic)
        } catch {
            throw PlaylistExportError.fileWrite(error.localizedDescription)
        }

        return url
    }

    // MARK: Helpers

    /// Strip path-hostile characters from a playlist name for use in a filename.
    /// Falls back to "Playlist" if the result would be empty.
    private static func sanitisedFilename(for rawName: String) -> String {
        let invalid = CharacterSet(charactersIn: "/\\:*?\"<>|\n\r")
        let scalars = rawName.unicodeScalars.filter { !invalid.contains($0) }
        let trimmed = String(String.UnicodeScalarView(scalars))
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? "Playlist" : trimmed
    }
}
