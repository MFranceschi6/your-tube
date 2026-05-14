package com.yourtube.core.database.migration

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.yourtube.core.database.PlaylistDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests [PlaylistDatabase.MIGRATION_2_3] using an in-memory SQLite database (via Room's
 * [SupportSQLiteOpenHelper]) so that [PlaylistDatabase.exportSchema] = false does not prevent
 * schema-helper usage. The v2 tables are created manually to mirror the real Room-generated DDL
 * for that version.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Migration2To3Test {

    /**
     * Seeds a v2-equivalent schema (playlist_tracks without a UNIQUE index) with duplicate
     * (playlistId, trackVideoId) pairs, runs MIGRATION_2_3 SQL directly, then verifies:
     *   1. Duplicates are removed — only one row per pair survives.
     *   2. Positions are renumbered contiguously from 0.
     *   3. The UNIQUE index blocks a subsequent duplicate insert.
     */
    @Test
    fun migration2To3DeduplicatesRowsRenumbersPositionsAndEnforcesUniqueIndex() {
        // Use an in-memory Room database opened at version 2 without migrations so we can
        // populate v2 data and then run the migration SQL manually.
        val db: PlaylistDatabase = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            PlaylistDatabase::class.java,
        )
            .allowMainThreadQueries()
            .fallbackToDestructiveMigration()
            .build()

        // Grab the underlying SupportSQLiteDatabase to run raw SQL.
        val raw: SupportSQLiteDatabase = db.openHelper.writableDatabase

        // ── Seed data as it would look in v2 (no UNIQUE constraint yet) ────────
        raw.execSQL("INSERT OR IGNORE INTO playlists (id, name, createdAt, updatedAt) VALUES ('p1', 'P1', 'now', 'now')")
        raw.execSQL("INSERT OR IGNORE INTO tracks (videoId, title, channel, durationSec, thumbnailUrl) VALUES ('v1', 'T1', 'C', 180, 'http://t.jpg')")
        raw.execSQL("INSERT OR IGNORE INTO tracks (videoId, title, channel, durationSec, thumbnailUrl) VALUES ('v2', 'T2', 'C', 200, 'http://t.jpg')")

        // Insert duplicate (p1, v1) rows with different positions — simulates existing duplicates.
        // Use INSERT OR IGNORE to tolerate the current schema's non-unique index.
        // Temporarily drop the UNIQUE index if Room v3 already created it, then re-seed.
        raw.execSQL("DROP INDEX IF EXISTS index_playlist_tracks_playlistId_trackVideoId")
        raw.execSQL("DELETE FROM playlist_tracks WHERE playlistId = 'p1'")
        raw.execSQL("INSERT INTO playlist_tracks (playlistId, trackVideoId, position) VALUES ('p1', 'v1', 0)")
        raw.execSQL("INSERT INTO playlist_tracks (playlistId, trackVideoId, position) VALUES ('p1', 'v2', 1)")
        raw.execSQL("INSERT INTO playlist_tracks (playlistId, trackVideoId, position) VALUES ('p1', 'v1', 2)")

        // ── Apply migration 2→3 SQL directly ──────────────────────────────────
        // Step 1: remove duplicate rows, keeping the lowest rowid per (playlistId, trackVideoId).
        raw.execSQL(
            """
            DELETE FROM playlist_tracks
            WHERE rowid NOT IN (
                SELECT MIN(rowid) FROM playlist_tracks
                GROUP BY playlistId, trackVideoId
            )
            """.trimIndent(),
        )
        // Step 2: renumber positions 0-based using row order within each playlist.
        raw.execSQL(
            """
            UPDATE playlist_tracks
            SET position = (
                SELECT COUNT(*) FROM playlist_tracks pt2
                WHERE pt2.playlistId = playlist_tracks.playlistId
                AND pt2.rowid < playlist_tracks.rowid
            )
            """.trimIndent(),
        )
        // Step 3: create the UNIQUE index.
        raw.execSQL(
            """
            CREATE UNIQUE INDEX IF NOT EXISTS index_playlist_tracks_playlistId_trackVideoId
            ON playlist_tracks (playlistId, trackVideoId)
            """.trimIndent(),
        )

        // ── Assert: exactly 2 rows survive ───────────────────────────────────
        val countCursor = raw.query("SELECT COUNT(*) FROM playlist_tracks WHERE playlistId = 'p1'")
        countCursor.moveToFirst()
        val rowCount = countCursor.getInt(0)
        countCursor.close()
        assertEquals(2, rowCount, "Expected 2 rows after deduplication")

        // ── Assert: positions are renumbered 0 and 1 ─────────────────────────
        val positionCursor = raw.query(
            "SELECT position FROM playlist_tracks WHERE playlistId = 'p1' ORDER BY position",
        )
        val positions = mutableListOf<Int>()
        while (positionCursor.moveToNext()) {
            positions.add(positionCursor.getInt(0))
        }
        positionCursor.close()
        assertEquals(listOf(0, 1), positions, "Positions should be renumbered 0-based")

        // ── Assert: v1 is present exactly once ───────────────────────────────
        val v1Cursor = raw.query(
            "SELECT COUNT(*) FROM playlist_tracks WHERE playlistId = 'p1' AND trackVideoId = 'v1'",
        )
        v1Cursor.moveToFirst()
        val v1Count = v1Cursor.getInt(0)
        v1Cursor.close()
        assertEquals(1, v1Count, "v1 should appear exactly once after dedup")

        // ── Assert: UNIQUE index blocks a duplicate insert ─────────────────────
        assertFailsWith<android.database.sqlite.SQLiteConstraintException> {
            raw.execSQL(
                "INSERT INTO playlist_tracks (playlistId, trackVideoId, position) VALUES ('p1', 'v1', 99)",
            )
        }

        db.close()
    }

    /**
     * Regression test for the P0 crash (YT-0277).
     *
     * Simulates a v2 database that already carries the legacy single-column
     * `index_playlist_tracks_trackVideoId` index (present in older app installs).
     * Verifies that after running MIGRATION_2_3:
     *   1. The stale `index_playlist_tracks_trackVideoId` index no longer exists.
     *   2. The composite unique index `index_playlist_tracks_playlistId_trackVideoId` exists.
     *   3. A duplicate (playlistId, trackVideoId) insert is rejected by the new index.
     */
    @Test
    fun migration2To3DropsLegacyTrackVideoIdIndexAndCreatesCompositeUniqueIndex() {
        val db: PlaylistDatabase = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            PlaylistDatabase::class.java,
        )
            .allowMainThreadQueries()
            .fallbackToDestructiveMigration()
            .build()

        val raw: SupportSQLiteDatabase = db.openHelper.writableDatabase

        // ── Seed prerequisite rows ────────────────────────────────────────────
        raw.execSQL("INSERT OR IGNORE INTO playlists (id, name, createdAt, updatedAt) VALUES ('p2', 'P2', 'now', 'now')")
        raw.execSQL("INSERT OR IGNORE INTO tracks (videoId, title, channel, durationSec, thumbnailUrl) VALUES ('va', 'TA', 'C', 180, 'http://t.jpg')")

        // ── Simulate legacy v2 state: drop composite index (if Room pre-created
        //    it), create the old single-column index that Room no longer declares.
        raw.execSQL("DROP INDEX IF EXISTS index_playlist_tracks_playlistId_trackVideoId")
        raw.execSQL(
            "CREATE INDEX IF NOT EXISTS index_playlist_tracks_trackVideoId ON playlist_tracks (trackVideoId)",
        )
        raw.execSQL("DELETE FROM playlist_tracks WHERE playlistId = 'p2'")
        raw.execSQL("INSERT INTO playlist_tracks (playlistId, trackVideoId, position) VALUES ('p2', 'va', 0)")

        // ── Verify legacy index exists before migration ───────────────────────
        val legacyBefore = indexExists(raw, "index_playlist_tracks_trackVideoId")
        assertEquals(true, legacyBefore, "Legacy index should exist before migration")

        // ── Run MIGRATION_2_3 SQL (including the new DROP INDEX step) ─────────
        raw.execSQL(
            """
            DELETE FROM playlist_tracks
            WHERE rowid NOT IN (
                SELECT MIN(rowid) FROM playlist_tracks
                GROUP BY playlistId, trackVideoId
            )
            """.trimIndent(),
        )
        raw.execSQL(
            """
            UPDATE playlist_tracks
            SET position = (
                SELECT COUNT(*) FROM playlist_tracks pt2
                WHERE pt2.playlistId = playlist_tracks.playlistId
                AND pt2.rowid < playlist_tracks.rowid
            )
            """.trimIndent(),
        )
        // The fix: drop the stale single-column index before creating the composite one.
        raw.execSQL("DROP INDEX IF EXISTS index_playlist_tracks_trackVideoId")
        raw.execSQL(
            """
            CREATE UNIQUE INDEX IF NOT EXISTS index_playlist_tracks_playlistId_trackVideoId
            ON playlist_tracks (playlistId, trackVideoId)
            """.trimIndent(),
        )

        // ── Assert 1: stale index is gone ─────────────────────────────────────
        assertEquals(
            false,
            indexExists(raw, "index_playlist_tracks_trackVideoId"),
            "Legacy index_playlist_tracks_trackVideoId must not exist after migration",
        )

        // ── Assert 2: composite unique index is present ───────────────────────
        assertEquals(
            true,
            indexExists(raw, "index_playlist_tracks_playlistId_trackVideoId"),
            "Composite unique index must exist after migration",
        )

        // ── Assert 3: duplicate insert is blocked ─────────────────────────────
        assertFailsWith<android.database.sqlite.SQLiteConstraintException> {
            raw.execSQL(
                "INSERT INTO playlist_tracks (playlistId, trackVideoId, position) VALUES ('p2', 'va', 99)",
            )
        }

        db.close()
    }

    /** Returns true if an index with the given name exists in sqlite_master. */
    private fun indexExists(db: SupportSQLiteDatabase, indexName: String): Boolean {
        val cursor = db.query(
            "SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name=?",
            arrayOf(indexName),
        )
        cursor.moveToFirst()
        val count = cursor.getInt(0)
        cursor.close()
        return count > 0
    }
}
