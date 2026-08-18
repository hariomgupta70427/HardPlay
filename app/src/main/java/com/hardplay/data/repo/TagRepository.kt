package com.hardplay.data.repo

import com.hardplay.data.db.HardPlayDatabase
import com.hardplay.data.db.dao.MediaDao
import com.hardplay.data.db.dao.TagDao
import com.hardplay.data.db.entity.MediaTagCrossRef
import com.hardplay.data.db.entity.TagEntity
import com.hardplay.data.db.projection.TagFacet
import com.hardplay.data.tagging.CaptionParser
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tag writes.
 *
 * Every mutation here ends in `mediaDao.reindex`, and that is the point of the
 * class. Tag text is part of the full-text index (see `MediaFtsEntity`), so a tag
 * added without a reindex is a tag you cannot search for — a bug that looks like
 * flaky search rather than a missing call. Keeping the pairing in one file makes
 * it hard to write half of it.
 */
@Singleton
class TagRepository @Inject constructor(
    private val database: HardPlayDatabase,
    private val tagDao: TagDao,
    private val mediaDao: MediaDao,
) {

    fun observeAll(): Flow<List<TagEntity>> = tagDao.observeAll()

    fun observeForItem(localId: Long): Flow<List<TagEntity>> = tagDao.observeForItem(localId)

    suspend fun suggest(prefix: String): List<TagFacet> =
        tagDao.suggest(TagEntity.normalise(prefix))

    /** Add a tag by name, creating it if new. No-op for blank input. */
    suspend fun addToItem(localId: Long, name: String, auto: Boolean = false): Boolean {
        if (name.isBlank()) return false
        return database.withTransaction {
            val tagId = tagDao.resolve(name, auto, System.currentTimeMillis())
            if (tagId <= 0L) return@withTransaction false
            tagDao.link(MediaTagCrossRef(localId = localId, tagId = tagId))
            mediaDao.reindex(localId)
            true
        }
    }

    suspend fun removeFromItem(localId: Long, tagId: Long) {
        database.withTransaction {
            tagDao.unlink(localId, tagId)
            mediaDao.reindex(localId)
            tagDao.pruneOrphanedAutoTags()
        }
    }

    /**
     * Replace an item's tags wholesale, which is what the editor commits.
     *
     * One transaction so a half-applied edit can't be observed, and so the FTS
     * row is rewritten exactly once rather than per tag.
     */
    suspend fun replaceItemTags(localId: Long, names: List<String>) {
        val now = System.currentTimeMillis()
        database.withTransaction {
            tagDao.unlinkAll(localId)
            val refs = names
                .filter { it.isNotBlank() }
                .distinctBy { TagEntity.normalise(it) }
                .mapNotNull { name ->
                    tagDao.resolve(name, auto = false, now = now)
                        .takeIf { it > 0L }
                        ?.let { MediaTagCrossRef(localId = localId, tagId = it) }
                }
            tagDao.linkAll(refs)
            mediaDao.reindex(localId)
            tagDao.pruneOrphanedAutoTags()
        }
    }

    /**
     * Run the caption parser over one item and attach what it finds.
     *
     * Auto tags are *added*, never used to clear existing ones: a re-sync after a
     * caption edit must not remove a tag the user added by hand.
     */
    suspend fun applyAutoTags(localId: Long, caption: String): Int {
        val names = CaptionParser.tags(caption)
        if (names.isEmpty()) {
            mediaDao.markTagsParsed(localId)
            return 0
        }
        val now = System.currentTimeMillis()
        return database.withTransaction {
            val refs = names.mapNotNull { name ->
                tagDao.resolve(name, auto = true, now = now)
                    .takeIf { it > 0L }
                    ?.let { MediaTagCrossRef(localId = localId, tagId = it) }
            }
            tagDao.linkAll(refs)
            mediaDao.markTagsParsed(localId)
            mediaDao.reindex(localId)
            refs.size
        }
    }

    /**
     * Rename, or merge if the new name is already taken.
     *
     * Renaming happens in place. The obvious implementation — delete the row and
     * create one with the new name — silently destroys the tag's links, because
     * `media_tag` cascades on delete. Every item carrying the tag is reindexed
     * afterwards, since the tag's text is part of the search index.
     */
    suspend fun renameTag(tagId: Long, newName: String) {
        if (newName.isBlank()) return
        val normalised = TagEntity.normalise(newName)
        database.withTransaction {
            val current = tagDao.byId(tagId) ?: return@withTransaction
            if (current.normalised == normalised) {
                // Same word, different casing — a display-only change.
                tagDao.renameById(tagId, newName.trim(), normalised)
                return@withTransaction
            }
            val collision = tagDao.byNormalised(normalised)
            if (collision != null) {
                // Renaming onto a name that exists is a merge, and the user means
                // it: they are collapsing two words for the same thing.
                moveLinks(fromTagId = tagId, intoTagId = collision.id)
                return@withTransaction
            }
            tagDao.renameById(tagId, newName.trim(), normalised)
            tagDao.itemIdsForTag(tagId).forEach { mediaDao.reindex(it) }
        }
    }

    /** Move every link from [fromTagId] onto [intoTagId], then drop the source. */
    suspend fun mergeInto(fromTagId: Long, intoTagId: Long) {
        if (fromTagId == intoTagId) return
        database.withTransaction { moveLinks(fromTagId, intoTagId) }
    }

    /** Caller must already hold the transaction. */
    private suspend fun moveLinks(fromTagId: Long, intoTagId: Long) {
        val affected = tagDao.itemIdsForTag(fromTagId)
        tagDao.linkAll(affected.map { MediaTagCrossRef(localId = it, tagId = intoTagId) })
        // Drops the source tag and, by cascade, its now-duplicated links.
        tagDao.deleteById(fromTagId)
        affected.forEach { mediaDao.reindex(it) }
    }

    suspend fun deleteTag(tagId: Long) {
        database.withTransaction {
            val affected = tagDao.itemIdsForTag(tagId)
            tagDao.deleteById(tagId)
            affected.forEach { mediaDao.reindex(it) }
        }
    }
}
