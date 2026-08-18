package com.hardplay.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.hardplay.data.db.entity.FavouriteEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FavouriteDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun add(entry: FavouriteEntity)

    @Query("DELETE FROM favourites WHERE localId = :localId")
    suspend fun remove(localId: Long)

    @Query("SELECT EXISTS(SELECT 1 FROM favourites WHERE localId = :localId)")
    fun observeIsFavourite(localId: Long): Flow<Boolean>

    @Query("SELECT EXISTS(SELECT 1 FROM favourites WHERE localId = :localId)")
    suspend fun isFavourite(localId: Long): Boolean

    /**
     * Flip the saved state in one statement pair.
     *
     * @return the state afterwards, so the caller can drive a haptic and a label
     *   without a second read racing the write.
     */
    @Transaction
    suspend fun toggle(localId: Long, now: Long): Boolean {
        return if (isFavourite(localId)) {
            remove(localId)
            false
        } else {
            add(FavouriteEntity(localId = localId, addedAt = now))
            true
        }
    }

    @Query("DELETE FROM favourites")
    suspend fun clearAll()
}
