# Mockup Index — Per Task ID

Questo file mappa ogni task Obsidian al mockup HTML corrispondente nel design system.
**Uso:** quando Claude Code lavora a un task UI, legge questo file per trovare il mockup di riferimento.

---

## Android UI Tasks

### YT-0011 — Build Android App Shell And Components
**Mockup:** `mockups/android/YT-0011-0012-0013-shell-search-player.html`
Mostra: M3 Navigation Bar (Search/Library/Settings), MiniPlayer persistente su `primaryContainer`, status bar, dynamic color picker. Componenti riusabili: `TrackRow`, `MiniPlayer`, skeleton loading rows.

### YT-0012 — Build Android Search Workflow
**Mockup:** `mockups/android/YT-0011-0012-0013-shell-search-player.html`
Mostra: M3 Search Bar (pill 56dp), filter chips per suggestions, stati: idle (chips), loading (skeleton), results (TrackRow list), tap-to-play con indicatore EQ animato.

### YT-0013 — Build Android Now Playing Workflow
**Mockup:** `mockups/android/YT-0011-0012-0013-shell-search-player.html`
Mostra: NowPlaying screen completo — artwork scalabile (shrink se paused), scrubber M3 con thumb `primary`-colored, FAB-style play button (72dp), transport controls, volume slider, queue collassabile. MiniPlayer: `primaryContainer` bg, progress bar bottom.

### YT-0014 — Build Android Library And Playlist Management
**Mockup:** `mockups/android/YT-0014-0015-0016-0017-library-history-settings-sharing.html`
Mostra: Library screen con Extended FAB "New Playlist", lista playlist con Cover 4-up, M3 dialog per nome nuova playlist. PlaylistDetail: copertina 140dp, Play/Shuffle buttons, TrackRow con edit mode (remove button rosso + drag handle), bottom padding per MiniPlayer.

### YT-0015 — Build Android Recently Played Workflow
**Mockup:** `mockups/android/YT-0014-0015-0016-0017-library-history-settings-sharing.html`
Mostra: History tab nel NavBar, lista sorted newest-first con timestamp, tap-to-play, "Clear all" button, M3 Dialog di conferma distruttiva, empty state con icon + copy.

### YT-0016 — Build Android Playlist Sharing Entry Points
**Mockup:** `mockups/android/YT-0014-0015-0016-0017-library-history-settings-sharing.html`
Mostra: Bottom sheet export con file preview (`.ytplaylist.json`, nome, track count), share targets (Nearby Share, Gmail, Files), bottom sheet import con success state. Trigger: bottone import nella top app bar Library + voci Settings.

### YT-0017 — Build Android Settings Workflow
**Mockup:** `mockups/android/YT-0014-0015-0016-0017-library-history-settings-sharing.html`
Mostra: Settings screen M3 con sezioni (Appearance/Playback/Playlists/Data/About), M3 Switch per dark mode, quality picker segmented, Export/Import entries con chevron, Clear cache con M3 Dialog confirm, about section con versione e disclaimer.

---

## iOS UI Tasks

### YT-0025 — Build iOS App Shell And Components
**Mockup:** `mockups/ios/YT-0025-0026-0027-shell-search-player.html`
Mostra: TabView (Search/Library/Settings), tab bar frosted glass 83pt, Dynamic Island + status bar, MiniPlayer con `safeAreaInset` pattern (padding sotto tab bar), componenti riusabili: `TrackRow`, `MiniPlayer`, `EqIndicator`, `SkeletonRow`.

### YT-0026 — Build iOS Search Workflow
**Mockup:** `mockups/ios/YT-0025-0026-0027-shell-search-player.html`
Mostra: SearchBar iOS (36pt rounded, inline Cancel, accent border on focus), suggestion chips (pill outlined), stati: idle (chips), loading (skeleton con shimmer), results (TrackRow list in inset grouped style), empty state, tap-to-play con EQ indicator animato.

### YT-0027 — Build iOS Now Playing Workflow
**Mockup originale:** `mockups/ios/YT-0025-0026-0027-shell-search-player.html`
**Handoff package (canonico per YT-0027):** `handoff/YT-0027/`
- `mockup.html` — interactive mockup con toggle per ogni decisione (background, queue, reduce-motion, haptics)
- `decision-log.md` — 10 decisioni con rationale (presentazione, artwork, scrubber, transport, queue, action row, haptics, reduce-motion, system parity)
- `swiftui-spec.md` — view hierarchy, snippet SwiftUI, mapping CSS → SwiftUI
- `haptics-and-a11y.md` — tabella `.sensoryFeedback`, Dynamic Type, reduce-motion contract, VoiceOver
- `system-parity.md` — `MPNowPlayingInfoCenter`, `MPRemoteCommandCenter`, Live Activity / Dynamic Island

Quando implementi YT-0027, **leggi prima `handoff/YT-0027/README.md`**. Il mockup originale resta come riferimento layout ma l'handoff ha precedenza su differenze (background, scrubber thumb color, play button size, queue access pattern, AirPlay placement).

