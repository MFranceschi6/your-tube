# App icon audit — legibility verification

Verifies every shipped variant renders correctly in its target context. Manual check; redo when assets change.

## Master vector

| Asset | Resolution | Renders correctly | Notes |
|---|---|---|---|
| `source.svg` | 1024 | ✓ | Full color composition; gradient + glyph. |
| `glyph.svg` | 1024 | ✓ | White-on-transparent silhouette; serves as iOS tinted source. |
| `android/ic_launcher_foreground.svg` | 1024 (108 dp safe / 192 dp bleed) | ✓ | Glyph translated 170 / scaled 0.668; fits within Android safe zone under all OEM masks. |
| `android/ic_launcher_background.svg` | 1024 | ✓ | Radial gradient. |
| `android/ic_launcher_monochrome.svg` | 1024 | ✓ | Black silhouette on transparent; reflection highlight intentionally omitted. |

## Android targets

| Mask context | Test on | Status | Comment |
|---|---|---|---|
| Default circle (Pixel launcher) | Pixel 8 API 35 | ✓ | Verified 2026-05-09. YT-0269 assets absorbed; no placeholder diamond. |
| Squircle | Pixel 8 API 35 | ✓ | Verified 2026-05-09. YT-0269. |
| Rounded square | Pixel 8 API 35 | ✓ | Verified 2026-05-09. YT-0269. |
| Teardrop (Samsung) | Galaxy AVD | not tested | Defer to QA wave. |
| Themed icon (Material You ON) | Pixel 8 API 35 | ✓ | Verified 2026-05-09. YT-0269. Monochrome XML uses solid black fills; Material You tinting correct. |
| Play Store listing | Console preview | ✓ | `ic_launcher-playstore-512.png` accepted by Console preview tool. |
| Smallest legacy bucket (`mipmap-mdpi/ic_launcher-48.png`) | inspect at 48×48 | ✓ | Glyph remains identifiable; headphone cup geometry holds. |

## iOS targets

| Context | Size | Status | Comment |
|---|---|---|---|
| App Store (1024) | 1024 | ✓ | sRGB, no alpha, full bleed. Verified visually in `ios/icon-1024.png`. |
| iPhone home (180) | 180 | ✓ | Glyph crisp, gradient smooth. |
| iPad Pro home (167) | 167 | ✓ | Same. |
| iPhone Settings (87) | 87 | ✓ | Headphones cup geometry still legible. |
| iPad notification (40) | 40 | ✓ | At threshold of legibility — Y wordmark recognizable, headphones cup reads as silhouette. |
| iPad notification (20) | 20 | ⚠️ | Silhouette readable; brand identity less distinct. Acceptable per Apple HIG (notification glyphs prioritize recognizability over detail). |
| Dark variant (iOS 18+) | 1024 | ⏳ | Asset not yet produced; rules in `ios/dark-and-tinted-variants.md`. |
| Tinted variant (iOS 18+) | 1024 | ⏳ | Asset not yet produced; rules in `ios/dark-and-tinted-variants.md`. Use `glyph.svg` as source. |
| iOS 26 Liquid Glass home | varies | not tested | Pending iOS 26 simulator absorption. Liquid Glass treatment doesn't change the raster; legibility expected to hold. |

## Web favicons

`web/favicon-{16,32,192,512}.png` — ship for completeness. Out of scope for native MVP. No verification in this task.

## Open items

1. **iOS dark variant PNG** — `ios/icon-1024-dark.png` not yet produced. Follow-on production task.
2. **iOS tinted variant PNG** — `ios/icon-1024-tinted.png` not yet produced. Follow-on production task.
3. **Android assets absorption** — ✓ Done. YT-0269 absorbed assets into `mipmap-*`; verified 2026-05-09.
4. **iOS assets absorption** — `Assets.xcassets/AppIcon.appiconset/` not yet present in repo. Tracked outside this docs task.

## Sign-off rule

The handoff is complete (this folder is the source of truth) when:

- Every legend marked ✓ above stays ✓.
- Open items 1–4 each have an explicit follow-up task ID or are explicitly deferred.

Open items 1–4 are absorption / variant-production work, not blockers for this docs handoff.
