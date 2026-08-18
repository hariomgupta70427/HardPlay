package com.hardplay.ui.image

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import com.hardplay.data.db.dao.MediaDao
import com.hardplay.data.db.entity.MediaEntity
import com.hardplay.data.prefs.SettingsStore
import com.hardplay.di.IoDispatcher
import com.hardplay.playback.TelegramMediaDataSource
import com.hardplay.telegram.TelegramGateway
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Getting a real frame out of a video that has no artwork.
 *
 * There are two ways in, and the cheap one matters more than the clever one:
 *
 *  * [capture] is free. The player already has a decoded frame on its own surface, so
 *    anything watched ends up with a full-resolution poster for no traffic at all.
 *  * [sweep] costs bandwidth, and every bound on it below is there because the naive
 *    version of this — decode a frame for every video — would pull gigabytes out of a
 *    large channel to improve some cells in a grid.
 *
 * The sweep is deliberately restricted to videos Telegram gave **no** artwork at all
 * (`MediaDao.needingFrameArt`). Those cells currently draw a ~40px inline preview or a
 * fallback initial, so a frame is the difference between seeing the content and not.
 * A video that already has a thumbnail looks acceptable, and is left alone.
 */
@Singleton
class FrameHarvester @Inject constructor(
    private val gateway: TelegramGateway,
    private val mediaDao: MediaDao,
    private val posterStore: PosterStore,
    private val settings: SettingsStore,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /**
     * Two decodes at once, no more.
     *
     * A hardware decoder is a shared, small pool: three or four concurrent
     * `MediaMetadataRetriever` instances will start failing to initialise, and they
     * compete directly with the decoder the player is using.
     */
    private val gate = Semaphore(DECODE_CONCURRENCY)

    /** One sweep at a time. A second request is dropped, not queued. */
    private val running = Mutex()

    /**
     * Items already tried this process.
     *
     * `needingFrameArt` returns the same newest-first head every call, so without this a
     * video whose index is unreachable — a `moov` past the byte budget, a codec this
     * device cannot open — would be retried on every sweep and would block anything
     * behind it forever.
     */
    private val attempted: MutableSet<Long> =
        Collections.newSetFromMap(ConcurrentHashMap<Long, Boolean>())

    private val harvested = AtomicInteger(0)

    /**
     * Keep a frame the player already decoded.
     *
     * The caller keeps ownership of [bitmap] — it is very likely still on screen.
     */
    suspend fun capture(localId: Long, bitmap: Bitmap) {
        posterStore.write(localId, bitmap)
    }

    /**
     * Decode artwork for up to [limit] artless videos.
     *
     * Safe to call whenever; it returns immediately when there is nothing to do, when
     * the user has turned the feature off, or when this process has already spent its
     * allowance.
     */
    suspend fun sweep(limit: Int = SWEEP_LIMIT) {
        // Demo mode has metadata and rendered artwork but no video bytes at all, so
        // every extraction is guaranteed to fail — and would spend the session's
        // allowance discovering that.
        if (gateway.isDemo) return
        if (harvested.get() >= MAX_PER_SESSION) return
        if (!settings.settings.first().sharpVideoArtwork) return
        if (!running.tryLock()) return

        try {
            withContext(io) {
                // Over-fetch and filter, so a head full of already-attempted items does
                // not starve the sweep of work it could still do.
                val candidates = mediaDao.needingFrameArt(limit * OVERFETCH)
                    .filterNot { it.localId in attempted }
                    .take(limit)

                coroutineScope {
                    candidates.forEach { entity ->
                        launch {
                            gate.withPermit {
                                if (harvested.get() < MAX_PER_SESSION) {
                                    attempted.add(entity.localId)
                                    if (extract(entity)) harvested.incrementAndGet()
                                }
                            }
                        }
                    }
                }
            }
        } finally {
            running.unlock()
        }
    }

    /** @return true when a frame was decoded and stored. */
    private suspend fun extract(entity: MediaEntity): Boolean {
        if (entity.fileSizeBytes <= 0L) return false

        val source = TelegramMediaDataSource(
            gateway = gateway,
            initialFileId = entity.fileId,
            sizeBytes = entity.fileSizeBytes,
            remoteFileId = entity.remoteFileId,
        )
        val retriever = MediaMetadataRetriever()
        var frame: Bitmap? = null

        return try {
            retriever.setDataSource(source)
            frame = grabFrame(retriever, frameTimeUs(entity.durationSeconds))
            val bitmap = frame ?: return false
            posterStore.write(entity.localId, bitmap) != null
        } catch (cancellation: CancellationException) {
            // Must not be swallowed with the rest: this is the sweep being shut down,
            // not a file that failed to decode.
            throw cancellation
        } catch (failure: Throwable) {
            // MediaMetadataRetriever signals almost everything as an unchecked
            // RuntimeException, and one unreadable file must not end the sweep.
            false
        } finally {
            frame?.recycle()
            // A leaked retriever holds a hardware codec, which the player then cannot
            // get. Released before the source, since it reads through it.
            runCatching { retriever.release() }
            runCatching { source.close() }
        }
    }

    private fun grabFrame(retriever: MediaMetadataRetriever, timeUs: Long): Bitmap? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            // Scaled during decode rather than after, so a 4K frame never becomes a
            // 30 MB bitmap on the heap on its way to being shrunk to 1280.
            retriever.getScaledFrameAtTime(
                timeUs,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                TARGET_EDGE_PX,
                TARGET_EDGE_PX,
            )
        } else {
            retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        }

    /**
     * Where in the video to take the frame from.
     *
     * Not the beginning. Frame zero is very often black, a title card or a fade-in, and
     * a grid of black cells is indistinguishable from a grid of failures. A little way
     * in is nearly always representative.
     *
     * Capped in absolute terms as well as proportional ones: 12% of a two-hour file is
     * fourteen minutes deep, and while TDLib will happily serve a range from there, the
     * index lookup and the seek both get more expensive the further in it is.
     */
    private fun frameTimeUs(durationSeconds: Int?): Long {
        val duration = durationSeconds?.takeIf { it > 0 } ?: return DEFAULT_FRAME_US
        val proportional = (duration * 1_000_000L * FRAME_POSITION).toLong()
        return proportional.coerceIn(0L, MAX_FRAME_US)
    }

    private companion object {
        const val DECODE_CONCURRENCY = 2

        /** Items per sweep. Small: the point is a trickle, not a batch job. */
        const val SWEEP_LIMIT = 8

        /** Rows to read so that already-attempted ids can be filtered out. */
        const val OVERFETCH = 4

        /**
         * Frames per process launch. Bounds the worst case — a first sync of a large
         * channel — to a few tens of megabytes rather than a few gigabytes.
         */
        const val MAX_PER_SESSION = 24

        const val FRAME_POSITION = 0.12
        const val DEFAULT_FRAME_US = 3_000_000L
        const val MAX_FRAME_US = 90_000_000L

        /** Matches `PosterStore`'s own cap, so nothing is decoded larger than it is kept. */
        const val TARGET_EDGE_PX = 1280
    }
}
