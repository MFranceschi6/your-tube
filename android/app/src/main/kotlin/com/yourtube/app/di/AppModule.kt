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
import com.yourtube.core.data.preferences.AmoledPreferences
import com.yourtube.core.data.preferences.AudioQualityPreferences
import com.yourtube.core.data.preferences.AutoplayPreferences
import com.yourtube.app.haptics.AndroidHapticsController
import com.yourtube.core.common.haptics.HapticsController
import com.yourtube.core.data.preferences.DataStoreAmoledPreferences
import com.yourtube.core.data.preferences.DataStoreAudioQualityPreferences
import com.yourtube.core.data.preferences.DataStoreAutoplayPreferences
import com.yourtube.core.data.preferences.DataStoreHapticsPreferences
import com.yourtube.core.data.preferences.DataStorePlaybackLifecyclePreferences
import com.yourtube.core.data.preferences.DataStorePlaybackSpeedPreferences
import com.yourtube.core.data.preferences.DataStoreRecentSearchPreferences
import com.yourtube.core.data.preferences.DataStoreThemePreferences
import com.yourtube.core.data.preferences.HapticsPreferences
import com.yourtube.core.data.preferences.PlaybackLifecyclePreferences
import com.yourtube.core.data.preferences.ThemePreferences
import com.yourtube.core.data.preferences.PlaybackSpeedPreferences
import com.yourtube.core.data.preferences.RecentSearchPreferences
import com.yourtube.core.data.repository.DefaultPlayerSnapshotRepository
import com.yourtube.core.data.repository.OfflineFirstPlaylistRepository
import com.yourtube.core.data.repository.PlayerSnapshotRepository
import com.yourtube.core.data.repository.PlaylistRepository
import com.yourtube.core.data.update.RemoteUpdateCheckRepository
import com.yourtube.core.data.update.UpdateCheckRepository
import com.yourtube.core.database.PlaylistDatabase
import com.yourtube.core.database.dao.HistoryDao
import com.yourtube.core.database.dao.PlaylistDao
import com.yourtube.core.database.dao.PlayerSnapshotDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import okhttp3.OkHttpClient

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun providePlaylistDatabase(@ApplicationContext context: Context): PlaylistDatabase =
        Room.databaseBuilder(context, PlaylistDatabase::class.java, "yourtube.db")
            .addMigrations(
                PlaylistDatabase.MIGRATION_1_2,
                PlaylistDatabase.MIGRATION_2_3,
                PlaylistDatabase.MIGRATION_3_4,
            )
            .build()

    @Provides
    fun providePlaylistDao(db: PlaylistDatabase): PlaylistDao = db.playlistDao()

    @Provides
    fun provideHistoryDao(db: PlaylistDatabase): HistoryDao = db.historyDao()

    @Provides
    fun providePlayerSnapshotDao(db: PlaylistDatabase): PlayerSnapshotDao = db.playerSnapshotDao()

    @Provides
    @Singleton
    fun providePlayerSnapshotRepository(
        dao: PlayerSnapshotDao,
    ): PlayerSnapshotRepository = DefaultPlayerSnapshotRepository(dao)

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
    fun providePlaybackSpeedPreferences(
        dataStore: DataStore<Preferences>,
    ): PlaybackSpeedPreferences = DataStorePlaybackSpeedPreferences(dataStore)

    @Provides
    @Singleton
    fun provideHapticsPreferences(
        dataStore: DataStore<Preferences>,
    ): HapticsPreferences = DataStoreHapticsPreferences(dataStore)

    /**
     * YT-0102 — AMOLED-black theme toggle. Backed by the same DataStore instance as
     * other preferences; the key `amoled_black_enabled` does not collide with existing keys.
     */
    @Provides
    @Singleton
    fun provideAmoledPreferences(
        dataStore: DataStore<Preferences>,
    ): AmoledPreferences = DataStoreAmoledPreferences(dataStore)

    @Provides
    @Singleton
    fun provideHapticsController(
        impl: AndroidHapticsController,
    ): HapticsController = impl

    @Provides
    @Singleton
    fun providePlaylistCodec(): PlaylistCodec = KotlinxPlaylistCodec()

    /**
     * YT-0251 — UpdateCheckRepository backed by the hosted GitHub Pages JSON feed.
     * Uses OkHttp (already on the classpath) + kotlinx.serialization; no Play Core,
     * no Google Play Services.
     */
    @Provides
    @Singleton
    fun provideUpdateCheckRepository(
        okHttpClient: OkHttpClient,
    ): UpdateCheckRepository = RemoteUpdateCheckRepository(okHttpClient)

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

    /**
     * YT-0082 — recent search queries. Backed by the shared `settings` DataStore;
     * the `recent_searches` key does not collide with any existing key.
     */
    @Provides
    @Singleton
    fun provideRecentSearchPreferences(
        dataStore: DataStore<Preferences>,
    ): RecentSearchPreferences = DataStoreRecentSearchPreferences(dataStore)

    /**
     * YT-0089 — autoplay preference. Backed by the shared `settings` DataStore;
     * the `autoplay_enabled` key does not collide with any existing key.
     */
    @Provides
    @Singleton
    fun provideAutoplayPreferences(
        dataStore: DataStore<Preferences>,
    ): AutoplayPreferences = DataStoreAutoplayPreferences(dataStore)

    /**
     * YT-0316 — theme preference (System / Light / Dark). Backed by the shared `settings`
     * DataStore; the `theme_preference` key does not collide with any existing key.
     */
    @Provides
    @Singleton
    fun provideThemePreferences(
        dataStore: DataStore<Preferences>,
    ): ThemePreferences = DataStoreThemePreferences(dataStore)
}
