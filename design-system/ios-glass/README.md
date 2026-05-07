# YourTube iOS — Translucent Materials Layer

An original translucent-materials language for the iOS build of YourTube. Sits on top of the existing brand tokens and adds glass-like chrome surfaces (tab bar, MiniPlayer, NowPlaying controls, sheets, menus, toasts).

> **Note.** This is YourTube's own translucent layer, not a recreation of any vendor's OS chrome. Apply inside the YourTube app shell only — do not present these surfaces as system UI.

## Files
- `glass_tokens.css` — additive token layer (materials, edge-light, depth, shape). Loaded after `colors_and_type.css`.
- `translucent-materials.html` — full design system spec: tokens, 7 components, 4 assembled screens, trade-offs per component.

## Five-layer material recipe
Every glass surface composes the same five layers:
1. **Backdrop blur** (18 / 28 / 40 / 56px — thin / regular / thick / ultra)
2. **Fill** — semi-opaque tint that controls weight (42–96%)
3. **Specular highlight** — 135° linear gradient at 6–10% white
4. **Refracted edge** — asymmetric inner stroke (bright top, dark bottom)
5. **Depth shadow** — soft outer shadow

## Usage
```html
<link rel="stylesheet" href="../colors_and_type.css">
<link rel="stylesheet" href="./glass_tokens.css">

<div class="glass glass-pill">…tab bar…</div>
<div class="glass">…MiniPlayer / sheet…</div>
<div class="glass glass-thick glass-bubble">…context menu…</div>
<div class="glass glass-ultra glass-sheet">…full-height modal…</div>
```

## Recommendation
Ship for iOS only. Start with MiniPlayer + tab bar + NowPlaying. Defer sheets and menus. Android stays Material You.
