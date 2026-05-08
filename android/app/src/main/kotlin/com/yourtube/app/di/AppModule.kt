package com.yourtube.app.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import com.yourtube.app.connectivity.AndroidConnectivityMonitor
import com.yourtube.core.common.ConnectivityMonitor
import com.yourtube.core.data.codec.KotlinxPlaylistCodec
import com.yourtube.core.data.codec.PlaylistCodec
import com.yourtube.core.data.preferences.AudioQualityPreferences
import com.yourtube.core.data.preferences.DataStoreAudioQualityPreferences
import com.yourtube.core.data.preferences.DataStorePlaybackLifecyclePreferences
import com.yourtube.core.data.preferences.PlaybackLifecyclePreferences
import com.yourtube.core.data.repository.OfflineFirstPlaylistRepository
import com.yourtube.core.data.repository.PlaylistRepository
import com.yourtube.core.database.PlaylistDatabase
import com.yourtube.core.database.dao.HistoryDao
import com.yourtube.core.database.dao.PlaylistDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun providePlaylistDatabase(@ApplicationContext context: Context): PlaylistDatabase =
        Room.databaseBuilder(context, PlaylistDatabase::class.java, "yourtube.db")
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides
    fun providePlaylistDao(db: PlaylistDatabase): PlaylistDao = db.playlistDao()

    @Provides
    fun provideHistoryDao(db: PlaylistDatabase): HistoryDao = db.historyDao()

    @Provides
    @Singleton
    fun providePlaylistRepository(
        playlistDao: PlaylistDao,
        historyDao: HistoryDao,
    ): PlaylistRepository = OfflineFirstPlaylistRepository(playlistDao, historyDao)

    @Provides
    @Singleton
    fun provideSettingsDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        context.settingsDataStore

    @Provides
    @Singleton
    fun provideAudioQualityPreferences(
        dataStore: DataStore<Preferences>,
    ): AudioQualityPreferences = DataStoreAudioQualityPreferences(dataStore)

    /**
     * YT-0241 — opt-in toggle for "stop playback when app is closed". Backed by the
     * same DataStore instance as audio quality; keys do not collide.
     */
    @Provides
    @Singleton
    fun providePlaybackLifecyclePreferences(
        dataStore: DataStore<Preferences>,
    ): PlaybackLifecyclePreferences = DataStorePlaybackLifecyclePreferences(dataStore)

    @Provides
    @Singleton
    fun providePlaylistCodec(): PlaylistCodec = KotlinxPlaylistCodec()

    /**
     * YT-0164 — `ConnectivityMonitor` for Search C4 vs. C5 (offline) discrimination.
     * Backed by `ConnectivityManager`; the manifest already declares the
     * `ACCESS_NETWORK_STATE` permission.
     */
    @Provides
    @Singleton
    fun provideConnectivityMonitor(
        impl: AndroidConnectivityMonitor,
    ): ConnectivityMonitor = impl
}
