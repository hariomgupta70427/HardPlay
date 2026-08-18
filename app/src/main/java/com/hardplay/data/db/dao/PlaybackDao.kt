package com.hardplay.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.hardplay.data.db.entity.PlaybackEntity
import kotlinx.coroutines.flow.Flow

/**
 * Resume positions and play counts.
 *
 * The shelves that read this table (continue-watching, history, most-watched) live
 * in [MediaDao] instead, because they all select from the `library_row` view and
 * that view is the media table's shape, not this one's.
 */
@Dao
interface PlaybackDao {

    @Upsert
    suspend fun upsert(entry: PlaybackEntity)

    @Query("SELECT * FROM playback WHERE localId = :localId")
    suspend fun byId(localId: Long): PlaybackEntity?

    @Query("SELECT * FROM playback WHERE localId = :localId")
    fun observeById(localId: Long): Flow<PlaybackEntity?>

    /**
     * Record that playback actually started.
     *
     * Upsert-shaped rather than `UPDATE`, because the first play of an item has no
     * row yet — and an `UPDATE` that matches nothing fails silently, which would
     * leave "most watched" permanently empty for exactly the items played once and
     * then again later.
     */
    @Query(
        """
        INSERT INTO playback (localId, positionMs, durationMs, completed, playCount, updatedAt)
        VALUES (:localId, 0, 0, 0, 1, :now)
        ON CONFLICT(localId) DO UPDATE SET
            playCount = playCount + 1,
            updatedAt = :now
        """,
    )
    suspend fun recordPlayStarted(localId: Long, now: Long)

    @Query("DELETE FROM playback WHERE localId = :localId")
    suspend fun clear(localId: Long)

    @Query("DELETE FROM playback")
    suspend fun clearAll()

    @Query("UPDATE playback SET completed = 1, updatedAt = :now WHERE localId = :localId")
    suspend fun markCompleted(localId: Long, now: Long)
}
