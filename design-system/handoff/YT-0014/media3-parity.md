# YT-0014 — Media3 parity (v2 / MVP — Playlists-only scope)

> Trimmed for the Playlists-only MVP. References to Downloads/History/Liked tabs removed. The full `MediaSession` contract lives in `design-system/handoff/YT-0013/media3-parity.md` — this file only documents the Library-side surface.

## Scope

The Library screen and `PlaylistDetailScreen` interact with Media3 in three places:

1. **"Play" / "Shuffle" buttons on `PlaylistDetailScreen`** — set the current queue, start playback, set shuffle mode.
2. **"Play next" / "Add to queue" actions** in the contextual sheet (Q6 read-only) — append to the current queue without disrupting playback.
3. **Reorder + remove during edit mode** — must reconcile with the *playing* queue if the playlist is currently being played.

## Contract: queue replacement (Play / Shuffle)

```kotlin
fun playFromPlaylist(playlistId: String, startTrackId: String? = null, shuffle: Boolean = false) {
  viewModelScope.launch {
    val tracks = repo.tracksFor(playlistId).first()
    val mediaItems = tracks.map { it.toMediaItem() }
    val startIndex = startTrackId?.let { tid -> tracks.indexOfFirst { it.id == tid } }?.coerceAtLeast(0) ?: 0
    playerController.setQueue(
      items = mediaItems,
      startIndex = startIndex,
      shuffle = shuffle,
    )
  }
}
```

`PlayerController.setQueue` (existing per YT-0013) wraps `Player.setMediaItems(...)` + `Player.shuffleModeEnabled = shuffle` + `Player.prepare()` + `Player.play()`. Do **not** reach into ExoPlayer directly from the feature module.

## Contract: append (Play next / Add to queue)

```kotlin
fun playNext(track: TrackItem) {
  val item = track.toMediaItem()
  val currentIndex = playerController.currentMediaItemIndex
  playerController.addMediaItem(currentIndex + 1, item)
}

fun addToQueue(track: TrackItem) {
  val item = track.toMediaItem()
  playerController.addMediaItem(item)            // appends to end
}
```

Neither action touches the playing track or the playback position.

## Contract: reorder / remove during edit mode

Edit mode in `PlaylistDetailScreen` mutates the **playlist's stored ordering** (a Room `playlist_tracks` table with a gap-based `position` column). It does **not** automatically mutate the *playing* queue.

When the user reorders or removes a track in a playlist that is currently being played:

1. The playing queue keeps its current ordering — reorders in the playlist do not jump the playing index around.
2. A **persistent banner** appears at the top of `PlaylistDetailScreen` (only when this playlist is the source of the playing queue): "Now playing — changes apply on next play". Body color `colorScheme.onTertiaryContainer`, container `colorScheme.tertiaryContainer`, dismissible.
3. If the user removes the *currently playing* track from the playlist via edit mode:
   - The track stays in the playing queue (cannot remove the active track without a skip).
   - The banner extends: "The current track stays in this session and is removed from the playlist on close."
4. `playerController.reorderMediaItems(...)` is **not** called from edit-mode commits.

Rationale: surgical edits to the playing queue while the user is reordering the playlist's stored copy are confusing. Make the boundary explicit via the banner.

## `MediaItem` factory

Every track converted for Media3 must populate the `MediaMetadata` per YT-0013 §`MediaMetadata`:

```kotlin
fun TrackItem.toMediaItem(): MediaItem = MediaItem.Builder()
  .setMediaId(videoId)
  .setUri(streamUri)
  .setMediaMetadata(
    MediaMetadata.Builder()
      .setTitle(title)
      .setArtist(channel)
      .setArtworkUri(Uri.parse("https://i.ytimg.com/vi/$videoId/maxresdefault.jpg"))
      .setExtras(Bundle().apply { putString("yt.videoId", videoId) })
      .build()
  )
  .build()
```

Lock-screen + notification mirroring is owned by `PlaybackService` (YT-0013); the Library feature module only needs to construct `MediaItem`s correctly.

## What's NOT in this file (deferred to other handoffs)

- `MediaSession` setup — YT-0013 `media3-parity.md`.
- `setSessionActivity` PendingIntent — YT-0013.
- Lock-screen / notification layout — YT-0013 / YT-0076 (Android MediaSession Notification Polish).
- Shuffle/repeat state reconciliation — YT-0013 §"Shuffle / repeat callbacks".
- Downloads / Liked Media3 contract — post-MVP, see `v1x-tabs-addendum.md`.
- History playback resume — YT-0015.

## Validation hooks

Add Library-specific MediaSession assertions to the existing instrumentation suite:

1. Tap "Play" on a playlist → `MediaSession`'s `currentMediaItem.metadata.title` matches `tracks[0].title` within 1 frame of the play call.
2. Tap "Shuffle" on a playlist → `Player.shuffleModeEnabled == true` *and* the playing track is one of the queue (not necessarily index 0).
3. "Play next" on a playlist row → `currentMediaItemIndex + 1` slot is the new track; current track unaffected.
4. Edit mode reorder while playing → the playing queue's `currentMediaItemIndex` does not change; the banner is visible.
5. Edit mode remove of the currently-playing track → playback continues; the track is no longer in `playlistTracksFlow(playlistId).first()`.
