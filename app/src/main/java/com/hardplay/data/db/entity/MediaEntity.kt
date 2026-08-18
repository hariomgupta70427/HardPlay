package com.hardplay.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One photo or video post, as indexed from a channel.
 *
 * Deviates from the PRD sketch (§7) in two ways, both forced by multi-channel
 * support:
 *
 *  * `messageId` is **not** the primary key. Telegram message ids are unique per
 *    chat, not globally, so two channels will collide. The key is a synthetic
 *    `localId` with a unique index on (chatId, messageId).
 *  * `localId` doubles as the FTS4 `rowid`, which is why it has to be a single
 *    INTEGER column rather than the composite key it would otherwise be.
 *
 * No media bytes are ever stored here. Indexing writes metadata only — the file
 * itself stays on Telegram's CDN until playback asks TDLib for a byte range
 * (PRD §5.2). [minithumbnail] is the one exception, and it is bytes Telegram
 * already sent with the message rather than anything fetched.
 *
 * **Entity equality is not meaningful on this class.** [minithumbnail] is a
 * `ByteArray`, so the generated `equals` compares it by reference. Nothing
 * compares whole entities today — `MediaDao.upsert` compares the caption field
 * directly — and hand-writing `equals` over twenty fields would rot the first time
 * someone adds a column without updating it. Compare fields, not rows.
 */
@Entity(
    tableName = "media",
    indices = [
        Index(value = ["chatId", "messageId"], unique = true),
        Index(value = ["date"]),
        Index(value = ["chatId"]),
        Index(value = ["type"]),
    ],
    foreignKeys = [
        ForeignKey(
            entity = ChannelEntity::class,
            parentColumns = ["chatId"],
            childColumns = ["chatId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class MediaEntity(
    @PrimaryKey(autoGenerate = true) val localId: Long = 0,

    val chatId: Long,
    val messageId: Long,

    /** [MediaType] name. Stored as text so a future "document" type is additive. */
    val type: String,

    /** Raw caption exactly as posted. The tag parser reads it; the UI shows it. */
    val caption: String,

    /** First line of the caption, or a synthesised label. Cached so the grid
     *  doesn't re-derive a title for every visible cell on every scroll frame. */
    val title: String,

    /** Telegram message date, epoch seconds (TDLib's unit — not millis). */
    val date: Long,

    val durationSeconds: Int?,
    val width: Int?,
    val height: Int?,

    /**
     * TDLib file id of the thumbnail. Ids are session-scoped: they stay valid
     * for the life of a TDLib database but must be refreshed if it's wiped,
     * which is why [remoteFileId] is stored alongside.
     */
    val thumbnailFileId: Int?,

    /**
     * A larger rung of the same artwork, where Telegram offered one.
     *
     * See `TelegramMessage.previewFileId`. [thumbnailFileId] is deliberately small
     * so a three-column grid stays cheap; this is what a full-width card and the
     * player's transition poster use instead. Choosing one rung for both was why
     * artwork looked soft wherever it was shown large.
     */
    val previewFileId: Int?,

    /**
     * A decoded frame cached on disk, and the best artwork an item can have.
     *
     * Telegram gives a video one small thumbnail and nothing else, so a video shown
     * in a full-width cell is always upscaled from a few hundred pixels. A real frame
     * fixes that outright, and one is written here whenever a frame becomes available
     * — most cheaply from the player's own surface once the item has been watched.
     *
     * A path rather than bytes: these are 100–300 KB JPEGs, and the metadata database
     * has no business holding them. Absent or deleted means "fall back to the rungs
     * above", so evicting the directory is always safe.
     */
    val posterPath: String?,

    /**
     * The message's inline preview bytes — TDLib's `minithumbnail`, a JPEG of
     * around 40px that travels *with* the message rather than being a separate
     * file to download.
     *
     * Stored because it is the difference between a grid that has artwork
     * immediately and one that shows fallback initials: plenty of Telegram videos
     * carry no `thumbnail` file at all, but almost all of them carry this. A
     * couple of KB per row, and it needs no network.
     */
    val minithumbnail: ByteArray?,

    /**
     * A *neighbouring* photo to use as this video's poster.
     *
     * Channels commonly post a screenshot immediately before or after the video it
     * belongs to, as a human-readable preview. When a video has no thumbnail of its
     * own, that adjacent still is a far better poster than a 40px minithumbnail —
     * so the indexer pairs them and records the photo's file id here. See
     * `PosterPairing`.
     */
    val posterFileId: Int?,

    /**
     * Set on a *photo* that is standing in as some video's poster.
     *
     * Holds that video's message id. A channel that posts a screenshot and then the
     * video it belongs to produces two rows for one piece of content, so the grid
     * showed everything twice — which is half of why a synced library looked
     * cluttered. Flagging the still lets the library fold it away and keep the video,
     * while the row itself stays indexed and searchable.
     */
    val posterForMessageId: Long?,

    /** Session-scoped file id of the media itself. */
    val fileId: Int,
    /**
     * Persistent remote id. Survives a TDLib database reset, where [fileId]
     * does not — this is what makes a re-login cheap instead of a full re-index.
     */
    val remoteFileId: String,
    /** Remote unique id — stable across accounts, so it dedupes forwards. */
    val remoteUniqueId: String,

    val fileSizeBytes: Long,

    /** True once the caption parser has run, so re-parsing is skippable. */
    @ColumnInfo(defaultValue = "0") val tagsParsed: Boolean = false,

    val lastSyncedAt: Long,
)

/** Content kinds the indexer recognises. Persisted by `name`. */
enum class MediaType {
    VIDEO,
    PHOTO,
    ;

    companion object {
        fun fromStored(value: String): MediaType =
            entries.firstOrNull { it.name == value } ?: VIDEO
    }
}
