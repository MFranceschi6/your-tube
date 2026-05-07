---
name: yourtube-design
description: Use when implementing or reviewing YourTube UI tasks that reference the design-system folder, HTML mockups, visual tokens, design_skill/design_mockup metadata, or visual parity with the app design system.
---

# YourTube Design Skill

Use this as the lightweight project skill for design-system-aware UI work.

## Workflow

1. Read `docs/design-system.md` for canonical product-level rules.
2. Read `design-system/SKILL.md` and `design-system/README.md` for the full design-system source.
3. If the task has `design_mockup`, open that file. Otherwise use `design-system/mockups/MOCKUP_INDEX.md` to find the mockup for the task ID.
4. Treat mockups as visual and interaction targets, not implementation code. Translate them into platform-native UI:
   - Android: Kotlin, Jetpack Compose, Material 3, dynamic color, Material Symbols.
   - iOS: SwiftUI, SF Symbols, Dynamic Type, native sheets/forms/navigation.
5. Keep implementation ownership with the task's platform `agent_profile`; this skill supplies design taste, component intent, and visual parity checks.

## Review Checklist

- The implementation follows the linked mockup's relevant states, layout intent, and interaction pattern.
- Platform idioms win over web mockup details when they conflict.
- Accessibility labels, tap targets, text scaling, contrast, loading, empty, and error states are covered.
- Shared behavior discovered during UI work is documented in tracked `docs/`, not only in Obsidian.
