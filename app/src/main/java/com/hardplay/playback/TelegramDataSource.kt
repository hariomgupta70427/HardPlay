package com.hardplay.playback

import android.net.Uri
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.hardplay.telegram.GatewayError
import com.hardplay.telegram.GatewayResult
import com.hardplay.telegram.TelegramFileState
import com.hardplay.telegram.TelegramGateway
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Media3 reading straight out of Telegram (PRD §5.4).
 *
 * ExoPlayer asks for byte ranges; TDLib fetches them from Telegram's CDN into a
 * sparse local file; this class is the adapter between the two. It is the reason a
 * 2 GB HDR file starts playing in a second or two instead of after a full
 * download, and the reason scrubbing backwards costs nothing — TDLib keeps what it
 * already fetched.
 *
 * ## The contract that actually matters
 *
 * TDLib does not report "how much of this file do I have". It reports a single
 * **contiguous prefix**: `downloadedPrefixSize` bytes starting at
 * `downloadOffset`. Bytes outside that window may be absent even though the file
 * on disk is nominally large enough to contain them, because the file is sparse.
 * Reading there returns zeroes, and zeroes fed to a demuxer surface as corrupt
 * media rather than as an error — which is a genuinely horrible bug to diagnose.
 * So every read here is clamped to that window, and [awaitWindow] blocks until the
 * window actually covers the read position.
 *
 * ## Blocking is correct here
 *
 * [read] blocks, deliberately. Media3 calls it on a loader thread built to block,
 * and the alternative — returning 0 and busy-looping — burns a core while
 * buffering. Cancellation comes from [close], which cancels [lifecycle] and so
 * unblocks the [runBlocking] mid-wait.
 */
class TelegramDataSource(
    private val gateway: TelegramGateway,
) : BaseDataSource(/* isNetwork = */ true) {

    private var uri: Uri? = null
    private var fileId: Int = NO_FILE
    private var remoteFileId: String? = null

    private var handle: RandomAccessFile? = null
    private var readPosition: Long = 0L
    private var bytesRemaining: Long = C.LENGTH_UNSET.toLong()

    /** Last window TDLib reported. Re-read only when a read runs past its edge. */
    private var window: TelegramFileState? = null

    /** Cancelled by [close]; unblocks any wait in flight. */
    private var lifecycle = Job()

    @Volatile private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)

        val target = TelegramMediaUri.parse(dataSpec.uri)
            ?: throw IOException("Not a HardPlay media uri: ${dataSpec.uri}")

        uri = dataSpec.uri
        fileId = target.fileId
        remoteFileId = target.remoteFileId
        readPosition = dataSpec.position
        lifecycle = Job()

        // A bounded request when Media3 asked for a bounded range — which it does
        // for the moov atom at the tail of a non-streaming MP4. Unbounded
        // otherwise, so sequential playback keeps filling forward instead of
        // stalling at the end of every chunk.
        val limit = if (dataSpec.length == C.LENGTH_UNSET.toLong()) 0L else dataSpec.length

        val state = request(readPosition, limit)
        window = state

        val totalSize = if (state.expectedSize > 0L) state.expectedSize else target.sizeBytes
        bytesRemaining = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            totalSize > 0L -> (totalSize - readPosition).coerceAtLeast(0L)
            else -> C.LENGTH_UNSET.toLong()
        }

        handle = openHandle(state)
        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    private fun openHandle(state: TelegramFileState): RandomAccessFile {
        val path = state.localPath
            ?: throw IOException("Telegram has no local file for id $fileId yet.")
        return runCatching { RandomAccessFile(path, "r") }
            .getOrElse { throw IOException("Cannot open $path for reading.", it) }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        val file = handle ?: throw IOException("read() before open().")

        val readable = awaitWindow(readPosition)
        // Clamp to three separate limits: what the caller asked for, what is left
        // of the requested range, and — the one that matters — how far TDLib's
        // contiguous window currently reaches.
        var wanted = length.toLong()
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) wanted = minOf(wanted, bytesRemaining)
        wanted = minOf(wanted, readable - readPosition)
        if (wanted <= 0L) return C.RESULT_END_OF_INPUT

        val read = try {
            file.seek(readPosition)
            file.read(buffer, offset, wanted.toInt())
        } catch (io: IOException) {
            throw IOException("Read failed at $readPosition of file $fileId.", io)
        }
        if (read == -1) return C.RESULT_END_OF_INPUT

        readPosition += read
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= read
        bytesTransferred(read)
        return read
    }

    /**
     * Blocks until TDLib's contiguous window covers [position].
     *
     * @return the exclusive upper bound that can be read right now.
     */
    private fun awaitWindow(position: Long): Long {
        window?.takeIf { it.canRead(position) }?.let { return it.readableUntil }

        // Either the window never covered this position (a seek) or playback has
        // caught up with the download edge. Both are answered by asking again.
        val refreshed = request(position, limit = 0L)
        window = refreshed

        if (!refreshed.canRead(position)) {
            // A completed file whose window ends here means the real end of the
            // stream, not a stall.
            if (refreshed.isDownloadingCompleted) return refreshed.readableUntil
            throw IOException("Telegram did not deliver byte $position of file $fileId.")
        }

        // The handle may predate the file existing at all.
        if (handle == null) handle = openHandle(refreshed)
        return refreshed.readableUntil
    }

    /**
     * One TDLib range request, with the stale-file-id repair built in.
     *
     * Session file ids die when TDLib's database is recreated, which happens on
     * re-login. Rather than invalidate the whole index, the persistent remote id
     * travels in the URI and is used to re-resolve exactly once.
     */
    private fun request(position: Long, limit: Long): TelegramFileState = runBlocking(lifecycle) {
        when (val first = gateway.requestRange(fileId, position, limit)) {
            is GatewayResult.Success -> first.value
            is GatewayResult.Failure -> {
                val remote = remoteFileId
                if (first.error != GatewayError.FILE_UNAVAILABLE || remote == null) {
                    throw IOException("Telegram refused file $fileId: ${first.message}")
                }
                val healed = gateway.fileIdForRemoteId(remote)
                if (healed !is GatewayResult.Success) {
                    throw IOException("Telegram lost file $fileId: ${first.message}")
                }
                fileId = healed.value
                when (val retry = gateway.requestRange(fileId, position, limit)) {
                    is GatewayResult.Success -> retry.value
                    is GatewayResult.Failure ->
                        throw IOException("Telegram refused re-resolved file: ${retry.message}")
                }
            }
        }
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        // Cancel first: a read may be parked in awaitWindow, and closing the file
        // handle out from under it would surface as a spurious IO error instead of
        // the cancellation it is.
        lifecycle.cancel(CancellationException("DataSource closed"))

        runCatching { handle?.close() }
        handle = null
        window = null
        uri = null

        // Stop the abandoned download. Without this a seek leaves the previous
        // range still pulling bytes, competing for bandwidth with the range the
        // user is now waiting on.
        val id = fileId
        if (id != NO_FILE) {
            runCatching { runBlocking { gateway.cancelDownload(id) } }
        }

        if (opened) {
            opened = false
            transferEnded()
        }
    }

    /** Media3 builds one source per playback; the gateway is shared. */
    class Factory(private val gateway: TelegramGateway) : DataSource.Factory {
        override fun createDataSource(): DataSource = TelegramDataSource(gateway)
    }

    private companion object {
        const val NO_FILE = -1
    }
}

