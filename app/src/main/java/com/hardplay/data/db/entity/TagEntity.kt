package com.hardplay.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A tag. Fully user-defined — there is no fixed taxonomy (PRD §6.2).
 *
 * [normalised] exists so that "Behind The Scenes", "behind the scenes" and
 * "behind  the  scenes" are one tag rather than three. It carries the unique
 * index; [name] keeps whatever casing the user typed, because a library that
 * lower-cases your tags for you feels like it's correcting you.
 */
@Entity(
    tableName = "tags",
    indices = [Index(value = ["normalised"], unique = true)],
)
data class TagEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val normalised: String,
    /** True when the caption parser created it rather than the user. Lets the
     *  tag editor offer "clear auto tags" without touching hand-made ones. */
    @ColumnInfo(defaultValue = "0") val auto: Boolean = false,
    val createdAt: Long,
) {
    companion object {
        /** Collapse case and internal whitespace. The single source of identity. */
        fun normalise(raw: String): String =
            raw.trim().lowercase().replace(WHITESPACE, " ")

        private val WHITESPACE = Regex("\\s+")
    }
}
