package com.hardplay.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Saved items.
 *
 * Its own table rather than a column on [MediaEntity], for the same reason
 * [PlaybackEntity] is separate: the library grid's Paging query observes `media`,
 * and writing a flag into that table on every tap of a heart would invalidate the
 * pager and re-query the grid the user is looking at.
 *
 * [addedAt] exists so the Saved tab can order by when you saved something, which is
 * almost never the same as when it was posted.
 */
@Entity(
    tableName = "favourites",
    indices = [Index(value = ["addedAt"])],
    foreignKeys = [
        ForeignKey(
            entity = MediaEntity::class,
            parentColumns = ["localId"],
            childColumns = ["localId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class FavouriteEntity(
    @PrimaryKey val localId: Long,
    val addedAt: Long,
)
