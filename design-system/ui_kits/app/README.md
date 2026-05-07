# YourTube App UI Kit

A high-fidelity click-through prototype of the YourTube mobile app.

## Design decisions
- Dark-first, brand purple `#8B5CF6` accent
- Follows `docs/design-system.md` cross-platform spec
- Web prototype uses Geist font (substitute for SF Pro / Roboto)
- Icons: Lucide (CDN) — maps to SF Symbols (iOS) / Material Symbols (Android)
- 390×844px viewport (iPhone 15 Pro dimensions)

## Screens
- **SearchScreen** — search bar + results list
- **LibraryScreen** — playlists + recently played entry point
- **HistoryScreen** — recently played tracks
- **NowPlayingScreen** — full-screen player (expanded from MiniPlayer)
- **SettingsScreen** — theme toggle, import/export

## Components
- `TrackRow.jsx` — track item with thumbnail, title, channel, duration, overflow
- `MiniPlayer.jsx` — persistent bottom player with progress bar
- `NowPlaying.jsx` — full screen player with scrubber and controls
- `SearchScreen.jsx` — search tab view
- `LibraryScreen.jsx` — library tab view
- `HistoryScreen.jsx` — history tab view
- `SettingsScreen.jsx` — settings tab view

## Usage
Open `index.html` for the interactive click-through prototype.
