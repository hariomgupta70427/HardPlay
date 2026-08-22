package com.hardplay.data.db.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.hardplay.data.db.entity.MediaEntity
import com.hardplay.data.db.projection.LibraryRow
import com.hardplay.data.db.projection.LibraryTotals
import kotlinx.coroutines.flow.Flow

/**
 * The library's read and write path.
 *
 * Three things are worth knowing before editing:
 *
 *  1. **Reads go through the `library_row` view**, not hand-written joins. See
 *     [LibraryRow] — the shape is declared once and every screen shares it.
 *  2. **The FTS table is never written from Kotlin.** [reindex] rebuilds a row
 *     with `INSERT ... SELECT`, so the indexed text is derived from `media` and
 *     `media_tag` by the database itself. Passing caption and tag strings in from
 *     the caller would eventually let the index drift from the rows it indexes.
 *  3. **Sorting is a `CASE` ladder, not string concatenation.** A dynamic
 *     `ORDER BY` would mean `@RawQuery`, which gives up Room's compile-time SQL
 *     verification. Only one branch is ever non-null, so the rest collapse to
 *     ties and cost nothing.
 */
@Dao
interface MediaDao {

    // ---------------------------------------------------------------- library

    /**
     * The library grid, paged.
     *
     * @param sourceCount size of [sourceIds]; zero means "every enabled channel".
     *   SQLite permits an empty `IN ()` (it evaluates false), but the count guard
     *   is what makes the unfiltered case skip the test entirely.
     * @param ftsMatch a [com.hardplay.data.db.FtsQuery] expression. Must be
     *   non-null whenever [hasQuery] is 1.
     * @param tagCount size of [tagIds]. Selected tags are ANDed — picking a
     *   second tag narrows the grid, which is what a filter is for.
     * @param sort ordinal of [com.hardplay.data.model.LibrarySort].
     * @param shuffleSeed seed for [com.hardplay.data.model.LibrarySort.SHUFFLE]. One
     *   value per process, so the order holds still while paging walks it and changes on
     *   the next launch. `ORDER BY random()` cannot be used: it is re-evaluated per
     *   query, so page two would be drawn from a different permutation than page one and
     *   the grid would repeat and skip items as you scrolled.
     */
    @Query(
        """
        SELECT * FROM library_row
        WHERE channelEnabled = 1
          AND (:sourceCount = 0 OR chatId IN (:sourceIds))
          AND (:type IS NULL OR type = :type)
          AND (:favouritesOnly = 0 OR favouritedAt IS NOT NULL)
          AND (:hasQuery = 0 OR localId IN (
                SELECT rowid FROM media_fts WHERE media_fts MATCH :ftsMatch))
          AND (:tagCount = 0 OR (
                SELECT COUNT(DISTINCT mt.tagId) FROM media_tag mt
                WHERE mt.localId = library_row.localId AND mt.tagId IN (:tagIds)) = :tagCount)
          AND (:unseenOnly = 0 OR positionMs IS NULL)
          AND (:hidePairedStills = 0 OR posterForMessageId IS NULL)
        ORDER BY
            CASE :sort WHEN 0 THEN date END DESC,
            CASE :sort WHEN 1 THEN date END ASC,
            CASE :sort WHEN 2 THEN fileSizeBytes END DESC,
            CASE :sort WHEN 3 THEN durationSeconds END DESC,
            CASE :sort WHEN 4 THEN title END COLLATE NOCASE ASC,
            CASE :sort WHEN 5 THEN favouritedAt END DESC,
            CASE :sort WHEN 6 THEN lastPlayedAt END DESC,
            CASE :sort WHEN 7 THEN (localId * :shuffleSeed) % 2147483647 END ASC,
            date DESC, localId DESC
        """,
    )
    fun pageLibrary(
        sourceIds: List<Long>,
        sourceCount: Int,
        type: String?,
        hasQuery: Int,
        ftsMatch: String?,
        tagIds: List<Long>,
        tagCount: Int,
        unseenOnly: Int,
        favouritesOnly: Int,
        hidePairedStills: Int,
        sort: Int,
        shuffleSeed: Int,
    ): PagingSource<Int, LibraryRow>

