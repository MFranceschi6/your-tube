# Canonical Light / Dark Token Table — YT-0172

> **Mirror.** This table is derived from `design-system/tokens/tokens.json` after `tokens-additions.json` is merged. When values disagree, `tokens.json` wins.
>
> **`from` column.** Each row records its semantic ancestor on each platform so an implementer knows whether to read it from a Material 3 `ColorScheme`, an iOS system color, or hold the literal hex. `—` = no system equivalent; hold the literal.
>
> **Required-coverage tokens** (per YT-0172 acceptance criteria) are marked with a leading `★`. Every other row is included because it appears on a production surface and must be themed in lockstep.

---

## Color — Background / Surface

| Token | Category | Dark | Light | `from` (M3 / SF) | Description |
|---|---|---|---|---|---|
| ★ `color.background.base` | background | `#0F0F0F` | `#F2F2F7` | M3 `background` / SF `systemGroupedBackground` | Base page / screen background |
| ★ `color.background.surface` | surface | `#1C1C1E` | `#FFFFFF` | M3 `surface` / SF `systemBackground` | Cards, sheets, MiniPlayer container |
| ★ `color.background.surface-variant` | surface-variant | `#2C2C2E` | `#F2F2F7` | M3 `surfaceVariant` / SF `secondarySystemBackground` | List row hover/press, SearchBar fill |
| ★ `color.background.surface-container` ¹ | surface-container | `#1C1C1E` | `#FFFFFF` | M3 `surfaceContainer` | Alias of `surface` in our scheme — see note |
| ★ `color.background.surface-container-highest` ¹ | surface-container-highest | `#3A3A3C` | `#E5E5EA` | M3 `surfaceContainerHighest` | Alias of `surface-high` — see note |
| `color.background.surface-high` | surface-high | `#3A3A3C` | `#E5E5EA` | M3 `surfaceContainerHighest` / SF `tertiarySystemBackground` | Highest-elevation surface |

> ¹ **M3 alias rows.** YourTube's token vocabulary predates the full M3 v3 container scale. `surface-container` ≡ `surface`; `surface-container-highest` ≡ `surface-high`. The aliases are listed so downstream tasks can use M3 names without a translation layer.

---

## Color — Foreground / Text

| Token | Category | Dark | Light | `from` (M3 / SF) | Description |
|---|---|---|---|---|---|
| ★ `color.foreground.primary` (alias `on-surface`) | on-surface | `#FFFFFF` | `#000000` | M3 `onSurface` / SF `label` | Primary text — titles, headlines |
| ★ `color.foreground.secondary` (alias `on-surface-variant`) | on-surface-variant | `rgba(235,235,245,0.80)` | `rgba(60,60,67,0.80)` ² | M3 `onSurfaceVariant` / SF `secondaryLabel` ² | Channel names, body metadata. **Light alpha bumped 0.60 → 0.80** to satisfy 4.5:1 body floor — see `audit.md` § Defects D-1. |
| ★ `color.foreground.tertiary` | tertiary | `rgba(235,235,245,0.40)` | `rgba(60,60,67,0.30)` | SF `tertiaryLabel` / M3 `onSurfaceVariant` @ 0.38 | Placeholders, disabled text, empty/error icon glyph. Used for **non-essential UI only** (3:1 floor does not apply — title carries meaning per state-catalog rule). |

> ² **Divergence from SF `secondaryLabel`.** Apple's `secondaryLabel` light value is `rgba(60,60,67,0.60)` which gives 3.29:1 on `systemGroupedBackground` — below the 4.5:1 body floor. We deliberately diverge for body parity with dark; documented as a recorded exception in `swift-codegen-hint.md` once codegen lands.

---

## Color — Brand / Primary

| Token | Category | Dark | Light | `from` (M3 / SF) | Description |
|---|---|---|---|---|---|
| ★ `color.brand.accent` (alias `primary`) | primary | `#8B5CF6` | `#7C3AED` ³ | M3 `primary` / SF `accentColor` | Primary interactive — buttons, links, scrubber fill |
| ★ `color.brand.accent-on` (alias `on-primary`) | on-primary | `#FFFFFF` | `#FFFFFF` | M3 `onPrimary` / SF `Color.white` on `.tint` | Text/icon on accent background |
| ★ `color.brand.accent-subtle` (alias `primary-container`) | primary-container | `#8B5CF620` | `#7C3AED20` | M3 `primaryContainer` (low-emphasis variant) | Tinted backgrounds behind accent elements (12% alpha) |
| ★ `color.brand.accent-on-subtle` (alias `on-primary-container`) | on-primary-container | `#B794F6` | `#6D28D9` ⁴ | M3 `onPrimaryContainer` | Text on `primary-container`. Light: must satisfy 4.5:1 on `#F2F2F7`-tinted surface. |
| `color.brand.accent-dim` | primary-dim | `#7C3AED` | `#6D28D9` | M3 `primary` @ pressed / SF `.tint` @ 0.85 | Pressed / active state of accent |

