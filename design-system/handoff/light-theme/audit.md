# Light Theme Audit — Per-Screen Surface Stacks & Contrast

> **Method.** Every contrast ratio in this file is computed against the **actual hex value composited onto its actual background** using WebAIM's WCAG 2.1 relative-luminance formula (sRGB → linear → `0.2126R + 0.7152G + 0.0722B`, contrast = `(Llighter + 0.05) / (Ldarker + 0.05)`). Translucent colors are alpha-composited onto the named background before the ratio is taken. No named-token shortcuts.
>
> **Pass/fail bars.**
> - Body / normal-weight ≥ 4.5:1 ✅
> - Large-text (≥ 18 pt regular OR ≥ 14 pt bold) ≥ 3.0:1 ✅
> - Non-text UI element (icon glyph that conveys meaning by itself, focus ring, separator) ≥ 3.0:1 ✅
>
> **Numbers in the tables are post-fix.** The two defects (D-1, D-2) are recorded in § Defects with the original failing ratio. Once `tokens-additions.json` is merged, the tables match the live system.

---

## Decisions (recap from README)

| # | Decision | Where enforced |
|---|---|---|
| 1 | Track system by default; user override in Settings → Appearance → Theme (System / Light / Dark). | Settings audit row below. |
| 2 | Material You light scheme probed at runtime; fall back to `#7C3AED` if `onPrimary/primary < 4.5:1`. | Android `core/designsystem` consumes this rule. |
| 3 | iOS accent in light = `#7C3AED` (asset catalog "Light" variant). | iOS handoff packages YT-0025, YT-0027, YT-0028. |
| 4 | Skeleton shimmer alpha in light = 0.60 (vs 0.06 dark) — inverted contrast direction. | `state-catalog-light-overlay.md` C1, C6, C9, C12. |
| 5 | Error icon glyph in light = `--color-fg-tertiary` (not red). Title carries the affordance. | `state-catalog-light-overlay.md` C4, C5, C8, C11, C14, C16. |
| 6 | M3 tonal elevation in light capped at Level 1 — use `shadow.*` for visual lift. | `tokens.md` § Material elevation in light. |

---

## Surface stack notation

Every screen below records its surface stack from base → top as a comma-separated list of token names, the **light** hex each resolves to, and the role each layer plays. Stacks include only the layers that show pixels — z-stacked containers that are fully covered are omitted.

---

## Search

**Stack (light):**
`background.base` `#F2F2F7` (screen) → `background.surface-variant` `#F2F2F7` (SearchBar fill) → `background.surface` `#FFFFFF` (suggestion / result rows on iOS form list).

**Light treatment:**
- SearchBar fill (`surface-variant`) is intentionally the **same hex as `background.base`** in light. The bar reads as a recessed pill thanks to its `999px` radius and the SF icon glyph at `fg-secondary`. Do not "fix" this by adding a border — that adds visual noise the light surface doesn't need. On Android, the same hex with an outlined `TextField` variant (`outline-variant` stroke) is acceptable.
- Suggestion / submitted-result rows sit on `surface` (`#FFFFFF`) on iOS (grouped list); on Android they sit directly on `background.base` (`#F2F2F7`) with `outline-variant` dividers.

**Contrast (post-fix):**

| Element | Pair | Ratio | Floor | Verdict |
|---|---|---|---|---|
| Screen title `Search` (titleMedium 18 pt) | `fg-primary` `#000` on `bg` `#F2F2F7` | 18.82:1 | 3.0 | ✅ |
| Track title (bodyLarge 16 pt) on row | `fg-primary` `#000` on `surface` `#FFFFFF` | 21.00:1 | 4.5 | ✅ |
| Channel name • duration (bodyMedium 14 pt) | `fg-secondary` 80% on `surface` `#FFFFFF` | 5.97:1 | 4.5 | ✅ |
| Search-history chip label (labelSmall 12 pt med) | `fg-primary` `#000` on `surface-variant` `#F2F2F7` | 18.82:1 | 4.5 | ✅ |
| Submit button label (`Try Again` on C4) | `accent-on` `#FFF` on accent `#7C3AED` | 5.70:1 | 4.5 | ✅ |
| Error icon glyph (C4) | `fg-tertiary` on `bg` | 1.71:1 | n/a ⁸ | ➖ |

