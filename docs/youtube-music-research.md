# YouTube Music research — audio-first extraction source

YT-0057. Research only — no implementation. Companion to [youtube-extraction-notes.md](youtube-extraction-notes.md) and [youtube-data-api-research.md](youtube-data-api-research.md) (YT-0056).

**Recommendation: partial-go.** Adopt the YouTube Music `WEB_REMIX` InnerTube client **only for the search call** (`LiveYouTubeService.fetchInnerTubeSearch`). Reject any plan to route stream extraction through YT Music — the `n`-throttling pipeline that YouTubeKit fights is identical on YT Music's `/player` endpoint, so a switch buys nothing for the playback fragility that motivated this research and adds non-trivial parser code.

The full rationale follows the five research questions.

## TL;DR table

| Surface | YouTube (WEB) — current | YouTube Music (WEB_REMIX) | Adopt? |
|---|---|---|---|
| Search host | `www.youtube.com/youtubei/v1/search` | `music.youtube.com/youtubei/v1/search` | **Yes — partial** |
| Search response shape | `twoColumnSearchResultsRenderer` → `videoRenderer` | `tabbedSearchResultsRenderer` → `musicShelfRenderer` → `musicResponsiveListItemRenderer` | swap parser only |
| Result quality (music intent) | mixes videos, shorts, channels, lives | tagged `Song / Video / Album / Artist` shelves; "Song" maps to track recordings | better for our use case |
| Stream extraction host | `www.youtube.com/youtubei/v1/player` | `music.youtube.com/youtubei/v1/player` | **No** |
| `streamingData.adaptiveFormats` shape | same | same | n/a |
| `n`-throttling param | yes | yes (confirmed, see Q2) | **no escape** |
| PO-token gate (2024+) | yes | yes (confirmed, see Q2) | **no escape** |

## Q1 — InnerTube clients used by YouTube Music

YT Music does not have one client; it has a family. Each is a tuple of `(clientName, clientVersion, host, INNERTUBE_API_KEY, User-Agent)`. Empirical values harvested from `view-source:music.youtube.com` and corroborated against `ytmusicapi` source:

| `clientName`     | Surface                     | Host                          | Notes |
|------------------|-----------------------------|-------------------------------|-------|
| `WEB_REMIX`      | YT Music desktop web        | `music.youtube.com`           | Embedded in the page; `INNERTUBE_CLIENT_VERSION` rotates frequently (observed `1.20260503.12.00` on 2026-05-06). Used by `ytmusicapi`. |
| `IOS_MUSIC`      | YT Music iOS app            | `music.youtube.com`           | Mobile UA. Returns `LOGIN_REQUIRED` for cold curl without auth cookies (confirmed). |
| `ANDROID_MUSIC`  | YT Music Android app        | `music.youtube.com`           | Mobile UA. Same auth gate as IOS_MUSIC for cold requests. |

Compare with the iOS app today, which posts to `www.youtube.com/youtubei/v1/search` with `clientName=WEB`, `clientVersion=2.20260114.08.00` ([LiveYouTubeService.swift:208-228](../ios/YourTube/Core/YouTube/LiveYouTubeService.swift)).

The request shape itself is essentially identical — both clients send `{"context":{"client":{"clientName":..., "clientVersion":...}}, "query":...}` to a `/youtubei/v1/search` endpoint. The differences are: (a) host (b) the embedded API key path (c) the JSON shape returned. Switching is a **localized parser swap inside one private method**, not a re-architecture.

References:
- `ytmusicapi/auth/oauth.py` and `ytmusicapi/_continuations.py` (upstream): the `WEB_REMIX` constant and the `music.youtube.com/youtubei/v1` base URL.
- `yt-dlp/yt_dlp/extractor/youtube.py` `_INNERTUBE_CLIENTS` table: enumerates all client tuples including `web_music`, `ios_music`, `android_music`.

## Q2 — Does YT Music bypass `n`-throttling? **No.**

This is the critical question and the answer is the recommendation. **YT Music stream URLs go through the exact same signature and `n`-throttling pipeline as regular YouTube.** Evidence from probes in [`tools/ytmusic-probe/`](../tools/ytmusic-probe/):

