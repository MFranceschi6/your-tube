# Material You themed icon — rules

Android 13+ ("Themed icons" in Settings → Wallpaper & style) tints the launcher icon by sampling the wallpaper / system color. The OS reads `ic_launcher_monochrome.svg` (or PNG drawable) and applies a single duotone palette derived from the active Material You theme.

## Source

`android/ic_launcher_monochrome.svg` — full glyph at 1024 viewBox, transformed into the safe zone (same `translate(170, 170) scale(0.668)` as the foreground), all paths `#000000` on transparent. The OS replaces black with the themed foreground color and the surrounding canvas with the themed background color.

The 25%-alpha highlight stroke present in `ic_launcher_foreground.svg` (the subtle reflection at the top of the headphone band) is **dropped** in monochrome — it would muddy the silhouette under tinting. The themed channel is intentionally flatter than the color foreground.

## Contrast rules

Material You publishes three pairings (light / dark / vibrant). The OS handles tinting; designers verify that the silhouette stays legible across every published palette:

| Theme mode | FG (icon path) | BG (canvas) | Floor |
|---|---|---|---|
| Light themed | `seed.40` | `seed.95` | 4.5:1 ratio (the OS picks tones with this floor; we verify) |
| Dark themed | `seed.80` | `seed.20` | 4.5:1 |
| Vibrant | `seed.30` | `seed.90` | 3:1 (large-glyph leniency) |

Manual verification: open the system theme picker on a Pixel 8 and cycle through 6 wallpaper colors with themed icons enabled. The Y+headphones silhouette must remain unambiguously identifiable in every pairing.

## Anti-patterns

- Multi-color paths in the monochrome drawable. The OS only honors black-on-transparent; non-black fills are ignored or composited unpredictably.
- Translucent fills (`fill-opacity < 1.0`). Use a solid silhouette; let the OS tint apply alpha if needed.
- Stroke-only paths without a paired fill. The OS may tint the stroke separately from filled shapes.
- Tiny detail (sub-2 dp lines) that disappears under low-contrast tints.

## Fallback

If a wallpaper palette produces a pairing that fails the 4.5:1 floor for the silhouette (rare — OEM skins sometimes diverge from AOSP), the OS fallback is the standard `ic_launcher` (color foreground + gradient background). No app-side override; OEM choice.

## Verification

- [ ] Themed icon renders silhouette legibly under 6+ wallpaper palettes on Pixel 8 API 35.
- [ ] Themed icon renders silhouette legibly with themed icons OFF (color icon path).
- [ ] No alpha/multi-color artifact in the monochrome SVG.