/**
 * The `tg://` URI that identifies a Telegram file to Media3.
 *
 * Media3 addresses media by URI, so the ids have to travel in one. Size and the
 * persistent remote id ride along because the `DataSource` needs both before it
 * has spoken to TDLib: the size to answer ExoPlayer's length question on open, and
 * the remote id to repair a stale session file id without a database lookup on the
 * loader thread.
 */
object TelegramMediaUri {

    private const val SCHEME = "tg"
    private const val HOST = "file"
    private const val PARAM_SIZE = "size"
    private const val PARAM_REMOTE = "remote"

    data class Target(val fileId: Int, val sizeBytes: Long, val remoteFileId: String?)

    fun build(fileId: Int, sizeBytes: Long, remoteFileId: String?): Uri =
        Uri.Builder()
            .scheme(SCHEME)
            .authority(HOST)
            .appendPath(fileId.toString())
            .appendQueryParameter(PARAM_SIZE, sizeBytes.toString())
            .apply { if (remoteFileId != null) appendQueryParameter(PARAM_REMOTE, remoteFileId) }
            .build()

    fun buildString(fileId: Int, sizeBytes: Long, remoteFileId: String?): String =
        build(fileId, sizeBytes, remoteFileId).toString()

    fun parse(uri: Uri): Target? {
        if (uri.scheme != SCHEME || uri.host != HOST) return null
        val fileId = uri.pathSegments.firstOrNull()?.toIntOrNull() ?: return null
        return Target(
            fileId = fileId,
            sizeBytes = uri.getQueryParameter(PARAM_SIZE)?.toLongOrNull() ?: 0L,
            remoteFileId = uri.getQueryParameter(PARAM_REMOTE),
        )
    }

    fun parse(value: String): Target? = parse(value.toUri())
}
