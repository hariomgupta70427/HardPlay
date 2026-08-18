package com.hardplay.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.hardplay.data.db.entity.MediaTagCrossRef
import com.hardplay.data.db.entity.TagEntity
import com.hardplay.data.db.projection.TagFacet
import kotlinx.coroutines.flow.Flow

@Dao
interface TagDao {

    /**
     * Tags with live counts under the current filter (PRD §6.2).
     *
     * The filter sits in the `LEFT JOIN ... ON` clause, not in `WHERE`, and that
     * placement is the whole trick: in `WHERE` it would delete the joined rows
     * and the tag would vanish from the sheet, whereas in `ON` a tag with no
     * matches survives with a count of zero. A filter sheet whose options
     * disappear as you use it is unusable.
     */
    @Query(
        """
        SELECT t.id AS id, t.name AS name, t.auto AS auto,
               COUNT(m.localId) AS itemCount
        FROM tags t
        LEFT JOIN media_tag mt ON mt.tagId = t.id
        LEFT JOIN media m ON m.localId = mt.localId
            AND m.chatId IN (SELECT chatId FROM channels WHERE enabled = 1)
            AND (:sourceCount = 0 OR m.chatId IN (:sourceIds))
            AND (:type IS NULL OR m.type = :type)
            AND (:hasQuery = 0 OR m.localId IN (
                  SELECT rowid FROM media_fts WHERE media_fts MATCH :ftsMatch))
        GROUP BY t.id, t.name, t.auto
        ORDER BY itemCount DESC, t.name COLLATE NOCASE ASC
        """,
    )
    fun observeFacets(
        sourceIds: List<Long>,
        sourceCount: Int,
        type: String?,
        hasQuery: Int,
        ftsMatch: String?,
    ): Flow<List<TagFacet>>

    @Query("SELECT * FROM tags ORDER BY name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<TagEntity>>

    /**
     * Autocomplete for the tag editor. Ordered by how heavily used the tag
     * already is, because the point of autocomplete here is to stop
     * near-duplicate tags being created (PRD §6.2 §5).
     */
    @Query(
        """
        SELECT t.id AS id, t.name AS name, t.auto AS auto,
               COUNT(mt.localId) AS itemCount
        FROM tags t
        LEFT JOIN media_tag mt ON mt.tagId = t.id
        WHERE :prefix = '' OR t.normalised LIKE :prefix || '%'
        GROUP BY t.id, t.name, t.auto
        ORDER BY itemCount DESC, t.name COLLATE NOCASE ASC
        LIMIT :limit
        """,
    )
    suspend fun suggest(prefix: String, limit: Int = 8): List<TagFacet>

    @Query("SELECT * FROM tags WHERE normalised = :normalised")
    suspend fun byNormalised(normalised: String): TagEntity?

    @Query("SELECT * FROM tags WHERE id = :tagId")
    suspend fun byId(tagId: Long): TagEntity?

    /** Items carrying a tag. Needed to reindex them after the tag changes. */
    @Query("SELECT localId FROM media_tag WHERE tagId = :tagId")
    suspend fun itemIdsForTag(tagId: Long): List<Long>

    /**
     * Rename in place.
     *
     * `normalised` moves with `name` because it *is* the identity, and leaving it
     * stale would let the same word be created a second time. Renaming in place —
     * rather than delete-and-recreate — is what keeps the tag's links, which
     * cascade away on delete.
     */
    @Query("UPDATE tags SET name = :name, normalised = :normalised, auto = 0 WHERE id = :tagId")
    suspend fun renameById(tagId: Long, name: String, normalised: String)


    @Query(
        """
        SELECT t.* FROM tags t
        INNER JOIN media_tag mt ON mt.tagId = t.id
        WHERE mt.localId = :localId
        ORDER BY t.name COLLATE NOCASE ASC
        """,
    )
    fun observeForItem(localId: Long): Flow<List<TagEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoring(tag: TagEntity): Long

    /**
     * Find or create, keyed on [TagEntity.normalised].
     *
     * @return the tag's id, existing or new. Two items being tagged at once must
     *   not produce two rows for the same word, which is why this is a
     *   transaction and not an insert followed by a lookup.
     */
    @Transaction
    suspend fun resolve(name: String, auto: Boolean, now: Long): Long {
        val normalised = TagEntity.normalise(name)
        byNormalised(normalised)?.let { return it.id }
        val inserted = insertIgnoring(
            TagEntity(name = name.trim(), normalised = normalised, auto = auto, createdAt = now),
        )
        if (inserted != -1L) return inserted
        // Lost the race: the winner's row is the one to use.
        return byNormalised(normalised)?.id ?: -1L
    }

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun link(ref: MediaTagCrossRef)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun linkAll(refs: List<MediaTagCrossRef>)

    @Query("DELETE FROM media_tag WHERE localId = :localId AND tagId = :tagId")
    suspend fun unlink(localId: Long, tagId: Long)

    @Query("DELETE FROM media_tag WHERE localId = :localId")
    suspend fun unlinkAll(localId: Long)

    @Delete
    suspend fun delete(tag: TagEntity)

    @Query("DELETE FROM tags WHERE id = :tagId")
    suspend fun deleteById(tagId: Long)

    /**
     * Drop tags nothing points at any more.
     *
     * Deleting an item leaves its hand-made tags behind as empty options in the
     * filter sheet. Auto tags are swept unconditionally; user-made tags are kept
     * even at zero, because a word you typed yourself is a category you meant to
     * have and having it silently disappear is worse than an unused chip.
     */
    @Query(
        """
        DELETE FROM tags WHERE auto = 1 AND id NOT IN (SELECT DISTINCT tagId FROM media_tag)
        """,
    )
    suspend fun pruneOrphanedAutoTags(): Int
}
