package com.yourtube.app.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Top-level navigation destinations for the bottom navigation bar.
 * Recently Played is accessible from within Library — it does not get its own bottom-bar item.
 */
enum class TopLevelDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val contentDescription: String,
) {
    SEARCH(
        route = "search",
        label = "Search",
        icon = Icons.Rounded.Search,
        contentDescription = "Search tab",
    ),
    LIBRARY(
        route = "library",
        label = "Library",
        icon = Icons.Rounded.LibraryMusic,
        contentDescription = "Library tab",
    ),
    SETTINGS(
        route = "settings",
        label = "Settings",
        icon = Icons.Rounded.Settings,
        contentDescription = "Settings tab",
    ),
}
