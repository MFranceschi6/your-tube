package com.yourtube.core.player

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
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

    /** YT-0093 — Sleep timer. */
    @Binds
    @Singleton
    abstract fun bindSleepTimerController(impl: DefaultSleepTimerController): SleepTimerController

    /** YT-0089 — Autoplay controller. */
    @Binds
    @Singleton
    abstract fun bindAutoplayController(impl: DefaultAutoplayController): AutoplayController

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
                // YT-0244 — snap seeks to the nearest sync sample. Music-only HLS sources
                // typically use sub-10s segments, so the worst-case offset is small and well
                // below the threshold of perception, while the seek itself completes WITHOUT
                // a network round-trip to fetch the next sync sample. `SeekParameters.EXACT`
                // would re-decode from the previous sync sample (closer to the original slow
                // behaviour the user reported); `CLOSEST_SYNC` is the right default for
                // music playback. Set on the built ExoPlayer because `setSeekParameters` is
                // an ExoPlayer-level (not Builder-level) API in Media3 1.4.x.
                .apply { setSeekParameters(SeekParameters.CLOSEST_SYNC) }
        }

        @Provides
        @MainDispatcher
        fun provideMainDispatcher(): CoroutineDispatcher = Dispatchers.Main.immediate
    }
}