### YT-0028 — Build iOS Library And Playlist Management
**Mockup:** `mockups/ios/YT-0028-0029-library-history.html`
Mostra: Library Large Title, "Recently Played" entry con SF Symbol clock, lista playlist inset grouped, "+" button navigazione, PlaylistDetail — Cover 140pt, Play/Shuffle/Share pills, TrackRow con edit mode (swipe-style remove + drag), rename bottom sheet, delete button destructive rosso.

### YT-0029 — Build iOS Recently Played Workflow
**Mockup:** `mockups/ios/YT-0028-0029-library-history.html`
Mostra: HistoryScreen come subview di Library (back button "Library"), lista sorted newest-first con timestamp relativo, tap-to-play, "Clear" top-right, action sheet di conferma iOS, empty state.

### YT-0030 — Complete iOS Playlist Sharing And Import
**Mockup:** `mockups/ios/YT-0030-0031-settings-sharing.html`
Mostra: UIActivityViewController-style share sheet con file preview (`.ytplaylist.json`), AirDrop/Files/Mail/Messages targets, "Copy" e "Save to Files" rows, dismiss Cancel. Import result sheet: success (checkmark verde, "Playlist Imported") e error (cerchio rosso, "This playlist was made with a newer version...").

### YT-0031 — Build iOS Settings Workflow
**Mockup:** `mockups/ios/YT-0030-0031-settings-sharing.html`
Mostra: Settings Large Title, Form-style grouped rows (Appearance/Playback/Playlists/Data/About), UISwitch per dark mode, quality segmented control, Export/Import con chevron (triggera share sheet/import result), Clear Cache con action sheet confirm, Clear History (destructive red), About section con versione e disclaimer personal use.

---

## Riferimenti Design System

| Risorsa | Path |
|---------|------|
| Token CSS completi | `colors_and_type.css` |
| Documentazione design | `README.md` |
| UI Kit interattivo (generico) | `ui_kits/app/index.html` |
| Componenti JSX riusabili | `ui_kits/app/*.jsx` |
| Preview card componenti | `preview/component-*.html` |
| Guida mockup piattaforme | `mockups/README.md` |

---

## Note per l'implementazione

- I mockup mostrano l'**aspetto target**, non l'implementazione. Usa Compose/SwiftUI idiomatici.
- I colori nel mockup Android usano variabili CSS `pal.*` — in Compose usa `MaterialTheme.colorScheme.*`
- I colori nel mockup iOS usano `C.*` — in SwiftUI usa `Color.accentColor`, `.primary`, `.secondary` etc.
- Tap targets: min 44pt iOS / 48dp Android (rispettati nei mockup con bottoni 40-48px min)
- Font: Roboto su Android (automatico M3), SF Pro su iOS (automatico SwiftUI) — i mockup usano system font del browser come proxy

---

## Handoff Packages

Handoff packages sono la **source of truth** per l'implementazione. Vivono in `design-system/handoff/<TICKET>/` e sovrascrivono il mockup HTML corrispondente dove i due divergono.

### Android

| Ticket | Surface | Folder | Mockup (layout reference only) |
|---|---|---|---|
| YT-0011 | App shell | `design-system/handoff/YT-0011/` | `design-system/mockups/android/YT-0011-0012-0013-shell-search-player.html` (combined) |
| YT-0013 | Now Playing | `design-system/handoff/YT-0013/` | `design-system/mockups/android/YT-0011-0012-0013-shell-search-player.html` (combined) |
| **YT-0014 (v2)** | **Library — Playlists (MVP)** | **`design-system/handoff/YT-0014/`** | **`design-system/mockups/android/YT-0014-0015-0016-0017-library-history-settings-sharing.html`** |

### iOS

| Ticket | Surface | Folder |
|---|---|---|
| YT-0027 | Now Playing | `design-system/handoff/YT-0027/` |
| YT-0028 | Library | `design-system/handoff/YT-0028/` |

### Cross-platform

| Ticket | Surface | Folder |
|---|---|---|
| YT-0073 | Empty/Loading/Error state catalog | `design-system/handoff/state-catalog/` |
| YT-0074 | MiniPlayer ↔ NowPlaying motion spec | `design-system/handoff/YT-0074/` |
| YT-0180 | App icon production (Android adaptive + iOS icon set + favicons) | `design-system/handoff/app-icon/` |

### YT-0014 v2 redo notice

YT-0014 è stato rifatto sotto YT-0075 per chiudere quattro blocker di review (Extended FAB, 4-up cover, in-place edit mode, MiniPlayer-aware bottom padding) e ridurre lo scope a Playlists-only per MVP. La spec 4-tab originale è in `design-system/handoff/YT-0014/v1x-tabs-addendum.md` per uso post-MVP.

### Reading order per una handoff folder

1. `README.md`
2. `decision-log.md`
3. `compose-spec.md` o `swiftui-spec.md`
4. Approfondimenti (`haptics-and-a11y.md`, `media3-parity.md`, `system-parity.md`, `aesthetic-direction.md`)
5. `mockup.html` + `mockup-states.html`
6. Addenda (es. `v1x-tabs-addendum.md`)

### Precedence rule

Quando il markdown in `handoff/<TICKET>/` e il mockup HTML divergono, **vince il markdown.**
