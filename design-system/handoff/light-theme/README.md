# YT-0172 — Light theme audit + canonical light/dark token table

> **Audience.** Claude Code and downstream implementation tasks (Android `core/designsystem`, iOS asset catalog + `Color` extensions, web previews). Read first when wiring any light-theme surface, when filling a `null` / `TBD` cell in `design-system/tokens/tokens.json`, or when verifying a light-mode contrast claim.
>
> **Status.** YT-0172 closes the gap left by `docs/design-system.md` § Color ("Light theme supported, follows system") — no mockup, no token table, no contrast verification existed before this package. It does **not** replace `design-system/tokens/` — it consumes it, fills its `TBD` cells, audits every surface, and ships a light-theme mockup.
>
> **Not a binding visual spec.** Match the *tokens*, *contrast guarantees*, and *cell-by-cell parity rules*. Use idiomatic M3 light tones on Android and iOS system semantic colors where possible — never port web CSS hex literally onto either platform.

---

## Precedence

When values in two places disagree, the higher row wins.

| Rank | Source | Notes |
|---|---|---|
| 1 | `design-system/tokens/tokens.json` | Single source of truth. After this package lands, `tokens-additions.json` is merged into it and the `TBD` cells are gone. |
| 2 | `design-system/handoff/light-theme/tokens.md` | Canonical light/dark mapping table — human-readable mirror with `M3 / SF` ancestry per row. |
| 3 | `design-system/handoff/light-theme/audit.md` | Per-screen contrast verification + defect log. Cited when an implementer questions a token's fitness for a specific surface. |
| 4 | `design-system/handoff/light-theme/state-catalog-light-overlay.md` | Per-cell (C1–C16) light-theme rule — confirms automatic follow OR records an explicit divergence. |
| 5 | `design-system/handoff/light-theme/component-light.html` | Light-theme component reference. Intent, not pixel spec. |
| 6 | `design-system/colors_and_type.css` § "Light theme overrides" | **Deprecated** for new work — kept as a web-preview fallback. Diverges from `tokens.json` in one place today (light `fg-secondary` alpha — see `audit.md` § Defects). Will be re-generated from `tokens.json` once codegen lands; do not edit by hand. |
| 7 | Inline light hex literals in `compose-spec.md` / `swiftui-spec.md` / `motion-spec.md` of any prior handoff | Historical context only. Resolve against rank 1. |

---

## What's in this folder

| File | Purpose |
|---|---|
| `README.md` | This file. Audience, precedence, file index. |
| `tokens.md` | Canonical mapping table — every required token with dark hex, light hex, M3 / SF semantic ancestor (`from`), and a one-line description. Organized by category. Machine-friendly for future lint. |
| `audit.md` | Per-screen walk: Search, Library, PlaylistDetail, History, Now Playing, MiniPlayer, Settings. Surface stack, light treatment, contrast ratios per surface (title vs bg, body vs bg, action label vs accent fill, error icon vs bg). Defects flagged with proposed fix. |
| `state-catalog-light-overlay.md` | C1–C16 confirm / override sheet. Default rule: light follows tokens. Explicit divergences for skeleton shimmer (Δ alpha) and error icon (no Δ — flagged on title/body color, not glyph). |
| `component-light.html` | Every production component rendered in light theme on iOS-393 + Android-360 frames. TrackRow, MiniPlayer, NowPlaying chrome, SearchBar, EmptyState, ErrorState, AlertDialog, BottomSheet, Snackbar. Mirrors the dark mockup style under `design-system/preview/`. |
| `tokens-additions.json` | Light values to **merge** into `design-system/tokens/tokens.json` — resolves every `{ "TBD": true }` cell and corrects the one secondary-alpha defect. Same shape; intended for direct merge. |
| `docs-design-system.patch.md` | Proposed patch to `docs/design-system.md` § Color. Retains existing dark-only description, points at this package's `tokens.md` and `audit.md`. |

---

## Decisions recorded in this package

