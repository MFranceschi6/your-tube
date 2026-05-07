package com.yourtube.core.network.di

import com.yourtube.core.network.InnerTubeYoutubeExtractorClient
import com.yourtube.core.network.NewPipeYoutubeService
import com.yourtube.core.network.YoutubeService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder().build()

    @Provides
    @Singleton
    fun provideYoutubeService(okHttpClient: OkHttpClient): YoutubeService =
        NewPipeYoutubeService(
            extractorClient = InnerTubeYoutubeExtractorClient(okHttpClient),
        )
}
