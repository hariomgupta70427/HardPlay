package com.hardplay.data.repo

import com.hardplay.data.db.dao.PlaybackDao
import com.hardplay.data.db.entity.PlaybackEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resume positions.
 *
 * Deliberately thin, and deliberately not called on a timer from the player: see
 * [shouldPersist] for why the write is throttled by *distance moved* rather than
 * by elapsed time.
 */
@Singleton
class PlaybackRepository @Inject constructor(
    private val playbackDao: PlaybackDao,
) {

    fun observe(localId: Long): Flow<PlaybackEntity?> = playbackDao.observeById(localId)

    suspend fun position(localId: Long): PlaybackEntity? = playbackDao.byId(localId)

    /**
     * @param positionMs where playback is now.
     * @param durationMs Media3's duration, which overrules Telegram's metadata —
     *   the value in the message and the value in the container disagree often
     *   enough that trusting the message leaves resume bars in the wrong place.
     */
    suspend fun save(localId: Long, positionMs: Long, durationMs: Long) {
        if (durationMs <= 0L) return
        val completed = positionMs >= durationMs * COMPLETION_THRESHOLD
        playbackDao.upsert(
            PlaybackEntity(
                localId = localId,
                // A finished item resumes from the start, not from its last
                // second — otherwise reopening it drops you on the credits.
                positionMs = if (completed) 0L else positionMs,
                durationMs = durationMs,
                completed = completed,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun markCompleted(localId: Long) =
        playbackDao.markCompleted(localId, System.currentTimeMillis())

    /**
     * Mark an item watched without playing it.
     *
     * Two statements, not one: `markCompleted` is an `UPDATE`, and an item that has
     * never been opened has no row for it to match — so marking an untouched item as
     * viewed would silently do nothing, which is precisely the case the action exists
     * for. The insert comes first and the flag second.
     */
    suspend fun markViewed(localId: Long) {
        playbackDao.recordPlayStarted(localId, System.currentTimeMillis())
        playbackDao.markCompleted(localId, System.currentTimeMillis())
    }

    /**
     * Put an item back to never-opened.
     *
     * Deleting the row rather than zeroing it, because "unseen" in `library_row` is
     * `positionMs IS NULL` — a row with a zero position is *watched from the start*,
     * not unwatched, and the two look identical in the grid until you notice the
     * ember tick never came back.
     */
    suspend fun markUnviewed(localId: Long) = playbackDao.clear(localId)

    /** Bumps the play count and the last-played stamp. Drives History and Most watched. */
    suspend fun recordPlayStarted(localId: Long) =
        playbackDao.recordPlayStarted(localId, System.currentTimeMillis())

    suspend fun clear(localId: Long) = playbackDao.clear(localId)

    suspend fun clearAll() = playbackDao.clearAll()

    companion object {
        /** Past this fraction the item counts as watched. */
        const val COMPLETION_THRESHOLD = 0.97f

        /** Below this, a resume offer is noise rather than help. */
        const val RESUME_FLOOR_MS = 15_000L

        /**
         * Persist only after playback has moved this far.
         *
         * Distance, not time: a paused player would keep writing the same row on a
         * timer, and every write invalidates the queries watching the table. Five
         * seconds of *travel* bounds the loss to five seconds while making an idle
         * player completely silent.
         */
        const val PERSIST_INTERVAL_MS = 5_000L

        fun shouldPersist(lastSavedMs: Long, positionMs: Long): Boolean =
            kotlin.math.abs(positionMs - lastSavedMs) >= PERSIST_INTERVAL_MS
    }
}
