# Android YouTube extraction

After YT-0163, both extraction surfaces (search and stream resolution) talk to
InnerTube directly. NewPipeExtractor and its Rhino-based JS signature solver
have been removed from the dependency graph entirely.

## Search — InnerTube (direct)

`InnerTubeSearchClient` POSTs to `https://www.youtube.com/youtubei/v1/search`
with the `WEB` client context. NewPipe's `YoutubeSearchExtractor.getInitialPage`
NPE'd against the current YouTube response shape (v0.26.1), and
`ExtractorHelper.getItemsPageOrLogError` swallowed the failure and returned
zero results — the silent-empty trap from YT-0008.

Response is parsed by walking
`contents → twoColumnSearchResultsRenderer → primaryContents
→ sectionListRenderer → contents[] → itemSectionRenderer → contents[]
→ videoRenderer`. Non-`videoRenderer` shelf entries are skipped. Failures
(non-2xx, malformed JSON, unexpected shape) all return an empty list and log
through `android.util.Log`.

Fragility we accept knowingly:

- `INNERTUBE_CLIENT_VERSION` (`YoutubeHttp.kt`) is pinned. YouTube rejects
  stale versions over time; bump or fall back to scraping `ytcfg` from
  `/watch` when the version goes cold.
- `parseResults` is unit-tested against `innertube-search-fixture.json` to
  catch regressions where YouTube changes the JSON shape and we silently
  return empty.

## Stream resolution — InnerTube `/player` (direct, VISIONOS client)

`LivePlayerExtractor.resolve` POSTs to `https://www.youtube.com/youtubei/v1/player`
with the `VISIONOS` InnerTube client. The `/player` response carries
pre-signed `googlevideo.com` URLs — no JS signature solver needed (this is
the load-bearing reason iOS YT-0162 made the same swap; YT-0163 mirrors it
on Android for the latency win).

### Client constants (yt-dlp pin)

Constants live in a single block in `InnerTubePlayerClient.kt`. Source of
truth: `yt-dlp/yt_dlp/extractor/youtube/_base.py`, `visionos` entry
(verified 2026-09-03 against upstream master). VISIONOS is yt-dlp's default
JS-less client (`_DEFAULT_JSLESS_CLIENTS`) and needs no PO token. When
YouTube rotates the values, update this single block:

- `clientName = "VISIONOS"`
- `clientVersion = "1.02"`
- `INNERTUBE_CONTEXT_CLIENT_NAME = 101` (sent as `X-YouTube-Client-Name`)
- `deviceMake = "Apple"`, `deviceModel = "RealityDevice17,1"`
- `osName = "visionOS"`, `osVersion = "26.5.23O471"`
- `User-Agent: Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15`

Known VISIONOS quirks:

- No progressive `formats` array; only `adaptiveFormats` + `hlsManifestUrl`.
  The muxed fallback is dormant.
- Un-ranged GETs on the `googlevideo.com` URL are throttled server-side;
  ranged requests are served at full speed.
- "Made for kids" videos are unavailable (same as ANDROID_VR).

### Why not ANDROID_VR anymore (2026-09-03)

The previous pin was `ANDROID_VR` 1.65.10. Since 2026-08-17 YouTube gates
its `googlevideo.com` URLs behind a GVS Proof-of-Origin token: `HEAD`
returns 403 and any URL returns 403 after ~1 MB cumulative. yt-dlp upstream
note: "Since 2026.08.17, ALL formats (including live HLS and itag 18) are
403'd with version 1.65.10"; its `GVS_PO_TOKEN_POLICY` for `android_vr` is
now `required=True`. Reproduced with curl on 2026-09-03.

### Visitor data prerequisite

