import SwiftUI

// MARK: - Tokens

/// Design tokens that map the shared design system (docs/design-system.md) onto
/// iOS-native values. All measurements are in points on a 4 pt grid.
enum Tokens {

    // MARK: Spacing (4 pt grid)

    enum Spacing {
        /// 4 pt
        static let xs: CGFloat = 4
        /// 8 pt
        static let sm: CGFloat = 8
        /// 16 pt
        static let md: CGFloat = 16
        /// 24 pt
        static let lg: CGFloat = 24
        /// 32 pt
        static let xl: CGFloat = 32
    }

    // MARK: Radius

    enum Radius {
        static let sm: CGFloat = 8
        static let md: CGFloat = 12
        static let lg: CGFloat = 16
        static let pill: CGFloat = 999
    }

    // MARK: Corner style

    /// Continuous (squircle) corner style used on every `RoundedRectangle` in the
    /// design system. Downstream tasks (YT-0026, YT-0027, YT-0028) must use this
    /// constant so the choice cannot regress to the default `.circular` style.
    static let cornerStyle: RoundedCornerStyle = .continuous

    // MARK: Hit Targets

    enum HitTarget {
        /// Minimum 44 × 44 pt per iOS HIG.
        static let minimum: CGFloat = 44
    }

    // MARK: Thumbnail

    enum Thumbnail {
        /// Track row thumbnail size.
        static let row: CGFloat = 52
        /// Mini-player thumbnail size.
        static let mini: CGFloat = 46
    }
}
