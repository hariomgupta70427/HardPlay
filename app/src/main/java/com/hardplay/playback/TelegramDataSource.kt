package com.hardplay.playback

import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.hardplay.BuildConfig
import com.hardplay.data.repo.MediaFileRepair
import com.hardplay.data.repo.MediaFileRole
import com.hardplay.telegram.GatewayError
import com.hardplay.telegram.GatewayResult
import com.hardplay.telegram.TelegramFileState
import com.hardplay.telegram.TelegramGateway
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
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
 * ## Failures are retried, not reported
 *
 * Every `IOException` thrown from here reaches the user as ExoPlayer's
 * `ERROR_CODE_IO_UNSPECIFIED`, whose message is the literal string "Source error" —
 * so anything this class gives up on becomes that, with no indication of why. It
 * therefore gives up as late as it reasonably can:
 *
 *  * A **refused handle** — a stale session file id, or a file reference Telegram
 *    has rotated — is repaired through [MediaFileRepair], which re-reads the message
 *    and writes the fresh ids back to the row. This is the path that used to fail
 *    permanently for anything indexed more than a few days earlier.
 *  * A **slow** range is simply asked for again with backoff. One timeout on a train
 *    is not a reason to end playback.
 *  * A **flood-wait** waits the interval Telegram named. Retrying sooner extends it.
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
    private val repair: MediaFileRepair,
) : BaseDataSource(/* isNetwork = */ true) {

    private var uri: Uri? = null
    private var fileId: Int = NO_FILE
    private var remoteFileId: String? = null
    private var localId: Long = 0L

    private var handle: RandomAccessFile? = null

    /**
     * The path [handle] was opened for.
     *
     * Tracked because TDLib may delete and re-create a file underneath us — a cache
     * sweep does exactly that — and a descriptor left pointing at the old file keeps
     * answering reads from content that is no longer the one being played.
     */
    private var handlePath: String? = null

    private var readPosition: Long = 0L
    private var bytesRemaining: Long = C.LENGTH_UNSET.toLong()

    /** Last window TDLib reported. Re-read only when a read runs past its edge. */
    private var window: TelegramFileState? = null

    /** Cancelled by [close]; unblocks any wait in flight. */
    private var lifecycle = Job()

    @Volatile private var opened = false

    /** One head-of-stream sample per source. See the log call in [read]. */
    private var firstBytesLogged = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)

        val target = TelegramMediaUri.parse(dataSpec.uri)
            ?: throw IOException("Not a HardPlay media uri: ${dataSpec.uri}")

        uri = dataSpec.uri
        fileId = target.fileId
        remoteFileId = target.remoteFileId
        localId = target.localId
        readPosition = dataSpec.position
        lifecycle = Job()

        // The URI carries a *snapshot* of the file id, and TDLib file ids perish.
        //
        // Media3 opens a DataSource per byte range — once for the header, again for the
        // moov atom at the tail of a non-streaming MP4 — and every open re-parses this
        // URI, so `fileId` was reset to whatever was current when `buildMediaItem` ran.
        // A repair paid for during the first open was therefore thrown away by the
        // second, which re-paid the same round trip and could fail on its own. The
        // device log showed exactly that: `repaired file=1498 -> 1493`, then the next
        // range still asking for 1498, then repairing it again, two seconds apart.
        //
        // `localId` never expires, so ask what the id has become. Local only — the memo
        // or the row, never the network — because this runs on a loader thread on the
        // hot path of every open.
        if (localId > 0L) {
            runBlocking(lifecycle) {
                repair.knownFileId(localId, MediaFileRole.ORIGINAL)
            }?.let { live ->
                if (live != fileId) {
                    Log.i(TAG, "uri file=$fileId is stale; row says $live")
                    fileId = live
                }
            }
        }

        // A bounded request when Media3 asked for a bounded range — which it does
        // for the moov atom at the tail of a non-streaming MP4. Unbounded
        // otherwise, so sequential playback keeps filling forward instead of
        // stalling at the end of every chunk.
        val limit = if (dataSpec.length == C.LENGTH_UNSET.toLong()) 0L else dataSpec.length

        var state = request(readPosition, limit)

        // **Is this actually our file?**
        //
        // The assumption everything above rests on — that a perished file id comes back
        // *refused* — is only half true. A TDLib session id is an index into its file
        // database, and when that database is rebuilt the same integer is handed out for
        // something else. Two branches follow, and only one of them is noisy:
        //
        //  * the id resolves to nothing valid -> FILE_UNAVAILABLE -> `request` repairs it
        //    and playback continues. This is the "it works after Retry" case.
        //  * the id resolves to a **valid but unrelated** file -> TDLib serves it happily.
        //    No error, no repair, every read succeeding, nothing logged. ExoPlayer then
        //    reports `UnrecognizedInputFormatException` with `contentIsMalformed=false`,
        //    which surfaces as a bare "Source error".
        //
        // The second branch was measured on device: a row whose video is 240 MB opened
        // `profile_photos/…jpg` at 35,244 bytes with a `FF D8 FF` JPEG header, and another
        // opened a 397-byte file under `documents/` beginning `#EX`.
        //
        // `fileSizeBytes` is exact — the indexer takes it from the message — and travels
        // in the URI, so the check costs a comparison. On a mismatch the id is re-resolved
        // through `(chatId, messageId)`, the only addressing that never expires, which
        // also writes the corrected ids back to the row so later opens skip all of this.
        //
        // Note what this deliberately does **not** do: it never throws. If the size still
        // disagrees after re-resolution the original bytes are served anyway, exactly as
        // before. A guard for a rare fault must not be able to break the common case.
        val declaredSize = target.sizeBytes
        if (sizeMismatch(declaredSize, state.expectedSize)) {
            Log.w(
                TAG,
                "file=$fileId is not this item: serving ${state.expectedSize}B where the " +
                    "row says ${declaredSize}B — re-resolving through the message",
            )
            // `repair` rather than `knownFileId`: the latter reads the memo and then the
            // row, and here the row is precisely what holds the wrong value. Only
            // re-reading the message is authoritative. No `forget` first — the memo is
            // only ever filled from a message, so anything in it is already trustworthy
            // and returning it saves the round trip.
            val healed = runBlocking(lifecycle) {
                repair.repair(localId, MediaFileRole.ORIGINAL)
            }
            if (healed != null && healed != fileId) {
                Log.i(TAG, "identity repaired file=$fileId -> $healed")
                fileId = healed
                state = request(readPosition, limit)
            }
        }

        window = state

        val totalSize = if (state.expectedSize > 0L) state.expectedSize else target.sizeBytes
        bytesRemaining = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            totalSize > 0L -> (totalSize - readPosition).coerceAtLeast(0L)
            else -> C.LENGTH_UNSET.toLong()
        }

        adoptFile(state)
        opened = true
        // Per-open tracing, **debug builds only**.
        //
        // This is the instrumentation that identified the real cause of the
        // unexplainable "Source error": a persisted session file id resolving to an
        // entirely different file, which TDLib served without complaint. Knowing the id,
        // the window, the size and the path at open is what distinguished "we served
        // zeroes" from "we served the wrong file", and it is worth keeping for the next
        // time.
        //
        // It cannot ship enabled, though: `state.localPath` is TDLib's own filename for
        // the media, so in release this line would write the titles of everything in the
        // user's library into logcat. That is the same reason TDLib's own log is turned
        // off in release builds (PRD §9), and a diagnostic is not worth breaking it for.
        if (BuildConfig.DEBUG) {
            Log.i(
                TAG,
                "open file=$fileId pos=$readPosition len=${dataSpec.length} " +
                    "remaining=$bytesRemaining size=${state.expectedSize} " +
                    "window=[${state.downloadOffset},${state.readableUntil}) " +
                    "complete=${state.isDownloadingCompleted} path=${state.localPath}",
            )
        }
        transferStarted(dataSpec)
        return bytesRemaining
    }

    /**
     * Point [handle] at the file TDLib is currently filling.
     *
     * Reopens when the reported path has changed, and is a no-op when it hasn't —
     * so this is safe to call on every window refresh.
     */
    private fun adoptFile(state: TelegramFileState): RandomAccessFile {
        val path = state.localPath
            ?: run {
                // Logged because this throw becomes a bare "Source error" too, and it
                // is indistinguishable from every other one without a line here.
                Log.w(TAG, "no local path yet for file=$fileId size=${state.expectedSize}")
                throw IOException("Telegram has no local file for id $fileId yet.")
            }

        handle?.let { current -> if (handlePath == path) return current }

        runCatching { handle?.close() }
        val opened = runCatching { RandomAccessFile(path, "r") }
            .getOrElse {
                // The path is TDLib's filename for the media, so it stays out of a
                // release log for the same reason the per-open trace above does.
                Log.w(TAG, "cannot open local file for file=$fileId", it)
                throw IOException("Cannot open $path for reading.", it)
            }
        handle = opened
        handlePath = path
        return opened
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        val readable = awaitWindow(readPosition)
        val file = handle ?: throw IOException("read() before open().")

        // Clamp to three separate limits: what the caller asked for, what is left
        // of the requested range, and — the one that matters — how far TDLib's
        // contiguous window currently reaches.
        var wanted = length.toLong()
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) wanted = minOf(wanted, bytesRemaining)
        wanted = minOf(wanted, readable - readPosition)
        if (wanted <= 0L) {
            // Nothing readable. That is a legitimate end of stream at the end of a
            // file, and a bug anywhere else — most sharply at the very first byte,
            // where handing ExoPlayer an empty stream makes every extractor fail to
            // recognise the container and surfaces as "Source error" with no cause.
            // Throwing instead of reporting EOF is what gets it retried rather than
            // silently accepted as an unplayable file.
            val expected = window?.expectedSize ?: 0L
            if (readPosition == 0L && expected > 0L) {
                Log.w(
                    TAG,
                    "empty stream at byte 0 for file=$fileId: readable=$readable " +
                        "size=$expected window=[${window?.downloadOffset}, $readable)",
                )
                throw IOException("Telegram served no bytes at all for file $fileId.")
            }
            return C.RESULT_END_OF_INPUT
        }

        val read = try {
            file.seek(readPosition)
            file.read(buffer, offset, wanted.toInt())
        } catch (io: IOException) {
            throw IOException("Read failed at $readPosition of file $fileId.", io)
        }
        if (read == -1) return C.RESULT_END_OF_INPUT

        // The first bytes this source ever serves, once, as hex.
        //
        // This is the one piece of evidence that separates the two candidate causes of
        // "no extractor could read the stream": a run of zeroes means we served a hole
        // in a sparse file, whereas a plausible signature (`....ftyp` for MP4,
        // `1A45DFA3` for Matroska, `FFD8FF` for JPEG) means we served real bytes and the
        // problem is that they belong to the wrong file. Guessing between those two cost
        // a round of fixes that changed nothing.
        if (BuildConfig.DEBUG && !firstBytesLogged) {
            firstBytesLogged = true
            val sample = buffer.copyOfRange(offset, offset + minOf(read, FIRST_BYTES))
            Log.i(
                TAG,
                "first read file=$fileId at=${readPosition} n=$read " +
                    "head=${sample.joinToString(" ") { "%02X".format(it) }}",
            )
        }

        readPosition += read
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= read
        bytesTransferred(read)
        return read
    }

    /**
     * Blocks until TDLib's contiguous window covers [position].
     *
     * @return the exclusive upper bound that can be read right now. Guaranteed to be
     *   either greater than [position] with every byte between them present, or equal
     *   to it, which [read] turns into a clean end of stream.
     */
    private fun awaitWindow(position: Long): Long {
        window?.takeIf { it.canRead(position) }?.let { return it.readableUntil }

        // Either the window never covered this position (a seek) or playback has
        // caught up with the download edge. Both are answered by asking again.
        val refreshed = request(position, limit = 0L)
        window = refreshed

        if (!refreshed.canRead(position)) {
            // Past the end of a file TDLib has finished is a real end of stream.
            // Reported as the position itself so `read` yields exactly zero bytes.
            if (refreshed.isDownloadingCompleted && position >= refreshed.readableUntil) {
                return position
            }

            // Anything else is a **hole**, and this is the branch that mattered.
            //
            // It used to return `readableUntil` for any completed file, which is only
            // the end of the stream when the position is past it. When the position is
            // *below* `downloadOffset` — which is where TDLib's last ranged request left
            // it, so byte 0 after a moov-atom read at the tail qualifies — that bound is
            // above the position, `read` happily seeks there, and a sparse file returns
            // **zeroes**. Zeroes are not an error to a demuxer: ExoPlayer reported
            // `UnrecognizedInputFormatException` with `contentIsMalformed=false`, i.e.
            // "none of the extractors could read this", and surfaced it as a bare
            // "Source error" — 27 times in one session, with nothing logged, because
            // as far as this class was concerned every read had succeeded.
            Log.w(
                TAG,
                "hole at $position for file=$fileId: window=[${refreshed.downloadOffset}," +
                    "${refreshed.readableUntil}) size=${refreshed.expectedSize} " +
                    "active=${refreshed.isDownloadingActive} " +
                    "complete=${refreshed.isDownloadingCompleted}",
            )
            throw IOException(
                "Telegram did not deliver byte $position of file $fileId " +
                    "(has [${refreshed.downloadOffset}, ${refreshed.readableUntil})).",
            )
        }

        // Also picks up a file TDLib has re-created since the last read, and covers
        // the case where the handle predates the file existing at all.
        adoptFile(refreshed)
        return refreshed.readableUntil
    }

    /**
     * One TDLib range request, with repair and retry.
     *
     * Returns a state whose window covers [position], or throws once every avenue
     * is spent. The repair runs at most once per call — a second attempt would be
     * the same round trip with the same answer — while a merely slow range is
     * retried until [ATTEMPTS] is exhausted.
     */
    private fun request(position: Long, limit: Long): TelegramFileState = runBlocking(lifecycle) {
        var lastError: GatewayResult.Failure? = null
        var repairAttempted = false
        val deadline = System.currentTimeMillis() + TOTAL_BUDGET_MS

        repeat(ATTEMPTS) { attempt ->
            when (val result = gateway.requestRange(fileId, position, limit)) {
                is GatewayResult.Success -> return@runBlocking result.value

                is GatewayResult.Failure -> {
                    lastError = result
                    Log.w(
                        TAG,
                        "range file=$fileId pos=$position attempt=${attempt + 1}/$ATTEMPTS " +
                            "-> ${result.error}: ${result.message}",
                    )

                    // Nothing to retry against: no session, or no TDLib at all.
                    if (result.error == GatewayError.NOT_AUTHENTICATED ||
                        result.error == GatewayError.UNAVAILABLE
                    ) {
                        return@repeat
                    }

                    if (result.error.isStaleHandle()) {
                        if (!repairAttempted) {
                            repairAttempted = true
                            val healed = heal()
                            if (healed != null) {
                                // Any answer at all is progress — *including the same
                                // id*. What goes stale here is the file reference encoded
                                // inside the id, not the id itself, and re-reading the
                                // message refreshes it in place, so TDLib rightly hands
                                // back the same integer, now downloadable. This used to
                                // require `healed != fileId` and skip the immediate retry
                                // otherwise, which cost a wasted backoff on the commonest
                                // repair there is.
                                if (healed != fileId) {
                                    Log.i(TAG, "repaired file=$fileId -> $healed")
                                    fileId = healed
                                } else {
                                    Log.i(TAG, "refreshed file reference for file=$fileId")
                                }
                                // No backoff: the failure was the handle, not the network.
                                return@repeat
                            }
                        } else {
                            // The repaired id was refused too, so what `MediaFileRepair`
                            // remembers is wrong — its success memo is keyed on "we asked
                            // recently", not on "the ids work". Drop it so the next
                            // request re-reads the message.
                            //
                            // Only here, on evidence. Forgetting before every repair —
                            // which is what this did briefly — forces a fresh `getMessage`
                            // on every range open, and a burst of those across the header
                            // and moov reads of one file is how an account earns a
                            // flood-wait, turning a recoverable failure into a locked-out
                            // one.
                            repair.forget(localId)
                        }
                    }

                    // Each attempt already carries TDLib's own 45s range timeout, so
                    // four of them is minutes of spinner on a dead connection. The
                    // wall-clock budget is what actually bounds the wait; the attempt
                    // count only bounds how many distinct things are tried.
                    if (System.currentTimeMillis() >= deadline) return@repeat

                    if (attempt < ATTEMPTS - 1) {
                        val wait = if (result.error == GatewayError.FLOOD_WAIT) {
                            result.retryAfterSeconds.coerceIn(1, MAX_FLOOD_WAIT_S) * 1_000L
                        } else {
                            BACKOFF_MS * (attempt + 1)
                        }
                        delay(wait)
                    }
                }
            }
        }

        val error = lastError
        throw IOException(
            "Telegram would not serve byte $position of file $fileId" +
                (error?.let { " (${it.error}: ${it.message})" } ?: "."),
        )
    }

    /**
     * Re-establish a downloadable id for this item.
     *
     * [MediaFileRepair] first, because re-reading the message is the only repair
     * that fixes an expired file reference *and* writes the result back to the row,
     * so the grid and the photo viewer stop failing too. The remote-id route is a
     * fallback for the one case the first cannot serve — a row that has since been
     * deleted from the library while playback continued.
     */
    private suspend fun heal(): Int? {
        repair.repair(localId, MediaFileRole.ORIGINAL)?.let { return it }
        val remote = remoteFileId ?: return null
        return gateway.fileIdForRemoteId(remote).valueOrNull
    }

    /**
     * True for errors that mean "this handle is no good", as opposed to "the network
     * is having a moment".
     *
     * [GatewayError.UNKNOWN] is included on purpose. TDLib's refusals on this path
     * are free-text and the set of exact strings is not something to bet playback on;
     * a wasted repair costs one round trip, whereas a missed one costs the item
     * permanently. That asymmetry is what made the previous, narrower test the reason
     * old content never recovered.
     */
    private fun GatewayError.isStaleHandle(): Boolean =
        this == GatewayError.FILE_UNAVAILABLE || this == GatewayError.UNKNOWN

    override fun getUri(): Uri? = uri

    override fun close() {
        // Cancel first: a read may be parked in awaitWindow, and closing the file
        // handle out from under it would surface as a spurious IO error instead of
        // the cancellation it is.
        lifecycle.cancel(CancellationException("DataSource closed"))

        runCatching { handle?.close() }
        handle = null
        handlePath = null
        window = null
        uri = null

        // Stop the abandoned download. Without this a seek leaves the previous
        // range still pulling bytes, competing for bandwidth with the range the
        // user is now waiting on. Bounded, because this runs on the loader thread
        // during teardown and an unresponsive TDLib must not hold the screen open.
        val id = fileId
        if (id != NO_FILE) {
            runCatching {
                runBlocking {
                    withTimeoutOrNull(CANCEL_TIMEOUT_MS) { gateway.cancelDownload(id) }
                }
            }
        }

        if (opened) {
            opened = false
            transferEnded()
        }
    }

    /** Media3 builds one source per playback; the gateway and repair are shared. */
    class Factory(
        private val gateway: TelegramGateway,
        private val repair: MediaFileRepair,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = TelegramDataSource(gateway, repair)
    }

    private companion object {
        const val TAG = "HardPlay/Stream"
        const val NO_FILE = -1

        /**
         * Range attempts before playback fails.
         *
         * Four, because one of them is typically spent on the repair and the caller
         * of last resort is a user watching a spinner. Each attempt already carries
         * TDLib's own 45s range timeout, so this is a bound on distinct tries rather
         * than on time.
         */
        const val ATTEMPTS = 4
        const val BACKOFF_MS = 700L
        const val MAX_FLOOD_WAIT_S = 30
        const val CANCEL_TIMEOUT_MS = 2_000L

        /** Bytes of the head of stream to log. Enough for any container signature. */
        const val FIRST_BYTES = 16

        /**
         * Wall-clock ceiling on one range request, across all its attempts.
         *
         * The attempt count alone is not a bound on time: TDLib's own range timeout is
         * 45s, so four attempts on a dead connection is minutes of spinner. This is
         * what actually decides when to stop, while [ATTEMPTS] decides how many
         * distinct things get tried — which matters because one of them is the repair.
         */
        const val TOTAL_BUDGET_MS = 70_000L
    }
}

