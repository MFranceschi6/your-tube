# Mix Queue Contract

## Overview

When a user taps a track, the app populates the playback queue with a YouTube "Mix [title]" radio queue — an ordered, curated list of ~25 tracks seeded from the tapped video. This is distinct from the `RelatedVideoClient` autoplay-related flow, which fetches a single end-of-queue candidate.

## Endpoint

| Property | Value |
|---|---|
| Method | `POST` |
| URL | `https://www.youtube.com/youtubei/v1/next` |
| Headers | Same as `RelatedVideoClient` (see below) |

**Headers:**

```
Content-Type: application/json
User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36
X-YouTube-Client-Name: 1
X-YouTube-Client-Version: 2.20240726.00.00
```

## Request Shape

```json
{
  "context": {
    "client": {
      "clientName": "WEB",
      "clientVersion": "2.20240726.00.00",
      "hl": "en"
    }
  },
  "videoId": "<videoId>",
  "playlistId": "RD<videoId>"
}
```

The only difference from the `getRelatedVideos` request body is the addition of `"playlistId": "RD<videoId>"`. The `RD` prefix is the legacy YouTube radio/mix prefix.

## Response Parse Path

```
root
  .contents
  .twoColumnWatchNextResults
  .playlist
  .playlist
  .contents[]
  .playlistPanelVideoRenderer
```

## Field Mapping

| Field | JSON Path (from `playlistPanelVideoRenderer`) | Notes |
|---|---|---|
| `videoId` | `.videoId` | Direct top-level field (NOT via navigationEndpoint) |
| `title` | `.title.simpleText` | Plain string |
| `channel` | `.longBylineText.runs[0].text` | First run text |
| `durationSec` | `.lengthText.simpleText` parsed via `parseDuration()` | e.g. `"4:56"` → `296` |
| `thumbnailUrl` | `.thumbnail.thumbnails[-1].url` | Last entry = largest resolution |

## Seed Track Inclusion

`tracks[0].videoId == seedVideoId`. The first entry in the Mix is the tapped track itself. **The client includes it at index 0** — it is the track already playing, so the queue at position 0 reflects what the user tapped. The tail (`tracks.drop(1)`) are the Mix candidates that follow.

Consumers in `DefaultPlayerController.tryLoadMixQueue` skip the seed (index 0) before appending the tail to the queue, because the seed is already at queue index 0 from the `playNow(track)` call.

## Mix Length

~25 entries is typical. `isInfinite: true` is returned in the playlist metadata, meaning the server can extend the list on further requests. The client does **not** cap the list — the server response shape determines the count.

## Filtering

Apply the same eligibility rules as autoplay (per `docs/autoplay.md`):

- Filter tracks with `durationSec < 60` (Shorts, previews).
- Filter tracks with `durationSec == 0` (livestreams).
- Do **not** apply history loop-avoidance for the initial Mix population — the Mix is a curated, ordered radio queue, not an autoplay candidate selection.

## Difference vs. `RelatedVideoClient`

| Dimension | Mix Queue (`getMixQueue`) | Related Videos (`getRelatedVideos`) |
|---|---|---|
| Parse path | `twoColumnWatchNextResults.playlist.playlist.contents[]` | `twoColumnWatchNextResults.secondaryResults` |
| Renderer type | `playlistPanelVideoRenderer` | `lockupViewModel` / `compactVideoRenderer` |
| Response size | ~25 entries | ~20 entries |
| Ordering | Stable curated radio order | Contextual, varies per session |
| Use case | Initial queue population on track tap | Single end-of-queue autoplay candidate |
| Request body | Adds `"playlistId": "RD<videoId>"` | No `playlistId` |

## Client Recommendation

Extend `RelatedVideoClient` with `getMixQueue(videoId): List<SearchResult>`. Same HTTP infrastructure (`NEXT_URL`, same headers, same `OkHttpClient`), keeps all InnerTube `/next` concerns in one file. Introduce a `toMixTrack()` private extension on `JsonObject` since the renderer shape (`playlistPanelVideoRenderer`) differs from both `lockupViewModel` and `compactVideoRenderer`.

