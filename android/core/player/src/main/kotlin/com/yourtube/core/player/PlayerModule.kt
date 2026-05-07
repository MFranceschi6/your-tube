package com.yourtube.core.player

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

@Module
@InstallIn(SingletonComponent::class)
abstract class PlayerModule {

    @Binds
    @Singleton
    abstract fun bindPlayerController(impl: DefaultPlayerController): PlayerController

    @Binds
    @Singleton
    abstract fun bindPlaybackTransport(impl: MediaControllerPlaybackClient): PlaybackTransport

    @Binds
    @Singleton
    abstract fun bindLogger(impl: AndroidLogger): Logger

    companion object {

        /**
         * Default [PlayerFactory] that creates an [ExoPlayer] with:
         * - Audio-only [AudioAttributes] (music usage + media content type)
         * - `handleAudioBecomingNoisy = true` — ExoPlayer pauses automatically on headset unplug
         * - `handleAudioFocus = true` — ExoPlayer manages audio focus automatically
         *
         * All three are Media3 defaults; listed explicitly for clarity and testability.
         */
        @Provides
        @Singleton
        fun providePlayerFactory(): PlayerFactory = PlayerFactory { context ->
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build()

            ExoPlayer.Builder(context)
                .setAudioAttributes(audioAttributes, /* handleAudioFocus= */ true)
                .setHandleAudioBecomingNoisy(true)
                .build()
        }

        @Provides
        @MainDispatcher
        fun provideMainDispatcher(): CoroutineDispatcher = Dispatchers.Main.immediate
    }
}