/**
 * True when the file TDLib is serving cannot be the one the row describes.
 *
 * A separate, pure function so the one judgement in it is testable without Media3: the
 * comparison is **exact**, not a tolerance. `MediaEntity.fileSizeBytes` is taken from the
 * message at index time and TDLib reports the same figure for the same file, so any
 * difference at all means a different file — and the differences actually observed were
 * not marginal (35 KB against 240 MB, 397 B against hundreds of MB).
 *
 * Both zero cases yield false, deliberately. A size of 0 means "not known yet" rather
 * than "empty", and treating unknown as a mismatch would re-resolve every file whose
 * size TDLib has not yet reported.
 */
internal fun sizeMismatch(declaredBytes: Long, servingBytes: Long): Boolean =
    declaredBytes > 0L && servingBytes > 0L && declaredBytes != servingBytes

/**
 * The `tg://` URI that identifies a Telegram file to Media3.
 *
 * Media3 addresses media by URI, so the ids have to travel in one. Size, the
 * persistent remote id and the row's local id ride along because the `DataSource`
 * needs all three before it has spoken to TDLib or to Room: the size to answer
 * ExoPlayer's length question on open, and the two ids to repair a refused file
 * without a database lookup on the loader thread.
 */
object TelegramMediaUri {

    private const val SCHEME = "tg"
    private const val HOST = "file"
    private const val PARAM_SIZE = "size"
    private const val PARAM_REMOTE = "remote"
    private const val PARAM_LOCAL = "local"

