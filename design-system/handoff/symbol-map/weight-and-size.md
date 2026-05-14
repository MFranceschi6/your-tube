# Icon Weight and Size — Per-Surface Rules

> **Applies to:** all icons in `symbol-map.md`.
> **Authority:** this file defines sizing and weight. It overrides any pixel value stated in a per-surface handoff package wherever there is a conflict.

---

## Sizing grid

Icon sizes follow the 4 dp/pt grid. The sizes below are glyph sizes (the visual symbol), not the touch/hit target. Hit targets are always at least 48 dp (Android) or 44 pt (iOS) regardless of glyph size. Use padding or `.contentShape` to expand the touch area without inflating the glyph.

| Surface | Glyph size (Android dp) | Glyph size (iOS pt) | Hit target (Android dp) | Hit target (iOS pt) |
|---|---|---|---|---|
| Now Playing — transport row, primary (play/pause) | 36 dp | 36 pt | 72 dp | 72 pt |
| Now Playing — transport row, secondary (skip-next, skip-previous) | 32 dp | 26 pt | 56 dp | 56 pt |
| Now Playing — transport row, flanking (shuffle, repeat) | 24 dp | 20 pt | 48 dp | 44 pt |
| Now Playing — collapse button | 28 dp | 22 pt | 48 dp | 44 pt |
| Now Playing — action row (queue, share, add-to-playlist) | 24 dp (default `IconButton`) | 22 pt | 48 dp | 44 pt |
| Now Playing — more-options (MoreVert) | 24 dp (default) | 24 pt | 48 dp | 44 pt |
| MiniPlayer — play/pause | 24 dp | 22 pt | 48 dp | 44 pt |
| List row — overflow/more (MoreVert, ellipsis) | 24 dp | 20 pt | 48 dp | 44 pt |
| List row — drag handle | 24 dp (in 48 dp hit, 12 dp padding each side) | system-rendered | 48 dp | system |
| List row — remove button (edit mode) | 24 dp | system-rendered | 48 dp | system |
| Toolbar / top app bar action | 24 dp | 20–24 pt | 48 dp | 44 pt |
| FAB icon (Android Extended FAB only) | 24 dp | n/a | n/a | n/a |
| Empty / error state illustration | 56 dp | 56 pt | non-interactive | non-interactive |
| Playlist cover fallback glyph | 32 dp | 22 pt | non-interactive | non-interactive |
| Tab bar icon | 24 dp (Material NavigationBar default) | system-managed | system | system |

---

## Android — weight rules (Filled vs. Outlined / Rounded)

The project uses **Material Symbols Rounded** throughout. Within that style family, the distinction between filled and unfilled communicates active/inactive state for toggle-like icons.

| Icon category | Default (inactive) | Active / selected |
|---|---|---|
| Transport: shuffle | `Icons.Rounded.Shuffle` (unfilled appearance) | Same glyph, tint changes to `colorScheme.primary` |
| Transport: repeat | `Icons.Rounded.Repeat` (unfilled appearance) | Same glyph, tint changes to `colorScheme.primary`; when repeat-one active, swap to `Icons.Rounded.RepeatOne` |
| Favourite/heart | `Icons.Rounded.FavoriteBorder` | `Icons.Rounded.Favorite` (filled) |
| All other interactive icons | As listed in `symbol-map.md` | No filled variant; use tint change only |
| Tab bar icons | Outlined/unfilled Material symbol | Filled equivalent (where one exists); use `Icons.Filled.*` or the closest rounded alternative |
| Static / decorative icons | As listed in `symbol-map.md` | N/A |

**Tint rule for active transport icons (shuffle, repeat):** use `colorScheme.primary` for the active tint, `colorScheme.onSurfaceVariant` for the inactive tint. This matches the `TransportIcon` wrapper in `design-system/handoff/YT-0013/compose-spec.md`.

**Error-tinted icons:** only the remove-from-playlist button uses `colorScheme.error` for its tint. All other icons use the surface/variant tints above.

---

## iOS — weight rules (Regular vs. Filled)

SF Symbols supports weight and fill as independent axes. The project convention:

| Icon category | Default (inactive) | Active / selected |
|---|---|---|
| Transport: play, pause, skip-next, skip-previous | `.fill` variant always | N/A (always filled) |
| Transport: shuffle | `shuffle` (regular) | Same glyph, tint changes to `Color.accentColor` |
| Transport: repeat | `repeat` (regular) | Same glyph, tint changes to `Color.accentColor`; swap to `repeat.1` for repeat-one |
| Favourite/heart | `heart` (regular) | `heart.fill` |
| Toolbar / navigation icons | Regular weight | N/A; filled variants not used in toolbars |
| Tab bar icons | Regular weight | Filled variant (e.g. `gearshape.fill`, `music.note.list` — SF Symbols auto-provides this via `tabItem` in some cases; check each symbol for a `.fill` sibling) |
| Empty / error state icons | Regular weight | N/A |
| Static / decorative icons | As listed in `symbol-map.md` | N/A |

**Font size for SF Symbols:** use `.font(.system(size: N))` or `.imageScale()` to match the pt sizes above rather than `.frame()` alone. SF Symbols scale with Dynamic Type when rendered with `.font()`; this is the preferred approach for all icons except the fixed-size empty-state illustration.

**Empty and error state icons are exempt from Dynamic Type scaling.** Hold at 56 pt using `.frame(width: 56, height: 56)` and `.font(.system(size: 40))` (scaled to fill the frame). See `design-system/handoff/state-catalog/README.md` — the icon size is fixed, not scaled.

---

## Minimum hit target enforcement

Do not shrink the hit target to match the glyph. These are the enforcement patterns:

**Android:**
```kotlin
IconButton(
    modifier = Modifier.size(48.dp),  // hit target
    onClick = { ... }
) {
    Icon(
        imageVector = Icons.Rounded.SkipNext,
        contentDescription = "Next track",
        modifier = Modifier.size(32.dp),  // glyph
    )
}
```

For the primary play/pause button with a 72 dp hit target, use a `Box` or `FilledIconButton` at 72 dp; the glyph inside is 36 dp.

**iOS:**
```swift
Button { action() } label: {
    Image(systemName: "forward.fill")
        .font(.system(size: 26))
}
.frame(width: 56, height: 56)
.contentShape(Rectangle())
```

Always pair `.frame()` with `.contentShape(Rectangle())` when the visible glyph is smaller than the desired hit target.

---

## Color rules (summary)

Full token reference is in `docs/design-system.md`. The icon-specific subset:

| Context | Android token | iOS semantic |
|---|---|---|
| Default icon color | `colorScheme.onSurfaceVariant` | `.secondary` (`.foregroundStyle(.secondary)`) |
| Primary / emphasis color | `colorScheme.onSurface` | `.primary` |
| Active toggle (shuffle, repeat) | `colorScheme.primary` | `Color.accentColor` |
| Destructive (remove, delete) | `colorScheme.error` | `.red` (system) via `role: .destructive` |
| Decorative / muted (empty state glyph, cover fallback) | `colorScheme.onSurfaceVariant` | `.white.opacity(0.28)` (cover glyph) or `.tertiary` (empty state) |
| On-primary (icon inside filled button) | `colorScheme.onPrimary` | `.white` |
