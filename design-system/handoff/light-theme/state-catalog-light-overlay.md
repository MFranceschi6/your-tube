# State Catalog — Light Theme Overlay

> **Scope.** Per-cell overlay sheet for the 16 cells C1–C16 defined in `design-system/handoff/state-catalog/README.md`. For each cell, this file either:
>
> - **Confirms** the default rule: *"Light theme follows tokens automatically without per-cell adjustment."* Marked `✅ follow`.
> - **Diverges** with an explicit replacement: a different token, alpha, or treatment in light. Marked `⚠ override` with the exact change.
>
> When a cell is `✅ follow`, the implementer reads tokens via `MaterialTheme.colorScheme` (Compose) or `Color(.systemBackground)` / `Color.accentColor` (SwiftUI) and gets the light treatment for free. When `⚠ override`, the implementer reads the override **from this file**, not from a fork of the cell.
>
> **Tokens referenced here are the post-`tokens-additions.json` values.** That includes the D-1 secondary-alpha fix.

---

## Default rule (cited per cell, never re-derived)

In light, every cell's surface comes from `tokens.json` (with `tokens-additions.json` merged). The rules that already hold in dark and continue to hold in light:

1. Background is `color.background.base` (light: `#F2F2F7`).
2. Empty / error card on iOS form is `color.background.surface` (light: `#FFFFFF`).
3. Title is `color.foreground.primary` (light: `#000`, 18.82–21.00:1).
4. Body is `color.foreground.secondary` 80% (light, post D-1 fix: 5.59–5.97:1).
5. Action button is `color.brand.accent` (light: `#7C3AED`, 5.70:1 on white text).
6. Empty / error icon is `color.foreground.tertiary` (light: `rgba(60,60,67,0.30)`, decorative — title carries meaning).
7. Skeleton fill is `color.skeleton.bg` (light: `#E5E5EA`); shimmer is `color.skeleton.shimmer` (light: `rgba(255,255,255,0.60)`, **inverted direction** vs dark).
8. Icon sizes (56 dp/pt empty/error), shimmer cycle (1400 ms linear), and the "error icon is NOT red" rule are theme-invariant.

---

## Per-cell sheet

### Search

#### C1 — Search · Loading (skeleton × 6)

- **Verdict:** `✅ follow`
- **Light treatment:** Six skeleton rows on `bg` `#F2F2F7`. Each row's thumbnail and text bars use `skeleton.bg` `#E5E5EA` with the 0.60-alpha shimmer wiping left-to-right at 1400 ms.
- **Why this works:** `#E5E5EA` on `#F2F2F7` is **1.04:1** — barely distinguishable by hex. The shimmer (high-alpha white) is what reads as motion against the slightly cooler `#E5E5EA`. This is intentional; the row outlines are not the signal, the shimmer is. Verified in `component-light.html`.
- **No override.**

#### C2 — Search · Empty (idle / chips)

- **Verdict:** `✅ follow`
- **Light treatment:** "Search YourTube" headline + `Try` chips. Chips render as `surface-variant` `#F2F2F7` pills (same hex as `bg`, defined by their `999px` radius and `outline-variant` `#D1D1D6` border). Chip label uses `fg-primary` `#000`.
- **No override.**

#### C3 — Search · Empty (no results for `<query>`)

- **Verdict:** `✅ follow`
- **Light treatment:** `magnifyingglass` glyph at `fg-tertiary`, "No results" title at `fg-primary`, body at `fg-secondary` 80%. No accent button (per state-catalog § C3 — Search no-results does not retry; user re-edits query).
- **No override.**

#### C4 — Search · Error (generic)

- **Verdict:** `✅ follow`
- **Light treatment:** Error glyph at `fg-tertiary` (1.71:1 on `bg` — decorative); `Try Again` button uses `accent` `#7C3AED` fill + `on-accent` white (5.70:1 ✅).
- **No override.** Note: on Android, if Material You produces a light primary that fails the runtime 4.5:1 probe, fall back to `#7C3AED` per decision #2.

#### C5 — Search · Error (offline)

- **Verdict:** `✅ follow`
- **Light treatment:** `wifi.slash` glyph at `fg-tertiary`. Body offers "Recent searches" affordance — recent-search chips reuse C2 chip styling.
- **No override.**

### Library

#### C6 — Library · Loading (skeleton × 4)

- **Verdict:** `✅ follow`
- Same skeleton rule as C1. Four rows (playlist row height ≠ track row height; the skeleton template differs, but the tokens do not).

#### C7 — Library · Empty (no playlists)

- **Verdict:** `✅ follow`
- `library_music` / `music.note.list` glyph at `fg-tertiary`, title + body, primary action `Create a playlist` on `accent` `#7C3AED`.

