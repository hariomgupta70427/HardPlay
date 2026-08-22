package com.hardplay.playback

import android.media.MediaDataSource
import com.hardplay.data.repo.MediaFileRepair
import com.hardplay.data.repo.MediaFileRole
import com.hardplay.telegram.GatewayResult
import com.hardplay.telegram.TelegramFileState
import com.hardplay.telegram.TelegramGateway
import com.hardplay.telegram.TelegramMediaKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Random access into a Telegram file, for the platform decoder.
 *
 * `MediaMetadataRetriever` needs to seek — the index of an MP4 may be at either end of
 * the file, and the frame it is asked for is somewhere else again — so it cannot be
 * given a stream. This is the adapter that lets it read a file that has never been
 * downloaded, which is what makes it possible to decode one frame out of a 2 GB video
 * for a few megabytes of traffic.
 *
 * It is a sibling of [TelegramDataSource] rather than a reuse of it: Media3's
 * `DataSource` is a forward-only cursor with an `open`/`read`/`close` lifecycle, while
 * this interface is a stateless positional read. The clamping logic below is the same
 * because the underlying hazard is the same one, and it is worth repeating in full:
 *
 * **TDLib's local file is sparse.** It reports one *contiguous prefix* —
 * `downloadedPrefixSize` bytes starting at `downloadOffset` — and bytes outside that
 * window read back as zeroes rather than as an error. Zeroes handed to a demuxer
 * surface as corrupt media, so every read here is clamped to the window and blocks
 * until the window actually covers the position asked for.
 *
 * @param byteBudget hard ceiling on bytes served. Not a nicety: a channel with 800
 *   artless videos would otherwise pull gigabytes of traffic for grid artwork. Past the
 *   budget the read throws, the decode is abandoned, and that item simply keeps the
 *   thumbnail it had.
 */
