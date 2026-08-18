package com.hardplay.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One Telegram channel the library draws from.
 *
 * HardPlay is multi-channel (see CLAUDE.md): the picker on first run may select
 * several, and the library merges them while still offering a source filter. So
 * a channel is a first-class row rather than a single stored chat id.
 *
 * @param accessHash not stored. TDLib owns chat access hashes in its own
 *   encrypted database and refuses to accept them from a caller; re-deriving one
 *   from a cached value is how you get CHANNEL_INVALID after a session restore.
 */
@Entity(
    tableName = "channels",
    indices = [Index(value = ["sortIndex"])],
)
data class ChannelEntity(
    @PrimaryKey val chatId: Long,
    val title: String,
    /** `@handle` when the channel is public, else null. Display only. */
    val username: String?,
    /** TDLib file id of the chat photo, for the source filter rows. */
    val photoFileId: Int?,
    /** Total message count TDLib reported at last sync, for progress readouts. */
    val knownMessageCount: Int,
    /** User-ordered position in the source filter. */
    val sortIndex: Int,
    /** Unticking a source hides it from the library without dropping its rows. */
    @ColumnInfo(defaultValue = "1") val enabled: Boolean = true,
    val addedAt: Long,
)
