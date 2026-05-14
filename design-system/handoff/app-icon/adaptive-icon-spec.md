# Android adaptive icon — spec

Production XML + drawable layout for `app/src/main/res/`. Implementation absorbs from this folder; this file documents the contract, not the wiring step.

## Geometry

Android adaptive icons render on a 108×108 dp canvas. The center 72×72 dp is the **safe zone** (always visible across all OEM masks: circle, squircle, rounded square, teardrop, hexagon, …). The outer 18 dp ring on each edge is **bleed** — used for parallax and mask cropping, may be clipped.

Source files in `android/`:

- `ic_launcher_foreground.svg` — 1024×1024 viewBox, glyph translated/scaled into the safe zone (`translate(170, 170) scale(0.668)`). Renders the brand "Y + headphones" silhouette.
- `ic_launcher_background.svg` — 1024×1024 radial gradient (`#A78BFA → #8B5CF6 → #6D28D9 → #4C1D95`).
- `ic_launcher_monochrome.svg` — black silhouette on transparent for the Android 13+ themed-icon channel. See [material-you-rules.md](material-you-rules.md).

Pre-rendered PNGs:

- `ic_launcher_foreground-1024.png`, `ic_launcher_foreground-432.png`
- `ic_launcher_background-1024.png`, `ic_launcher_background-432.png`

432 px is the Google-recommended minimum drawable resolution for adaptive icons (108 dp × 4 = 432 px at xxxhdpi).

## XML wiring

`app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@mipmap/ic_launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
</adaptive-icon>
```

`app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml`: identical content (Android still requires both `ic_launcher` and `ic_launcher_round` references; OS picks per launcher mask).

`AndroidManifest.xml` `<application>` block:

```xml
android:icon="@mipmap/ic_launcher"
android:roundIcon="@mipmap/ic_launcher_round"
```

## Density buckets

Place foreground / background bitmaps in density-specific mipmap folders:

| Folder | Density | Foreground / background size |
|---|---|---|
| `mipmap-mdpi/` | mdpi (1×) | 108 px |
| `mipmap-hdpi/` | hdpi (1.5×) | 162 px |
| `mipmap-xhdpi/` | xhdpi (2×) | 216 px |
| `mipmap-xxhdpi/` | xxhdpi (3×) | 324 px |
| `mipmap-xxxhdpi/` | xxxhdpi (4×) | 432 px |

Place legacy fallbacks in the same folders for pre-API-26 devices:

| Folder | Legacy size |
|---|---|
| `mipmap-mdpi/ic_launcher.png` | 48 px |
| `mipmap-hdpi/ic_launcher.png` | 72 px |
| `mipmap-xhdpi/ic_launcher.png` | 96 px |
| `mipmap-xxhdpi/ic_launcher.png` | 144 px |
| `mipmap-xxxhdpi/ic_launcher.png` | 192 px |

`mipmap-anydpi-v26/` overrides the legacy bucket on API 26+; the legacy PNGs only ship as fallback for pre-Oreo devices.

## Play Store

Upload `ic_launcher-playstore-512.png` (512×512, 32-bit PNG, sRGB) to Play Console listing. Same composition as the launcher icon at full-bleed 1:1 — Play Store applies its own corner mask.

## Validation

1. Install debug build on a Pixel 8 (API 35).
2. Verify the launcher icon renders correctly under each mask (Settings → Display → Style & wallpapers → Themed icons / icon shape).
3. Confirm no clipping into the safe zone at any mask.
4. Re-verify after every mask change in Android system updates.
