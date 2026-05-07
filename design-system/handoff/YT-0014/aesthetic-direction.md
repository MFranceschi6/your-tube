# YT-0014 — Aesthetic Direction (v2 / MVP)

> Tone, density, type rhythm, motion language for the Library Playlists surface. Preserved from v1; trimmed where it referenced Downloads/Liked.

## Tone

Library is the user's **collection** — calm, dense without being cramped, visually anchored by the cover art. It is **not** an editorial surface (no carousels, no "Featured for you" rows, no algorithmic shelves). The user sees their own things.

Adjective floor: confident, archival, M3-native, M3-not-Spotify.
Adjective ceiling: not playful, not "fun", not gradient-heavy, not skeuomorphic.

## Density

`LibraryScreen` `ListItem` rows: 72 dp tall (M3 two-line `ListItem` default with 56 dp leading). At a typical phone height of 880 dp content area, ~12 visible rows above the fold including header. That's the density to target.

Avoid over-dense (48 dp single-line rows) — the cover is the visual anchor and 48 dp swallows it. Avoid loose (96 dp+) — Library is a collection, not a hero list.

`PlaylistDetailScreen` track rows: 64 dp (single-line default). Cover header dominates; track rows defer.

## Type rhythm

| Element | Role | Weight |
|---|---|---|
| Top app bar title "Library" | `headlineSmall` (LargeTopAppBar collapse) | 400 |
| Playlist name | `bodyLarge` | 500 — bumped from default 400; the cover already carries weight, the name needs to hold its own |
| Track count | `bodyMedium` | 400, `colorScheme.onSurfaceVariant` |
| `PlaylistDetailScreen` title | `displaySmall` | 400 |
| Track title | `bodyLarge` | 400 (read-only) / 500 (active in playing queue, if surfaced via tint per YT-0013 active-row pattern) |
| Channel · duration | `bodyMedium` | 400, `onSurfaceVariant` |
| FAB label | `labelLarge` | 500 (M3 default) |

**Tabular numbers** on track-count strings, durations, and "5 tracks" pluralizations: `fontFeatureSettings = "tnum"`. Otherwise the playlist list visually jitters as the count digits change.

## Motion language

Borrow YT-0013's spring vocabulary for cover async crossfade and edit-mode toggle. For list reorder, use `tween(180, FastOutSlowInEasing)` — physical springs feel laggy when 5+ rows are shifting at once.

| Motion | Spec |
|---|---|
| Cover crossfade (placeholder → resolved) | `Crossfade(tween(180))` |
| Edit-mode toggle (thumbnail ↔ drag handle) | `Crossfade(tween(140))` per row, staggered by `index * 12.ms` cap 200 ms |
| Reorder row shift | `tween(180, FastOutSlowInEasing)` |
| FAB enter on first paint | M3 default (slight scale + fade) — don't over-design |
| Snackbar enter/exit | M3 default slide |
| `LargeTopAppBar` collapse | M3 default — don't intercept |

Reduce-motion path defined in `haptics-and-a11y.md`.

## Color economy

The Library surface uses **at most three accent colors**:
- `colorScheme.primary` — playing-track tint (when applicable), FAB container is `primaryContainer`.
- `colorScheme.tertiaryContainer` — the "now playing — changes apply on next play" banner (Q3 / `media3-parity.md`).
- `colorScheme.error` — remove icon, delete confirm action.

That's it. No custom Library accent. No "playlist color" derived from cover art. No category tagging. The surface is monochromatic except for these three signals.

## Imagery

Cover thumbnails are the **only imagery**. No illustrations on the empty state — Material Symbols glyph in `colorScheme.onSurfaceVariant` 64 dp. No promo banners, no tooltips with avatars, no animated splash. The cover collage is the visual moment.

## What it must NOT look like

- Spotify Library — they lean into shelf carousels and bright accent treatments. We do not.
- iOS Music Library — they use a `+` first-row pattern and a different cover algorithm. We do not.
- "Files" / "Downloads" desktop apps — list-of-rows with file-type icons. We are richer than that — the cover is the point.
- Notion's database UI — too dense, too neutral. We have the cover; use it.

## Validation against `docs/design-system.md`

Spot-check the design-system gospel:
- Tap targets ≥ 48 dp ✓ (decisions Q3, haptics-and-a11y "Tap targets").
- M3 tokens, no hardcoded colors ✓ (Q10 token map).
- `wrap-pretty` text behavior ✓ (Q9 empty state body).
- Material Symbols rounded ✓ (`Icons.Rounded.*` throughout).
- No third-party reorder lib ✓ (Q3 hand-rolls `LongPressDraggable`).