#### C8 — Library · Error (generic)

- **Verdict:** `✅ follow`

### PlaylistDetail

#### C9 — PlaylistDetail · Loading (skeleton header + × 6)

- **Verdict:** `✅ follow`
- **Light treatment:** Skeleton header is a 240-pt square `skeleton.bg` block with shimmer; meta lines are skeleton bars. Six track-row skeletons follow.
- **No override.**

#### C10 — PlaylistDetail · Empty (empty playlist)

- **Verdict:** `✅ follow`
- `music.note` glyph at `fg-tertiary`. Action: `Find songs to add` on `accent`.

#### C11 — PlaylistDetail · Error (generic)

- **Verdict:** `⚠ override` (one row, see audit D-3)
- **Light treatment:** The empty/error card itself follows the default rule. **The `Delete playlist` row inside the playlist's overflow** (rendered in light only — dark renders the same destructive row) must use **Path A** of D-3: label at `fg-primary` `#000` with a leading `trash` glyph tinted `error` `#FF3B30`. **Do not render the label itself in red on light.** This is an in-playlist concern, surfaced here because C11 is the screen's error variant and shares the overflow chrome.
- **Override summary:** `Delete playlist` label color: `fg-primary` (light) instead of `error` (dark). Glyph tint remains `error` on both themes.

### History

#### C12 — History · Loading (skeleton × 6)

- **Verdict:** `✅ follow`

#### C13 — History · Empty (nothing played yet)

- **Verdict:** `✅ follow`
- `clock.arrow.circlepath` / `history` glyph at `fg-tertiary`.

#### C14 — History · Error (generic)

- **Verdict:** `✅ follow`

### Settings

#### C15 — Settings · Loading (overlay spinner)

- **Verdict:** `⚠ override` (scrim alpha)
- **Light treatment:** The overlay sits over the Settings form. Use `scrim` `rgba(0,0,0,0.35)` (light value already in `tokens.json` — no addition needed) instead of the dark `rgba(0,0,0,0.45)`. The spinner color is `fg-primary` `#000` on the scrim (not white as in dark — white on a translucent black over light becomes ambiguous).
- **Override summary:** Spinner glyph color = `fg-primary` (= `#000` in light), not `accent` or `#FFF`. The platform `ProgressView()` / `CircularProgressIndicator()` honors `tint` — set tint to `Color.primary` (iOS) / `MaterialTheme.colorScheme.onSurface` (Android).

#### C16 — Settings · Error (operation failed)

- **Verdict:** `✅ follow`
- Inline error appears below the row whose operation failed (Export / Import / Clear cache / Clear history). Title + retry button identical to C4 / C8.

---

## Skeleton — explicit token confirmation (decision #4)

| Token | Dark | Light | Verified ratio against block fill |
|---|---|---|---|
| `color.skeleton.bg` | `#2C2C2E` | `#E5E5EA` | n/a (block fill itself) |
| `color.skeleton.shimmer` | `rgba(255,255,255,0.06)` | `rgba(255,255,255,0.60)` | Δ luminance vs `#E5E5EA` = **+0.10**, perceptible as a wipe |

The light shimmer's 0.60 alpha is **load-bearing** — a 0.10 alpha (mirror of dark) is invisible against `#E5E5EA`. This is the single largest dark/light asymmetry in the system and is intentional.

---

## Error icon — explicit confirmation (decision #5)

For every error cell (C4, C5, C8, C11, C14, C16), the icon glyph uses `color.foreground.tertiary`:

- Light composite: `rgba(60,60,67,0.30)` on `#F2F2F7` ≈ `#BCBCC0` → 1.71:1.
- Dark composite: `rgba(235,235,245,0.40)` on `#0F0F0F` ≈ `#5E5E60` → 4.16:1.

The light-theme glyph contrast is **below** the 3:1 non-text floor. **This is acceptable** because the title at 19:1 carries the affordance and the glyph is registered as decorative per `state-catalog/README.md` § Accessibility → "Icons are `accessibilityHidden = true`. The title carries the meaning."

If a future implementer is pressured to "fix" the low glyph contrast by red-tinting it, refer them back to `state-catalog/README.md` → "Error icon is NOT red. Red would imply destructive or system-level alarm." This rule survives the theme transition.

---

## Cells with override — summary

| Cell | Override | One-line reason |
|---|---|---|
| C11 | Destructive label in overflow uses `fg-primary` + `error` glyph, not `error` label. | Light: 3.55:1 fails body 4.5; HIG pattern uses black label + red glyph. |
| C15 | Spinner tint is `fg-primary`, scrim uses light `0.35` value. | White-on-translucent-black-on-light is ambiguous. |

All 14 remaining cells follow the default rule without adjustment.
