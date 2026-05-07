package com.yourtube.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.yourtube.core.database.dao.HistoryDao
import com.yourtube.core.database.dao.PlaylistDao
import com.yourtube.core.database.entity.HistoryEntryEntity
import com.yourtube.core.database.entity.PlaylistEntity
import com.yourtube.core.database.entity.PlaylistTrackEntity
import com.yourtube.core.database.entity.TrackEntity

@Database(
    entities = [
        PlaylistEntity::class,
        TrackEntity::class,
        PlaylistTrackEntity::class,
        HistoryEntryEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class PlaylistDatabase : RoomDatabase() {
    abstract fun playlistDao(): PlaylistDao
    abstract fun historyDao(): HistoryDao
}