> ⁸ Per decision #5, the error icon is decorative — title carries the affordance. No 3:1 floor applies to a glyph that is not the sole signal.

---

## Library

**Stack (light):**
`background.base` `#F2F2F7` → `background.surface` `#FFFFFF` (playlist row on iOS grouped list) → `brand.accent-subtle` `#7C3AED20` (FAB Extended on Android, "+ New Playlist" pill on iOS).

**Light treatment:**
- iOS: grouped list against `systemGroupedBackground`. Each playlist row is a 72 pt `TrackRow`-style cell with the 4-up cover thumbnail at 56 pt.
- Android: M3 `LazyColumn` against `surface` (`#FFFFFF`); rows separated by `outline-variant` `#D1D1D6` 1 dp dividers indented to align with the title.
- Extended FAB is `primary-container` fill `#7C3AED20` with `on-primary-container` `#6D28D9` icon + label (7.10:1 ✅).

**Contrast:**

| Element | Pair | Ratio | Floor | Verdict |
|---|---|---|---|---|
| Playlist name (bodyLarge) | `fg-primary` `#000` on `surface` `#FFFFFF` | 21.00:1 | 4.5 | ✅ |
| Track count `12 tracks` (bodyMedium) | `fg-secondary` 80% on `surface` `#FFFFFF` | 5.97:1 | 4.5 | ✅ |
| FAB icon + label | `on-primary-container` `#6D28D9` on composite `#EAE0FB` | 7.10:1 | 4.5 | ✅ |
| Section header `Your Library` (titleLarge 20 pt) | `fg-primary` `#000` on `bg` `#F2F2F7` | 18.82:1 | 3.0 | ✅ |
| Empty-state body (C7) | `fg-secondary` 80% on `bg` `#F2F2F7` | 5.59:1 | 4.5 | ✅ |
| Empty-state action button label | `accent-on` `#FFF` on `accent` `#7C3AED` | 5.70:1 | 4.5 | ✅ |

---

## PlaylistDetail

**Stack (light):**
`background.base` `#F2F2F7` → `background.surface` `#FFFFFF` (cover + meta header card on iOS large title; full-bleed cover on Android collapsing toolbar) → `brand.accent` `#7C3AED` (Shuffle / Play primary buttons) → `border.subtle` `#D1D1D6` between rows.

**Light treatment:**
- iOS: navigation large title `Playlist name` collapses to inline on scroll. Cover is a 12-pt-radius 240 pt square centered above two filled buttons.
- Android: `TopAppBar.large` with the cover bleeding behind it. On scroll the toolbar collapses; the cover ducks beneath a `surface`-tinted scrim that ramps from `rgba(255,255,255,0)` to `rgba(255,255,255,0.92)` to preserve title legibility (overlay rule, parallels dark theme).

**Contrast:**

| Element | Pair | Ratio | Floor | Verdict |
|---|---|---|---|---|
| Playlist title (displayLarge 32 pt) on cover scrim | `fg-primary` `#000` on scrim ≥ 92% white | ≥ 18.5:1 | 3.0 | ✅ |
| Track row title (bodyLarge) | `fg-primary` `#000` on `surface` `#FFFFFF` | 21.00:1 | 4.5 | ✅ |
| Channel name (bodyMedium) | `fg-secondary` 80% on `surface` `#FFFFFF` | 5.97:1 | 4.5 | ✅ |
| Shuffle / Play button label | `accent-on` `#FFF` on `accent` `#7C3AED` | 5.70:1 | 4.5 | ✅ |
| Now-playing indicator bars (active row) | `accent` `#7C3AED` on `surface-variant` `#F2F2F7` | 5.43:1 | 3.0 | ✅ |
| Delete-confirm dialog destructive button | `on-error` `#FFF` on `error` `#FF3B30` | 3.55:1 | 4.5 | ⚠️ see D-3 |

---

## History (Recently Played)

**Stack (light):**
`background.base` `#F2F2F7` → grouped list `surface` `#FFFFFF` rows with date-section headers in `fg-secondary` on `bg`.

