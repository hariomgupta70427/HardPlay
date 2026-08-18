package com.hardplay.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.PrimaryKey

/**
 * Full-text index over caption *and* tag text, so one MATCH answers "find
 * anything about X" (PRD §6.2 §3).
 *
 * Deliberately a standalone FTS table rather than an `contentEntity`-backed
 * external-content one. External content would keep the caption in sync via
 * generated triggers for free, but it can only mirror columns that exist on the
 * content table — and tags live in a join table, so they could never be indexed.
 * Searching captions and tags with a single ranked query is worth maintaining
 * the table by hand, which happens in exactly one place: `MediaDao.reindex`.
 *
 * `rowid` is [MediaEntity.localId]. That equality is what lets the library query
 * intersect FTS hits with the media table by primary key instead of a join.
 */
@Fts4
@Entity(tableName = "media_fts")
data class MediaFtsEntity(
    @PrimaryKey
    @ColumnInfo(name = "rowid")
    val rowId: Long,
    val caption: String,
    /** Tag names, space-joined. Denormalised on purpose — see class docs. */
    val tags: String,
)
