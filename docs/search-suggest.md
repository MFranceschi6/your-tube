# Search suggest

Cross-platform contract for the live "search suggest" UI. Both clients call the YouTube Suggest endpoint with the same shape so the experience is identical, and so a follow-up provider change can be done in one place.

This is **internal** behavior; nothing is sent to a YourTube server (there is none).

## Endpoint

```
GET https://suggestqueries-clients6.youtube.com/complete/search
```

A mirror of `https://suggestqueries.google.com/complete/search` exists; either host MAY be used. The `clients6` host is currently preferred because it returns CORS-friendly JSON when called with `xssi=t`.

### Query parameters

| Param | Required | Value | Notes |
|---|---|---|---|
| `client` | yes | `youtube` | Selects YouTube's suggestion corpus. |
| `ds` | yes | `yt` | Data source: YouTube. |
| `q` | yes | URL-encoded user input | Non-empty after trim. |
| `hl` | yes | language code | Sourced from device locale (`Locale.current.language.languageCode` on iOS; `Locale.getDefault().language` on Android). Two-letter lowercase, e.g. `en`, `it`, `de`. |
| `gl` | yes | region code | Sourced from device locale region (`Locale.current.region.identifier` / `Locale.getDefault().country`). Two-letter uppercase, e.g. `US`, `IT`. |
| `xssi` | yes | `t` | Strips the JSONP wrapper and returns parseable JSON. Without this the response is wrapped as `)]}'`-prefixed JSONP. |

No API key. No auth header. The endpoint is unauthenticated and rate-limited per source IP.

### Response shape

After `xssi=t`, the body is a JSON array of length 4:

```jsonc
[
  "query",                              // echoed q
  [                                     // suggestions
    ["suggestion text", 0, [512, 433]], // each item: [text, type, [internal flags]]
    ["another suggestion", 0, [512, 433]]
  ],
  { "k": 1, "q": "..." },               // metadata; ignored
  { "j": "..." }                        // metadata; ignored
]
```

Clients MUST:

- Decode element `[1]` as the suggestion array.
- For each entry, take element `[0]` as the suggestion text.
- Discard everything else.

If decoding fails (malformed body, network error, non-2xx status), treat the response as empty (see Offline fallback).

## Behavior

### Debounce

- **200 ms** debounce window. Fire the request 200 ms after the last keystroke.
- Cancel any in-flight request when a new one starts. The cancelled response MUST NOT update UI.
- Debounce is per-input-field, not global.

Rationale: 200 ms is below human perception of input lag and high enough to coalesce rapid typing without floods of requests on tablet keyboards.

### Maximum suggestion count

- Show at most **8** suggestions. If the endpoint returns more, take the first 8 in response order.
- If the endpoint returns fewer, render only what is returned. Do not pad.

### Dedup against recent searches

- Recent searches (the most recent N=10 stored locally) are shown when the input is empty.
- When suggestions ARE shown (non-empty input), recent-search items that are case-insensitive duplicates of suggested items are suppressed in the recent-search section.
- Suggestions themselves are NOT deduplicated against recent searches; the suggestion list is rendered as returned.

### Empty input

- Render only recent searches. Do NOT call the suggest endpoint.

### Submission

- Tapping a suggestion submits the search with that text and adds it to recent searches.
- Submitting the typed text via keyboard return adds it to recent searches.
- Recent searches are stored locally only. They are not sent over the network.

### Tap accessibility

Each suggestion row has:

- `accessibilityLabel` = suggestion text.
- `accessibilityHint` = "Searches for this suggestion." (or localized equivalent).
- Min hit target 44 × 44 pt (iOS) / 48 dp (Android).

## Offline fallback

When the network call fails (no connectivity, timeout, non-2xx, decoding error):

- **Show recent searches only.** Do not show a "no results" placeholder. Do not show an error toast.
- Continue to retry on each new keystroke (the debounce-and-cancel behavior naturally handles this).

Rationale: search suggest is a non-critical augmentation. Errors here should not interrupt the user.

## Rate limiting

The endpoint may return HTTP 429 or empty bodies under aggressive use. Clients SHOULD:

- Treat 429 like any other failure (offline fallback).
- Not implement client-side backoff beyond debounce; the 200 ms debounce + per-keystroke cancellation already bounds traffic.

If the endpoint persistently fails, this is a provider issue and is out of scope for the client. File a follow-up task instead of building elaborate retry logic.

## Persistence

Recent searches are persisted locally per platform:

- Android: DataStore (`core/datastore`).
- iOS: SwiftData with a small `RecentSearch` model.

Cap at **10** entries. Newest first. Inserting an entry that already exists moves it to the head.

Recent searches are NOT part of the playlist export contract and NOT in [api-contracts.md](api-contracts.md).

## Parity expectations

For a given input string and (`hl`, `gl`) pair, both clients display the same ordered suggestions (within the 8-item cap). Visual styling is per-platform but ordering, count, and dedup rules MUST match.

## Non-goals

- No personalized suggestions tied to a user account (there is no account).
- No history-based prediction beyond the local recent-searches list.
- No spell-correction or query rewriting beyond what the endpoint returns natively.

## Implementation tasks

- iOS: YT-0081 (SearchViewModel debounced suggest pipeline, recent-searches via SwiftData).
- Android: YT-0082 (SearchViewModel debounced suggest pipeline, recent-searches via DataStore).