1. **Theme follow strategy** — track system by default. Override lives in **Settings → Appearance → Theme** with three options: System (default), Light, Dark. Android binds to `Configuration.uiMode` (resource qualifier `night`); iOS binds to `UIUserInterfaceStyle` via `overrideUserInterfaceStyle` on the root scene. Web previews bind to `prefers-color-scheme` with a `[data-theme="light"]` override hook. Documented in `audit.md` § Settings.
2. **Material You / dynamic color in light** — M3-generated light scheme **must** be probed at runtime against `onPrimary` vs `primary` ≥ 4.5:1. If the user's wallpaper produces a primary that fails, fall back to brand-purple light variant `#7C3AED` (not `#8B5CF6`). Documented in `audit.md` § Decisions and `state-catalog-light-overlay.md` (C4 action).
3. **iOS accent in light** — Asset catalog ships two variants: Any Appearance `#8B5CF6`, Light `#7C3AED`. `Color.accentColor` resolves to the light variant in light, the brand variant in dark. Reason: `#8B5CF6` on `Color(.systemBackground)` (white) = **4.23:1** → fails 4.5:1 body floor for action labels. `#7C3AED` = **5.70:1** ✅. Documented in `audit.md` § Defects → D-2.
4. **Skeleton tokens in light** — Confirms `--skeleton-bg: #E5E5EA` and `--skeleton-shimmer: rgba(255,255,255,0.60)` from `tokens.json`. Shimmer alpha is much higher than dark (0.06) because in light the shimmer is a *highlight*, not a fog — the contrast it needs against `#E5E5EA` runs in the opposite direction. Documented in `state-catalog-light-overlay.md` → all loading cells.
5. **Error icon in light** — Renders at `--color-fg-tertiary` (= `rgba(60,60,67,0.30)` composite ~`#BCBCC0` on `#F2F2F7`), matching the dark-theme rule "error icon is NOT red — the title carries the affordance." The glyph contrast is intentionally low (1.7:1); the title at `--color-fg-primary` carries 19:1. Documented in `state-catalog-light-overlay.md` (C4, C5, C8, C11, C14, C16).
6. **Material elevation in light** — M3 tonal elevation in light shifts toward the *primary* tone, which would muddy `surface-container` against `surface` when the primary is a saturated violet. We cap tonal elevation at **Level 1** (5% primary tint) for `MiniPlayer` and `BottomSheet` and reach for `shadow.sm` / `shadow.md` for the visual lift instead. Documented in `audit.md` § Decisions and the elevation table.

---

## Out of scope

- Codegen from `tokens.json` to Kotlin / Swift (post-MVP — see `tokens/kotlin-codegen-hint.md`, `tokens/swift-codegen-hint.md`).
- Typography or font subsetting (light theme reuses dark theme's `typography.*` block verbatim).
- Per-component Compose / SwiftUI implementation — those are downstream tasks scoped per epic. This package is contract, not code.
- Reduce-motion or VoiceOver / TalkBack rules — those are theme-independent. See `design-system/handoff/state-catalog/README.md` and `design-system/handoff/YT-0074/motion-spec.md`.
- Toast / Snackbar copy — see `design-system/handoff/toast-catalog/`. This package only re-themes the surface and verifies contrast.

---

## Cross-references

- `design-system/tokens/` — token source of truth (merge target for `tokens-additions.json`).
- `design-system/handoff/state-catalog/` — cell IDs C1–C16, overridden per cell in `state-catalog-light-overlay.md`.
- `design-system/handoff/symbol-map/` — semantic action ↔ glyph; light mode does not alter the symbol map.
- `design-system/handoff/toast-catalog/` — toast copy is theme-independent.
- `design-system/handoff/YT-0011`, `YT-0013`, `YT-0014`, `YT-0025`, `YT-0027`, `YT-0028` — per-screen specs. Their inline light hex literals (where present) are deprecated; defer to `tokens.md`.
- `docs/design-system.md` § Color — patched by `docs-design-system.patch.md` to point at this package.
