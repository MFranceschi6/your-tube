package com.yourtube.app.navigation

import com.yourtube.core.designsystem.IconKey

/**
 * Top-level navigation destinations for the bottom navigation bar.
 * Recently Played is accessible from within Library — it does not get its own bottom-bar item.
 */
enum class TopLevelDestination(
    val route: String,
    val label: String,
    val icon: IconKey,
    val contentDescription: String,
) {
    SEARCH(
        route = "search",
        label = "Search",
        icon = IconKey.Search,
        contentDescription = "Search tab",
    ),
    LIBRARY(
        route = "library",
        label = "Library",
        icon = IconKey.Library,
        contentDescription = "Library tab",
    ),
    SETTINGS(
        route = "settings",
        label = "Settings",
        icon = IconKey.Settings,
        contentDescription = "Settings tab",
    ),
}