class TelegramMediaDataSource(
    private val gateway: TelegramGateway,
    initialFileId: Int,
    private val sizeBytes: Long,
    private val remoteFileId: String? = null,
    private val byteBudget: Long = DEFAULT_BYTE_BUDGET,
    /**
     * The row this file belongs to, and [repair], so a refused handle can be
     * re-established the same way playback does it. Optional because the budgeted
     * frame sweep is allowed to give up on an item — an undecoded frame costs a
     * softer thumbnail, not a failure the user sees.
     */
    private val localId: Long = 0L,
    private val repair: MediaFileRepair? = null,
) : MediaDataSource() {

    /** Reassignable: a refused id is healed in place. See [request]. */
    private var fileId: Int = initialFileId

    /** Cancelled by [close]; unblocks any wait in flight. */
    private var lifecycle = Job()

    private var handle: RandomAccessFile? = null

    /** Last window TDLib reported. Re-read only when a read runs past its edge. */
    private var window: TelegramFileState? = null

    private var served: Long = 0L

    override fun getSize(): Long = if (sizeBytes > 0L) sizeBytes else UNKNOWN_SIZE

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (size == 0) return 0
        if (position < 0L) return END_OF_INPUT
        if (sizeBytes > 0L && position >= sizeBytes) return END_OF_INPUT
        if (served >= byteBudget) {
            throw IOException("Frame extraction budget of ${byteBudget}B spent on file $fileId.")
        }

        val readable = awaitWindow(position)

        // Clamp to four limits: what the decoder asked for, what is left of the file,
        // how far TDLib's contiguous window reaches, and what is left of the budget.
        var wanted = size.toLong()
        if (sizeBytes > 0L) wanted = minOf(wanted, sizeBytes - position)
        wanted = minOf(wanted, readable - position)
        wanted = minOf(wanted, byteBudget - served)
        if (wanted <= 0L) return END_OF_INPUT

        val file = handle ?: throw IOException("No local file for id $fileId.")
        val read = try {
            file.seek(position)
            file.read(buffer, offset, wanted.toInt())
        } catch (io: IOException) {
            throw IOException("Read failed at $position of file $fileId.", io)
        }
        if (read == -1) return END_OF_INPUT

        served += read
        return read
    }

    override fun close() {
        // Cancel first: a read may be parked waiting on a range, and closing the file
        // handle out from under it would surface as a spurious IO error rather than the
        // cancellation it is.
        lifecycle.cancel(CancellationException("MediaDataSource closed"))

        runCatching { handle?.close() }
        handle = null
        window = null

        // Stop whatever range was still arriving. Without this an abandoned decode keeps
        // pulling bytes and competes with playback the user is actually waiting on.
        runCatching { runBlocking { gateway.cancelDownload(fileId) } }
    }

    /**
     * Blocks until TDLib's contiguous window covers [position].
     *
     * @return the exclusive upper bound that can be read right now.
     */
    private fun awaitWindow(position: Long): Long {
        window?.takeIf { it.canRead(position) }?.let { cached ->
            ensureHandle(cached)
            return cached.readableUntil
        }

        val refreshed = request(position)
        window = refreshed

        if (!refreshed.canRead(position)) {
            // A completed file whose window ends here is the real end of the stream,
            // not a stall.
            if (refreshed.isDownloadingCompleted && refreshed.localPath != null) {
                ensureHandle(refreshed)
                return refreshed.readableUntil
            }
            throw IOException("Telegram did not deliver byte $position of file $fileId.")
        }

        ensureHandle(refreshed)
        return refreshed.readableUntil
    }

    private fun ensureHandle(state: TelegramFileState) {
        if (handle != null) return
        val path = state.localPath ?: throw IOException("Telegram has no local file for id $fileId yet.")
        handle = runCatching { RandomAccessFile(path, "r") }
            .getOrElse { throw IOException("Cannot open $path for reading.", it) }
    }

    /**
     * One TDLib range request, with the stale-file-id repair built in.
     *
     * A bounded limit rather than the unbounded one playback uses: streaming wants to
     * keep filling forward, whereas this wants a few hundred kilobytes around one
     * offset and nothing more. Asking for the rest of the file here would download the
     * whole video to produce a single thumbnail.
     */
    private fun request(position: Long): TelegramFileState = runBlocking(lifecycle) {
        when (val first = gateway.requestRange(fileId, position, READ_AHEAD_BYTES)) {
            is GatewayResult.Success -> first.value
            is GatewayResult.Failure -> {
                // Two things go stale: the session file id, which dies when TDLib's
                // database is recreated, and the file reference inside the persistent
                // id, which Telegram rotates. Only re-reading the message fixes the
                // second, so that is tried first; the remote id is the fallback for a
                // row that is no longer in the library.
                val healed = repair?.repair(localId, MediaFileRole.ORIGINAL)
                    ?: remoteFileId
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { gateway.fileIdForRemoteId(it, TelegramMediaKind.VIDEO).valueOrNull }

                if (healed == null || healed == fileId) {
                    throw IOException("Telegram refused file $fileId: ${first.message}")
                }
                fileId = healed
                when (val retry = gateway.requestRange(fileId, position, READ_AHEAD_BYTES)) {
                    is GatewayResult.Success -> retry.value
                    is GatewayResult.Failure ->
                        throw IOException("Telegram refused re-resolved file: ${retry.message}")
                }
            }
        }
    }

    companion object {
        /**
         * 6 MB. Enough for a front-loaded `moov` plus one keyframe, and a hard stop on a
         * file whose index sits at the tail and whose keyframes are a long way in.
         */
        const val DEFAULT_BYTE_BUDGET = 6L * 1024 * 1024

        /**
         * Read-ahead per range request. The decoder asks in 8–64 KB reads, so satisfying
         * each one exactly would mean a round trip to Telegram per read.
         */
        private const val READ_AHEAD_BYTES = 512L * 1024

        private const val END_OF_INPUT = -1
        private const val UNKNOWN_SIZE = -1L
    }
}
