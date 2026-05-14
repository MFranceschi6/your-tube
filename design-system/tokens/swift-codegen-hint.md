# Swift / iOS — Token Mapping Convention

> This document tells iOS engineers how `design-system/tokens/tokens.json` maps to SwiftUI code. It does **not** generate code — it documents the convention so engineers and agents produce consistent implementations.

---

## Where platform files live

| Token category | Swift home | Notes |
|---|---|---|
| Colors | `ios/YourTube/DesignSystem/Color+App.swift` | `extension Color` with static properties |
| Typography | `ios/YourTube/DesignSystem/Typography.swift` | `enum AppFont` or `extension Font` with static properties |
| Spacing | `ios/YourTube/DesignSystem/Spacing.swift` | `extension CGFloat` or `struct Spacing` with static `CGFloat` constants |
| Shape | Inline per-component via `RoundedRectangle(cornerRadius:)` | Keep radius values aligned with `tokens.json` |

If these files do not yet exist, create them under the project's existing DesignSystem group. Follow the existing naming convention in the project rather than inventing a new one.

---

## Color mapping

Token values under the `dark` key map to dark-mode colors; `light` values to light mode. Use Asset Catalog color sets (with `Appearances: Any, Dark`) or `Color(uiColor:)` with a `UIColor(dynamicProvider:)` block.

### Recommended pattern — Asset Catalog approach

1. Add a color set to `Assets.xcassets` with `Any Appearance` (light) and `Dark Appearance` entries.
2. Expose via `extension Color { static let brandAccent = Color("brandAccent") }`.
3. Xcode automatically selects the right variant at runtime.

### Recommended pattern — code-only approach

```swift
// Illustrative shape only — do not copy-paste the hex literals;
// read them from tokens.json.
extension Color {
    static let brandAccent = Color(
        uiColor: UIColor { trait in
            trait.userInterfaceStyle == .dark
                ? UIColor(hex: "<color.brand.accent.dark>")
                : UIColor(hex: "<color.brand.accent.light>")
        }
    )
}
```

### Brand accent

| `tokens.json` path | Dark | Light | SwiftUI semantic |
|---|---|---|---|
| `color.brand.accent` | `#8B5CF6` | `#7C3AED` | `Color.accentColor` (configure in asset catalog) / `Color.brandAccent` |
| `color.brand.accent-dim` | `#7C3AED` | `#6D28D9` | `Color.brandAccentPressed` (custom; no SwiftUI role) |
| `color.brand.accent-on` | `#FFFFFF` | `#FFFFFF` | `Color.white` (constant; no override needed) |

### Surface roles

| `tokens.json` path | Dark | Light | SwiftUI semantic |
|---|---|---|---|
| `color.background.base` | `#0F0F0F` | `#F2F2F7` | `.background` environment value / `Color.appBackground` |
| `color.background.surface` | `#1C1C1E` | `#FFFFFF` | `Color(uiColor: .secondarySystemBackground)` maps to this on iOS |
| `color.background.surface-variant` | `#2C2C2E` | `#F2F2F7` | `Color(uiColor: .tertiarySystemBackground)` |

Prefer system colors where the system-provided value matches the token value — it adapts automatically and respects Dynamic Colors. Use a custom extension only when the system color diverges from the token.

### Foreground / text roles

| `tokens.json` path | Dark | Light | SwiftUI semantic |
|---|---|---|---|
| `color.foreground.primary` | `#FFFFFF` | `#000000` | `Color.primary` (system) |
| `color.foreground.secondary` | `rgba(235,235,245,0.80)` | `rgba(60,60,67,0.60)` | `Color.secondary` (system) |
| `color.foreground.tertiary` | `rgba(235,235,245,0.40)` | `rgba(60,60,67,0.30)` | `Color(uiColor: .tertiaryLabel)` |

### Semantic roles

| `tokens.json` path | Dark | Light | SwiftUI semantic |
|---|---|---|---|
| `color.semantic.error` | `#FF453A` | `#FF3B30` | `Color(uiColor: .systemRed)` (matches on iOS) |
| `color.semantic.success` | `#30D158` | `#34C759` | `Color(uiColor: .systemGreen)` |
| `color.semantic.warning` | `#FFD60A` | TBD | `Color(uiColor: .systemYellow)` (TBD for light) |

