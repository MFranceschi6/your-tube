# Kotlin / Android — Token Mapping Convention

> This document tells Android engineers how `design-system/tokens/tokens.json` maps to Material 3 Kotlin code. It does **not** generate code — it documents the convention so engineers and agents produce consistent implementations.

---

## Where platform files live

| Token category | Kotlin home | Notes |
|---|---|---|
| Colors | `android/core/designsystem/src/main/kotlin/com/yourtube/core/designsystem/Color.kt` | Raw palette + semantic role aliases |
| Theme wiring | `android/core/designsystem/src/main/kotlin/com/yourtube/core/designsystem/Theme.kt` | `darkColorScheme()` / `lightColorScheme()` calls |
| Typography | `android/core/designsystem/src/main/kotlin/com/yourtube/core/designsystem/Type.kt` | `Typography` object with named roles |
| Spacing / dimensions | `android/core/designsystem/src/main/kotlin/com/yourtube/core/designsystem/Dimens.kt` | `object Dimens { val SpaceMd = 16.dp … }` |
| Shape | Inline in `Theme.kt` via `Shapes` or per-component `RoundedCornerShape` | Keep radius values aligned with `tokens.json` |

If these files do not yet exist, create them in `core/designsystem`. If the project uses `core/ui` instead, follow the existing convention rather than creating a parallel module.

---

## Color mapping

The token path pattern is: `color.<category>.<name>.dark` → the color used in `darkColorScheme()`.

### Brand accent → `primary`

| `tokens.json` path | Dark hex | Material 3 role |
|---|---|---|
| `color.brand.accent.dark` | `#8B5CF6` | `primary` in dark `ColorScheme` |
| `color.brand.accent-dim.dark` | `#7C3AED` | No direct M3 role — use as pressed overlay or custom semantic color |
| `color.brand.accent-on.dark` | `#FFFFFF` | `onPrimary` |
| `color.brand.accent-subtle.dark` | `#8B5CF620` | `primaryContainer` alpha fill (or custom `surfaceTint`) |

On Android 12+ Material You (`dynamicColorScheme()`) overrides these with the wallpaper palette. The fallback values from `tokens.json` apply on Android < 12 and all non-dynamic themes.

### Surface roles

| `tokens.json` path | Dark value | Material 3 role |
|---|---|---|
| `color.background.base.dark` | `#0F0F0F` | `background` |
| `color.background.surface.dark` | `#1C1C1E` | `surface` |
| `color.background.surface-variant.dark` | `#2C2C2E` | `surfaceVariant` |
| `color.background.surface-high.dark` | `#3A3A3C` | `surfaceContainerHighest` (M3 1.2+) |

### Foreground / content roles

| `tokens.json` path | Dark value | Material 3 role |
|---|---|---|
| `color.foreground.primary.dark` | `#FFFFFF` | `onBackground`, `onSurface` |
| `color.foreground.secondary.dark` | `rgba(235,235,245,0.80)` | `onSurfaceVariant` |
| `color.foreground.tertiary.dark` | `rgba(235,235,245,0.40)` | No direct M3 role — use as custom disabled / placeholder color |

### Semantic roles

| `tokens.json` path | Dark value | Material 3 role |
|---|---|---|
| `color.semantic.error.dark` | `#FF453A` | `error` |
| `color.semantic.error-surface.dark` | `rgba(255,69,58,0.12)` | `errorContainer` (treat as tinted surface) |

---

## Spacing mapping

Spacing tokens map to `Dp` constants. Convention:

```
tokens.json: spacing.md = "16dp"
Kotlin:      object Dimens { val SpaceMd = 16.dp }
```

Full mapping:

| Token | Value | Kotlin constant |
|---|---|---|
| `spacing.xs` | 4 dp | `Dimens.SpaceXs` |
| `spacing.sm` | 8 dp | `Dimens.SpaceSm` |
| `spacing.md` | 16 dp | `Dimens.SpaceMd` |
| `spacing.lg` | 24 dp | `Dimens.SpaceLg` |
| `spacing.xl` | 32 dp | `Dimens.SpaceXl` |
| `spacing.2xl` | 48 dp | `Dimens.Space2xl` |
| `spacing.3xl` | 64 dp | `Dimens.Space3xl` |

Alternatively, use local `val` constants at the composable file level when a value is specific to one component. Prefer `Dimens` for values used across two or more files.

---

## Typography mapping

| Token | M3 typography role | Notes |
|---|---|---|
| `typography.display-large` | `typography.displayLarge` | Now-playing title |
| `typography.title-large` | `typography.titleLarge` | Section headers |
| `typography.title-medium` | `typography.titleMedium` | Screen titles, playlist names |
| `typography.body-large` | `typography.bodyLarge` | Track row title |
| `typography.body-medium` | `typography.bodyMedium` | Channel name, metadata |
| `typography.label-small` | `typography.labelSmall` | Duration, badges, captions |

The token `size` values are for web mockup reference. Kotlin implementations must use `sp` units and honour `FontScale` — never hardcode `px` or `pt`.

---

## Radius mapping

| Token | Value | Kotlin usage |
|---|---|---|
| `radius.xs` | 4 dp | `RoundedCornerShape(4.dp)` |
| `radius.sm` | 8 dp | `RoundedCornerShape(8.dp)` |
| `radius.md` | 12 dp | `MaterialTheme.shapes.medium` (must be configured to 12 dp in `Theme.kt`) |
| `radius.lg` | 16 dp | `RoundedCornerShape(16.dp)` |
| `radius.xl` | 20 dp | `RoundedCornerShape(20.dp)` |
| `radius.pill` | 999 dp | `CircleShape` or `RoundedCornerShape(999.dp)` |

---

## Motion mapping

| Token | Value | Compose API |
|---|---|---|
| `motion.duration.expand` | 320 ms | `tween(durationMillis = 320, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f))` |
| `motion.duration.collapse` | 260 ms | `tween(durationMillis = 260, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f))` |
| `motion.duration.reduce-motion` | 120 ms | `tween(durationMillis = 120)` when reduce-motion detected |
| `motion.easing.standard` | `cubic-bezier(0.2, 0.0, 0.0, 1.0)` | `CubicBezierEasing(0.2f, 0f, 0f, 1f)` |
| `motion.easing.exit` | `cubic-bezier(0.3, 0.0, 0.8, 0.15)` | `CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)` |
| `motion.delay.controls-fade-in` | 200 ms | `tween(delayMillis = 200)` |

Reduce-motion detection: read both `Settings.Global.TRANSITION_ANIMATION_SCALE` and `Settings.Global.ANIMATOR_DURATION_SCALE`; treat either being `0f` as reduce-motion active.

---

## Precedence reminder

If a handoff `compose-spec.md` lists a token value that differs from `tokens.json`, **`tokens.json` wins**. Flag the discrepancy as a documentation nit when you find it — do not silently adopt the handoff value.
