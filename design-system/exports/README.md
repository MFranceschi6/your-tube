# YourTube — App icon export

Tutti i file sono stati generati dalla variante 06 (Y compatta + cuffie trapezoidali asimmetriche).

## Sorgente vettoriale (master)
- `yourtube-icon-foreground.svg` — solo glyph trasparente (1024×1024)
- `ios-icon-1024.svg` — icona completa con sfondo viola gradient
- `android-foreground.svg` — foreground per adaptive icon (safe area Android)
- `android-background.svg` — background gradient per adaptive icon

## iOS — `exports/ios/`
PNG quadrati a tutte le size richieste da Xcode AppIcon set:
- `icon-1024.png` — App Store
- `icon-180.png` — iPhone @3x (60pt)
- `icon-167.png` — iPad Pro
- `icon-152.png` — iPad @2x
- `icon-120.png` — iPhone @2x / Spotlight @3x
- `icon-87/80/76/60/58/40/29/20.png` — Spotlight, Settings, Notification

iOS arrotonda automaticamente le icone (squircle), quindi i PNG sono quadrati pieni.

## Android — `exports/android/`
**Adaptive icon (Android 8+, raccomandato):**
- `ic_launcher_foreground-432.png` (e -1024) → `res/mipmap-anydpi-v26/ic_launcher_foreground.png`
- `ic_launcher_background-432.png` (e -1024) → `res/mipmap-anydpi-v26/ic_launcher_background.png`

Esempio `mipmap-anydpi-v26/ic_launcher.xml`:
```xml
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@mipmap/ic_launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
</adaptive-icon>
```

**Legacy (pre-API 26):**
- `ic_launcher-192.png` → `mipmap-xxxhdpi`
- `ic_launcher-144.png` → `mipmap-xxhdpi`
- `ic_launcher-96.png`  → `mipmap-xhdpi`
- `ic_launcher-72.png`  → `mipmap-hdpi`
- `ic_launcher-48.png`  → `mipmap-mdpi`

**Play Store:** `ic_launcher-playstore-512.png` (512×512, 32-bit PNG).

## Web — `exports/web/`
- `favicon-512.png`, `favicon-192.png` — PWA / Apple touch icon
- `favicon-32.png`, `favicon-16.png` — favicon classico

## Colori
- Accent viola: `#8B5CF6`
- Gradient sfondo: `#A78BFA → #8B5CF6 → #6D28D9 → #4C1D95`
- Glyph: `#FFFFFF`
