package com.hardplay.sync

import com.hardplay.telegram.TelegramMediaKind
import com.hardplay.telegram.TelegramMessage
import kotlin.math.abs

/**
 * Borrowing a neighbouring screenshot as a video's poster.
 *
 * Channels very commonly post a still *of* a video immediately next to the video
 * itself, as a human-readable preview. Telegram does not link the two, so a video
 * with no thumbnail of its own shows fallback art while the screenshot that belongs
 * to it sits in the next cell of the grid — which is exactly what made the library
 * hard to scan.
 *
 * Two signals, used in order of how much they can be trusted:
 *
 *  1. **Album id.** When the pair was posted as one album, both messages carry the
 *    same [TelegramMessage.albumId]. That is a fact, not an inference.
 *  2. **Adjacency.** Otherwise: the nearest photo within a few message ids *and* a
 *    few minutes, preferring the one posted just before the video, since "screenshot
 *    then video" is the usual order.
 *
 * Both windows are deliberately tight. A wrong poster is worse than a missing one —
 * a missing poster looks like missing data, while a wrong one looks like the app
 * cannot tell its own items apart. Two bugs had it producing them anyway; both are
 * described where they were fixed, at [SERVER_ID_SHIFT] and at the album grouping in
 * [pair].
 */
object PosterPairing {

    /** Ids this far apart are not neighbours. Counted in **server** ids — see below. */
    private const val MAX_ID_GAP = 3L

    /**
     * TDLib's message-id shift: `message.id == server_message_id shl 20`.
     *
     * The reason [MAX_ID_GAP] has to be applied to a shifted id rather than to
     * `messageId` directly. TDLib does not hand out consecutive integers — it leaves the
     * low 20 bits free for local and temporary messages — so two posts made back to back
     * differ by **1,048,576**, not by 1. The window used to be compared against the raw
     * ids, where `<= 3` cannot be true for any two distinct messages, so the entire
     * adjacency rung was dead code on device: every video that had its screenshot beside
     * it, but no album to prove it, kept its fallback initial.
     *
     * It passed its unit tests the whole time, because those built messages with small
     * synthetic ids like 10 and 11, where a gap of 3 means what it looks like. The tests
     * now use realistic shifted ids for exactly this reason.
     */
    private const val SERVER_ID_SHIFT = 20

    /** Posted more than this apart, they are unrelated. Epoch **seconds**. */
    private const val MAX_TIME_GAP_SECONDS = 300L

    /**
     * @param posterFor video message id → file id of the photo to use as its poster.
     * @param stillServes photo message id → the video it is standing in for, so the
     *   library can fold the duplicate still away.
     */
    data class Result(
        val posterFor: Map<Long, Int>,
        val stillServes: Map<Long, Long>,
    ) {
        companion object {
            val Empty = Result(emptyMap(), emptyMap())
        }
    }

    /**
     * @param messages one page of history, in any order.
     *
     * Only videos with no artwork of their own are paired; everything else is left
     * alone.
     */
    fun pair(messages: List<TelegramMessage>): Result {
        val photos = messages.filter { it.kind == TelegramMediaKind.PHOTO }
        if (photos.isEmpty()) return Result.Empty

        val orphanVideos = messages.filter { it.kind == TelegramMediaKind.VIDEO && it.needsPoster() }
        if (orphanVideos.isEmpty()) return Result.Empty

        // An album holds a *list* of photos, not one.
        //
        // This was `associateBy { it.albumId }`, which keeps the last photo of each album
        // and silently discards the rest — so an album posting several videos and several
        // screenshots together handed every one of those videos the *same* still, and at
        // most one of them was the right one. Producing wrong posters wholesale is the one
        // failure this file exists to avoid, and that is how it did it.
        val photosByAlbum = photos.filter { it.albumId != 0L }.groupBy { it.albumId }

        val posterFor = mutableMapOf<Long, Int>()
        val stillServes = mutableMapOf<Long, Long>()

        // A still can stand in for exactly one video, and the claim has to be recorded
        // against *both* maps. Only `stillServes` was deduplicated before, via
        // `putIfAbsent`, while `posterFor` was written unconditionally — so two videos
        // could still be given the same poster; the grid merely folded one still away.
        val claimed = mutableSetOf<Long>()

        // Walked in message order so a page pairs identically however history arrived.
        orphanVideos.sortedBy { it.messageId }.forEach { video ->
            val albumPhotos = video.albumId.takeIf { it != 0L }?.let { photosByAlbum[it] }
            val still = if (albumPhotos != null) {
                // Album membership is the association, so the adjacency windows have
                // nothing to add and would only reject a legitimate pair.
                nearest(video, albumPhotos, claimed, requireWindow = false)
            } else {
                // Deliberately not "the album's photos, or else every photo on the page":
                // a video whose album happens to contain no photo has to fall back to
                // plain adjacency, with both windows applied.
                nearest(video, photos, claimed, requireWindow = true)
            }
            if (still == null) return@forEach

            // The *large* rung, not the grid rung. A borrowed still is only used when
            // the video has no artwork whatsoever, so this one file is the item's only
            // picture — in a full-width cell and in the player's transition as well as
            // in a small cell. Coil samples it down for the small case; nothing can
            // recover detail the other way round.
            posterFor[video.messageId] = still.previewFileId
                ?: still.thumbnailFileId
                ?: still.fileId
            stillServes[still.messageId] = video.messageId
            claimed += still.messageId
        }

        return Result(posterFor = posterFor, stillServes = stillServes)
    }

    /** True when Telegram gave the video no artwork at all. */
    private fun TelegramMessage.needsPoster(): Boolean =
        thumbnailFileId == null && minithumbnail == null

    /** The server-side message number, which is what actually increments by one. */
    private val TelegramMessage.serverId: Long get() = messageId shr SERVER_ID_SHIFT

    /**
     * Closest unclaimed photo, optionally inside both windows.
     *
     * Ties break toward the *earlier* message. Where a video sits between two
     * screenshots, the one before it is far more likely to be its own — a channel
     * posting "still, video, still, video" would otherwise pair every video with the
     * next item's still and label the whole library one row out.
     */
    private fun nearest(
        video: TelegramMessage,
        candidates: List<TelegramMessage>,
        claimed: Set<Long>,
        requireWindow: Boolean,
    ): TelegramMessage? = candidates
        .asSequence()
        .filter { it.messageId !in claimed }
        .filter { !requireWindow || withinWindow(video, it) }
        .minWithOrNull(
            compareBy(
                { abs(it.serverId - video.serverId) },
                { if (it.messageId < video.messageId) 0 else 1 },
                // A deterministic last resort, so one page can never pair two ways
                // depending on the order the list happened to arrive in.
                { it.messageId },
            ),
        )

    private fun withinWindow(video: TelegramMessage, photo: TelegramMessage): Boolean =
        abs(photo.serverId - video.serverId) <= MAX_ID_GAP &&
            abs(photo.date - video.date) <= MAX_TIME_GAP_SECONDS
}
