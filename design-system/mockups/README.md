# YourTube — UI Mockups

Prototipi interattivi HTML per ogni workflow di UI. Ogni file è un frame iPhone/Android
a 393×852px con componenti React cliccabili. Aprire in browser per interagire.

## Come usare questi mockup con Claude

Quando lavori a un task di UI, passa il percorso del mockup corrispondente a Claude.
Esempio: "Implementa YT-0026 seguendo il mockup in `mockups/ios/YT-0025-0026-0027-shell-search-player.html`"

---

## iOS Mockups

| File | Task(s) | Contenuto |
|------|---------|-----------|
| `ios/YT-0025-0026-0027-shell-search-player.html` | YT-0025, YT-0026, YT-0027 | Tab shell (Search/Library/Settings), SearchBar (idle/focused/loading/results/empty), TrackRow, MiniPlayer con progresso, NowPlaying full-screen (artwork, scrubber, transport, queue) |
| `ios/YT-0028-0029-library-history.html` | YT-0028, YT-0029 | Library con Recently Played entry, lista playlist, PlaylistDetail (copertina 4-up, Play/Shuffle, edit mode con remove+drag, rename sheet), HistoryScreen (list, clear confirm) |
| `ios/YT-0030-0031-settings-sharing.html` | YT-0030, YT-0031 | Settings (dark mode toggle, quality picker, export/import, clear cache/history, about), iOS share sheet UIActivityViewController-style, import success/error result sheet |

### Convenzioni iOS mostrate
- SF Pro (system font via `-apple-system`)
- Dynamic Island + status bar
- Tab bar con backdrop blur
- SearchBar stile iOS (rounded, no bordo, Cancel inline)
- Grouped inset list style (bordi 0.5px `rgba(84,84,88,0.65)`)
- Sheet con handle drag indicator
- Action sheet con Cancel separato
- Titoli Large Title (`font-size: 28px fontWeight 700`)
- `safeAreaInset` simulato per MiniPlayer sopra tab bar

---

## Android Mockups

| File | Task(s) | Contenuto |
|------|---------|-----------|
| `android/YT-0011-0012-0013-shell-search-player.html` | YT-0011, YT-0012, YT-0013 | M3 Navigation Bar, **dynamic color picker** (dimostra Material You con 3 palette), SearchBar stile M3 (pill shape), filter chips, TrackRow M3, MiniPlayer su `primaryContainer`, NowPlaying con FAB-style play button |
| `android/YT-0014-0015-0016-0017-library-history-settings-sharing.html` | YT-0014, YT-0015, YT-0016, YT-0017 | Library con **Extended FAB** "New Playlist", playlist detail con edit mode, History con M3 dialog confirm, Settings con M3 switch + dialog, share bottom sheet stile Android |

### Convenzioni Android mostrate
- Roboto font
- Material 3 Navigation Bar con indicator pill su item attivo
- **Dynamic Color (Material You)**: picker in-app con 3 palette (purple/blue/green) — in produzione viene dal wallpaper utente su Android 12+; fallback brand purple `#8B5CF6`
- M3 Search bar (pill, `56dp` height, shadow-1)
- M3 Filter chips per suggestions
- Extended FAB per "New Playlist"
- M3 Dialog (border-radius 28px) invece di action sheet
- Bottom sheet con drag handle
- Switch M3 (track colorato)
- `primaryContainer` per MiniPlayer (colore warm, leggibile)
- Elevation tramite shadow, non borders

---

## Note di Design

### Dynamic Color (Android)
Sì, ha senso applicare Material You: su Android 12+ l'accent del sistema viene estratto
dal wallpaper. Il mockup mostra 3 esempi di palette per dimostrare come tutto l'UI
si adatta coerentemente. Il brand purple `#8B5CF6` rimane il fallback per Android <12
e per il caso in cui il wallpaper non generi abbastanza contrasto.

### Differenze piattaforma intenzionali
| Aspetto | iOS | Android |
|---------|-----|---------|
| Font | SF Pro (system) | Roboto |
| Tab bar | Frosted glass, icon+label, height 83pt | M3 NavBar, indicator pill, height 80dp |
| Search bar | Rounded rect 36pt, inline Cancel | Pill 56dp, icon leading |
| Suggestions | Pill chips | M3 Filter chips (outlined) |
| New playlist | Sheet bottom con text input | M3 Dialog + Extended FAB |
| Conferma distruttiva | Action sheet iOS | M3 Dialog |
| Colore accent | `Color.accentColor` / `#8B5CF6` | Dynamic Color / `#D0BCFF` (M3 primary su dark) |
| Player controls | Bianco su gradient scuro, thumb bianco | `primary` color M3, FAB-style play |
| MiniPlayer bg | `rgba(44,44,46,0.95)` blur | `primaryContainer` |
