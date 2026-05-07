package com.yourtube.app.ui.theme

import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.yourtube.core.ui.LocalReduceMotion

@Composable
fun YourTubeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> BrandDarkColors
        else -> BrandLightColors
    }
    val reduceMotion = remember(context) {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.TRANSITION_ANIMATION_SCALE,
            1f,
        ) == 0f
    }
    CompositionLocalProvider(LocalReduceMotion provides reduceMotion) {
        MaterialTheme(colorScheme = colorScheme, content = content)
    }
}

@Preview(name = "Brand fallback - dark", showBackground = true)
@Composable
private fun YourTubeThemeBrandFallbackPreview() {
    YourTubeTheme(darkTheme = true, dynamicColor = false) {
        Box(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.background)
                .padding(16.dp),
        ) {
            Text(
                text = "Brand purple",
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
