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
 * cannot tell its own items apart.
 */
object PosterPairing {

    /** Ids this far apart are not neighbours. Telegram increments per message. */
    private const val MAX_ID_GAP = 3L

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

        val photosByAlbum = photos.filter { it.albumId != 0L }.associateBy { it.albumId }

        val posterFor = mutableMapOf<Long, Int>()
        val stillServes = mutableMapOf<Long, Long>()

        orphanVideos.forEach { video ->
            val still = photosByAlbum[video.albumId.takeIf { it != 0L }]
                ?: nearestPhoto(video, photos)
                ?: return@forEach
            // The *large* rung, not the grid rung. A borrowed still is only used when
            // the video has no artwork whatsoever, so this one file is the item's only
            // picture — in a full-width cell and in the player's transition as well as
            // in a small cell. Coil samples it down for the small case; nothing can
            // recover detail the other way round.
            val poster = still.previewFileId ?: still.thumbnailFileId ?: still.fileId
            posterFor[video.messageId] = poster
            // A still can only stand in for one video; first claim wins, and the
            // ordering in nearestPhoto makes that the closest one.
            stillServes.putIfAbsent(still.messageId, video.messageId)
        }

        return Result(posterFor = posterFor, stillServes = stillServes)
    }

    /** True when Telegram gave the video no artwork at all. */
    private fun TelegramMessage.needsPoster(): Boolean =
        thumbnailFileId == null && minithumbnail == null

    /**
     * Closest photo inside both windows.
     *
     * Ties break toward the *earlier* message. Where a video sits between two
     * screenshots, the one before it is far more likely to be its own — a channel
     * posting "still, video, still, video" would otherwise pair every video with the
     * next item's still and label the whole library one row out.
     */
    private fun nearestPhoto(
        video: TelegramMessage,
        photos: List<TelegramMessage>,
    ): TelegramMessage? = photos
        .filter { photo ->
            abs(photo.messageId - video.messageId) <= MAX_ID_GAP &&
                abs(photo.date - video.date) <= MAX_TIME_GAP_SECONDS
        }
        .minWithOrNull(
            compareBy(
                { abs(it.messageId - video.messageId) },
                { if (it.messageId < video.messageId) 0 else 1 },
            ),
        )
}