    /** Row count for the same filter, so the header can say how many items the
     *  current view holds without walking the pager. */
    @Query(
        """
        SELECT COUNT(*) FROM library_row
        WHERE channelEnabled = 1
          AND (:sourceCount = 0 OR chatId IN (:sourceIds))
          AND (:type IS NULL OR type = :type)
          AND (:favouritesOnly = 0 OR favouritedAt IS NOT NULL)
          AND (:hasQuery = 0 OR localId IN (
                SELECT rowid FROM media_fts WHERE media_fts MATCH :ftsMatch))
          AND (:tagCount = 0 OR (
                SELECT COUNT(DISTINCT mt.tagId) FROM media_tag mt
                WHERE mt.localId = library_row.localId AND mt.tagId IN (:tagIds)) = :tagCount)
          AND (:unseenOnly = 0 OR positionMs IS NULL)
          AND (:hidePairedStills = 0 OR posterForMessageId IS NULL)
        """,
    )
    fun countLibrary(
        sourceIds: List<Long>,
        sourceCount: Int,
        type: String?,
        hasQuery: Int,
        ftsMatch: String?,
        tagIds: List<Long>,
        tagCount: Int,
        unseenOnly: Int,
        favouritesOnly: Int,
        hidePairedStills: Int,
    ): Flow<Int>

    @Query(
        """
        SELECT
            COUNT(*) AS itemCount,
            COALESCE(SUM(CASE WHEN type = 'VIDEO' THEN 1 ELSE 0 END), 0) AS videoCount,
            COALESCE(SUM(CASE WHEN type = 'PHOTO' THEN 1 ELSE 0 END), 0) AS photoCount,
            COALESCE(SUM(fileSizeBytes), 0) AS totalBytes
        FROM library_row WHERE channelEnabled = 1
        """,
    )
    fun observeTotals(): Flow<LibraryTotals>

    @Query("SELECT * FROM library_row WHERE localId = :localId")
    fun observeRow(localId: Long): Flow<LibraryRow?>

    @Query("SELECT * FROM library_row WHERE localId = :localId")
    suspend fun row(localId: Long): LibraryRow?

    // ------------------------------------------------------------- the shelves

    /** Recently opened, newest first — the History tab. */
    @Query(
        """
        SELECT * FROM library_row
        WHERE channelEnabled = 1 AND lastPlayedAt IS NOT NULL
        ORDER BY lastPlayedAt DESC
        """,
    )
    fun pageHistory(): PagingSource<Int, LibraryRow>

    /**
     * Most watched.
     *
     * `playCount > 1` on purpose: everything you have ever opened has a count of
     * one, so including those would just be History with a different sort and the
     * shelf would say nothing.
     */
    @Query(
        """
        SELECT * FROM library_row
        WHERE channelEnabled = 1 AND playCount > 1
        ORDER BY playCount DESC, lastPlayedAt DESC
        LIMIT :limit
        """,
    )
    fun observeMostWatched(limit: Int = 20): Flow<List<LibraryRow>>

    /**
     * Items sharing tags with what you've been watching, that you haven't opened.
     *
     * The ranking is tag overlap: an item carrying three of your recent tags comes
     * before one carrying a single tag. All local — no model, no network, nothing
     * leaves the device (PRD §9).
     */
    @Query(
        """
        WITH recent AS (
            SELECT localId FROM playback ORDER BY updatedAt DESC LIMIT 20
        ),
        recent_tags AS (
            SELECT DISTINCT tagId FROM media_tag WHERE localId IN (SELECT localId FROM recent)
        )
        SELECT * FROM library_row r
        WHERE r.channelEnabled = 1
          AND r.positionMs IS NULL
          AND EXISTS (
              SELECT 1 FROM media_tag mt
              WHERE mt.localId = r.localId AND mt.tagId IN (SELECT tagId FROM recent_tags)
          )
        ORDER BY (
            SELECT COUNT(*) FROM media_tag mt2
            WHERE mt2.localId = r.localId AND mt2.tagId IN (SELECT tagId FROM recent_tags)
        ) DESC, r.date DESC
        LIMIT :limit
        """,
    )
    fun observeBecauseYouWatched(limit: Int = 20): Flow<List<LibraryRow>>

    /** Newest unopened items — the "haven't got to these yet" shelf. */
    @Query(
        """
        SELECT * FROM library_row
        WHERE channelEnabled = 1 AND positionMs IS NULL AND posterForMessageId IS NULL
        ORDER BY date DESC
        LIMIT :limit
        """,
    )
    fun observeUnseen(limit: Int = 20): Flow<List<LibraryRow>>

