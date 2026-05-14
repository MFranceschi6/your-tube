# YourTube — App icon (production handoff)

Canonical icon production package. Implementation tasks (Android `app/src/main/res/mipmap-*` + manifest icon attribute; iOS `Assets.xcassets/AppIcon.appiconset` + Info.plist) absorb from this folder.

## Design rationale

Variant 06: compact "Y" wordmark fused with asymmetric trapezoidal over-ear headphones. Glyph-only — no text in the icon (Apple HIG forbids it; bad practice on Android). The headphones cup geometry doubles as the legs of the Y, keeping the silhouette legible at the smallest target size (40×40 iOS notification, 48×48 Android `mdpi`).

Brand color: `#8B5CF6` (purple) per [docs/design-system.md](../../../docs/design-system.md). Background gradient is a radial sweep top-left → bottom-right anchored on the brand purple to keep the glyph readable on both light and dark home-screen wallpapers.

## Precedence

This folder is the **source of truth** for all icon assets. It overrides:

- `design-system/mockups/app-icon.html` — concept exploration only; predates this package and stays for design history.
- The previous `design-system/exports/` folder, which was relocated here verbatim.

The vector source is canonical; PNGs are derived. Do not hand-edit a PNG; re-export from the SVG.

## File index

```
app-icon/
├── README.md                      ← this file (rationale + precedence)
├── source.svg                     ← master 1024×1024 (gradient bg + glyph)
├── glyph.svg                      ← glyph-only master, transparent (1024×1024)
├── adaptive-icon-spec.md          ← Android adaptive-icon XML + density buckets
├── material-you-rules.md          ← Android themed-icon (Android 13+) tinting rules
├── audit.md                       ← per-target legibility audit
├── android/
│   ├── ic_launcher_foreground.svg     ← adaptive foreground (108 dp safe / 192 dp bleed)
│   ├── ic_launcher_background.svg     ← adaptive background (gradient)
│   ├── ic_launcher_monochrome.svg     ← Android 13+ themed-icon silhouette
│   ├── ic_launcher_foreground-1024.png, ic_launcher_foreground-432.png
│   ├── ic_launcher_background-1024.png, ic_launcher_background-432.png
│   ├── ic_launcher-{48,72,96,144,192}.png    ← legacy mipmap density buckets
│   └── ic_launcher-playstore-512.png          ← Play Store listing
├── ios/
│   ├── icon-set-spec.md               ← Contents.json shape + AppIcon.appiconset layout
│   ├── dark-and-tinted-variants.md    ← iOS 18+ dark + tinted home-screen design rules
│   ├── icon-1024.png                  ← App Store
│   └── icon-{180,167,152,120,87,80,76,60,58,40,29,20}.png   ← App Icon set
└── web/
    └── favicon-{16,32,192,512}.png    ← PWA / favicon (out of scope for native MVP, kept for completeness)
```

## Color tokens

- Accent: `#8B5CF6`
- Gradient stops: `#A78BFA → #8B5CF6 → #6D28D9 → #4C1D95`
- Glyph: `#FFFFFF` (full color), `#000000` on transparent (monochrome)

## Reading order

1. This README.
2. `audit.md` — verify legibility at every target size before consuming assets.
3. Per-platform spec (`adaptive-icon-spec.md`, `material-you-rules.md`, `icon-set-spec.md`, `dark-and-tinted-variants.md`).
4. SVG sources, then PNG exports.
