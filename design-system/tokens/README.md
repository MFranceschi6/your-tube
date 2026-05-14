# Design Tokens

The single source of truth for every design token used by both Android and iOS clients. Per-handoff token tables and inline values in `docs/design-system.md` are deprecated — read from this directory instead.

## Precedence

1. `tokens.json` is the **source of truth**. All other files in this directory mirror it. When values disagree, `tokens.json` wins.
2. `tokens.md` is the human-readable mirror (table form). Regenerate it whenever `tokens.json` changes.
3. `tokens.css` is the web-preview surface used by `design-system/preview/` and the HTML mockups. Custom-property names mirror token names.
4. Per-handoff token values (e.g. duplicated color hex or duration constants inside `compose-spec.md`, `swiftui-spec.md`, or `motion-spec.md`) are **deprecated**. Treat them as historical context; resolve all live questions against `tokens.json`.

## Files

- `tokens.json` — machine-readable source of truth (color, typography, spacing, radius, motion).
- `tokens.md` — human-readable table mirroring `tokens.json`.
- `tokens.css` — CSS custom properties under `[data-theme="dark"]` and `[data-theme="light"]` for web previews.
- `kotlin-codegen-hint.md` — convention for mapping tokens into Material 3 `ColorScheme` and `Dimens.kt` (no codegen yet).
- `swift-codegen-hint.md` — convention for mapping tokens into SwiftUI `Color` extensions and spacing constants (no codegen yet).

## Open work

Light-theme color values currently marked **TBD** in `tokens.json` and `tokens.md` are filled by [[YT-0172]] (light-theme color audit). Do not invent placeholders — leave `"TBD": true` until that task lands.