    /**
     * Oldest unopened items.
     *
     * The counterweight to a reverse-chronological library: without it, anything
     * more than a few screens down is never seen again.
     */
    @Query(
        """
        SELECT * FROM library_row
        WHERE channelEnabled = 1 AND positionMs IS NULL AND posterForMessageId IS NULL
        ORDER BY date ASC
        LIMIT :limit
        """,
    )
    fun observeRediscover(limit: Int = 20): Flow<List<LibraryRow>>

    /** Started but not finished — the continue-watching shelf. */
    @Query(
        """
        SELECT * FROM library_row
        WHERE channelEnabled = 1
          AND completed = 0
          AND playbackDurationMs > 0
          AND CAST(positionMs AS REAL) / playbackDurationMs BETWEEN 0.02 AND 0.97
        ORDER BY lastPlayedAt DESC
        LIMIT :limit
        """,
    )
    fun observeContinueWatching(limit: Int = 12): Flow<List<LibraryRow>>

    /** Saved items, most recently saved first. */
    @Query(
        """
        SELECT * FROM library_row
        WHERE channelEnabled = 1 AND favouritedAt IS NOT NULL
        ORDER BY favouritedAt DESC
        """,
    )
    fun pageFavourites(): PagingSource<Int, LibraryRow>

    @Query("SELECT COUNT(*) FROM library_row WHERE channelEnabled = 1 AND favouritedAt IS NOT NULL")
    fun observeFavouriteCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM library_row WHERE channelEnabled = 1 AND lastPlayedAt IS NOT NULL")
    fun observeHistoryCount(): Flow<Int>

    /**
     * The player's up-next list: the same ordering the grid uses, ids only.
     * Cheap enough to hold in memory, which is what lets the player advance
     * without reaching back through a PagingSource it doesn't own.
     */
    @Query(
        """
        SELECT localId FROM library_row
        WHERE channelEnabled = 1 AND type = 'VIDEO'
          AND (:sourceCount = 0 OR chatId IN (:sourceIds))
        ORDER BY date DESC, localId DESC
        """,
    )
    suspend fun videoIdsInOrder(sourceIds: List<Long>, sourceCount: Int): List<Long>

    // --------------------------------------------------------------- entities

    @Query("SELECT * FROM media WHERE localId = :localId")
    suspend fun byId(localId: Long): MediaEntity?

    @Query("SELECT localId FROM media WHERE chatId = :chatId AND messageId = :messageId")
    suspend fun findLocalId(chatId: Long, messageId: Long): Long?

    @Query("SELECT COUNT(*) FROM media WHERE chatId = :chatId")
    suspend fun countForChannel(chatId: Long): Int

    /** Highest indexed message id for a channel — the incremental sync floor. */
    @Query("SELECT MAX(messageId) FROM media WHERE chatId = :chatId")
    suspend fun newestMessageId(chatId: Long): Long?

    // --------------------------------------------------------------- writes

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoring(item: MediaEntity): Long

    @Update
    suspend fun update(item: MediaEntity)

    @Query("UPDATE media SET tagsParsed = 1 WHERE localId = :localId")
    suspend fun markTagsParsed(localId: Long)

    /**
     * Write back file ids that were re-resolved after Telegram refused the stored
     * ones.
     *
     * Deliberately narrow. The alternative — running the whole row through `upsert`
     * — would re-derive `tagsParsed` and re-run pairing over an item whose caption
     * has not changed, when all that actually went stale is a handle.
     *
     * Persisting the repair is what makes it worth doing once instead of on every
     * scroll: the grid cell, the player, the photo viewer and the open-in-another-app
     * action all read these columns, so a repair paid by any one of them fixes the
     * rest. See `MediaFileRepair`.
     */
    @Query(
        """
        UPDATE media
        SET fileId = :fileId,
            thumbnailFileId = :thumbnailFileId,
            previewFileId = :previewFileId
        WHERE localId = :localId
        """,
    )
    suspend fun refreshFileIds(
        localId: Long,
        fileId: Int,
        thumbnailFileId: Int?,
        previewFileId: Int?,
    )

    /**
     * Record a decoded frame as this item's artwork.
     *
     * Separate from any sync write because the frame arrives from a completely
     * different direction — the player's own surface, or a one-off decode — long
     * after the row was indexed.
     */
    @Query("UPDATE media SET posterPath = :path WHERE localId = :localId")
    suspend fun setPosterPath(localId: Long, path: String?)

