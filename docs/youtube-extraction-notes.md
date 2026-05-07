# YouTube extraction notes

YourTube has no backend. Stream URL resolution is fully client-side, so extractor fragility surfaces directly to the user. This page captures observed failure modes from the YT-0033 / YT-0046 manual validation sessions, the upstream causes, and the mitigations we considered. It exists so the next contributor recognizes upstream fragility for what it is and does not chase it as a local bug.

iOS uses YouTubeKit (SPM), pinned at **0.4.8** in `Package.resolved`. Production wiring is `LiveYouTubeService` + `LiveStreamExtractor` in `ios/YourTube/Core/YouTube/LiveYouTubeService.swift`.

> [!note] Android
> The Android client uses NewPipeExtractor (separate dependency, separate failure surface). It is not covered here. When Android playback validation reaches manual sim, track its equivalent fragility in its own doc / task — do not assume parity with this page.

## Failure modes observed

- **Intermittent decode failure on a stable video.** Same short track sometimes plays, sometimes does not. No deterministic input change between attempts.
- **Long videos / livestreams returned empty `streams`.** YouTubeKit's `streams` array is empty (or throws) for 24/7 livestreams (Lofi Girl, news loops). The DASH path returns nothing playable; the HLS manifest is reachable via `livestreams` instead. Handled in YT-0046 v3 (`StreamExtracting.firstHLSLivestreamURL`).
- **First-play stall on multi-hour mp4a streams.** Convenience `AVPlayerItem(url:)` triggered an eager full-asset duration probe before flipping to `.readyToPlay`. Not strictly an extractor fault but adjacent to it — fixed in YT-0046 v1/v2 by switching to `AVURLAsset(automaticallyLoadedAssetKeys: ["playable"])` with `AVURLAssetPreferPreciseDurationAndTimingKey: false`.
- **Audio queue teardown immediately after playback starts.** `AQME Default-InputOutput: client stopping; running count now 1`, `timeControlStatus` flips 2 → 0 within milliseconds. Caused by a missed `AVAudioSession` activation; defensive re-activation landed in YT-0046 v3.
- **No reproducible captcha / bot-challenge yet** during manual runs, but assumed-possible — InnerTube can return interstitial / consent payloads under the same JSON envelope and the parser silently empties out.

## Upstream causes

- **YouTube rotates player JS.** Signature decryption, throttling parameters, and the JSON envelope shape change without notice. YouTubeKit's parser pins to a snapshot of that shape; when the shape drifts, `Extraction.parseForObjectFromStartpoint` and friends throw decode errors.
- **Livestreams use a separate API path.** `streams` (DASH/progressive) and `livestreams` (HLS) are populated by different InnerTube responses; long-running content often only has the HLS manifest.
- **Signature / `n`-parameter decryption is required for some itags.** When the player JS rotates, the decryption helper functions move and YouTubeKit cannot wire them up — selected URLs come back unsigned or 403.
- **Bot detection / consent interstitials.** YouTube can serve a captcha or consent page for the same InnerTube call depending on IP, user-agent, cookies, and request cadence. The current InnerTube search payload uses a fixed `clientVersion` string and a generic `Mozilla/5.0` UA; both are stale-by-design.
- **No official public API for stream URLs.** YouTube Data API v3 exposes metadata but not media URLs. There is no supported contract; everything below the `SearchResult` / `Track` boundary in `docs/api-contracts.md` is reverse-engineered.

## Log signatures to recognize

When triaging a "track will not start" report, grep the console for these:

- `[com.matteofranceschi.yourtube:Extraction] Failed to decode object from given start point: The data couldn't be read because it isn't in the correct format.` — emitted by `YouTubeKit.Extraction.parseForObjectFromStartpoint` when YouTube's player JS structure has drifted underneath the parser. **This is the canonical "upstream rotated, our pinned parser is now wrong" signal.**
- `AQME Default-InputOutput: client stopping; running count now 1` followed by `timeControlStatus=0` — audio session activation gap, not the extractor. See YT-0046 v3.
- Empty `streams` with no thrown error followed by the HLS fallback succeeding — livestream that never had a DASH path.
- A successful `streams` array but every URL 403s on first byte-range request — signature decryption mismatch (player JS rotated faster than YouTubeKit).

## Candidate mitigations (ranked by cost)

Cost is rough engineering cost / ongoing maintenance burden. **MVP-blocking** means we should not ship MVP without it. **Post-MVP** means it is worth tracking but extraction fragility is partially structural and the MVP target is *robustness on the happy path + clear error UX when extraction fails*, not *perfect extraction*.