- `WEB_REMIX` `/player` for `videoId=4D7u5KF7SP8` (Daft Punk — Get Lucky, Song): HTTP 200, but `playabilityStatus={status: "UNPLAYABLE", reason: "Video unavailable", subreason: "The page needs to be reloaded."}`. This `RELOAD_PAGE` signal is YouTube's canonical PO-Token (Proof-of-Origin) gate — the same gate yt-dlp tracks in [yt-dlp/yt-dlp#10135](https://github.com/yt-dlp/yt-dlp/issues/10135) and that produces YouTubeKit's `Failed to decode object from given start point` symptom.
- `IOS_MUSIC` `/player` same id: `LOGIN_REQUIRED` (needs auth cookies; not viable for an unauthenticated client).
- `TVHTML5_SIMPLY_EMBEDDED_PLAYER` (yt-dlp's old fallback): `ERROR — YouTube is no longer supported in this application or device`.
- `IOS` (regular YT iOS app, yt-dlp's pre-2024 fallback): HTTP 400 — endpoint shape no longer accepted.

Conclusion: YT Music is audio-*first* in product surface but not in extraction surface. The actual stream URLs come back from the same player infrastructure with the same `signatureCipher` / `n=` parameter that requires JS-side decryption. `yt-dlp/yt_dlp/extractor/youtube/_video.py` confirms this in code: it routes `WEB_REMIX` extraction through the same `_decrypt_nsig` path used for `WEB`.

**This kills the hypothesis** that motivated the research — "YT Music may avoid the DASH-vs-HLS-vs-throttle dance". It does not.

## Q3 — Stream URL JSON shape

Same. YT Music `/player` (when it returns playable data) emits `streamingData.adaptiveFormats` with the same itag-keyed entries, same `mimeType` strings, same `signatureCipher` field for signed formats, same `hlsManifestUrl` for live content. `yt-dlp`'s `_extract_streaming_data` is shared between WEB and WEB_REMIX precisely because the response is the same.

This is good news for **search-only** adoption: if we ever did want to feed REMIX-discovered videoIds into the existing YouTubeKit player, the shapes line up at the boundary. No data-contract divergence at the `Track` / `SearchResult` level (see [api-contracts.md](api-contracts.md)).

## Q4 — Search semantics: better quality for music intent? **Yes, materially.**

Empirical comparison for query `"Daft Punk Get Lucky"` (probe: [`scripts/search-web-remix.sh`](../tools/ytmusic-probe/scripts/search-web-remix.sh), 2026-05-06):

- **`WEB_REMIX`** (240 KB response): `tabbedSearchResultsRenderer` with three sections —
  1. `messageRenderer` ("Showing results for…")
  2. `musicCardShelfRenderer` (top "one-box" result, tagged `Video • Daft Punk • 854M views • 4:09`).
  3. `musicShelfRenderer` with 29 `musicResponsiveListItemRenderer` items, **each labelled `Song / Video / Album / Artist`**. The "Song" rows point to actual track recordings (videoIds `4D7u5KF7SP8`, `Rgrt_8mXrK8`, `NnC2MtBSdsg`) — these are the audio-only YT Music tracks that don't surface as the top result on regular YouTube.

- **`WEB`** (518 KB response): `twoColumnSearchResultsRenderer` with `videoRenderer` items mixed indiscriminately — official video, fan uploads, shorts, mixes, "best moments" cuts. No type tag, no music-quality signal.

For "user wants to listen to a specific song", REMIX is better. The "Song" rows usually map to YT Music's higher-bitrate audio masters of the track, while WEB top results often map to the music video — same audio with worse extraction characteristics (often longer duration, occasional baked-in ads, no album metadata).

**Caveat:** REMIX search returns *less* metadata for non-music queries. If we ever search for talks, livestreams, or non-music content the results shrink. Today the app is an audio player — non-music search is out of scope. But this is a constraint to record.

## Q5 — Is there a Swift YT Music client? Effort estimate.

No Swift YT Music library exists in our pin set or in the broader Swift package ecosystem. The reference implementations are:

- `ytmusicapi` (Python, mature, ~3.5k GitHub stars). Search, playlist, library, upload. Wraps `WEB_REMIX`. **Search and metadata only — no stream extraction.**
- `yt-dlp` (Python). Full extraction including YT Music via `_INNERTUBE_CLIENTS["web_music"]`. **Source of truth** for client tuples and the `n`-decryption machinery.

Adoption paths and honest cost:

| Path | Scope | Effort | Risk | Recommendation |
|---|---|---|---|---|
| **Swap REMIX search into `fetchInnerTubeSearch`** | replace one POST URL, one client tuple, one parser walk (`twoColumnSearchResultsRenderer` → `tabbedSearchResultsRenderer` → `musicShelfRenderer.contents[].musicResponsiveListItemRenderer`) | **Low — ~1-2 days** including unit tests with a captured fixture. Existing `SearchResult` contract unchanged. | Low — REMIX response shape is more stable than WEB (less surface area, less A/B). Still client-side reverse-engineered, so subject to silent rotation; same fragility class as today, not worse. | **Yes — file as a follow-up ticket.** |
| Port `ytmusicapi`'s search + library surface to Swift | replace search + browse + watch endpoints | Medium — ~1-2 weeks including tests, plus ongoing parity work as `ytmusicapi` evolves. | Medium — owning a parser fork. | Skip for MVP and the immediate post-MVP window. |
| Port `yt-dlp`'s YT Music **stream extraction** to Swift | replace YouTubeKit's player + signature path | **High — multiple weeks**, plus the part `yt-dlp` itself struggles to keep working (PO Token / BotGuard / nsig rotation). | High — we re-implement the exact thing YouTubeKit fails at, with no existing Swift reference. | **Reject.** Q2 confirms the gate is identical, so the win is zero. |
| Fork YouTubeKit and add a `WEB_REMIX` path internally | reuse YouTubeKit's signature / nsig / HLS code, swap client tuple | Medium — needs upstream understanding of YouTubeKit internals. | Medium — owning a fork. | Skip unless YouTubeKit's `WEB` path stops working in a way the REMIX client survives — empirically there is no such gap today (Q2). |

## Cost vs benefit vs the cheaper path

The cheaper path is "**stay on YouTubeKit + land the fixes already filed**":
- HLS-routing fix (the DASH-vs-HLS-vs-throttle decision).
- Retry-once on intermittent decode failures (per [youtube-extraction-notes.md](youtube-extraction-notes.md) item #3).
- `clientVersion` / UA rotation if intermittents cluster (item #4).

This research **does not displace that path**. None of those fixes target search quality, which is the only place YT Music wins. So the two are **additive, not alternative**: ship the YouTubeKit fixes as planned, optionally swap REMIX search in afterwards.

## Recommendation

**Partial-go.** File two follow-up tickets:

1. **YT-XXXX — Swap `LiveYouTubeService.fetchInnerTubeSearch` to `WEB_REMIX`.**
   Replace host + client tuple + response walker. Keep the existing `SearchResult` shape. Add a unit test against a captured 200-response fixture. Validation: manual sim sweep on the 8-query battery from `tools/yt-probe/ids.sample.txt`, expect music-intent queries to surface the YT Music "Song" recording as a top-3 result. **Cross-platform mirror:** file the equivalent ticket on Android (NewPipeExtractor's `MusicService` already targets WEB_REMIX, so the Android side may be a config flip rather than a parser rewrite — confirm in the ticket).

2. **YT-XXXX — Decide on the WEB_REMIX search ticket only after YT-0056 result.**
   YT-0056 is researching whether YouTube Data API v3 should replace the search call entirely. If YT-0056 lands "yes" or "partial-go for search", the WEB_REMIX swap above is moot — drop it. If YT-0056 lands "no", proceed with WEB_REMIX search.

**Do not file** any ticket that swaps stream extraction. The research disproved the hypothesis that motivated it.

## Open questions / what could not be resolved without more code

- **Authenticated YT Music extraction.** Every probe in this research was unauthenticated cold-curl. With a logged-in YT Music Premium cookie, `IOS_MUSIC` / `ANDROID_MUSIC` may return playable URLs without PO Token. We did not test this because (a) we have no plan to ship an auth flow, (b) shipping per-user auth would change the app's no-backend, no-account stance documented in [api-contracts.md](api-contracts.md). Recording the gap so the next contributor doesn't repeat it.
- **PO Token solving.** We confirmed PO Token is now the gate; we did not measure how often `WEB_REMIX` resolves a token vs `WEB` in YouTubeKit's flow. If empirical data shows REMIX gates *less often* than WEB in YouTubeKit's actual run, the calculus changes — but no observation in this research supports that and no upstream issue claims it.
- **Search result coverage on long-tail / non-Anglosphere queries.** All probes used English-language queries. REMIX search is known to thin out on regional or niche tracks; we did not measure this. Worth one probe pass before locking in the swap.
- **Lyrics / artist / album metadata.** REMIX exposes these; the app does not surface them today. Out of scope, but a future ticket may revisit.

## Cross-references

- [youtube-extraction-notes.md](youtube-extraction-notes.md) — the failure modes that motivated this research and the mitigation table this complements.
- [youtube-data-api-research.md](youtube-data-api-research.md) — companion research (YT-0056) on Data API v3 as a search/metadata replacement; resolution of that doc determines whether the REMIX search swap actually ships.
- [api-contracts.md](api-contracts.md) — confirms `SearchResult` / `Track` shapes are unchanged by any of this; stream URLs remain extractor-owned.
- [`tools/ytmusic-probe/`](../tools/ytmusic-probe/) — probe scripts backing every empirical claim above. The `reports/` subdirectory is gitignored (per existing rule) since responses contain visitorData and rotate quickly; re-run the scripts to regenerate.
- `ios/YourTube/Core/YouTube/LiveYouTubeService.swift:208-281` — the swap site for the partial-go.
