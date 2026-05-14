# iOS 18+ dark and tinted icon variants — design rules

iOS 18 introduced two home-screen appearances beyond the default colored icon:

- **Dark** — designer-supplied dark variant; user opts in via Settings → Display & Brightness → Dark.
- **Tinted** — system reduces the icon to a single tinted layer + a dark gradient background; user opts in via Settings → Customize home screen → Tinted.

iOS 26+ retains both variants and adds the Liquid Glass home-screen treatment. The icon variants below survive Liquid Glass without rework — Liquid Glass affects chrome materials, not the icon raster.

## Dark variant

Designer-controlled. Supply a separate 1024×1024 PNG that reads correctly against a dark wallpaper. For YourTube the existing color icon already has a dark gradient background (`#A78BFA → … → #4C1D95`); the dark variant can be the same composition with the gradient compressed toward the deeper stops:

- Gradient stops: `#6D28D9 → #4C1D95 → #2E1065`
- Glyph: `#FFFFFF` (unchanged)

This keeps the brand purple identity while improving optical match against the iOS 18+ dark home screen.

## Tinted variant

System-controlled tint; designer supplies a **single grayscale layer** that the OS multiplies against the user-selected accent. Rules:

- Glyph in white (`#FFFFFF`) on transparent.
- Background **transparent** — the OS supplies a dark gradient fill driven by the user's tint color.
- Internal tonal shading optional; if present, use grayscale (no color) and rely on lightness only.
- The 25%-alpha highlight stroke from the foreground SVG can stay; tinted icons accept alpha.

Practical: the existing `glyph.svg` (full glyph, white on transparent) is the tinted source. Export at 1024×1024 to PNG with alpha for the asset catalog.

## `Contents.json` extension

Each of the 13 image entries in the `appiconset/Contents.json` grows an `appearances` array, one per variant:

```json
{
  "size" : "1024x1024",
  "idiom" : "universal",
  "platform" : "ios",
  "filename" : "icon-1024.png",
  "scale" : "1x"
},
{
  "size" : "1024x1024",
  "idiom" : "universal",
  "platform" : "ios",
  "appearances" : [
    { "appearance" : "luminosity", "value" : "dark" }
  ],
  "filename" : "icon-1024-dark.png",
  "scale" : "1x"
},
{
  "size" : "1024x1024",
  "idiom" : "universal",
  "platform" : "ios",
  "appearances" : [
    { "appearance" : "luminosity", "value" : "tinted" }
  ],
  "filename" : "icon-1024-tinted.png",
  "scale" : "1x"
}
```

Apple's recommended pattern (Xcode 15+) is **single-image-per-appearance** (one 1024 per variant); Xcode generates the smaller sizes at build time. The repo currently ships only the default (color) PNGs. Dark and tinted PNGs are a follow-on production task; the rules in this file are the contract.

## Asset deliverable status

| Variant | Source | Status |
|---|---|---|
| Color | `source.svg` + `ios/icon-*.png` | shipped |
| Dark | derived from `source.svg` with darker gradient stops | **TODO** — produce `ios/icon-1024-dark.png` |
| Tinted | derived from `glyph.svg` (white-on-transparent) | **TODO** — produce `ios/icon-1024-tinted.png` |

The two TODOs are intentionally outside this docs task's scope; the rules above are sufficient for the design consultant or implementation engineer to render the missing PNGs and drop them in `ios/` when scheduled.

## Validation

- [ ] On iPhone 16 (iOS 26+), enable Settings → Customize home screen → Dark. Verify icon renders against dark wallpapers without losing legibility.
- [ ] Enable Tinted with several accent hues (purple, blue, orange, green). Verify silhouette stays identifiable at home-screen size.
- [ ] Cross-check legibility on Liquid Glass home-screen treatment (clear backdrop). Tinted variant should remain readable.
