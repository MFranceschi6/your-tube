package com.yourtube.core.data.preferences

import kotlinx.coroutines.flow.Flow

enum class ThemePreference {
    SYSTEM,
    LIGHT,
    DARK,
}

interface ThemePreferences {

    val themePreference: Flow<ThemePreference>

    suspend fun setThemePreference(preference: ThemePreference)

    companion object {
        val DEFAULT_THEME_PREFERENCE = ThemePreference.SYSTEM
    }
}
