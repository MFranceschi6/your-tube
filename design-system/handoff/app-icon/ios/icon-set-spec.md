# iOS AppIcon set — spec

Production layout for `ios/YourTube/Resources/Assets.xcassets/AppIcon.appiconset/`. Implementation absorbs from this folder; this file is the contract.

Companion: `dark-and-tinted-variants.md` (same folder) — design rules for the iOS 18+ dark and tinted appearances.

## Source

`icon-1024.png` (this folder) derived from `../source.svg` (full composition: gradient background + glyph). Apple flattens iOS icons against the home-screen squircle mask automatically — submit a square PNG, no pre-rounded corners.

PNGs supplied at every Apple-required size:

| File | Size (px) | Idiom | Scale | Purpose |
|---|---|---|---|---|
| `icon-1024.png` | 1024 | App Store marketing | 1× | Single-image icon for App Store Connect |
| `icon-180.png` | 180 | iPhone | 3× | Home screen on iPhone 6+ |
| `icon-120.png` | 120 | iPhone | 2× | Home screen on iPhone, Spotlight @3× |
| `icon-167.png` | 167 | iPad Pro | 2× | Home screen on iPad Pro |
| `icon-152.png` | 152 | iPad | 2× | Home screen on iPad |
| `icon-87.png` | 87 | iPhone | 3× | Settings @3× |
| `icon-80.png` | 80 | iPhone / iPad | 2× | Spotlight @2× |
| `icon-76.png` | 76 | iPad | 1× | Home screen on legacy iPad (deprecated, ship for compat) |
| `icon-60.png` | 60 | iPhone | 3×→2× | Notification @3× / 60pt placeholder |
| `icon-58.png` | 58 | iPhone / iPad | 2× | Settings @2× |
| `icon-40.png` | 40 | iPhone / iPad | 2× | Notification @2× / Spotlight @1× |
| `icon-29.png` | 29 | iPhone / iPad | 1× | Settings @1× |
| `icon-20.png` | 20 | iPhone / iPad | 1× | Notification @1× |

## `Contents.json` shape (single-image icon, recommended)

Apple's "Single Size" icon configuration replaces the legacy 13-PNG matrix. Xcode 14+ accepts a single 1024×1024 source and derives the rest at build time. **Use this form** for new projects:

```json
{
  "images" : [
    {
      "filename" : "icon-1024.png",
      "idiom" : "universal",
      "platform" : "ios",
      "size" : "1024x1024"
    }
  ],
  "info" : {
    "author" : "xcode",
    "version" : 1
  }
}
```

If single-image isn't viable (e.g. distinct iPad design — not the case for YourTube), use the multi-size matrix:

```json
{
  "images" : [
    { "size" : "20x20", "idiom" : "iphone", "filename" : "icon-40.png", "scale" : "2x" },
    { "size" : "20x20", "idiom" : "iphone", "filename" : "icon-60.png", "scale" : "3x" },
    { "size" : "29x29", "idiom" : "iphone", "filename" : "icon-58.png", "scale" : "2x" },
    { "size" : "29x29", "idiom" : "iphone", "filename" : "icon-87.png", "scale" : "3x" },
    { "size" : "40x40", "idiom" : "iphone", "filename" : "icon-80.png", "scale" : "2x" },
    { "size" : "40x40", "idiom" : "iphone", "filename" : "icon-120.png", "scale" : "3x" },
    { "size" : "60x60", "idiom" : "iphone", "filename" : "icon-120.png", "scale" : "2x" },
    { "size" : "60x60", "idiom" : "iphone", "filename" : "icon-180.png", "scale" : "3x" },
    { "size" : "20x20", "idiom" : "ipad",   "filename" : "icon-20.png",  "scale" : "1x" },
    { "size" : "20x20", "idiom" : "ipad",   "filename" : "icon-40.png",  "scale" : "2x" },
    { "size" : "29x29", "idiom" : "ipad",   "filename" : "icon-29.png",  "scale" : "1x" },
    { "size" : "29x29", "idiom" : "ipad",   "filename" : "icon-58.png",  "scale" : "2x" },
    { "size" : "40x40", "idiom" : "ipad",   "filename" : "icon-40.png",  "scale" : "1x" },
    { "size" : "40x40", "idiom" : "ipad",   "filename" : "icon-80.png",  "scale" : "2x" },
    { "size" : "76x76", "idiom" : "ipad",   "filename" : "icon-76.png",  "scale" : "1x" },
    { "size" : "76x76", "idiom" : "ipad",   "filename" : "icon-152.png", "scale" : "2x" },
    { "size" : "83.5x83.5", "idiom" : "ipad", "filename" : "icon-167.png", "scale" : "2x" },
    { "size" : "1024x1024", "idiom" : "ios-marketing", "filename" : "icon-1024.png", "scale" : "1x" }
  ],
  "info" : {
    "author" : "xcode",
    "version" : 1
  }
}
```

For dark + tinted variants on iOS 18+, see `dark-and-tinted-variants.md` (same folder). The `Contents.json` shape grows an `appearances` array per image.

## Folder layout

```
ios/YourTube/Resources/Assets.xcassets/AppIcon.appiconset/
├── Contents.json
├── icon-1024.png
├── (and the 12 derived sizes if multi-size form)
```

`Info.plist` references `CFBundleIconName` = `AppIcon`.

## Constraints

- No transparent corners. Apple applies the squircle mask. Submit full-bleed.
- No alpha channel on the 1024 marketing icon (App Store rejects it).
- sRGB color space. Wide-gamut Display P3 is allowed but optional; submit sRGB to avoid shifts under non-P3 wallpapers.
- No text. Apple HIG forbids it.

## Validation

- [ ] Install on a physical iPhone 16 (iOS 26+); verify home-screen rendering at every size context (home, Settings, Spotlight, notifications).
- [ ] Submit to TestFlight; verify App Store Connect accepts the 1024 marketing icon.
- [ ] Verify dark + tinted variants per `dark-and-tinted-variants.md`.
