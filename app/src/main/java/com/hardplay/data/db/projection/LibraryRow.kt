package com.hardplay.data.db.projection

import androidx.room.ColumnInfo
import androidx.room.DatabaseView

/**
 * The `library_row` view's definition, as one constant.
 *
 * Extracted from the annotation because **Room does not create views during a
 * migration.** Its generated `onPostMigrate` is empty, while `onValidateSchema`
 * does check the view exists and that its SQL matches byte for byte — so a
 * migration that forgets to create it crashes the app on launch with "Migration
 * didn't properly handle: library_row".
 *
 * The migration therefore issues the `CREATE VIEW` itself, and both it and the
 * `@DatabaseView` below read this same string. Two copies of this SQL would drift,
 * and the failure mode of drift is that same launch crash.
 *
 * **The absence of leading and trailing whitespace is load-bearing.** Room *trims* the
 * annotation value when it generates the expected `CREATE VIEW … AS <sql>`, while the
 * migration stores whatever it is given — so a constant written in the natural
 * triple-quoted shape, opening with a newline, produces two strings differing by one
 * character and fails validation on every launch. That is not a hypothetical: it
 * shipped, and it crashed every device that had a database to upgrade while every
 * clean install was fine, because a clean install has Room create the view itself.
 * `MigrationTest` exists to keep it fixed.
 */
internal object LibraryRowSql {
    const val NAME = "library_row"

    const val SELECT = """SELECT
            m.localId            AS localId,
            m.chatId             AS chatId,
            m.messageId          AS messageId,
            m.type               AS type,
            m.title              AS title,
            m.caption            AS caption,
            m.date               AS date,
            m.durationSeconds    AS durationSeconds,
            m.fileSizeBytes      AS fileSizeBytes,
            m.width              AS width,
            m.height             AS height,
            m.thumbnailFileId    AS thumbnailFileId,
            m.previewFileId      AS previewFileId,
            m.posterPath         AS posterPath,
            m.minithumbnail      AS minithumbnail,
            m.posterFileId       AS posterFileId,
            m.posterForMessageId AS posterForMessageId,
            m.fileId             AS fileId,
            m.remoteFileId       AS remoteFileId,
            c.title              AS channelTitle,
            c.enabled            AS channelEnabled,
            p.positionMs         AS positionMs,
            p.durationMs         AS playbackDurationMs,
            p.completed          AS completed,
            p.playCount          AS playCount,
            p.updatedAt          AS lastPlayedAt,
            f.addedAt            AS favouritedAt
        FROM media m
        INNER JOIN channels c ON c.chatId = m.chatId
        LEFT JOIN playback p ON p.localId = m.localId
        LEFT JOIN favourites f ON f.localId = m.localId"""
}

/**
 * One library row, everywhere.
 *
 * A [DatabaseView] rather than a projection repeated per query. The grid, the Saved
 * tab, History, Most-watched and every recommendation shelf all need the same
 * assembled shape — media joined to its channel, its playback state and whether it
 * is saved — and by the fifth hand-written `SELECT m.localId AS localId, …` the
 * lists had already started to disagree. The view is declared once and the DAOs say
 * `SELECT * FROM library_row WHERE …`.
 *
 * `channelEnabled` is exposed rather than filtered inside the view: the library
 * hides disabled sources, but the source manager has to show them.
 */
@DatabaseView(viewName = LibraryRowSql.NAME, value = LibraryRowSql.SELECT)
data class LibraryRow(
    val localId: Long,
    val chatId: Long,
    val messageId: Long,
    val type: String,
    val title: String,
    val caption: String,
    val date: Long,
    val durationSeconds: Int?,
    val fileSizeBytes: Long,
    val width: Int?,
    val height: Int?,

    val thumbnailFileId: Int?,
    /** A larger rung of the same artwork. See `MediaEntity.previewFileId`. */
    val previewFileId: Int?,
    /** A decoded frame on disk — the best artwork available. See `MediaEntity.posterPath`. */
    val posterPath: String?,
    /** Inline preview bytes from the message. See `MediaEntity.minithumbnail`. */
    val minithumbnail: ByteArray?,
    /** A neighbouring screenshot standing in as this video's poster. */
    val posterFileId: Int?,

    /** Non-null when this row is a still standing in as some video's poster. */
    val posterForMessageId: Long?,

    val fileId: Int,
    val remoteFileId: String,

    @ColumnInfo(name = "channelTitle") val channelTitle: String,
    @ColumnInfo(name = "channelEnabled") val channelEnabled: Boolean,

    /** Null when the item has never been opened. */
    @ColumnInfo(name = "positionMs") val positionMs: Long?,
    @ColumnInfo(name = "playbackDurationMs") val playbackDurationMs: Long?,
    @ColumnInfo(name = "completed") val completed: Boolean?,
    @ColumnInfo(name = "playCount") val playCount: Int?,
    @ColumnInfo(name = "lastPlayedAt") val lastPlayedAt: Long?,

    /** Null when not saved. */
    @ColumnInfo(name = "favouritedAt") val favouritedAt: Long?,
) {
    /** 0f..1f for the poster's resume bar. Finished items show none. */
    val resumeFraction: Float
        get() {
            if (completed == true) return 0f
            val pos = positionMs ?: return 0f
            val dur = playbackDurationMs ?: return 0f
            if (dur <= 0L) return 0f
            return (pos.toFloat() / dur).coerceIn(0f, 1f)
        }

    /** Never opened. Drives the ember tick in the poster's corner. */
    val unseen: Boolean get() = positionMs == null

    val isFavourite: Boolean get() = favouritedAt != null

    val isVideo: Boolean get() = type == "VIDEO"

    /**
     * Entity equality is not meaningful — [minithumbnail] is a `ByteArray` and the
     * generated `equals` compares it by reference. Paging and LazyList identity both
     * go through [localId] keys, never through row equality.
     */
    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = localId.hashCode()

    companion object {
        const val VIEW_NAME = "library_row"
    }
}

/** A tag plus its live count under the current filter, for the filter sheet. */
data class TagFacet(
    val id: Long,
    val name: String,
    val auto: Boolean,
    @ColumnInfo(name = "itemCount") val itemCount: Int,
)

/** Aggregate library figures for the header and empty states. */
data class LibraryTotals(
    @ColumnInfo(name = "itemCount") val itemCount: Int,
    @ColumnInfo(name = "videoCount") val videoCount: Int,
    @ColumnInfo(name = "photoCount") val photoCount: Int,
    @ColumnInfo(name = "totalBytes") val totalBytes: Long,
)
