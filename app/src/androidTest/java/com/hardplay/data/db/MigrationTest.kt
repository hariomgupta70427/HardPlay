package com.hardplay.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The migrations, run against a real database.
 *
 * This test exists because of a specific gap that cost a release: a clean install
 * creates the newest schema directly and therefore never runs a single migration, so
 * every unit test and every fresh install can pass while an *upgrade* — which is what
 * every existing user gets — crashes on launch. `runMigrationsAndValidate` compares the
 * migrated database against the exported schema JSON column by column, index by index
 * **and view by view**, which is the one check that catches it.
 *
 * The view is the part most likely to break. Room does not create views during a
 * migration — its generated `onPostMigrate` is empty — while `onValidateSchema` does
 * check them, so a migration that changes the row shape and forgets its `CREATE VIEW`
 * fails with "Migration didn't properly handle: library_row" *after* the migration has
 * already run.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        HardPlayDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    /**
     * v2 → v3, with data in the tables.
     *
     * Rows are inserted rather than migrating an empty database, because an empty one
     * cannot catch the things that actually go wrong here: a foreign key that stops
     * resolving, or a row that the new columns silently drop.
     */
    @Test
    fun migrates2To3AndKeepsData() {
        helper.createDatabase(NAME, 2).use { db ->
            db.execSQL(
                """
                INSERT INTO channels
                    (chatId, title, username, photoFileId, knownMessageCount,
                     sortIndex, enabled, addedAt)
                VALUES (-100, 'Night Reel', NULL, NULL, 12, 0, 1, 1700000000000)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO media
                    (localId, chatId, messageId, type, caption, title, date,
                     durationSeconds, width, height, thumbnailFileId, minithumbnail,
                     posterFileId, posterForMessageId, fileId, remoteFileId,
                     remoteUniqueId, fileSizeBytes, tagsParsed, lastSyncedAt)
                VALUES (1, -100, 41, 'VIDEO', 'Harbour Lights', 'Harbour Lights',
                        1700000000, 120, 1920, 1080, 7, NULL, NULL, NULL, 9,
                        'remote-9', 'uniq-9', 1024, 0, 1700000000000)
                """.trimIndent(),
            )
            // A playback row and a favourite, because the whole point of writing a
            // migration rather than dropping the database is that these survive.
            db.execSQL(
                """
                INSERT INTO playback (localId, positionMs, durationMs, completed,
                                      playCount, updatedAt)
                VALUES (1, 5000, 120000, 0, 2, 1700000001000)
                """.trimIndent(),
            )
            db.execSQL("INSERT INTO favourites (localId, addedAt) VALUES (1, 1700000002000)")
        }

        // Throws if the migrated schema disagrees with 3.json in any respect.
        val migrated = helper.runMigrationsAndValidate(
            NAME,
            3,
            /* validateDroppedTables = */ true,
            HardPlayDatabase.MIGRATION_2_3,
        )

        migrated.query("SELECT previewFileId, posterPath FROM media WHERE localId = 1").use {
            assertTrue("the media row must survive the migration", it.moveToFirst())
            assertTrue("previewFileId starts null and is filled by a re-sync", it.isNull(0))
            assertTrue("posterPath starts null; frames are decoded later", it.isNull(1))
        }

        // The view has to be readable, not merely present: the whole app reads through
        // it, so a view that validates but selects a column that no longer exists would
        // still take every screen down.
        migrated.query("SELECT localId, previewFileId, posterPath FROM library_row").use {
            assertTrue("library_row must return the joined row", it.moveToFirst())
            assertEquals(1L, it.getLong(0))
        }

        migrated.query("SELECT positionMs FROM playback WHERE localId = 1").use {
            assertTrue(it.moveToFirst())
            assertEquals(5000L, it.getLong(0))
        }
        migrated.query("SELECT COUNT(*) FROM favourites WHERE localId = 1").use {
            assertTrue(it.moveToFirst())
            assertEquals(1, it.getInt(0))
        }

        // The cursor rewind is the point of this migration, not a side effect: without
        // it no sync direction ever revisits existing rows and the artwork columns above
        // stay null forever.
        migrated.query("SELECT backfillComplete, oldestIndexedMessageId FROM sync_state").use {
            while (it.moveToNext()) {
                assertEquals("backfill must be reopened", 0, it.getInt(0))
                assertEquals("tail cursor must be rewound", 0L, it.getLong(1))
            }
        }
        migrated.close()
    }

    /** The full ladder, as a device upgrading from the very first release would run it. */
    @Test
    fun migrates1To3() {
        helper.createDatabase(NAME, 1).close()
        helper.runMigrationsAndValidate(
            NAME,
            3,
            true,
            HardPlayDatabase.MIGRATION_1_2,
            HardPlayDatabase.MIGRATION_2_3,
        ).close()
    }

    private companion object {
        const val NAME = "migration-test.db"
    }
}
