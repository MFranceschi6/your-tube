# Playback state restoration

Cross-platform contract for restoring the last-known player state at cold launch. Both clients (Android, iOS) implement this so users see equivalent behavior after relaunch.

This is an **internal** contract; nothing is sent off-device. Persistence layer is platform-native (Room on Android, SwiftData on iOS), but the **logical fields, the refresh policy, and the recovery rules are shared**. Stream URLs are still ephemeral and re-resolved at playback time per [docs/api-contracts.md](api-contracts.md).

## Persisted fields

The minimum a client MUST persist between sessions is below. Platforms MAY persist more (e.g., Media3 timeline windows on Android) for engine fidelity, but the fields below are the contract surface.

| Field | Type | Units | Required | Notes |
|---|---|---|---|---|
| `currentVideoId` | string \| null | — | yes | Empty queue ⇒ `null`. |
| `queue` | `Track[]` | — | yes | Ordered. May be empty. `Track` shape per [api-contracts.md](api-contracts.md#track). |
| `queueIndex` | integer | — | yes | Index into `queue` for `currentVideoId`. `-1` when `queue` is empty or `currentVideoId` is null. |
| `positionMs` | integer | milliseconds | yes | Last reported playback offset for the current track. Clamped to `[0, durationMs]` if `durationMs` known; otherwise `>= 0`. |
| `repeatMode` | enum (`off` \| `one` \| `all`) | — | yes | Wire values match `androidx.media3.common.Player.REPEAT_MODE_*` (0/1/2). iOS persists the same enum and maps to its internal type. |
| `shuffleOn` | boolean | — | yes | Persistent across sessions. |
| `playbackSpeed` | number | multiplier | yes | Range `[0.5, 2.0]`. Default `1.0`. |
| `savedAt` | iso8601 string | — | yes | Wall-clock timestamp of the last write. Used only for staleness telemetry; not for conflict resolution. |

Volume is **not** in the contract. Each platform respects the OS-level media volume; in-app volume is not persisted.

### Field-level notes

- `Track` is the resolved playable item per [api-contracts.md](api-contracts.md#track). Stream URLs are NOT persisted with the track; they are resolved at playback time.
- `queueIndex` is authoritative; `currentVideoId` is redundant but kept for sanity-check on load. If `queue[queueIndex].videoId != currentVideoId`, the client MUST treat the snapshot as corrupt and fall back to **empty state** (see Recovery, below).
- `repeatMode` persists across launches. `shuffleOn` persists across launches. The shuffled order itself is **not** persisted — on restore with `shuffleOn=true`, the client builds a fresh shuffle from `queue` keeping `queueIndex` as the new head.

## Refresh policy on cold launch

After the snapshot is loaded into memory, the client MUST:

1. **Load paused.** Restore the queue, current index, and offset into the player engine. Do NOT auto-resume playback. The user must explicitly hit play. Lock-screen / Now-Playing controls reflect the paused state.
2. **Lazy stream URL resolution.** Do NOT resolve the stream URL at launch. The first play action triggers extractor resolution exactly as it would for a freshly-tapped track.
3. **Surface the now-playing chrome.** MiniPlayer (Android) / NowPlaying card (iOS) is visible immediately on launch when `currentVideoId != null`, showing the persisted track metadata (title, channel, thumbnail). The play/pause control reflects the paused state.
4. **Preserve seek position.** When the user hits play, playback starts at `positionMs`, not at zero. If extraction returns a duration shorter than `positionMs` (rare), clamp to `durationMs - 1000ms` (one second before end) and continue.

## Recovery

These cases MUST be handled without crashing or showing an error toast unless explicitly noted:

| Case | Behavior |
|---|---|
| Persisted snapshot is missing or unparseable | Start with empty queue, no current track, default modes. No toast. |
| `queue` is empty (or `currentVideoId` is null) | Empty state. No MiniPlayer / NowPlaying card. Player shows the empty/idle screen. |
| `queueIndex` is out of bounds for `queue` | Treat as corrupt; fall back to empty state. Log at debug level only. |
| `queue[queueIndex].videoId` does not match `currentVideoId` | Treat as corrupt; fall back to empty state. |
| User hits play and stream URL resolution fails (track unavailable, region blocked, age-restricted, etc.) | Show the error inline on the player surface (existing extractor error path per [youtube-extraction-notes.md](youtube-extraction-notes.md)). Track stays in queue at `queueIndex`. User can skip-next or remove. |
| User hits play and the persisted track is a livestream that has ended | Same as resolution failure: inline error, user can skip. |
| `playbackSpeed` is outside `[0.5, 2.0]` | Clamp to nearest bound. No toast. |
| `repeatMode` value is unknown (future schema) | Coerce to `off`. No toast. |

## Parity expectations

Behavior the user sees 1 second after cold launch must match across platforms:

- Same persisted track metadata in MiniPlayer/NowPlaying chrome (title, channel, thumbnail).
- Same paused state (never auto-resumes).
- Same seek position on next play.
- Same queue ordering.
- Same `repeatMode` and `shuffleOn` reflected in chrome controls.
- Same `playbackSpeed` reflected in settings/sheet.

Tests SHOULD assert these via paired snapshots when feasible. Manual cross-platform verification is acceptable while no shared fixture format exists.

## When to write the snapshot

Each client writes the snapshot at minimum on:

- Track change (new current track loaded).
- Pause.
- Repeat-mode or shuffle change.
- Speed change.
- Periodic position write while playing (≤ once per 5 seconds).
- App backgrounding.
- Process termination signal where the platform allows.

Excessive writes are a perf bug, not a correctness bug. The 5-second cap balances battery against losing the seek position on crash.

## Non-goals

- No cross-device sync. The snapshot lives only on the device that wrote it.
- No history of past sessions. Only the most recent snapshot is kept.
- No restoration of queue items added during the previous session that are not in the snapshot at write time (e.g., items added between the last write and a crash are lost — accepted).

## Implementation tasks

- Android: YT-0079 (`feature/player` cold-launch restoration on top of `core/database` + `core/data`).
- iOS: YT-0078 (SwiftData-backed session snapshot, restored by `PlayerCoordinator` at app launch).