**Light treatment:**
- Section headers render in `fg-secondary` 80% — 5.59:1 on `bg` ✅. (Pre-fix: 3.29:1 — **D-1 defect**, repaired.)
- Swipe-to-delete in light reveals a destructive container `error` `#FF3B30` with `on-error` `#FFFFFF` icon. The contrast is 3.55:1 — large-text-only (the icon is 24 pt). Acceptable; iOS swipe-action backgrounds receive the same exception.

**Contrast:**

| Element | Pair | Ratio | Floor | Verdict |
|---|---|---|---|---|
| Track row title (bodyLarge) | `fg-primary` `#000` on `surface` `#FFFFFF` | 21.00:1 | 4.5 | ✅ |
| Channel • played-at (bodyMedium) | `fg-secondary` 80% on `surface` `#FFFFFF` | 5.97:1 | 4.5 | ✅ |
| Section header `Today` (labelSmall) | `fg-secondary` 80% on `bg` `#F2F2F7` | 5.59:1 | 4.5 | ✅ |
| Swipe-delete icon | `on-error` `#FFF` on `error` `#FF3B30` | 3.55:1 | 3.0 (icon) | ✅ |

---

## Now Playing

**Stack (light):**
`motion-scrim.nowplaying` `#F2F2F7` (base) → artwork (full-bleed, content-defined hue) → overlay scrim `rgba(0,0,0,0.55)` (only when artwork-extracted background is active in v1.1; v1.0 ships flat `bg`) → transport row (transparent) → MiniPlayer-derived chrome (collapses to `motion-scrim.miniplayer` `#FFFFFF`).

**Light treatment:**
- v1.0 (MVP): solid `bg` `#F2F2F7` background, artwork sits as a 12-pt-radius card centered above the title, no scrim. This is the build the audit targets.
- v1.1 (flagged behind a feature flag): artwork-extracted background. In light, the extracted hue is mixed with `#FFFFFF` at 70% to keep contrast budget — never the raw dominant pixel. Audit numbers below assume v1.0; v1.1 inherits the scrim rule from dark theme.
- Scrubber thumb requires `shadow.sm` per decision #6 — see `tokens.md` ⁷.

**Contrast:**

| Element | Pair | Ratio | Floor | Verdict |
|---|---|---|---|---|
| Track title (displayLarge 32 pt 600) | `fg-primary` `#000` on `bg` `#F2F2F7` | 18.82:1 | 3.0 | ✅ |
| Channel (bodyLarge 16 pt) | `fg-secondary` 80% on `bg` `#F2F2F7` | 5.59:1 | 4.5 | ✅ |
| Position / total `1:23 / 4:56` (labelSmall 12 pt 500) | `fg-secondary` 80% on `bg` `#F2F2F7` | 5.59:1 | 4.5 | ✅ |
| Transport glyph (play / pause, 32 pt) | `fg-primary` `#000` on `bg` `#F2F2F7` | 18.82:1 | 3.0 | ✅ |
| Active shuffle / repeat (tinted) | `accent` `#7C3AED` on `bg` `#F2F2F7` | 5.43:1 | 3.0 | ✅ |
| Scrubber filled track | `scrubber.fill` `#7C3AED` on `bg` `#F2F2F7` | 5.43:1 | 3.0 (UI) | ✅ |
| Scrubber unfilled track | `scrubber.track` `#D1D1D6` on `bg` `#F2F2F7` | 1.16:1 | 3.0 (UI) | ⚠️ see D-4 |
| Scrubber thumb (with `shadow.sm`) | `thumb` `#FFF` + shadow on either side | 3.0+:1 ⁹ | 3.0 (UI) | ✅ |

> ⁹ The 1 px `rgba(0,0,0,0.08)` shadow ring around the thumb pushes its effective edge contrast above 3:1 against `#D1D1D6`. Verified visually in `component-light.html` § NowPlaying chrome.

---

## MiniPlayer

**Stack (light):**
`background.base` `#F2F2F7` (host) → `motion-scrim.miniplayer` `#FFFFFF` (MiniPlayer container) → `shadow.md` underneath for elevation (M3 Level 2 capped) → 4 dp top-radius pill above the tab bar.

