# Proposed patch — `docs/design-system.md` § Color

> **What.** Update the § Color block in `docs/design-system.md` to point at this handoff package while retaining the existing dark-first description.
>
> **How to apply.** Replace the existing § Color block (lines reproduced below in "Before") with the "After" block. Other sections (`### Typography`, `### Spacing`, `### Iconography`) keep their existing wording — they already point at `design-system/tokens/` and need no change.
>
> **Why.** YT-0172 closes the audit gap. Today the doc says light is "supported, follows system" with no link; the After block points at the canonical mapping and the verified audit. No token values are introduced into this doc — token values stay in `tokens.json` per the established precedence rule.

---

## Before

````markdown
### Color
- **Dark-first.** Default theme is dark. Light theme supported, follows system.
- **Accent**: per-platform system tint. Android uses Material 3 dynamic color (Material You) on Android 12+ with the brand purple as fallback; iOS uses `Color.accentColor` driven from the asset catalog with the brand purple as default.
- Surfaces follow a `background` / `surface` / `surfaceVariant` hierarchy with semantic `error`, `success`, and `onX` content-on-surface pairs.

Source of truth: `design-system/tokens/` — see `tokens.json` (machine-readable) and `tokens.md` (table). Do not duplicate token values here.
````

## After

````markdown
### Color
- **Dark-first.** Default theme is dark. Light theme is at parity with dark, audited per surface, and tracks the system setting by default. A three-option override (System / Light / Dark) lives in **Settings → Appearance → Theme**.
- **Accent**: per-platform system tint. Android uses Material 3 dynamic color (Material You) on Android 12+ with the brand purple as the fallback; the runtime probes the generated primary against the 4.5:1 floor and falls back to `#7C3AED` if Material You fails. iOS uses `Color.accentColor` driven from the asset catalog — ships two variants (Any Appearance `#8B5CF6`, Light `#7C3AED`) so the accent stays above the 4.5:1 floor on white.
- Surfaces follow a `background` / `surface` / `surface-variant` / `surface-container` / `surface-container-highest` hierarchy with semantic `error`, `success`, `warning`, and `on-*` content-on-surface pairs. M3 names alias to YourTube names per the canonical table.
- **M3 tonal elevation in light is capped at Level 1.** Above Level 1 we use `shadow.*` instead — saturated primary tint at M3 Levels 2–5 muddies surface separation in light.

Source of truth: `design-system/tokens/` — see `tokens.json` (machine-readable) and `tokens.md` (table). Do not duplicate token values here.

**Light-theme audit + canonical mapping:** `design-system/handoff/light-theme/`
- `tokens.md` — dark / light / M3 / SF ancestry per token, organized by category.
- `audit.md` — per-screen surface stack and contrast verification (Search, Library, PlaylistDetail, History, NowPlaying, MiniPlayer, Settings). Defects flagged with proposed fix.
- `state-catalog-light-overlay.md` — per-cell (C1–C16) confirm/override sheet.
- `component-light.html` — every production component rendered in light theme on both device frames.
````

---

## Inventory of edits

| Edit | Type | Reason |
|---|---|---|
| Bullet 1 reworded ("at parity with dark, audited per surface, … System / Light / Dark") | substitution | "Supported, follows system" was the gap YT-0172 closes. New wording records decision #1 from `README.md`. |
| Bullet 2 reworded (adds 4.5:1 probe + iOS dual-variant asset catalog rule) | substitution | Records decisions #2 and #3 from `README.md`. |
| Bullet 3 reworded (adds surface-container scale + warning) | substitution | Reflects new minimum-coverage token set from YT-0172 acceptance criteria. |
| New bullet 4 (M3 elevation cap) | addition | Records decision #6 — surfaces in `audit.md` § Material elevation in light. |
| New bottom block (Light-theme audit + canonical mapping) | addition | Adds the four required cross-references. |
| `tokens/` source-of-truth pointer | unchanged | The existing precedence rule still holds — this patch points readers downstream, doesn't duplicate values. |

No other section of `docs/design-system.md` requires a change. The `## Empty / loading / error` block at the bottom already cites `state-catalog/`; it does **not** need to mention `state-catalog-light-overlay.md` because the overlay is a sub-document of the catalog, not a peer.

---

## Verification after merge

1. `docs/design-system.md` § Color renders four bullets + a "Light-theme audit + canonical mapping" sub-block with four links.
2. The links resolve to files in `design-system/handoff/light-theme/`.
3. No token hex literals appear in § Color (precedence rule preserved).
4. Acceptance criterion "patch updating § Color to point at the new audit, retaining existing dark-only descriptions but referencing the new light table" is satisfied — the dark-first sentence is retained; the audit + table are now linked.
