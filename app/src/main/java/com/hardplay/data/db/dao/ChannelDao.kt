package com.hardplay.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.hardplay.data.db.entity.ChannelEntity
import com.hardplay.data.db.entity.SyncStateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChannelDao {

    @Query("SELECT * FROM channels ORDER BY sortIndex ASC, title COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels WHERE enabled = 1 ORDER BY sortIndex ASC")
    fun observeEnabled(): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels ORDER BY sortIndex ASC")
    suspend fun all(): List<ChannelEntity>

    @Query("SELECT * FROM channels WHERE chatId = :chatId")
    suspend fun byId(chatId: Long): ChannelEntity?

    @Query("SELECT COUNT(*) FROM channels")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM channels")
    suspend fun count(): Int

    @Query("SELECT COALESCE(MAX(sortIndex), -1) + 1 FROM channels")
    suspend fun nextSortIndex(): Int

    @Upsert
    suspend fun upsert(channel: ChannelEntity)

    @Upsert
    suspend fun upsertAll(channels: List<ChannelEntity>)

    @Update
    suspend fun update(channel: ChannelEntity)

    @Query("UPDATE channels SET enabled = :enabled WHERE chatId = :chatId")
    suspend fun setEnabled(chatId: Long, enabled: Boolean)

    /**
     * Refresh only what a re-read of the chat can tell us. Deliberately does not
     * touch [ChannelEntity.enabled] or [ChannelEntity.sortIndex] — those are the
     * user's, and a background sync overwriting them would silently undo a
     * choice made in the source filter.
     */
    @Query(
        """
        UPDATE channels
        SET title = :title, username = :username, photoFileId = :photoFileId,
            knownMessageCount = :knownMessageCount
        WHERE chatId = :chatId
        """,
    )
    suspend fun refreshMetadata(
        chatId: Long,
        title: String,
        username: String?,
        photoFileId: Int?,
        knownMessageCount: Int,
    )

    /** Cascades to media, tag links, playback and sync state. */
    @Query("DELETE FROM channels WHERE chatId = :chatId")
    suspend fun delete(chatId: Long)
}

@Dao
interface SyncStateDao {

    @Query("SELECT * FROM sync_state")
    fun observeAll(): Flow<List<SyncStateEntity>>

    @Query("SELECT * FROM sync_state WHERE chatId = :chatId")
    suspend fun byId(chatId: Long): SyncStateEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoring(state: SyncStateEntity)

    @Upsert
    suspend fun upsert(state: SyncStateEntity)

    @Query("SELECT COUNT(*) FROM sync_state WHERE backfillComplete = 0")
    fun observeIncompleteBackfills(): Flow<Int>

    @Query("UPDATE sync_state SET lastError = :message WHERE chatId = :chatId")
    suspend fun recordError(chatId: Long, message: String?)
}
