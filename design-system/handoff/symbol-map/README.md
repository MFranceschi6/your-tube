# Symbol Map — Semantic Action ↔ Platform Icon Reference

**Task:** YT-0179
**Status:** Production spec
**Audience:** Claude Code (implementation), design reviewers, and any contributor adding UI icons.

---

## Purpose

This package is the single authoritative cross-reference between **semantic UI actions** and their platform-specific icon glyphs. It exists because:

- Material Symbols Rounded (Android) and SF Symbols (iOS) do not share glyph names.
- Several handoff packages were already using inline icon references inconsistently.
- The state-catalog established the rule "Do not invent custom icons" but had no single lookup table.

This map enforces that rule by making the canonical pair explicit for every semantic action the app uses.

---

## What's in this folder

| File | Purpose |
|---|---|
| `README.md` | This file. Purpose, usage, and precedence rules. |
| `symbol-map.md` | The main lookup table: semantic action → Android glyph → iOS symbol → notes. |
| `weight-and-size.md` | Per-surface icon sizing (toolbar, list row, mini-player, now-playing) and active/inactive weight rules. |
| `accessibility.md` | `contentDescription` rules for Android and `accessibilityLabel` rules for iOS. When to mark decorative. |
| `coverage-audit.md` | Which semantic actions from `symbol-map.md` are confirmed used in the codebase vs. missing or inconsistent. |

---

## Precedence rules

1. **Semantic action is the source of truth.** The column "Semantic Action" in `symbol-map.md` defines the *meaning* that must be communicated to the user. Glyph names are implementation details.
2. **This map overrides inline icon references** in all other handoff packages (YT-0013, YT-0014, YT-0027, YT-0028, state-catalog) wherever there is a conflict. Inline references in those packages are left in place for readability, but this map wins.
3. **Platform-native vocabulary overrides visual parity.** An Android icon and an iOS icon for the same action will not look identical. They will communicate the same meaning using the glyph vocabulary familiar to each platform's user. That is the goal. Do not substitute a Material glyph for an SF Symbol to achieve visual parity.
4. **Filled vs. outlined state is semantic, not aesthetic.** See `weight-and-size.md` for the active/inactive state rules. Do not apply filled style arbitrarily.
5. **When no perfect match exists**, use the closest named equivalent from the platform's bundled set and document the mismatch in `coverage-audit.md`. Do not create custom icons unless a future design task explicitly commissions them.

---

## How to use this map

**When implementing a new icon:**
1. Identify the semantic action from the "Semantic Action" column in `symbol-map.md`.
2. Read the Android or iOS glyph name from the same row.
3. Check `weight-and-size.md` for the correct size and weight for your surface.
4. Set the accessibility label from `accessibility.md`.
5. If the action is not in the map, add it to `symbol-map.md` and update `coverage-audit.md`.

**When reviewing an icon implementation:**
1. Confirm the glyph matches the row in `symbol-map.md` for that semantic action.
2. Confirm the size and weight match `weight-and-size.md` for the surface.
3. Confirm the accessibility label matches `accessibility.md`.

**When adding a new semantic action:**
1. Add a row to `symbol-map.md`.
2. Note whether the platform has a glyph or requires a closest equivalent.
3. Add the new action to `coverage-audit.md` under "Not yet implemented."

---

## Relationship to other handoff packages

| Package | Icon references | Status |
|---|---|---|
| `design-system/handoff/YT-0013/compose-spec.md` | Inline `Icons.Rounded.*` | Still present; this map is authoritative |
| `design-system/handoff/YT-0027/swiftui-spec.md` | Inline SF symbol strings | Still present; this map is authoritative |
| `design-system/handoff/YT-0014/compose-spec.md` | Inline `Icons.Rounded.*` | Still present; this map is authoritative |
| `design-system/handoff/YT-0028/swiftui-spec.md` | Inline SF symbol strings | Still present; this map is authoritative |
| `design-system/handoff/state-catalog/empty.md` | Per-cell icon pairs | Still present; this map is authoritative |
| `design-system/handoff/state-catalog/README.md` | General icon rules | Complementary; no conflict |

The `docs/design-system.md` iconography section links here. That is the entry point for anyone unfamiliar with the vault.