Expose via `YoutubeService.getMixQueue(videoId)`, delegated by `NewPipeYoutubeService` following the same `withContext(dispatcher)` + empty-on-failure pattern as `getRelatedVideos`.

## Edge Cases

| Case | Behavior |
|---|---|
| Video with no Mix available | Server returns `playlist.contents` with 0 entries → empty list → fall back to single-track queue silently |
| HTTP failure / parse error | Return `emptyList()` — same as `getRelatedVideos` contract |
| Blank `videoId` in an entry | Skip that entry (null-safe in `toMixTrack()`) |
| Zero-duration entry (livestream) | Filtered out client-side (`durationSec == 0` treated as ineligible) |
| Short entry (`durationSec < 60`) | Filtered out client-side |
| Age-restricted / region-restricted entries | Returned by the Mix endpoint but stream resolution will fail at play time — not filtered pre-queue; the existing error state handles it |
| Music-only Mixes | Same shape; no special handling needed |

## Anti-Bot / Rate-Limit Considerations

- Same WEB client credentials as `getRelatedVideos` — no API key required (hardcoded key causes 4xx rejections).
- The Mix fetch is fire-and-forget and runs once per `playNow` call. No polling.
- Failures are silently swallowed (empty list) — no retry logic.

## Continuation / Pagination

YouTube's Mix is effectively infinite on the web client: scrolling the Mix panel triggers a follow-up `POST /youtubei/v1/next` whose body carries the previous page's continuation token, and the server appends ~25 more entries plus a *new* token. The MVP `getMixQueue` call only walks the initial page; this section documents how to extend the queue past it.

Implementation lands in YT-0297 (Android) and YT-0298 (iOS). This section is the cross-platform contract.

### Token location (initial page)

The initial page response (the same payload `getMixQueue` already parses) carries the first continuation token at:

```
root
  .contents.twoColumnWatchNextResults
  .playlist.playlist
  .continuations[]
  .nextContinuationData
  .continuation
```

`continuations[]` is a sibling of `contents[]` inside `playlist.playlist`. Production responses (WEB client `2.20240726.00.00`, observed Aug 2024) ship the **legacy** `nextContinuationData` envelope on the Mix panel — they have *not* migrated to the modern `continuationItemRenderer.continuationEndpoint.continuationCommand.token` shape that browse/search responses use. Cross-referenced with `NewPipeExtractor`'s `YoutubeMixOrPlaylistExtractor` (reads `nextContinuationData`) and yt-dlp's `_extract_continuation` (probes both shapes).

The reference parser in `RelatedVideoClient.extractContinuationToken` probes the legacy path first and falls back to the modern path defensively, so a future migration on YouTube's side will not silently break pagination.

### Continuation request shape

Same endpoint, same headers as the initial Mix call. The body carries **only** `context` and `continuation` — `videoId` and `playlistId` are NOT re-sent; the token already encodes which Mix is being walked.

```json
{
  "context": {
    "client": {
      "clientName": "WEB",
      "clientVersion": "2.20240726.00.00",
      "hl": "en"
    }
  },
  "continuation": "<token-from-previous-page>"
}
```

### Continuation response parse path

Continuation responses replace the `contents.twoColumnWatchNextResults.playlist.playlist` wrapper with `continuationContents.playlistPanelContinuation`. The inner `contents[] + continuations[]` shape is **identical** to the initial page's playlist node, so the same `playlistPanelVideoRenderer` field mapping (see §Field Mapping) applies.

```
root
  .continuationContents
  .playlistPanelContinuation
  .contents[]
  .playlistPanelVideoRenderer       // same shape as initial page
```

The next continuation token sits at `continuationContents.playlistPanelContinuation.continuations[0].nextContinuationData.continuation` — same envelope as the initial page.

### Pagination flow

```
[playNow] ──▶ POST /next { videoId, playlistId: "RD<videoId>" }
              └─▶ items_0[], token_A
                 │
                 ├─ enqueue items_0[1..]   (skip seed at index 0)
                 ▼
              POST /next { continuation: token_A }
              └─▶ items_1[], token_B
                 │
                 ├─ enqueue items_1[]
                 ▼
              POST /next { continuation: token_B }
              └─▶ items_2[], token_C
                 ...
              POST /next { continuation: token_N }
              └─▶ items_M[], (no continuations[])
                 │
                 └─ TERMINAL — fall back to autoplay-related at queue tail
```

