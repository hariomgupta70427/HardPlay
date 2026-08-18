package com.hardplay.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Media-to-tag join.
 *
 * Both foreign keys cascade: dropping a channel takes its media and therefore
 * its tag links, and deleting a tag unlinks it everywhere. Without the cascade,
 * the tag filter sheet ends up counting rows that point at nothing.
 */
@Entity(
    tableName = "media_tag",
    primaryKeys = ["localId", "tagId"],
    indices = [Index(value = ["tagId"]), Index(value = ["localId"])],
    foreignKeys = [
        ForeignKey(
            entity = MediaEntity::class,
            parentColumns = ["localId"],
            childColumns = ["localId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TagEntity::class,
            parentColumns = ["id"],
            childColumns = ["tagId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class MediaTagCrossRef(
    val localId: Long,
    val tagId: Long,
)
