package com.hardplay.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/**
 * Where playback got to, per item.
 *
 * Split from [MediaEntity] rather than added as nullable columns: a resume
 * position changes every few seconds during playback, and writing it into the
 * media row would invalidate every Paging query observing that table — which
 * means the library grid behind the player would re-query on a timer. Its own
 * table keeps that churn off the grid's invalidation path.
 */
@Entity(
    tableName = "playback",
    foreignKeys = [
        ForeignKey(
            entity = MediaEntity::class,
            parentColumns = ["localId"],
            childColumns = ["localId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class PlaybackEntity(
    @PrimaryKey val localId: Long,
    val positionMs: Long,
    /** Media3's reported duration, which is authoritative over Telegram's
     *  metadata — container duration and the value in the message can disagree. */
    val durationMs: Long,
    /** Set once watched past the completion threshold, so a finished item stops
     *  offering to resume two seconds from the end. */
    val completed: Boolean,
    /**
     * Times this item has been opened and actually played.
     *
     * Counted rather than derived, because "most watched" has no other source: the
     * position tells you where you got to, never how many times you got there.
     * Incremented once per player session, not per resume-write.
     */
    @ColumnInfo(defaultValue = "0") val playCount: Int = 0,
    val updatedAt: Long,
) {
    /** 0f..1f. Zero when the duration isn't known yet, so the UI draws nothing. */
    val fraction: Float
        get() = if (durationMs <= 0L) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
}
