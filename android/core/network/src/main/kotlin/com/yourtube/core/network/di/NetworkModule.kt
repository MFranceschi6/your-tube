package com.yourtube.core.network.di

import com.yourtube.core.network.InnerTubeYoutubeExtractorClient
import com.yourtube.core.network.NewPipeYoutubeService
import com.yourtube.core.network.RelatedVideoClient
import com.yourtube.core.network.SuggestClient
import com.yourtube.core.network.YoutubeService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        // YT-0309 — defense-in-depth: cap the full OkHttp call at 14s (1s shorter than
        // the 15s Kotlin withTimeoutOrNull ceilings in DefaultPlayerController and
        // PlaybackService) so the HTTP layer loses the race deterministically. OkHttp
        // throws IOException, propagating CancellationException up through the suspend
        // chain and releasing the service-side coroutine before the Kotlin timer fires.
        .callTimeout(14L, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    fun provideYoutubeService(okHttpClient: OkHttpClient): YoutubeService =
        NewPipeYoutubeService(
            extractorClient = InnerTubeYoutubeExtractorClient(okHttpClient),
            relatedVideoClient = RelatedVideoClient(okHttpClient),
        )

    /**
     * YT-0082 — autocomplete suggestions. [SuggestClient] uses the same
     * [OkHttpClient] instance as the rest of the network layer; no additional
     * interceptors are required.
     */
    @Provides
    @Singleton
    fun provideSuggestClient(okHttpClient: OkHttpClient): SuggestClient =
        SuggestClient(okHttpClient)
}
