import SwiftUI

// MARK: - Theme

/// iOS-specific theme derived from docs/design-system.md.
/// Colors are system-adaptive so the app responds correctly to Light/Dark mode.
/// Brand purple `#8B5CF6` is expressed as a literal below so the shipped tint
/// matches this file without requiring an asset catalog entry.
///
/// MiniPlayer material note (for YT-0027 implementer): The persistent MiniPlayer
/// uses `.regularMaterial` (heavier blur, closer to the mockup `rgba(44,44,46,0.95)`)
/// rather than `.ultraThinMaterial`. Do not silently change it — discuss with
/// the YT-0027 implementer before switching.
enum Theme {

    // MARK: Brand

    /// Brand accent purple (#8B5CF6). Used for tint, active states, and focus rings.
    /// Expressed as a literal so it ships correctly without an asset catalog entry.
    static let accent = Color(red: 0.545, green: 0.361, blue: 0.965)

    // MARK: Surfaces

    /// Base background — maps to `systemBackground` (black in dark, white in light).
    static let background = Color(.systemBackground)

    /// Elevated surface — cards, sheets, list backgrounds.
    static let surface = Color(.secondarySystemBackground)

    /// Grouped list row background / hover/press surface.
    static let surfaceVariant = Color(.tertiarySystemBackground)

    // MARK: Content

    /// Primary text / icons.
    static let onBackground = Color.primary

    /// Secondary / subdued text.
    static let onBackgroundSecondary = Color.secondary

    /// Tertiary / hint text.
    static let onBackgroundTertiary = Color(.tertiaryLabel)

    // MARK: Semantic

    /// System red — error states.
    static let error = Color(.systemRed)

    /// System green — success / download complete.
    static let success = Color(.systemGreen)

    // MARK: Separator

    static let separator = Color(.separator)

    // MARK: Typography helpers
    //
    // Use SwiftUI built-in `Font` styles which automatically scale with Dynamic
    // Type — never use fixed point sizes directly in views.

    /// Display-large role: now-playing track title.
    static let displayLarge = Font.largeTitle.weight(.bold)

    /// Title-medium role: screen titles, playlist names.
    static let titleMedium = Font.title3.weight(.semibold)

    /// Body-large role: track row title.
    static let bodyLarge = Font.body

    /// Body-medium role: channel name, secondary metadata.
    static let bodyMedium = Font.subheadline

    /// Label-small role: duration, badges.
    static let labelSmall = Font.caption.monospacedDigit()

    // MARK: Icon size aliases
    //
    // Prefer `imageScale` modifiers for SF Symbols when possible. Use these
    // font aliases when the symbol needs a specific text-equivalent weight or
    // when `imageScale` alone is not sufficient (e.g. large artwork glyphs).

    /// Large icon: artwork placeholder, empty-state badge (~28 pt equivalent).
    static let iconLarge = Font.title3.weight(.medium)

    /// Medium icon: row-level controls (~20 pt equivalent).
    static let iconMedium = Font.body.weight(.medium)

    /// Small icon: inline decorative glyphs (~14 pt equivalent).
    static let iconSmall = Font.caption.weight(.regular)
}