    /**
     * Forget every decoded frame.
     *
     * The row half of clearing extracted artwork; `PosterStore` deletes the files.
     * Order does not matter and neither half needs the other to have run: a path
     * pointing at a deleted file simply falls through to the next rung, which is the
     * same behaviour as never having had one.
     */
    @Query("UPDATE media SET posterPath = NULL")
    suspend fun clearPosterPaths()

    /**
     * Videos Telegram gave no artwork at all, oldest-viewed first.
     *
     * The set worth spending bandwidth on: these currently draw a ~40px inline
     * preview or fallback initials, so a decoded frame is the difference between a
     * cell that shows the content and one that does not. Videos that *do* have a
     * thumbnail are excluded on purpose — they look acceptable, and decoding every
     * video in a large channel would pull gigabytes for a cosmetic gain.
     */
    @Query(
        """
        SELECT * FROM media
        WHERE type = 'VIDEO'
          AND posterPath IS NULL
          AND posterFileId IS NULL
          AND thumbnailFileId IS NULL
        ORDER BY date DESC
        LIMIT :limit
        """,
    )
    suspend fun needingFrameArt(limit: Int): List<MediaEntity>

    /**
     * Insert or refresh one indexed message.
     *
     * Re-running a sync over already-indexed messages has to be free of side
     * effects — captions get edited, and TDLib hands out fresh session-scoped
     * file ids after a re-login — so an existing row is updated in place, keeping
     * its [MediaEntity.localId] and therefore its tags, saved state and resume
     * position.
     *
     * @return the row's local id, or -1 if the insert lost a race and the row
     *   then vanished (channel removed mid-sync).
     */
    @Transaction
    suspend fun upsert(item: MediaEntity): Long {
        val inserted = insertIgnoring(item)
        if (inserted != -1L) {
            reindex(inserted)
            return inserted
        }
        val existing = findLocalId(item.chatId, item.messageId) ?: return -1L
        val current = byId(existing)
        update(
            item.copy(
                localId = existing,
                // Preserve parse state so an edit-free re-sync doesn't re-tag
                // everything; a changed caption is a real reason to re-parse.
                tagsParsed = current != null && current.caption == item.caption && current.tagsParsed,
                // Never clear a poster that pairing already found with a null from
                // a message that still has no thumbnail of its own.
                posterFileId = item.posterFileId ?: current?.posterFileId,
                // A decoded frame never comes from Telegram, so a re-sync has nothing
                // to say about it and must not erase it.
                posterPath = current?.posterPath,
            ),
        )
        reindex(existing)
        return existing
    }

    @Transaction
    suspend fun upsertAll(items: List<MediaEntity>): List<Long> = items.map { upsert(it) }

    @Query("DELETE FROM media WHERE localId = :localId")
    suspend fun deleteById(localId: Long)

    // ------------------------------------------------------------------ FTS

    @Query("DELETE FROM media_fts WHERE rowid = :localId")
    suspend fun deleteFtsRow(localId: Long)

    /**
     * Rebuild one FTS row straight from the tables it indexes. Caption and tag
     * text never travel through Kotlin, so the index cannot disagree with the
     * data.
     */
    @Query(
        """
        INSERT INTO media_fts (rowid, caption, tags)
        SELECT
            m.localId,
            m.caption,
            COALESCE((
                SELECT GROUP_CONCAT(t.name, ' ')
                FROM media_tag mt
                INNER JOIN tags t ON t.id = mt.tagId
                WHERE mt.localId = m.localId
            ), '')
        FROM media m
        WHERE m.localId = :localId
        """,
    )
    suspend fun insertFtsRow(localId: Long)

    /** Call after anything that changes a caption or an item's tags. */
    @Transaction
    suspend fun reindex(localId: Long) {
        deleteFtsRow(localId)
        insertFtsRow(localId)
    }

    @Query("DELETE FROM media_fts")
    suspend fun clearFts()

    @Query(
        """
        INSERT INTO media_fts (rowid, caption, tags)
        SELECT
            m.localId,
            m.caption,
            COALESCE((
                SELECT GROUP_CONCAT(t.name, ' ')
                FROM media_tag mt
                INNER JOIN tags t ON t.id = mt.tagId
                WHERE mt.localId = m.localId
            ), '')
        FROM media m
        """,
    )
    suspend fun insertFtsAll()

    /** Full rebuild. Used by the settings repair action and after a migration. */
    @Transaction
    suspend fun reindexAll() {
        clearFts()
        insertFtsAll()
    }
}
