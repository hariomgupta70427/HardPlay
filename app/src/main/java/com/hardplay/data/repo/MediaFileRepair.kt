package com.hardplay.data.repo

import android.util.Log
import com.hardplay.data.db.dao.MediaDao
import com.hardplay.di.AppScope
import com.hardplay.telegram.GatewayResult
import com.hardplay.telegram.TelegramGateway
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import javax.inject.Inject
import javax.inject.Singleton

/** Which of an item's several files a caller wants a live id for. */
enum class MediaFileRole {
    /** The media itself — the video to stream, or the photo at full resolution. */
    ORIGINAL,

    /** The large artwork rung, ~1280px. Photos only; video has no ladder. */
    PREVIEW,

    /** The small artwork rung, ~320px. */
    THUMBNAIL,
}

/**
 * Turns a stale file id back into one Telegram will serve.
 *
 * ## Why this exists
 *
 * Two of the app's identifiers expire and one does not.
 *
 *  * `fileId` is **session-scoped**. It is TDLib's handle for a file within the life
 *    of its database.
 *  * `remoteFileId` is persistent *as a string*, but the file **reference** encoded
 *    inside it is not: Telegram rotates references, and a stale one is refused.
 *  * `(chatId, messageId)` never expires. It is the only durable addressing the app
 *    holds, and it is what this class is built on.
 *
 * TDLib repairs an expired reference on its own — but only for a file it can trace
 * back to a *source*, and the source for these files is the message that carries
 * them. Re-reading the message re-establishes that link and yields live ids; asking
 * `getRemoteFile` instead does not, because it never contacts the server (see
 * [TelegramGateway.fileIdForRemoteId]). That distinction is the whole bug: content
 * indexed recently played, content indexed weeks ago failed permanently with a
 * source error, and the repair the app already had could not fix either.
 *
 * ## The write is the point
 *
 * A repair that is not persisted has to be paid again on the next scroll, and — worse
 * — only by whichever caller bothered to ask. Every repair here writes the fresh ids
 * back to the row, so one round trip fixes the grid cell, the player, the photo
 * viewer and the open-in-another-app action together.
 *
 * ## Coalescing without head-of-line blocking
 *
 * Two pressures pull in opposite directions, and the scenario this class exists for
 * hits both at once — a library whose ids have all gone stale has every visible cell
 * asking simultaneously.
 *
 *  * Concurrent requests for the **same** item must collapse to one round trip, so
 *    callers share an in-flight [Deferred] rather than taking turns.
 *  * Requests for **different** items must be bounded, because forty concurrent
 *    `getMessage` calls is how an account earns a flood-wait — but they must not be
 *    fully serialised either. A single lock would have put the video the user is
 *    waiting on behind however many grid cells got there first, and the streaming
 *    path calls this from a Media3 loader thread that is genuinely blocked while it
 *    waits.
 *
 * Hence [GATE_WIDTH] in flight at a time, and no lock held across the network.
 */