    data class Target(
        val fileId: Int,
        val sizeBytes: Long,
        val remoteFileId: String?,
        /** The `media.localId` this file belongs to; 0 when unknown. */
        val localId: Long,
    )

    fun build(
        fileId: Int,
        sizeBytes: Long,
        remoteFileId: String?,
        localId: Long,
    ): Uri = Uri.Builder()
        .scheme(SCHEME)
        .authority(HOST)
        .appendPath(fileId.toString())
        .appendQueryParameter(PARAM_SIZE, sizeBytes.toString())
        .appendQueryParameter(PARAM_LOCAL, localId.toString())
        .apply { if (remoteFileId != null) appendQueryParameter(PARAM_REMOTE, remoteFileId) }
        .build()

    fun buildString(
        fileId: Int,
        sizeBytes: Long,
        remoteFileId: String?,
        localId: Long,
    ): String = build(fileId, sizeBytes, remoteFileId, localId).toString()

    fun parse(uri: Uri): Target? {
        if (uri.scheme != SCHEME || uri.host != HOST) return null
        val fileId = uri.pathSegments.firstOrNull()?.toIntOrNull() ?: return null
        return Target(
            fileId = fileId,
            sizeBytes = uri.getQueryParameter(PARAM_SIZE)?.toLongOrNull() ?: 0L,
            remoteFileId = uri.getQueryParameter(PARAM_REMOTE),
            localId = uri.getQueryParameter(PARAM_LOCAL)?.toLongOrNull() ?: 0L,
        )
    }

    fun parse(value: String): Target? = parse(value.toUri())
}
