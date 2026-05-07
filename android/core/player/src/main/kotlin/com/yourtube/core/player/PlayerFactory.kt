package com.yourtube.core.player

import android.content.Context
import androidx.media3.exoplayer.ExoPlayer

/**
 * Factory for creating an [ExoPlayer] instance.
 *
 * Keeping creation behind an interface lets tests supply a fake player
 * without spinning up a real ExoPlayer process.
 */
fun interface PlayerFactory {
    fun create(context: Context): ExoPlayer
}