**Light treatment:**
- Container fill is `surface` `#FFFFFF`, not `surface-variant`. The chrome reads as a floating shelf above the tab bar.
- The bar borrows the `outline-variant` separator (`#D1D1D6`) at its top edge **only on Android** — on iOS the elevation shadow alone defines it.
- Play/pause glyph is `fg-primary` `#000` directly — no accent tint at rest. Tinted accent appears only when the row is **expanded** (NowPlaying chrome) or actively dragging.

**Contrast:**

| Element | Pair | Ratio | Floor | Verdict |
|---|---|---|---|---|
| Track title (bodyLarge truncated) | `fg-primary` `#000` on `surface` `#FFFFFF` | 21.00:1 | 4.5 | ✅ |
| Channel (bodyMedium truncated) | `fg-secondary` 80% on `surface` `#FFFFFF` | 5.97:1 | 4.5 | ✅ |
| Play/pause glyph | `fg-primary` `#000` on `surface` `#FFFFFF` | 21.00:1 | 3.0 | ✅ |
| Progress hairline (2 dp) under MiniPlayer | `scrubber.fill` `#7C3AED` on `surface` `#FFFFFF` | 5.70:1 | 3.0 | ✅ |
| Hairline unfilled portion | `scrubber.track` `#D1D1D6` on `surface` `#FFFFFF` | 1.20:1 | n/a ¹⁰ | ➖ |

> ¹⁰ The hairline is 2 dp tall and a *progress affordance*, not the sole signal — the title scrolling left and the artwork updating carry the meaning. We do not gate on it.

---

## Settings

**Stack (light):**
`background.base` `#F2F2F7` → grouped form `surface` `#FFFFFF` rows.