> ³ **Light accent rule.** Brand purple `#8B5CF6` on `#FFFFFF` = **4.23:1** — fails 4.5:1 for body-sized text on an accent fill. `#7C3AED` = **5.70:1** ✅. iOS asset catalog ships both variants; Android falls back to `#7C3AED` when Material You dynamic color fails the same probe.
>
> ⁴ `#6D28D9` on `rgba(124,58,237,0.12)` composited on `#F2F2F7` ≈ effective bg `#EAE0FB` → contrast ≈ 7.1:1 ✅.

---

## Color — Secondary / Tertiary (M3 fields)

YourTube does not currently use a distinct secondary color family — the brand uses a single accent. The M3 secondary/tertiary slots are populated as aliases of brand so M3 `ColorScheme` can be filled without `null`s.

| Token | Category | Dark | Light | `from` (M3) | Description |
|---|---|---|---|---|---|
| ★ `color.brand.secondary` (alias) | secondary | `#8B5CF6` | `#7C3AED` | M3 `secondary` ≡ `primary` | Aliased to brand — no distinct secondary in YourTube's vocabulary. |
| ★ `color.brand.on-secondary` (alias) | on-secondary | `#FFFFFF` | `#FFFFFF` | M3 `onSecondary` | Same as `on-primary`. |
| ★ `color.brand.tertiary` (alias) | tertiary | `#8B5CF6` | `#7C3AED` | M3 `tertiary` ≡ `primary` | Aliased to brand. |
| ★ `color.brand.on-tertiary` (alias) | on-tertiary | `#FFFFFF` | `#FFFFFF` | M3 `onTertiary` | Same as `on-primary`. |

---

## Color — Semantic

| Token | Category | Dark | Light | `from` (M3 / SF) | Description |
|---|---|---|---|---|---|
| ★ `color.semantic.error` | error | `#FF453A` | `#FF3B30` | M3 `error` / SF `systemRed` | Destructive actions, error icons (when red is required), remove tint in edit mode |
| ★ `color.semantic.on-error` | on-error | `#FFFFFF` | `#FFFFFF` | M3 `onError` | Text on error fill (e.g. destructive confirm button) |
| `color.semantic.error-surface` | error-container | `rgba(255,69,58,0.12)` | `rgba(255,59,48,0.10)` | M3 `errorContainer` | Tinted background behind error content |
| `color.semantic.success` | success | `#30D158` | `#34C759` | SF `systemGreen` | Download complete, positive confirmation |
| `color.semantic.success-surface` | success-container | `rgba(48,209,88,0.12)` | `rgba(52,199,89,0.10)` | — | Tinted background behind success content |
| `color.semantic.warning` | warning | `#FFD60A` | `#B45309` ⁵ | M3 `tertiary` (warning slot) / no SF equivalent | Warning notices. **TBD resolved** — see `tokens-additions.json`. |

> ⁵ `#FFD60A` on `#FFFFFF` = **1.49:1** — unreadable for any text use in light. `#B45309` (amber-700) = **5.42:1** ✅. The warning *surface* in light tints the background at 12% of `#B45309` to retain the warm hue.

---

## Color — Outline (border)

| Token | Category | Dark | Light | `from` (M3 / SF) | Description |
|---|---|---|---|---|---|
| ★ `color.border.subtle` (alias `outline-variant`) | outline-variant | `#3A3A3C` | `#D1D1D6` | M3 `outlineVariant` / SF `separator` | Dividers, subtle outlines |
| ★ `color.border.strong` (alias `outline`) | outline | `#636366` | `#8E8E93` | M3 `outline` / SF `opaqueSeparator` | Active field borders, focused rings |

---

## Color — Overlay / Scrim

| Token | Category | Dark | Light | `from` (M3 / SF) | Description |
|---|---|---|---|---|---|
| ★ `color.overlay.sheet` (alias `scrim`) | scrim | `rgba(0,0,0,0.45)` | `rgba(0,0,0,0.35)` | M3 `scrim` / SF `Color.black.opacity(...)` | Modal backdrop |
| `color.overlay.artwork` | scrim-artwork | `rgba(0,0,0,0.60)` | `rgba(0,0,0,0.55)` | — | Scrim on full-bleed artwork backgrounds |

---

## Color — Skeleton (loading)

