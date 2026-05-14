# Autoplay next track

Cross-platform contract for "what plays when the queue empties." Both clients implement identical selection rules so the user gets equivalent next-up behavior on each platform.

This is **internal**; no YourTube backend is involved. Related-video data is sourced from the same extractor each platform already uses for stream resolution.

## When autoplay fires

Autoplay runs when ALL of the following are true at the moment the current track finishes:

1. The current track played to completion (not skipped, not paused, not errored).
2. `repeatMode` is `off`. `repeat one` and `repeat all` are handled by the existing player loop and bypass autoplay entirely.
3. After advancing the queue index, the queue is empty (i.e., the just-finished track was the last item).
4. The user-facing **Autoplay** setting is enabled (see Toggle).

If any condition is false, autoplay does NOT fire and the player settles into its idle/empty state.

## Source

Related-video lookup uses the per-platform extractor's "related" surface for the just-finished video id:

- Android: `StreamInfo.getRelatedItems()` from NewPipeExtractor.
- iOS: equivalent surface via YouTubeKit; if a direct API is unavailable, fetch the watch page and parse related items from the `secondaryResults` ribbon.

Both surfaces return a list of candidate videos with at minimum `videoId`, `title`, `channel`, `durationSec`, `thumbnailUrl`. Clients map this to the `Track` shape from [api-contracts.md](api-contracts.md#track) before queuing.

The lookup is performed at the moment autoplay is needed, not pre-fetched. There is no cache.

## Eligibility

Iterate the candidate list **in the order returned** and pick the first candidate that passes ALL filters:

| Filter | Rule |
|---|---|
| Resolvable | `videoId` present and not empty. |
| Not a Short | `durationSec >= 60`. Items with `durationSec < 60` (or unknown) are skipped to avoid Short-style content. Livestreams (`durationSec == 0` indicator) are also skipped. |
| Loop avoidance | `videoId` is not present in the **last 20** played track ids. See History buffer. |
| Language match preferred | When a candidate's audio/title language is exposed by the extractor and matches the device locale's language, prefer it. When language is not exposed, do NOT skip — fall through to ordering by extractor return order. This is a soft preference, not a hard filter. |

If no candidate passes the filters, autoplay does NOT fire. The player settles into its idle/empty state. No toast, no error.

The "language preferred" rule means: scan the list once collecting eligible candidates; if any matches device language, pick that one (first such); otherwise pick the first eligible regardless of language.

## History buffer

Each client maintains a rolling buffer of the **last 20** played track ids. A track id enters the buffer when its track starts playing (any source: queue, autoplay, manual tap). The buffer is in-memory only — it does NOT need to survive cold launch.

Rationale: 20 is large enough to prevent the autoplay graph from settling into a 2- or 3-track loop on related-video clusters that mutually link, and small enough that the user is not blocked from re-hearing a track within a normal listening session if they request it manually.

Loop avoidance applies ONLY to autoplay. Manual queue-add or replay actions are NOT filtered against the history buffer.

## Behavior on success

When a candidate is selected:

1. Map to `Track` and append to the queue (becomes index `0` since the queue is empty).
2. Advance the player to the new track using the same code path as a normal "play next" transition.
3. Update Now-Playing chrome (MiniPlayer / NowPlaying).
4. Add the new track id to the history buffer when playback actually starts.

User-visible: there is **no** modal or toast. Autoplay is silent. The MiniPlayer / NowPlaying card simply transitions to the new track.

## Toggle

A single user setting controls autoplay:

- **Setting key** (Android DataStore / iOS UserDefaults): `autoplay.enabled`.
- **Type**: boolean.
- **Default**: `true` (autoplay ON for new installs).
- **Persistence**: per-device. Persists across launches.
- **Surface**: Settings screen → "Playback" section → "Autoplay" row. Standard platform toggle control.
- **Scope**: applies globally; no per-playlist override.

When the toggle is flipped during playback, the change applies on the next end-of-queue. It does NOT cancel an autoplay that is already mid-resolution.

## Failure modes

| Case | Behavior |
|---|---|
| Extractor lookup fails (network, parse error, rate limit) | Autoplay does NOT fire. No toast. Idle state. |
| Extractor returns empty list | Autoplay does NOT fire. No toast. Idle state. |
| Selected candidate's stream URL fails to resolve when playback starts | Show inline player error per existing extractor error path. Do NOT pick a different candidate; the user remains in control. The failed track stays at queue index 0; user can remove/skip. |
| Toggle is off | Autoplay does NOT fire regardless of any other state. Idle state. |

## Parity expectations

For a given just-finished `videoId` and a given history buffer, both clients SHOULD select the same next track when both extractors return the same related list. Differences in extractor surfaces (e.g., one platform returns more aggressive shorts filtering than the other) are accepted but should be documented if surfaced.

Tests SHOULD cover at least:

- Eligibility filter ordering (resolvable → duration → loop-avoidance → language preference).
- Loop avoidance with a 20-track buffer.
- Toggle off short-circuits the lookup.
- Empty / failed lookup is silent.

## Non-goals

- No "autoplay queue preview" UI (showing the upcoming track before the current finishes). Out of scope.
- No personalization based on listening history beyond the 20-track loop-avoidance buffer.
- No quality / upload-date / view-count weighting on candidates beyond what the extractor returns.
- No multi-step lookahead. The contract picks one next track at a time.

## Implementation tasks

- iOS: YT-0088 (PlayerCoordinator end-of-queue autoplay using YouTubeKit related items + Autoplay setting).
- Android: YT-0089 (PlayerViewModel end-of-queue autoplay using NewPipeExtractor `StreamInfo.getRelatedItems()` + Autoplay setting in DataStore).
