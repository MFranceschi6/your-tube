# API contracts

YourTube has no backend. This document defines the **internal cross-platform data contract** both clients (Android, iOS) implement against, so that exported playlist files and any future cloud-synced payloads round-trip across platforms.

Stream URL resolution is **not** part of this contract — stream URLs are ephemeral and re-resolved at playback time by the platform-specific extractor (NewPipeExtractor on Android, YouTubeKit on iOS). See [youtube-extraction-notes.md](youtube-extraction-notes.md) for observed iOS extractor failure modes and mitigation ranking.

## Types

### `SearchResult`

A single video result from a search query. Transient; never persisted.

```jsonc
{
  "videoId": "string",       // YouTube video id, e.g. "dQw4w9WgXcQ"
  "title": "string",
  "channel": "string",
  "durationSec": 0,          // integer seconds; 0 if unknown (livestream)
  "thumbnailUrl": "string"   // https URL, prefer mqdefault
}
```

### `Track`

A resolved, playable item. Persisted in playlists, queue, history.

```jsonc
{
  "videoId": "string",
  "title": "string",
  "channel": "string",
  "durationSec": 0,
  "thumbnailUrl": "string"
}
```

`Track` and `SearchResult` are intentionally identical today. They diverge later (e.g. `Track` may gain `addedAt`, `lastPlayedAt`).

### Playlist export file

File extension `.ytplaylist.json`, MIME `application/json`. Produced by export, consumed by import.

```jsonc
{
  "schemaVersion": 1,
  "id": "uuid",              // stable across export/import; used for upsert
  "name": "string",
  "createdAt": "iso8601",
  "updatedAt": "iso8601",
  "tracks": [ /* Track[] */ ]
}
```

## Schema versioning

- `schemaVersion` is an integer. Bump on any breaking change to the export shape.
- Importers MUST reject files with `schemaVersion` higher than they support and surface a "please update the app" error.
- Importers SHOULD accept files with lower `schemaVersion` and migrate up.

## Canonical fixtures

- Valid v1 fixture: `docs/fixtures/playlist-valid-v1.ytplaylist.json`
- Future-schema rejection fixture: `docs/fixtures/playlist-future-schema.ytplaylist.json`

Platform tests may copy these files into local test resources if required by the build system, but copied fixtures must stay byte-for-byte equivalent to the canonical files.

### Cross-platform parity fixtures

Two additional fixtures encode the **same logical playlist** in each platform's native export shape:

- `docs/fixtures/playlist-android-export-canonical.ytplaylist.json` — Android `kotlinx.serialization` output (declaration-order keys, `": "` separator, 2-space indent).
- `docs/fixtures/playlist-ios-export-canonical.ytplaylist.json` — iOS `JSONEncoder` output with `[.prettyPrinted, .sortedKeys]` (alphabetical keys, `" : "` separator, 2-space indent).

Both files describe the same `PlaylistPayload` (same `id`, `name`, timestamps, track order, track metadata) — only the JSON formatting differs. This is the contract surface the parity tests below exercise.

### Parity tests

Parity is asserted automatically in two paired tests; the manual real-app round trip in `YT-0034` is now a confirmation step on top of these checks:

- iOS: `ios/YourTubeTests/CrossPlatformParityTests.swift` decodes the Android-canonical fixture through `PlaylistCodec`, asserts the decoded `PlaylistPayload` matches the shared canonical record, decodes the iOS-canonical fixture and exercises a `decode -> encode -> decode` round trip to anchor the codec against the committed fixture, and asserts `playlist-future-schema.ytplaylist.json` decodes as `PlaylistCodecError.unsupportedSchemaVersion(999)`.
- Android: `android/core/data/src/test/kotlin/com/yourtube/core/data/codec/CrossPlatformParityTest.kt` decodes the iOS-canonical fixture through `KotlinxPlaylistCodec`, asserts the decoded `Playlist` matches the same shared canonical record, re-exports the canonical record and asserts the bytes match the Android-canonical fixture (after `trim`), and asserts the future-schema fixture is rejected with `PlaylistCodecError.UnsupportedSchemaVersion(found = 999, supported = 1)`.

If either side starts failing, do not silently update the fixture or the test expectation — file a follow-up task. The parity tests exist to expose codec drift, not to absorb it.

## Conflict resolution on import

- Match incoming playlist by `id`.
- If no match: insert as new.
- If match: compare `updatedAt`. Keep newer. Older copy is discarded (last-write-wins). User confirmation prompt is OPTIONAL but recommended.

## Related contracts

Cross-platform behaviors that are NOT part of the export shape but ARE shared between clients live in companion documents:

- [docs/playback-state.md](playback-state.md) — what each client persists for cold-launch player restoration.
- [docs/search-suggest.md](search-suggest.md) — YouTube Suggest endpoint usage and recent-searches behavior.
- [docs/autoplay.md](autoplay.md) — end-of-queue autoplay selection and loop avoidance.
- [docs/mix-queue.md](mix-queue.md) — YT-0293 Mix queue endpoint, request shape, response parse path, and field mapping for initial queue population on track tap.

## Search filters

Both clients support three filter groups on the search results screen:

| Group       | Values                                  |
|-------------|-----------------------------------------|
| Duration    | Any, Short (< 4 min), Medium (4-20 min), Long (> 20 min) |
| Upload date | Any, Today, This week, This month, This year |
| Type        | Any, Video, Playlist, Channel           |

**Filter groups are mutually exclusive for MVP.** Selecting a chip in one group clears the other two groups' selections. Only one `sp` parameter value is submitted to the YouTube search API per request.

### Rationale

The YouTube InnerTube `sp` query parameter encodes a protobuf `SearchFilter` message. Combining selections from multiple groups (e.g. duration = Short AND type = Video) requires building a multi-field protobuf payload. Implementing a protobuf encoder or a 4×5×4 = 80-combination lookup table is deferred post-MVP.

### Behavior contract (both platforms)

- Tapping an unselected chip: selects it, clears sibling groups, re-runs search with the new `sp`.
- Tapping the already-selected chip in a group: deselects it (group returns to Any), re-runs search without that group's `sp`.
- Clearing the query (X button or equivalent): resets all filter groups to Any in addition to clearing the query text and returning to the Idle state.
- No request is issued if the resolved `sp` value is identical to the last submitted one (deduplication on filter toggle).

### Post-MVP

Combining multiple filter groups requires protobuf encoding of the InnerTube `SearchFilter` message. Track in a post-MVP task.

## Non-goals

- Authentication tokens, signed URLs, server-side state — none. There is no server.
- Video stream URLs, audio stream URLs, format/bitrate info — extractor-owned, never persisted.
- Sync protocol — out of scope until v1.3 (cloud sync).
