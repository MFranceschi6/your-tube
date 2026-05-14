package com.yourtube.app.ui.theme

import android.content.Context
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.getSystemService
import com.yourtube.core.ui.LocalAmoledBlack
import com.yourtube.core.ui.LocalReduceMotion

@Composable
fun YourTubeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    /**
     * YT-0102 — when `true` AND [darkTheme] is `true`, overrides [ColorScheme.background]
     * and [ColorScheme.surface] to pure black (#000000). No-op in light mode.
     *
     * Callers thread this in from the [AmoledPreferences] flow collected at the `AppShell`
     * root so that the preference persists across app restarts and responds to DataStore
     * updates without restarting the activity.
     */
    amoledBlack: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val baseColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> BrandDarkColors
        else -> BrandLightColors
    }
    // YT-0102: override background + surface to pure black only when AMOLED mode is
    // requested AND we are actually in dark theme. Light mode is unaffected.
    val colorScheme = if (amoledBlack && darkTheme) {
        baseColorScheme.copy(
            background = Color.Black,
            surface = Color.Black,
        )
    } else {
        baseColorScheme
    }
    // YT-0166 §4 detection contract: reduce-motion is ON when BOTH transition and animator
    // scales are zero. Reading either alone misses the case where a user selected "Animation
    // off" via Developer Options (which writes both) vs an OEM that disables only one.
    //
    // YT-0062a Q10 — additionally register an `AccessibilityServicesStateChangeListener`
    // on API 33+ so that toggling "Remove animations" in Accessibility settings triggers
    // a `readReduceMotion()` re-read at runtime (no restart required). The "Remove
    // animations" Accessibility toggle zeros out both `TRANSITION_ANIMATION_SCALE` and
    // `ANIMATOR_DURATION_SCALE` on API 31+, so the dual-scale check in `readReduceMotion`
    // catches it without needing to call `isReducedAnimationsEnabled()` (API 35+).
    //
    // YT-0166 round 2 — register a `ContentObserver` on both `Settings.Global` rows so
    // toggling Developer Options → Transition animation scale takes effect at runtime
    // without an app restart. iOS sibling task (YT-0167) observes
    // `reduceMotionStatusDidChangeNotification`; this is the Android parity.
    var reduceMotion by remember(context) { mutableStateOf(readReduceMotion(context)) }
    DisposableEffect(context) {
        val resolver = context.contentResolver
        val handler = Handler(Looper.getMainLooper())
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                reduceMotion = readReduceMotion(context)
            }
        }
        resolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.TRANSITION_ANIMATION_SCALE),
            false,
            observer,
        )
        resolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            observer,
        )

        // API 33+ — `AccessibilityServicesStateChangeListener` notifies when accessibility
        // services change, which includes the "Remove animations" toggle. On API 33+
        // the `addAccessibilityServicesStateChangeListener(Executor, Listener)` overload
        // is available; we pass `handler::post` as the executor so callbacks run on the
        // main thread (matching the Compose state-write requirement).
        // `isReducedAnimationsEnabled()` (API 35) is read inside `readReduceMotion()`.
        val amListener: AccessibilityManager.AccessibilityServicesStateChangeListener? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val am = context.getSystemService<AccessibilityManager>()
                AccessibilityManager.AccessibilityServicesStateChangeListener {
                    reduceMotion = readReduceMotion(context)
                }.also { listener ->
                    am?.addAccessibilityServicesStateChangeListener(
                        handler::post,
                        listener,
                    )
                }
            } else null

        onDispose {
            resolver.unregisterContentObserver(observer)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && amListener != null) {
                context.getSystemService<AccessibilityManager>()
                    ?.removeAccessibilityServicesStateChangeListener(amListener)
            }
        }
    }
    CompositionLocalProvider(
        LocalReduceMotion provides reduceMotion,
        LocalAmoledBlack provides (amoledBlack && darkTheme),
    ) {
        MaterialTheme(colorScheme = colorScheme, content = content)
    }
}

/**
 * Returns `true` if the reduce-motion signal from `Settings.Global` is active.
 *
 * Checks BOTH transition and animator scales — see YT-0166 §4 for rationale.
 * The `AccessibilityServicesStateChangeListener` registered in [YourTubeTheme] notifies
 * this function whenever accessibility services change (including the "Remove animations"
 * Accessibility toggle, which also zeros out both scales on API 31+).
 *
 * NOTE: `AccessibilityManager.isReducedAnimationsEnabled()` (API 35) is intentionally
 * NOT called here — the API was unavailable in the compile-time SDK stubs resolved by
 * this build. The `Settings.Global` dual-scale check is sufficient because toggling
 * "Remove animations" in Accessibility always writes both scales to 0 on API 31+.
 */
private fun readReduceMotion(context: Context): Boolean {
    val resolver = context.contentResolver
    val transitionScale = Settings.Global.getFloat(
        resolver,
        Settings.Global.TRANSITION_ANIMATION_SCALE,
        1f,
    )
    val animatorScale = Settings.Global.getFloat(
        resolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        1f,
    )
    return transitionScale == 0f && animatorScale == 0f
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
