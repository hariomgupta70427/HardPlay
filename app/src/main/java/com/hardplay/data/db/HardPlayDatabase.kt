package com.hardplay.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.hardplay.data.db.dao.ChannelDao
import com.hardplay.data.db.dao.FavouriteDao
import com.hardplay.data.db.dao.MediaDao
import com.hardplay.data.db.dao.PlaybackDao
import com.hardplay.data.db.dao.SyncStateDao
import com.hardplay.data.db.dao.TagDao
import com.hardplay.data.db.entity.ChannelEntity
import com.hardplay.data.db.entity.FavouriteEntity
import com.hardplay.data.db.entity.MediaEntity
import com.hardplay.data.db.entity.MediaFtsEntity
import com.hardplay.data.db.entity.MediaTagCrossRef
import com.hardplay.data.db.entity.PlaybackEntity
import com.hardplay.data.db.entity.SyncStateEntity
import com.hardplay.data.db.entity.TagEntity
import com.hardplay.data.db.projection.LibraryRow
import com.hardplay.data.db.projection.LibraryRowSql

/**
 * The metadata cache. Never holds media bytes — those live in TDLib's own file
 * store, which has its own eviction policy and size cap (PRD §5.4).
 *
 * Not encrypted, and that is a considered choice rather than an omission: the
 * database holds captions and tags, while the thing actually worth protecting is
 * the authenticated Telegram session, which TDLib already encrypts. Adding
 * SQLCipher here would mean a passphrase to manage and a foreign-key/FTS-capable
 * build to maintain, in exchange for protecting text the biometric gate and
 * `FLAG_SECURE` already stand in front of.
 */
@Database(
    version = HardPlayDatabase.VERSION,
    exportSchema = true,
    views = [LibraryRow::class],
    entities = [
        ChannelEntity::class,
        MediaEntity::class,
        MediaFtsEntity::class,
        TagEntity::class,
        MediaTagCrossRef::class,
        PlaybackEntity::class,
        SyncStateEntity::class,
        FavouriteEntity::class,
    ],
)
abstract class HardPlayDatabase : RoomDatabase() {

    abstract fun mediaDao(): MediaDao
    abstract fun tagDao(): TagDao
    abstract fun channelDao(): ChannelDao
    abstract fun syncStateDao(): SyncStateDao
    abstract fun playbackDao(): PlaybackDao
    abstract fun favouriteDao(): FavouriteDao

    companion object {
        const val VERSION = 3
        const val NAME = "hardplay.db"

        /**
         * v1 → v2: poster artwork, favourites, play counts.
         *
         * Written rather than falling back to a destructive migration, because by
         * this point there is data here that Telegram cannot give back: tags typed
         * by hand, resume positions, and now saved items. Re-indexing recovers
         * captions; it does not recover those.
         *
         * The `sync_state` reset at the end is the part that makes the upgrade
         * *do* something. The new poster columns are null for every existing row,
         * and neither sync direction would ever revisit them — the head cursor only
         * looks at messages newer than the floor, and a completed backfill never
         * walks again. Rewinding the tail cursor makes the next sync re-walk
         * history and fill them in. `MediaDao.upsert` keys on (chatId, messageId)
         * and keeps the row's `localId`, so tags, favourites and positions all
         * survive that re-walk.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `media` ADD COLUMN `minithumbnail` BLOB DEFAULT NULL")
                db.execSQL("ALTER TABLE `media` ADD COLUMN `posterFileId` INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE `media` ADD COLUMN `posterForMessageId` INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE `playback` ADD COLUMN `playCount` INTEGER NOT NULL DEFAULT 0")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `favourites` (
                        `localId` INTEGER NOT NULL,
                        `addedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`localId`),
                        FOREIGN KEY(`localId`) REFERENCES `media`(`localId`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_favourites_addedAt` ON `favourites` (`addedAt`)",
                )

                db.execSQL(
                    "UPDATE `sync_state` SET `backfillComplete` = 0, `oldestIndexedMessageId` = 0",
                )

                // Room does *not* create views during a migration — its generated
                // onPostMigrate is empty — but onValidateSchema does check the view
                // exists and that its SQL matches exactly. Omitting this is a launch
                // crash reading "Migration didn't properly handle: library_row".
                db.execSQL("DROP VIEW IF EXISTS `${LibraryRowSql.NAME}`")
                db.execSQL("CREATE VIEW `${LibraryRowSql.NAME}` AS ${LibraryRowSql.SELECT}")
            }
        }

        /**
         * v2 → v3: artwork that isn't soft.
         *
         * Two columns, and a cursor rewind that is the point of the whole migration.
         *
         * `previewFileId` holds a larger rung of a photo's size ladder. Every existing
         * row has null there, and no sync direction would ever revisit them — the head
         * cursor only looks above the floor, and a finished backfill never walks again
         * — so the tail cursor is rewound to make the next sync re-walk history and
         * fill it in. `MediaDao.upsert` keys on (chatId, messageId) and keeps the row's
         * `localId`, so tags, saved state and resume positions all survive that walk.
         *
         * `posterPath` points at a decoded frame on disk. Nothing to backfill: it is
         * written as frames become available, and null simply means "use the rungs".
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `media` ADD COLUMN `previewFileId` INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE `media` ADD COLUMN `posterPath` TEXT DEFAULT NULL")

                db.execSQL(
                    "UPDATE `sync_state` SET `backfillComplete` = 0, `oldestIndexedMessageId` = 0",
                )

                // Room's generated onPostMigrate is empty while onValidateSchema does
                // check views — omitting this is a launch crash. See MIGRATION_1_2.
                db.execSQL("DROP VIEW IF EXISTS `${LibraryRowSql.NAME}`")
                db.execSQL("CREATE VIEW `${LibraryRowSql.NAME}` AS ${LibraryRowSql.SELECT}")
            }
        }
    }
}
