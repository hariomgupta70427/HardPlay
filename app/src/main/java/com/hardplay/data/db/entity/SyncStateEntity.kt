package com.hardplay.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/**
 * Per-channel sync bookkeeping (PRD §8).
 *
 * Two cursors, not one, because the two sync directions fail differently:
 *
 *  * [oldestIndexedMessageId] walks *backwards* through history on first index.
 *    A full scan of a large channel spans many app launches, so the backfill has
 *    to be resumable — losing this cursor means starting the scan over.
 *  * [newestIndexedMessageId] is the incremental cursor. Everything above it is
 *    new since the last sync and is fetched newest-first.
 *
 * [backfillComplete] is the flag that says the two have met and history is
 * fully indexed; after that, only the incremental path runs.
 */
@Entity(
    tableName = "sync_state",
    foreignKeys = [
        ForeignKey(
            entity = ChannelEntity::class,
            parentColumns = ["chatId"],
            childColumns = ["chatId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class SyncStateEntity(
    @PrimaryKey val chatId: Long,
    val newestIndexedMessageId: Long = 0,
    val oldestIndexedMessageId: Long = 0,
    val backfillComplete: Boolean = false,
    /** Rows written for this channel. Drives the "Indexing 240 / 1,800" readout. */
    val indexedCount: Int = 0,
    val lastSyncAt: Long = 0,
    /** Last failure, kept so the library can surface a quiet warning rather than
     *  silently showing a stale list. Cleared on the next success. */
    val lastError: String? = null,
)
