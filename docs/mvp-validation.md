# MVP Validation

Run this checklist before tagging `v0.1.0`. Record exact device, OS version, app build, date, and any failures in the release notes or the linked Obsidian validation task.

## Prerequisites

- Android debug build is installed on the target emulator or device.
- iOS build is installed on the iPhone 16 simulator, plus a real device for background audio confidence when available.
- Both clients use the shared playlist contract in `docs/api-contracts.md`.
- Canonical playlist fixtures live in `docs/fixtures/`.

## Android

- [ ] Search for "lofi beats"; results show title, channel, thumbnail, and duration.
- [ ] Tap a result; audio starts and the MiniPlayer appears.
- [ ] Open Now Playing; play/pause, seek, scrub, previous, and next controls respond.
- [ ] Lock the device; audio continues and notification or lockscreen controls can play, pause, and skip.
- [ ] Connect and disconnect headphones; unplug pauses playback or follows the platform audio-focus behavior.
- [ ] Create a playlist, add three tracks, rename it, reorder tracks, and remove one track.
- [ ] Export the playlist as `.ytplaylist.json` through the system share sheet.
- [ ] Import a valid `.ytplaylist.json`; playlist ID, name, timestamps, and track order remain intact.
- [ ] Force-kill the app while playing; the playback notification clears or stops as expected.

## iOS

- [ ] Search for "lofi beats"; results show title, channel, thumbnail, and duration.
- [ ] Tap a result; audio starts and the MiniPlayer appears.
- [ ] Open Now Playing; play/pause, seek, scrub, previous, and next controls respond.
- [ ] Send the app to the background; audio continues on a real device. Simulator results are useful but not release confidence.
- [ ] Lock the real device; lockscreen controls can play, pause, and skip.
- [ ] Create a playlist, add three tracks, rename it, reorder tracks, and remove one track.
- [ ] Export the playlist as `.ytplaylist.json` through `UIActivityViewController`.
- [ ] Import a valid `.ytplaylist.json` from Files or AirDrop; playlist ID, name, timestamps, and track order remain intact.
- [ ] Force-kill the app while playing; now-playing state clears or stops as expected.

## Cross-Platform Playlist Round Trip

- [ ] Export from Android and import on iOS; playlist metadata and track order match the Android source.
- [ ] Export from iOS and import on Android; playlist metadata and track order match the iOS source.
- [ ] Both platforms reject `docs/fixtures/playlist-future-schema.ytplaylist.json` with an update-required error.
- [ ] Imported tracks resolve streams at playback time rather than persisting stream URLs.
