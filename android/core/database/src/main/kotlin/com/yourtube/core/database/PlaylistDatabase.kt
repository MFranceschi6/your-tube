package com.yourtube.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.yourtube.core.database.dao.HistoryDao
import com.yourtube.core.database.dao.PlaylistDao
import com.yourtube.core.database.dao.PlayerSnapshotDao
import com.yourtube.core.database.entity.HistoryEntryEntity
import com.yourtube.core.database.entity.PlaylistEntity
import com.yourtube.core.database.entity.PlaylistTrackEntity
import com.yourtube.core.database.entity.PlayerSnapshotEntity
import com.yourtube.core.database.entity.TrackEntity

@Database(
    entities = [
        PlaylistEntity::class,
        TrackEntity::class,
        PlaylistTrackEntity::class,
        HistoryEntryEntity::class,
        PlayerSnapshotEntity::class,
    ],
    version = 4,
    exportSchema = false,
)
abstract class PlaylistDatabase : RoomDatabase() {
    abstract fun playlistDao(): PlaylistDao
    abstract fun historyDao(): HistoryDao
    abstract fun playerSnapshotDao(): PlayerSnapshotDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `player_snapshot` (
                        `id` INTEGER NOT NULL DEFAULT 1,
                        `currentVideoId` TEXT,
                        `queue` TEXT NOT NULL,
                        `queueIndex` INTEGER NOT NULL,
                        `positionMs` INTEGER NOT NULL,
                        `repeatMode` INTEGER NOT NULL,
                        `shuffleOn` INTEGER NOT NULL,
                        `playbackSpeed` REAL NOT NULL,
                        `savedAt` TEXT NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. Keep lowest-rowid row per (playlistId, trackVideoId) to deduplicate.
                db.execSQL(
                    """
                    DELETE FROM playlist_tracks
                    WHERE rowid NOT IN (
                        SELECT MIN(rowid) FROM playlist_tracks
                        GROUP BY playlistId, trackVideoId
                    )
                    """.trimIndent(),
                )
                // 2. Renumber positions 0-based per playlist using row order.
                db.execSQL(
                    """
                    UPDATE playlist_tracks
                    SET position = (
                        SELECT COUNT(*) FROM playlist_tracks pt2
                        WHERE pt2.playlistId = playlist_tracks.playlistId
                        AND pt2.rowid < playlist_tracks.rowid
                    )
                    """.trimIndent(),
                )
                // 3. Drop the legacy single-column trackVideoId index that Room no longer
                //    declares on PlaylistTrackEntity. Leaving it would cause Room's post-migration
                //    schema validation to throw IllegalStateException on migrated databases.
                db.execSQL("DROP INDEX IF EXISTS index_playlist_tracks_trackVideoId")
                // 4. Add the UNIQUE index to enforce the constraint going forward.
                db.execSQL(
                    """
                    CREATE UNIQUE INDEX IF NOT EXISTS index_playlist_tracks_playlistId_trackVideoId
                    ON playlist_tracks (playlistId, trackVideoId)
                    """.trimIndent(),
                )
            }
        }

        // YT-0273: adds title, channelName, thumbnailUrl to player_snapshot for widget display.
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE player_snapshot ADD COLUMN title TEXT")
                db.execSQL("ALTER TABLE player_snapshot ADD COLUMN channelName TEXT")
                db.execSQL("ALTER TABLE player_snapshot ADD COLUMN thumbnailUrl TEXT")
            }
        }
    }
}
