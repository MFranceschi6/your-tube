package com.yourtube.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Brand palette tokens. Source of truth: design-system/handoff/YT-0011/audit.md
// findings 4 + design-system/colors_and_type.css.
private val BrandPurple = Color(0xFF8B5CF6)
private val BrandPurpleDeep = Color(0xFF4C1D95)
private val BrandPurpleSoft = Color(0xFFEDE9FE)
private val BrandSecondary = Color(0xFFA78BFA)
private val BrandTertiary = Color(0xFF22D3EE)
private val BrandError = Color(0xFFFF453A)

// Dark surfaces (project tokens).
private val DarkBackground = Color(0xFF0F0F0F)
private val DarkSurface = Color(0xFF1C1C1E)
private val DarkSurfaceVariant = Color(0xFF2C2C2E)
private val DarkOnSurfaceVariant = Color(0xFFC7C7CC)

// Light surfaces (inverted tone).
private val LightBackground = Color(0xFFFFFFFF)
private val LightSurface = Color(0xFFF2F2F7)
private val LightSurfaceVariant = Color(0xFFE5E5EA)
private val LightOnSurfaceVariant = Color(0xFF49454F)

internal val BrandDarkColors: ColorScheme = darkColorScheme(
    primary = BrandPurple,
    onPrimary = Color.White,
    primaryContainer = BrandPurpleDeep,
    onPrimaryContainer = BrandPurpleSoft,
    secondary = BrandSecondary,
    onSecondary = Color(0xFF1A1525),
    secondaryContainer = Color(0xFF2E1065),
    onSecondaryContainer = Color(0xFFDDD6FE),
    tertiary = BrandTertiary,
    onTertiary = Color(0xFF0F172A),
    background = DarkBackground,
    onBackground = Color.White,
    surface = DarkSurface,
    onSurface = Color.White,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    error = BrandError,
    onError = Color.White,
)

internal val BrandLightColors: ColorScheme = lightColorScheme(
    primary = BrandPurple,
    onPrimary = Color.White,
    primaryContainer = BrandPurpleSoft,
    onPrimaryContainer = BrandPurpleDeep,
    secondary = BrandPurpleDeep,
    onSecondary = Color.White,
    secondaryContainer = BrandPurpleSoft,
    onSecondaryContainer = BrandPurpleDeep,
    tertiary = Color(0xFF0E7490),
    onTertiary = Color.White,
    background = LightBackground,
    onBackground = Color(0xFF0F0F0F),
    surface = LightSurface,
    onSurface = Color(0xFF0F0F0F),
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    error = BrandError,
    onError = Color.White,
)
