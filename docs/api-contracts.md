# API contracts

YourTube has no backend. This document defines the **internal cross-platform data contract** both clients (Android, iOS) implement against, so that exported playlist files and any future cloud-synced payloads round-trip across platforms.

Stream URL resolution is **not** part of this contract — stream URLs are ephemeral and re-resolved at playback time by the platform-specific extractor (NewPipeExtractor on Android, YouTubeKit on iOS).

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

## Conflict resolution on import

- Match incoming playlist by `id`.
- If no match: insert as new.
- If match: compare `updatedAt`. Keep newer. Older copy is discarded (last-write-wins). User confirmation prompt is OPTIONAL but recommended.

## Non-goals

- Authentication tokens, signed URLs, server-side state — none. There is no server.
- Video stream URLs, audio stream URLs, format/bitrate info — extractor-owned, never persisted.
- Sync protocol — out of scope until v1.3 (cloud sync).
