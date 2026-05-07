# YT-0013 — Media3 / lock-screen / notification parity

> Companion to `decision-log.md` Q11. Defines the contract between the in-app Now Playing surface and the system-side lock-screen / notification / Bluetooth-receiver surfaces. Referenced from YT-0062a Q11.

## Summary

Three external surfaces mirror the in-app player and must stay in sync:

1. **Lock screen** — the OS reads `MediaSession` metadata + playback state.
2. **Notification** — `DefaultMediaNotificationProvider` renders compact + expanded layouts on the lock screen and shade.
3. **Bluetooth / car / external transport receivers** — they bind to `MediaSession.Token` via `MediaController` and call `Player.*` methods directly.

## `MediaSession` setup (`PlaybackService.kt`)

```kotlin
mediaSession = MediaSession.Builder(this, exoPlayer)
  .setSessionActivity(nowPlayingPendingIntent())
  .build()

setMediaNotificationProvider(
  DefaultMediaNotificationProvider.Builder(this).build()
)

private fun nowPlayingPendingIntent(): PendingIntent {
  val intent = Intent(this, MainActivity::class.java).apply {
    action = ACTION_OPEN_NOW_PLAYING
    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
  }
  return PendingIntent.getActivity(
    this, 0, intent,
    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
  )
}

companion object {
  const val ACTION_OPEN_NOW_PLAYING = "com.yourtube.action.OPEN_NOW_PLAYING"
}
```

`MainActivity.onNewIntent` reads `intent.action == ACTION_OPEN_NOW_PLAYING` and emits on a `MutableSharedFlow<Unit>(replay = 1)`. `AppShell` collects and navigates to `AppRoute.NowPlaying.route` with `launchSingleTop = true`.

## `MediaMetadata`

Set on every `MediaItem.Builder().setMediaMetadata(...)`:

| Field | Source |
|---|---|
| `title` | `track.title` |
| `artist` | `track.channel` |
| `albumTitle` | omitted (no album concept) |
| `artworkUri` | `Uri.parse("https://i.ytimg.com/vi/${track.videoId}/maxresdefault.jpg")` |
| `extras` | `Bundle().apply { putString("yt.videoId", track.videoId) }` |

**Artwork URI:** `maxresdefault.jpg` is the highest-res YouTube thumbnail. **Known limitation:** not every video has it; missing videos render no artwork on the lock screen rather than falling back to `hqdefault.jpg`. A real fallback requires a runtime HTTP HEAD probe before publishing the URI, or a custom artwork loader populating `MediaMetadata.artworkData` byte[]. YT-0062a Q11 ships maxres-only and tracks the fallback as a follow-up.

The in-app `Track.thumbnailUrl` (lower-res, faster to load) stays untouched. Lock-screen artwork is intentionally a separate URI.

## Shuffle / repeat callbacks

In current Media3 versions, when a `MediaController` (lock screen, Bluetooth, etc) writes to `Player.shuffleModeEnabled` / `Player.repeatMode`, the default routing forwards through the `Player` automatically. **Do not override** `MediaSession.Callback.onSetShuffleMode` / `onSetRepeatMode` unless you need a transformation — empty overrides are dead code; non-empty ones duplicate work.

What you *do* need: a `Player.Listener` that pushes `ExoPlayer`-driven shuffle/repeat changes back into `mutablePlayerState` so a lock-screen toggle reflects in-app on unlock:

```kotlin
exoPlayer.addListener(object : Player.Listener {
  override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
    mutablePlayerState.update { it.copy(shuffleOn = shuffleModeEnabled) }
  }
  override fun onRepeatModeChanged(repeatMode: Int) {
    mutablePlayerState.update { it.copy(repeatMode = repeatMode) }
  }
})
```

This is the missing piece called out in the YT-0062a Q11 changelog ("worth noting — when Q6 wires shuffle/repeat UI, the listener should be added so a lock-screen toggle reflects in-app on unlock"). When Q6 ships, fold this listener in.

## Notification layout

`DefaultMediaNotificationProvider` renders:

- **Compact:** three actions auto-selected by Media3 — typically `[skip_prev, play_pause, skip_next]`.
- **Expanded:** the same three plus auto-selected secondary actions (shuffle, repeat).

**Layout customization** (forcing the exact slot order) is partly `@UnstableApi` in current Media3. The closest stable path is `MediaSession.setCustomLayout(...)` for media-3 buttons, which YT-0062a Q11 deferred. Track this as a follow-up; ship the default layout in MVP.

## `Player.shuffleModeEnabled` / `Player.repeatMode` integer contract

`PlayerState.repeatMode: Int` aligns with `androidx.media3.common.Player`:

| `repeatMode` | Constant | Meaning |
|---|---|---|
| 0 | `Player.REPEAT_MODE_OFF` | linear playback |
| 1 | `Player.REPEAT_MODE_ONE` | repeat current track |
| 2 | `Player.REPEAT_MODE_ALL` | repeat the whole queue |

`core/common` cannot import Media3 directly (clean module-graph rule), so the alignment is a literal contract. Document the constants on the `PlayerState.repeatMode` field doc-comment.

## `PlayerController` extensions

```kotlin
interface PlayerController {
  // …existing…
  suspend fun setShuffleMode(enabled: Boolean)
  suspend fun setRepeatMode(mode: Int)   // Player.REPEAT_MODE_*
}
```

`DefaultPlayerController` implementation: update `mutablePlayerState` synchronously *and* forward to `playbackTransport.setShuffleMode/setRepeatMode` so the ExoPlayer flag flips and in-process state reflects intent without waiting on an engine round-trip.

`PlayerViewModel` exposes thin one-line wrappers (`viewModelScope.launch { playerController.setShuffleMode(enabled) }`) — the same shape as `pause()` / `resume()`.

## What ships in MVP vs. follows up

| Capability | MVP (YT-0062a Q11) | Follow-up |
|---|---|---|
| `setSessionActivity` PendingIntent → NowPlaying nav | ✅ shipped | — |
| `MediaMetadata.artworkUri` (maxresdefault) | ✅ shipped | `hqdefault` fallback (HTTP probe or `artworkData` byte[]) |
| Shuffle/repeat callbacks | ✅ default routing (no overrides needed) | `Player.Listener` for ExoPlayer→state reconcile (fold into Q6) |
| `DefaultMediaNotificationProvider` | ✅ default layout | Custom compact `[prev, play, next]` + expanded `[shuffle, repeat]` slot order |
| `MediaSession.Token` exposure for external controllers | ✅ via `Service.onGetSession` | — |
| Bluetooth / car / Wear remote control | ✅ via standard `MediaController` binding | — |

## Validation

Run from a Pixel emulator with audio playing:

1. Lock device → confirm artwork + title + channel + scrubber render on the lock screen.
2. Drag the lock-screen scrubber → in-app `previewMs` updates within 1 frame on unlock.
3. Long-press shuffle/repeat from lock screen → in-app reflects on unlock (after the `Player.Listener` follow-up lands).
4. Tap notification body → opens NowPlaying via the PendingIntent (not the home screen).
5. Pair a Bluetooth headset → press play/pause/skip → confirm in-app state reflects within 1 frame.