| Token | Category | Dark | Light | `from` (M3 / SF) | Description |
|---|---|---|---|---|---|
| ★ `color.skeleton.bg` | skeleton | `#2C2C2E` | `#E5E5EA` | M3 `surfaceContainer` (light) / SF `tertiarySystemFill` | Skeleton loading block fill |
| ★ `color.skeleton.shimmer` | skeleton-shimmer | `rgba(255,255,255,0.06)` | `rgba(255,255,255,0.60)` ⁶ | — | Shimmer overlay on skeleton blocks |

> ⁶ **Shimmer inversion.** In dark, the shimmer is a faint highlight on a dark block (0.06 alpha). In light, the shimmer is a strong highlight on a grey block (0.60 alpha) — opposite contrast direction. Both directions read as a left-to-right wipe of brightness; the absolute alpha differs by design.

---

## Color — Scrubber

| Token | Category | Dark | Light | `from` (M3 / SF) | Description |
|---|---|---|---|---|---|
| `color.scrubber.track` | scrubber | `#3A3A3C` | `#D1D1D6` | SF `Slider` minimumTrackTintColor | Unfilled track |
| `color.scrubber.fill` | scrubber-fill | `#8B5CF6` | `#7C3AED` | SF `tintColor` | Filled portion (= `brand.accent`) |
| `color.scrubber.thumb` | scrubber-thumb | `#FFFFFF` | `#FFFFFF` ⁷ | — | Draggable thumb. **TBD resolved.** Pair with `shadow.sm` for definition on light backgrounds. |

> ⁷ The thumb is a 16×16 circle centered on the playhead. In light, its left half sits on `#7C3AED` (5.7:1 vs white) and its right half on `#D1D1D6` (1.27:1 vs white — undefined). The mandatory `shadow.sm` (`0 1px 4px rgba(0,0,0,0.08)`) under the thumb resolves the unfilled-side definition. Without the shadow, the thumb disappears at the right edge.

---

## Color — Motion-scrim (MiniPlayer ↔ NowPlaying transition)

| Token | Category | Dark | Light | `from` | Description |
|---|---|---|---|---|---|
| `color.motion-scrim.miniplayer` | motion-scrim | `#1C1C1E` | `#FFFFFF` | = `surface` | MiniPlayer fill at expand start. **TBD resolved.** |
| `color.motion-scrim.nowplaying` | motion-scrim | `#0F0F0F` | `#F2F2F7` | = `background.base` | NowPlaying base at expand end. **TBD resolved.** |

---

## Shadow (carried for reference)

| Token | Dark | Light | Notes |
|---|---|---|---|
| `shadow.sm` | `0 1px 4px rgba(0,0,0,0.20)` | `0 1px 4px rgba(0,0,0,0.08)` | Subtle lift |
| `shadow.md` | `0 4px 16px rgba(0,0,0,0.40)` | `0 4px 16px rgba(0,0,0,0.12)` | Cards |
| `shadow.lg` | `0 8px 32px rgba(0,0,0,0.60)` | `0 8px 32px rgba(0,0,0,0.20)` | Sheets, NowPlaying |

Shadows do **more work** in light (the only edge definition for floating chrome — M3 tonal elevation is capped at Level 1 per decision #6 in `README.md`).

---

## Material elevation in light — explicit table

M3 v3 tonal elevation applies a primary tint at increasing alpha. With YourTube's saturated violet primary, this muddies `surfaceContainer` against `surface` in light. We **cap tonal elevation at Level 1** in light and reach for `shadow.*` instead.

| M3 Level | Tint α (M3 default) | YourTube light fill | YourTube light shadow |
|---|---|---|---|
| 0 (surface) | 0% | `#FFFFFF` | none |
| 1 (surfaceContainerLow) | 5% primary | `#FFFFFF` (capped) | `shadow.sm` |
| 2 (surfaceContainer) | 8% primary | `#FFFFFF` (capped, alias of L1) | `shadow.sm` |
| 3 (surfaceContainerHigh) | 11% primary | `#F2F2F7` | `shadow.md` |
| 4 (surfaceContainerHighest) | 12% primary | `#E5E5EA` | `shadow.md` |
| 5 (bottom sheet rest) | 14% primary | `#FFFFFF` | `shadow.lg` |

In dark, M3 tonal elevation works as M3 intends — surface gets lighter as it elevates, no shadow needed below Level 4. The asymmetry is intentional.

---

## Coverage check

The minimum-coverage list in YT-0172 (background, surface, surface-variant, surface-container, surface-container-highest, on-surface, on-surface-variant, primary, on-primary, primary-container, on-primary-container, secondary, on-secondary, tertiary, on-tertiary, error, on-error, outline, outline-variant, scrim, skeleton-bg, skeleton-shimmer, fg-primary, fg-secondary, fg-tertiary) is fully covered above — each name appears either as the row's token name or as an explicit `alias` annotation. The `★` markers index the required rows.