---

## Spacing mapping

Convention: expose as static `CGFloat` constants on an extension or a `struct Spacing`.

| Token | Value | Swift constant name |
|---|---|---|
| `spacing.xs` | 4 | `Spacing.xs` |
| `spacing.sm` | 8 | `Spacing.sm` |
| `spacing.md` | 16 | `Spacing.md` |
| `spacing.lg` | 24 | `Spacing.lg` |
| `spacing.xl` | 32 | `Spacing.xl` |
| `spacing.2xl` | 48 | `Spacing.xxl` |
| `spacing.3xl` | 64 | `Spacing.xxxl` |

Values are `pt` on iOS — the unit matches `dp` for the same logical pixel density on standard-resolution screens. No conversion needed.

---

## Typography mapping

| Token | Size (pt) | Weight | SwiftUI role |
|---|---|---|---|
| `typography.display-large` | 32 | `.semibold` | `.largeTitle` scaled / custom `Font.appDisplayLarge` |
| `typography.title-large` | 20 | `.semibold` | `.title2` / `Font.appTitleLarge` |
| `typography.title-medium` | 18 | `.semibold` | `.title3` / `Font.appTitleMedium` |
| `typography.body-large` | 16 | `.regular` | `.body` / `Font.appBodyLarge` |
| `typography.body-medium` | 14 | `.regular` | `.subheadline` / `Font.appBodyMedium` |
| `typography.label-small` | 12 | `.medium` | `.caption` / `Font.appLabelSmall` |

Always use Dynamic Type scaling. Pass `relativeTo:` when using `custom` font style to preserve scaling behavior. Never hardcode `pt` sizes directly on a `Text` view — use the font extension.

---

## Radius mapping

| Token | Value (pt) | SwiftUI usage |
|---|---|---|
| `radius.xs` | 4 | `RoundedRectangle(cornerRadius: 4)` |
| `radius.sm` | 8 | `RoundedRectangle(cornerRadius: 8)` |
| `radius.md` | 12 | `RoundedRectangle(cornerRadius: 12)` — NowPlaying artwork, playlist cover |
| `radius.lg` | 16 | `RoundedRectangle(cornerRadius: 16)` — MiniPlayer, bottom sheets |
| `radius.xl` | 20 | `RoundedRectangle(cornerRadius: 20)` |
| `radius.pill` | 999 | `Capsule()` |

---

## Motion mapping

| Token | Value | SwiftUI API |
|---|---|---|
| `motion.duration.expand` | 0.32 s | `.animation(.timingCurve(0.2, 0.0, 0.0, 1.0, duration: 0.32), value: ...)` |
| `motion.duration.collapse` | 0.26 s | `.animation(.timingCurve(0.2, 0.0, 0.0, 1.0, duration: 0.26), value: ...)` |
| `motion.duration.reduce-motion` | 0.12 s | `.animation(.linear(duration: 0.12), value: ...)` |
| `motion.easing.standard` | `cubic-bezier(0.2, 0.0, 0.0, 1.0)` | `.timingCurve(0.2, 0.0, 0.0, 1.0, duration:)` |
| `motion.easing.exit` | `cubic-bezier(0.3, 0.0, 0.8, 0.15)` | `.timingCurve(0.3, 0.0, 0.8, 0.15, duration:)` |
| `motion.delay.controls-fade-in` | 0.20 s | `.animation(...).delay(0.20)` |

Reduce-motion detection: `@Environment(\.accessibilityReduceMotion) var reduce`. When `reduce == true`, replace transform animations with a plain `.opacity` transition at 0.12 s.

Artwork morph: use `matchedGeometryEffect(id: "np-artwork", in: namespace)` between MiniPlayer thumbnail and NowPlaying artwork. The corner radius animates automatically because both views declare the same `clipShape(RoundedRectangle(cornerRadius:))` at their respective sizes.

---

## Precedence reminder

If a handoff `swiftui-spec.md` lists a token value that differs from `tokens.json`, **`tokens.json` wins**. Flag the discrepancy as a documentation nit — do not silently adopt the handoff value.