@Singleton
class MediaFileRepair @Inject constructor(
    private val gateway: TelegramGateway,
    private val mediaDao: MediaDao,
    @AppScope private val appScope: CoroutineScope,
) {

    private class Ids(
        val original: Int,
        val preview: Int?,
        val thumbnail: Int?,
    ) {
        fun pick(role: MediaFileRole): Int? = when (role) {
            MediaFileRole.ORIGINAL -> original
            MediaFileRole.PREVIEW -> preview ?: original
            MediaFileRole.THUMBNAIL -> thumbnail ?: preview ?: original
        }
    }

    /** Guards [memo] and [inFlight] only — never held across a network call. */
    private val lock = Mutex()

    private val gate = Semaphore(GATE_WIDTH)

    /**
     * Outcome of the last repair per item — successes and failures both.
     *
     * Failures are remembered too, and that is not an optimisation. A message the
     * channel has deleted can never be repaired, and without a negative memo every
     * scroll past its cell would fire another round trip forever. The TTL is short
     * enough that a repair blocked by a dropped connection is retried once the
     * connection is back.
     */
    private val memo = LinkedHashMap<Long, Entry>()

    private class Entry(val ids: Ids?, val atMs: Long)

    /** Repairs already running, so N callers for one item share one round trip. */
    private val inFlight = mutableMapOf<Long, Deferred<Ids?>>()

    /**
     * A live file id for [role] of [localId], or null when the item cannot be
     * repaired at all.
     *
     * Safe to call from anywhere, including a Media3 loader thread. Never throws.
     */
    suspend fun repair(localId: Long, role: MediaFileRole): Int? {
        if (localId <= 0L) return null

        val existing = lock.withLock {
            val now = System.currentTimeMillis()
            memo[localId]?.let { entry ->
                val ttl = if (entry.ids == null) FAILURE_MEMO_MS else SUCCESS_MEMO_MS
                if (now - entry.atMs < ttl) return@withLock Resolved(entry.ids)
            }
            inFlight[localId]?.let { return@withLock Pending(it) }

            val started = appScope.async { runRepair(localId) }
            inFlight[localId] = started
            Pending(started)
        }

        return when (existing) {
            is Resolved -> existing.ids?.pick(role)
            // await outside the lock: this is where the round trip happens, and
            // holding the lock across it is exactly the serialisation to avoid.
            is Pending -> existing.job.await()?.pick(role)
        }
    }

    private sealed interface Lookup
    private class Resolved(val ids: Ids?) : Lookup
    private class Pending(val job: Deferred<Ids?>) : Lookup

    /** Drop the memo for one item, so the next miss re-asks immediately. */
    suspend fun forget(localId: Long) {
        lock.withLock { memo.remove(localId) }
    }

    /**
     * The best id this process currently knows for [role], **without any network call**.
     *
     * Exists because a TDLib file id is perishable while the thing that addresses it is
     * not, and callers that hold a *snapshot* of an id need a way to ask what it has
     * since become. `TelegramDataSource` is the case that mattered: Media3 bakes the id
     * into a URI, opens a DataSource per byte range — once for the header, again for the
     * moov atom at the tail of a non-streaming MP4 — and re-parses that URI on every
     * open. So a repair paid for during the first open was discarded by the second,
     * which re-paid it, and a video could sit in that loop failing intermittently
     * forever. The device log showed it plainly: `repaired file=1498 -> 1493` twice in
     * two seconds, with the second range request still asking for 1498.
     *
     * Memo first, then the row — [refresh] writes fresh ids back, so the row is the
     * durable answer. Deliberately never calls [refresh] itself: this is the cheap
     * question, asked on a loader thread, and it must not become a round trip.
     */
    suspend fun knownFileId(localId: Long, role: MediaFileRole): Int? {
        if (localId <= 0L) return null
        lock.withLock { memo[localId]?.ids }?.pick(role)?.let { return it }
        val entity = mediaDao.byId(localId) ?: return null
        return when (role) {
            MediaFileRole.ORIGINAL -> entity.fileId
            MediaFileRole.PREVIEW -> entity.previewFileId ?: entity.fileId
            MediaFileRole.THUMBNAIL -> entity.thumbnailFileId
                ?: entity.previewFileId
                ?: entity.fileId
        }
    }

    private suspend fun runRepair(localId: Long): Ids? {
        val ids = try {
            gate.withPermit { refresh(localId) }
        } catch (cancellation: CancellationException) {
            // The shared job was cancelled, not answered. Clear it so the next caller
            // starts a fresh attempt instead of awaiting a corpse, and record nothing
            // — a cancellation is not evidence that the item is unrepairable.
            lock.withLock { inFlight.remove(localId) }
            throw cancellation
        }

        lock.withLock {
            inFlight.remove(localId)
            remember(localId, ids)
        }
        return ids
    }

    private suspend fun refresh(localId: Long): Ids? {
        val entity = mediaDao.byId(localId) ?: return null

        val fresh = when (val result = gateway.refreshMessage(entity.chatId, entity.messageId)) {
            is GatewayResult.Success -> result.value
            is GatewayResult.Failure -> {
                Log.w(TAG, "repair($localId) failed: ${result.error} ${result.message}")
                return null
            }
        }

        // Only the ids are rewritten. A repair must not touch the caption, the tags
        // or the resume position — it is fixing a handle, not re-indexing an item,
        // and a full upsert here would clear `tagsParsed` on a caption edit that had
        // nothing to do with the failure.
        if (fresh.fileId != entity.fileId ||
            fresh.thumbnailFileId != entity.thumbnailFileId ||
            fresh.previewFileId != entity.previewFileId
        ) {
            mediaDao.refreshFileIds(
                localId = localId,
                fileId = fresh.fileId,
                thumbnailFileId = fresh.thumbnailFileId,
                previewFileId = fresh.previewFileId,
            )
        }

        return Ids(
            original = fresh.fileId,
            preview = fresh.previewFileId,
            thumbnail = fresh.thumbnailFileId,
        )
    }

    private fun remember(localId: Long, ids: Ids?) {
        memo[localId] = Entry(ids, System.currentTimeMillis())
        // Bounded, because the library can hold tens of thousands of rows and this
        // map has no other reason to shrink. LinkedHashMap iterates in insertion
        // order, so the head is the oldest.
        while (memo.size > MEMO_CAPACITY) {
            val oldest = memo.keys.firstOrNull() ?: break
            memo.remove(oldest)
        }
    }

    private companion object {
        const val TAG = "HardPlay/Repair"
        const val SUCCESS_MEMO_MS = 5 * 60 * 1000L
        const val FAILURE_MEMO_MS = 45 * 1000L
        const val MEMO_CAPACITY = 512

        /**
         * Repairs in flight at once.
         *
         * Three, not one: the video the user is waiting on must not queue behind a
         * screenful of grid cells. And not forty either — a burst of `getMessage`
         * calls at that width is what gets an account rate-limited, which would turn
         * a recoverable failure into a locked-out one.
         */
        const val GATE_WIDTH = 3
    }
}
