package com.yourtube.app.haptics

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.yourtube.core.common.haptics.HapticsController
import com.yourtube.core.data.preferences.HapticsPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * [HapticsController] backed by [VibratorManager] (API 31+) with a [Vibrator] fallback.
 * Uses [VibrationEffect] predefined constants for consistent cross-device feel.
 *
 * Haptics are gated by [HapticsPreferences.hapticsEnabled]; changes take effect immediately
 * because the preference is collected into a `@Volatile` field via an internal coroutine scope.
 * Only user-initiated transport actions trigger vibration — system-driven callbacks route
 * through [com.yourtube.core.player.PlaybackTransportListener] and never touch this class.
 */
private const val TAG = "AndroidHapticsController"

@Singleton
class AndroidHapticsController @Inject constructor(
    @ApplicationContext private val context: Context,
    hapticsPreferences: HapticsPreferences,
) : HapticsController {

    private val scope = CoroutineScope(SupervisorJob())

    @Volatile
    private var enabled = HapticsPreferences.DEFAULT_HAPTICS_ENABLED

    init {
        hapticsPreferences.hapticsEnabled
            .onEach { enabled = it }
            .launchIn(scope)
    }

    override fun onPlayPause() {
        vibrate(VibrationEffect.EFFECT_CLICK)
    }

    override fun onSkip() {
        vibrate(VibrationEffect.EFFECT_DOUBLE_CLICK)
    }

    override fun onQueueAdd() {
        vibrate(VibrationEffect.EFFECT_HEAVY_CLICK)
    }

    override fun onPlayNext() {
        vibrate(VibrationEffect.EFFECT_TICK)
    }

    private fun vibrate(predefinedEffectId: Int) {
        if (!enabled) return
        val effect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            VibrationEffect.createPredefined(predefinedEffectId)
        } else {
            @Suppress("DEPRECATION")
            VibrationEffect.createOneShot(50L, VibrationEffect.DEFAULT_AMPLITUDE)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                manager.defaultVibrator.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                vibrator.vibrate(effect)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "VIBRATE permission missing — haptics disabled", e)
            enabled = false
        }
    }
}