The former ANDROID_VR client returned `playabilityStatus.status = LOGIN_REQUIRED`
("Sign in to confirm you're not a bot") on most public videos when called without a
session-scoped `visitorData` token. `VisitorIdCache` lazily fetches one from
`https://www.youtube.com/youtubei/v1/visitor_id` on first resolve and caches
it for the lifetime of the extractor singleton. The token is injected as both
`X-Goog-Visitor-Id` request header AND `context.client.visitorData` body
field. Concurrent first-callers share the same in-flight `Deferred` so the
transport sees exactly ONE hit even with N awaiters
(`VisitorIdCacheTest.kt::concurrent first fetches collapse to a single transport hit`
locks this).

### Format selection

Audio-only selection mirrors the bitrate-ceiling logic that
`NewPipeYoutubeService.selectPreferredStream()` ran over NewPipe streams:
within a tier, pick the highest bitrate at or below the requested ceiling;
fall back to the lowest bitrate available within the tier when no candidate
respects the ceiling. Order of preference:

1. `audio/mp4` with `mp4a` codec — parity with iOS YT-0162.
2. `audio/webm` with `opus` codec — **Android-only fallback**. ExoPlayer /
   Media3 1.4.1 plays opus/webm natively via its built-in extractor. iOS
   skips this entirely because AVPlayer can't decode opus outside HLS.
3. Progressive (muxed audio+video) `video/mp4 (avc1+mp4a)` — used when no
   audio-only stream is present (rare on modern uploads).
4. `streamingData.hlsManifestUrl` — livestream manifest. ExoPlayer routes
   via `HlsMediaSource.Factory` (see `PlaybackPlayerAdapter.queue()` —
   livestream URLs are flagged via `PreparedPlayback.container == "hls"`).

Formats with both `bitrate` and `averageBitrate` null are rejected up-front
so they can never coalesce to `Int.MAX_VALUE` and win the lowest-bitrate
fallback (mirror of YT-0162 reviewer pass nit #1).

### Playability gating

`playabilityStatus.status` maps to `ExtractorError`:

- `OK` → proceed.
- `LOGIN_REQUIRED`, `AGE_VERIFICATION_REQUIRED`, `CONTENT_CHECK_REQUIRED`,
  `UNPLAYABLE`, `ERROR`, `LIVE_STREAM_OFFLINE` → `VideoUnavailable`.
- Any other status → `NetworkError` defensively (the existing retry banner
  gives the user a way out).

### Bot-challenge surfacing

HTTP 429 from `/player` is mapped directly to `ExtractorError.BotChallenge`
inside `LivePlayerExtractor` — no NewPipe symbols in the mapping path. The
existing `NewPipeYoutubeService` upstream handler still recognises the
typed exception and propagates it to the UI banner.

## Shared User-Agent

`InnerTubeSearchClient` injects `YOUTUBE_DESKTOP_USER_AGENT` (desktop Chrome)
so the UA matches the InnerTube `WEB` client and avoids the
mobile-UA + WEB-client asymmetry that triggers fresh-IP bot detection on
emulators. `LivePlayerExtractor` uses the VISIONOS Safari User-Agent
described above (see Client constants).

## Dependency injection

`NetworkModule` provides a `@Singleton OkHttpClient` and passes the same
instance to:

- `InnerTubeSearchClient` (search).
- `LivePlayerExtractor` and its embedded `VisitorIdCache` (stream resolution).

This guarantees that interceptors, timeouts, and connection pools added at the
graph root reach both extraction paths.

## Rotation monitoring

When YouTube rotates the `VISIONOS clientVersion`, the symptom is
`/player` returning `playabilityStatus.status = ERROR` or empty
`streamingData` for previously-resolvable videos. A subtler failure mode
(seen with ANDROID_VR in 2026-08) is `/player` still answering `OK` while
the `googlevideo.com` URL returns 403 on `HEAD` or after ~1 MB — that means
the client now needs a PO token and must be swapped. Watch yt-dlp's
`yt_dlp/extractor/youtube/_base.py` (`_DEFAULT_JSLESS_CLIENTS`) for the
current no-PO-token client; bump the constants block in
`InnerTubePlayerClient.kt` accordingly.
The MVP rotation-monitoring posture is shared with iOS YT-0162.