| # | Mitigation | Cost | MVP-blocking? | Notes |
|---|---|---|---|---|
| 1 | **Surface clear error UX when extraction fails** (distinct copy for "video unavailable", "extraction failure / try again", "network failure"). Already wired through `YouTubeServiceError` enum, just confirm UX. | Low | **Yes** | The user already reported intermittents — silent stalls are the worst outcome. Make the failure visible and retryable. |
| 2 | **Keep YouTubeKit pinned but track upstream releases.** Bump deliberately when a verified fix lands, run the full manual validation sweep before accepting. | Low | **Yes** (process discipline, not code) | We are on 0.4.8; treat any bump as a small dedicated task with manual sim verification, not an automatic dependency update. |
| 3 | **Retry-once on `Failed to decode object from given start point` style decode errors before surfacing failure.** Cheap mitigation for the intermittent class; does not fix the structural problem. | Low | No (post-MVP) | Should be paired with a retry counter and exponential backoff to avoid hammering YouTube under genuine outages. |
| 4 | **Rotate `clientVersion` / UA in the InnerTube search call** to a current value, possibly seeded from a small remote config. | Low-Medium | No (post-MVP) | Reduces some bot-flag risk but does not help signature decryption. Be careful not to imitate browser fingerprints we cannot maintain. |
| 5 | **Pin to a newer YouTubeKit / fork it.** Forking gives us the ability to patch decode-site fragility quickly; the cost is owning the fork. | Medium | No (post-MVP) | Only worth it if we hit a fragility that upstream is slow to fix and that blocks real users. Track as deferred. |
| 6 | **Switch to a different extractor (yt-dlp-style port, NewPipe-style port, or a Swift wrapper around yt-dlp via an embedded Python or shell-out)** | High | No (post-MVP, likely never on iOS) | yt-dlp on iOS is a non-starter for App Store distribution and adds a huge maintenance surface. Mentioned for completeness. |
| 7 | **Use YouTube's official Data API v3 where feasible** (search metadata only, never stream URLs — they do not exist in the official API). | Medium | No (post-MVP) | Would harden search but does nothing for playback. Also requires an API key and quota management — both add ops we deliberately avoid for MVP. |
| 8 | **Ship a server-side extractor** (small backend that runs yt-dlp, returns a signed short-lived URL). | High | No (post-MVP, would change the no-backend stance) | Solves both the fragility and the App Store distribution constraint, but breaks `docs/api-contracts.md` "no backend" stance. Not on the MVP table. |

### MVP plan (decision)

For MVP we ship items **1**, **2**, and the iOS HLS proxy described below. Everything else is explicitly deferred. The goal for MVP is: happy path plays, intermittent failures surface a retryable error rather than a silent stall, and we do not accept dependency bumps without manual sim verification.

## iOS HLS proxy for long fragmented MP4 audio (YT-0157)

### Why iOS needs this and Android does not

YouTubeKit on iOS picks the **mp4a (AAC) m4a** stream because its `Stream.isNativelyPlayable` filter excludes opus — AVPlayer cannot play standalone opus / webm. Android's NewPipeExtractor on the same videos picks **opus / webm** because Media3 ExoPlayer decodes opus natively via MediaCodec. Same googlevideo CDN host, different codec/container, different downstream parser.

The iOS `c=ANDROID_VR` mp4a URL points to a fragmented MP4 with an `ftyp + moov + sidx + (moof+mdat)*` layout. AVPlayer's non-HLS fmp4 parser stalls catastrophically when the sidx contains thousands of entries:

- `dQw4w9WgXcQ` (Rick Astley, 3:33, sidx 296 B / ~22 fragments) → AVPlayer reaches `.playing` in ~7s.
- `n61ULEU7CO0` (Lofi Girl "Best of 2021", 6:10:58, sidx ~26 KB / ~2230 fragments) → never reaches `.playing` within 45s in clean-process probes; user-reported "starts after hours" in the iOS app.

The YT-0046 v1/v2/v3 fixes (`automaticallyLoadedAssetKeys: ["playable"]`, `AVURLAssetPreferPreciseDurationAndTimingKey: false`, HLS livestream fallback for kit-empty cases) did not address this — those are about probing latency and the empty-streams case. The structural problem is AVPlayer's fmp4 parser itself.

### What the proxy does

The proxy synthesises an HLS playlist that maps each `moof+mdat` pair from the sidx onto an `#EXT-X-BYTERANGE` entry, hosts it via `AVAssetResourceLoaderDelegate`, and lets AVPlayer treat the file as HLS. AVPlayer's HLS engine reads byterange entries linearly without the fragmented-mp4 parser path that stalls.

Implementation sits in `ios/YourTube/Core/Audio/HLSProxyAsset.swift`:

1. `HLSProxy.prepareProxiedPlayback(originURL:)` — HEAD origin → atom-walk for `ftyp + moov + sidx` → range-fetch the full sidx atom → parse it → synthesise the m3u8 → return `(playlistURL, loader)`.
2. `HLSProxyLoader: AVAssetResourceLoaderDelegate` — three branches: serve the m3u8 inline on `playlist.m3u8` requests; **redirect (HTTP 302)** every `audio.mp4` byterange request to the original googlevideo URL so AVPlayer follows with its native HTTP loader and the same Range header (inline serving fails with `CoreMediaError -12881 "custom url not redirect"` — the engine REQUIRES a redirect for custom-scheme segments); ignore foreign schemes so AVPlayer's native loaders take over.
3. `LiveYouTubeService.resolveStreamURL` audio-only branches call `proxiedAudioStream(for:)`. On any `HLSProxy.ProxyError` (HEAD non-2xx, missing/unparseable sidx) it falls back to the unproxied googlevideo URL — short tracks (~tens of fragments) play fine without the proxy, so the fallback preserves the YT-0046 baseline rather than failing the whole resolve.
4. `AVPlayerAudioEngine.play(track:url:resourceLoader:)` retains the loader on `currentResourceLoader` for the lifetime of the current `AVPlayerItem`. `prepare(track:)` and `stop()` clear it alongside the existing `itemStatusObserver` cleanup. Dropping the loader while the item is alive immediately fails the asset.

### Bandwidth + cost

The proxy fetches only:
- One HEAD request to the origin (content-length).
- Range fetches walking the atom headers (16-byte reads, capped at 1 MB).
- The full `sidx` atom (typically a few KB to ~30 KB for multi-hour mixes).
- The init segment `[0, ftyp+moov+sidx]` (~28 KB).

After that, AVPlayer issues its own Range requests directly to googlevideo (via the 302 redirects). Bandwidth-equivalent to direct playback plus the small init prefetch.

### Locale + redirect invariants worth preserving

A few empirically-found invariants that must NOT be "simplified":

- `EXTINF:%.3f,` formatting is pinned to `Locale(identifier: "en_US_POSIX")` — RFC 8216 §4.3.2.1 requires `.` as the decimal separator. On `it_IT`/`de_DE`/etc. devices, an unpinned `String(format:)` emits `0,500` and AVPlayer rejects the whole playlist silently. See `HLSProxy.extinfLocale`.
- The custom URL scheme uses the triple-slash form `yt-prefetch://yt/playlist.m3u8`. Empty-host URLs (`yt-prefetch://playlist.m3u8`) parse with empty path and break the loader's path-suffix dispatch.
- `EXT-X-MAP` and segments must point to the SAME underlying URI; the byterange table is what distinguishes init from media. Do NOT use separate `init.mp4` / `audio.mp4` URIs — verified to fail.
- Segment requests must be REDIRECTED (302), not served inline. The error surface for "served inline" is `CoreMediaError -12881 "custom url not redirect"`, which AVPlayer does not recover from.

### When the proxy itself can fail

`HLSProxy.ProxyError` cases and what they imply:

- `.noContentLength` — HEAD returned non-2xx (signed URL expired, geoblocked, etc.) or content-length absent. Most common downstream of YT-0071 signature failures. Fall back to direct URL; user will likely see "Playback failed" via the existing AVPlayerItem `.failed` path (YT-0070).
- `.missingFtyp` / `.missingMoov` — non-fmp4 origin or malformed init region. Should never happen for the YouTubeKit-selected mp4a streams; if seen, log with the URL host (never the full URL — signed) and treat as "kit selected something we can't proxy".
- `.missingSidx` — no sidx atom in the first 1 MB (the proxy's scan cap). Either the file is not fragmented (rare for kit-selected long audio) or the layout is unusual. Falls back as above.
- `.sidxParseFailed` — the sidx parser returned nil. Indicates a sidx version we don't handle; v0 + v1 are covered, sub-sidx pointers (`referenceType == 1`) are skipped. Anything else falls back.

### Cross-platform divergence (do NOT mirror to Android)

This is iOS-only because Android's extractor stack picks a different codec/container that does not hit the fmp4 parser path. Per `feedback_cross_platform_mirror`: no Android mirror task. If/when Android hits its own long-track stall, the diagnosis will be entirely different (likely Media3-side) — file a separate task.

## Cross-references

- `docs/api-contracts.md` — confirms stream URLs are extractor-owned and not part of the cross-platform contract.
- `ios/YourTube/Core/YouTube/LiveYouTubeService.swift` — current iOS extractor wiring (DASH-then-HLS fallback, audio-only HLS-proxy wrapping, error mapping).
- `ios/YourTube/Core/Audio/HLSProxyAsset.swift` — atom walker, sidx parser, m3u8 synth, `AVAssetResourceLoaderDelegate` for the long-fmp4 mitigation.
- YT-0046 changelog (v3) — full root-cause writeup of the long-video / livestream split and the audio-session interaction.
- YT-0157 — implementation of the iOS HLS proxy.
- YT-0033 — manual validation session where these failure modes were first observed.