### Termination behaviour

The Mix panel response on `playlist.playlist` carries `isInfinite: true`, but in practice the server eventually stops returning a `continuations[]` block (or returns one whose `continuation` field is empty). When that happens the consumer must stop paginating and fall back to the existing autoplay-related path (`docs/autoplay.md`) — do **not** retry the same token, and do **not** re-fetch the initial Mix page hoping for a different terminal point.

Observed loop behaviour: very long walks (>10 pages) eventually start recycling earlier `videoId`s that already appeared in the merged queue. Consumers SHOULD dedupe appended items against the in-memory queue (videoId-keyed set), and MAY treat "all items in this page were duplicates" as an early termination signal even if the server still returned a token.

### Client surface

`RelatedVideoClient` exposes two entry points (YT-0296):

- `getMixQueueWithContinuation(videoId): MixQueueResult` — same wire call as the original `getMixQueue` but surfaces the first continuation token alongside the items. The legacy `getMixQueue(videoId): List<SearchResult>` is preserved as a thin convenience that drops the token, so existing callers (`DefaultPlayerController.tryLoadMixQueue`) keep working.
- `getMixContinuation(token): MixQueueResult` — fetches the next page given a token. Returns `MixQueueResult.empty()` (`items = []`, `nextToken = null`) on HTTP failure, parse error, or blank token. A non-null `nextToken` means the consumer SHOULD continue paginating; `null` means stop.

`MixQueueResult` is `(items: List<SearchResult>, nextToken: String?)`. iOS mirrors this with `(items: [SearchResult], nextToken: String?)` on the equivalent service surface.

### Edge cases

| Case | Behavior |
|---|---|
| HTTP failure on continuation call | Return empty `MixQueueResult` — consumer falls back to autoplay-related |
| Malformed continuation response | Same as HTTP failure |
| `continuations[]` block missing | `nextToken = null`; consumer stops, falls back to autoplay-related |
| `continuations[].nextContinuationData.continuation` empty string | Treated as null — stop paginating |
| All items in a page are duplicates of already-queued videoIds | Consumer-level termination (treat as terminal, do not fetch the next page) |
| Server migrates to `continuationItemRenderer.continuationEndpoint.continuationCommand.token` | Defensive parser probe surfaces it; no contract change required |
| Very long Mixes | No observed hard cap; eventual loop / terminal token is the natural stop |
| Blank continuation token passed to `getMixContinuation` | Short-circuit empty result with no HTTP call |

### Anti-bot / rate-limit considerations (continuation-specific)

- Continuation calls share the `/youtubei/v1/next` quota with `getRelatedVideos` and the initial Mix fetch. A burst of N continuation calls in <1 s looks scraper-shaped.
- Recommendation: paginate **lazily** (one page ahead of the queue tail), not eagerly (whole walk on `playNow`). Triggering the next continuation when the queue has ≤3 unplayed entries left is the target heuristic for YT-0297 / YT-0298.
- If a continuation call fails, do NOT retry on a tight loop. Single attempt → fall back to autoplay-related at the tail.
- Re-use the same `OkHttpClient` / `URLSession` instance and the same `X-YouTube-Client-Name` / `X-YouTube-Client-Version` headers as the initial call so connection-reuse and TLS fingerprint stay stable across the chain.
- Do NOT invent a `clickTrackingParams` value when sending a continuation; the production web client omits it on continuation POST bodies and the server is happy without it.

### Reference fixtures

- `docs/fixtures/mix-queue/dQw4w9WgXcQ.json` — initial page, includes the first continuation token.
- `docs/fixtures/mix-queue/dQw4w9WgXcQ-continuation.json` — second-page response shape with appended `playlistPanelVideoRenderer` items and the next token.
- Fixture chain documented in `docs/fixtures/mix-queue/README.md`.
- Tokens in committed fixtures are **synthetic / scrubbed**; they will not validate against the live YouTube backend. Capture fresh tokens from a logged-out DevTools session if you need a live round-trip.