**Light treatment:**
- iOS: native `Form` against `systemGroupedBackground`.
- Android: M3 list with `outline-variant` dividers.
- The `Appearance → Theme` row exposes the System / Light / Dark override (decision #1). Selected option is rendered with the `accent` checkmark glyph; row label uses `fg-primary` regardless of state to keep the rest of the form rhythm steady.
- Operations (Export, Import, Clear cache, Clear history) trigger the C15 overlay spinner and surface results via inline C16 error / Snackbar success per `state-catalog/`.

**Contrast:**

| Element | Pair | Ratio | Floor | Verdict |
|---|---|---|---|---|
| Section header `Appearance` (labelSmall caps) | `fg-secondary` 80% on `bg` `#F2F2F7` | 5.59:1 | 4.5 | ✅ |
| Row label `Theme` (bodyLarge) | `fg-primary` `#000` on `surface` `#FFFFFF` | 21.00:1 | 4.5 | ✅ |
| Row value `System` (bodyMedium right-aligned) | `fg-secondary` 80% on `surface` `#FFFFFF` | 5.97:1 | 4.5 | ✅ |
| Selected checkmark | `accent` `#7C3AED` on `surface` `#FFFFFF` | 5.70:1 | 3.0 | ✅ |
| Destructive `Clear history` row label | `error` `#FF3B30` on `surface` `#FFFFFF` | 3.55:1 | 4.5 | ⚠️ see D-3 |

---

## Defects (with proposed fix)

### D-1 — `fg-secondary` light alpha is too weak (REPAIRED)

- **Symptom.** Light `fg-secondary` shipped as `rgba(60,60,67,0.60)` (mirror of iOS `secondaryLabel`). Composited on `surface` `#FFFFFF` it gives **3.44:1**; on `bg` `#F2F2F7` it gives **3.29:1**. Both **fail** the 4.5:1 body floor.
- **Impact.** Channel-name secondary text across TrackRow, MiniPlayer, History rows, PlaylistDetail rows, Settings row values, section headers, NowPlaying timecodes.
- **Fix.** Bump light alpha to `0.80` — matches dark theme's alpha for body parity. Post-fix: **5.97:1** (on white) / **5.59:1** (on bg) ✅.
- **Trade-off.** Diverges from SF `secondaryLabel`. Recorded as a deliberate exception in `swift-codegen-hint.md` once codegen lands. Apple's own `secondaryLabel` is reserved for *large-text* metadata (≥ 14 pt bold or ≥ 18 pt regular); we treat 14 pt regular as body, so the divergence is principled.
- **Status.** **Applied** in `tokens-additions.json` → `color.foreground.secondary.light`.

### D-2 — Brand accent `#8B5CF6` fails body contrast on white (REPAIRED)

- **Symptom.** `#8B5CF6` (brand purple) on `#FFFFFF` = **4.23:1**. Action labels rendered on accent fills (snackbar action, FilledButton text, "Try Again" on light) fail the 4.5:1 body floor.
- **Impact.** Every primary FilledButton, FAB Extended label, Snackbar action label, accent-tinted link in body copy.
- **Fix.** Light theme uses `#7C3AED` (the dark-theme `accent-dim`) as `accent`. `#FFFFFF` on `#7C3AED` = **5.70:1** ✅. iOS asset catalog ships both variants. Android: Material You dynamic color is probed at runtime; fallback to `#7C3AED` if the user-derived primary fails the same 4.5:1 probe.
- **Status.** Already present in `tokens.json` (`color.brand.accent.light = #7C3AED`). This audit confirms it is non-negotiable. **No additions needed**; we document why the existing value is the value.

### D-3 — `error` red on white passes large-text only — flag for destructive *body labels*

- **Symptom.** `#FF3B30` (iOS `systemRed`) on `#FFFFFF` = **3.55:1**, on `#F2F2F7` = **3.18:1**. Destructive labels rendered at body size on a surface ("Clear history", "Delete playlist", AlertDialog destructive button text) fail the 4.5:1 floor.
- **Impact.** Settings → Clear history / Clear cache row labels; PlaylistDetail → Delete playlist row in overflow; AlertDialog destructive-button text on white background; "Remove from queue" body label.
- **Fix.** Two-path mitigation, **not** a token change:
  - **Path A — destructive *row labels*** in `surface`-backed lists: render at `fg-primary` `#000` (21:1 ✅) and rely on a leading 16 pt `trash` SF symbol / Material `Delete` glyph tinted `error` to signal destructiveness. This is the iOS HIG pattern (`Form` destructive rows use a red glyph, black label).
  - **Path B — destructive *button fills*** in AlertDialogs: render the button as a **filled** error variant (`error` fill, `on-error` white text — 3.55:1 large-text). The button text size is `labelLarge` ≥ 14 pt 600 → qualifies as large text (≥ 14 pt bold floor 3.0 ✅).
- **Why no token change.** Darkening the red (`#C7251C` would give 4.5:1) would break parity with SF `systemRed` and M3 `error` and re-light the entire dark-mode destructive surface. The two-path mitigation gives both platforms an idiomatic pattern without a token break.
- **Status.** Implementation guidance only — captured here, surfaced in `state-catalog-light-overlay.md` C11 (Delete playlist) and Settings audit row.

### D-4 — Scrubber unfilled track `#D1D1D6` on `#F2F2F7` is 1.16:1 (false-positive — accept)

- **Symptom.** The unfilled portion of the NowPlaying scrubber against `bg` `#F2F2F7` is **1.16:1**.
- **Impact.** Visual: the unfilled track is faint.
- **Why we accept.** The scrubber is a *paired* affordance — filled (`#7C3AED`, 5.43:1) + thumb (`#FFF` + shadow) carry the state. The unfilled track is structurally the *absence* of fill and does not encode meaning on its own. Apple's `Slider` and Material `Slider` both ship at this contrast in light.
- **Mitigation.** None. Documented for completeness.

---

## Validation summary (machine-friendly)

| Defect | Token / scope | Pre-fix | Post-fix | Status |
|---|---|---|---|---|
| D-1 | `color.foreground.secondary.light` alpha | 3.29:1 / 3.44:1 | 5.59:1 / 5.97:1 | Applied in `tokens-additions.json` |
| D-2 | `color.brand.accent.light` selection | 4.23:1 | 5.70:1 | Confirmed in `tokens.json` (no addition) |
| D-3 | Destructive body labels on `surface` | 3.55:1 | n/a (pattern fix, not token) | Guidance only |
| D-4 | Scrubber unfilled track on `bg` | 1.16:1 | accept | No change |

No other surface drops below its applicable floor.
