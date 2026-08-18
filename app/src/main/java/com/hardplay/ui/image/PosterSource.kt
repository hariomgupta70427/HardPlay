package com.hardplay.ui.image

import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.key.Keyer
import coil.request.Options
import coil.size.Dimension
import coil.size.pxOrElse
import com.hardplay.data.db.entity.MediaEntity
import com.hardplay.data.db.entity.MediaType
import com.hardplay.data.db.projection.LibraryRow
import com.hardplay.telegram.TelegramGateway
import com.hardplay.telegram.TelegramMediaKind
import okio.Buffer
import okio.Path.Companion.toOkioPath
import java.io.File

/**
 * Everything the app knows about how to draw one item's artwork.
 *
 * Deliberately not a path or a single id. Telegram hands out pictures in five
 * different shapes, any given item may have only one of them, and — the part that
 * was originally got wrong — **the right one depends on how big the target is.**
 *
 * The rungs, best first:
 *
 *  1. [localPath] — a decoded video frame, or any picture already on disk. Real
 *     pixels, no network, and the only way a video ever looks sharp: Telegram gives
 *     video exactly one small thumbnail and no ladder.
 *  2. [originalFileId] — the full-resolution file, used only when [preferOriginal]
 *     is set. This is the photo viewer's rung. Opening an image and being handed its
 *     grid thumbnail, upscaled, was the single most visible quality defect in the app.
 *  3. [previewFileId] — a large rung of Telegram's size ladder (~1280px).
 *  4. [posterFileId] — a neighbouring screenshot the channel posted as the video's
 *     preview. A human chose the frame, so it beats an automatic thumbnail.
 *  5. [thumbnailFileId] — Telegram's small thumbnail, ~320px. Right for a dense grid
 *     and wrong for anything full-width.
 *  6. [inlinePreview] — the `minithumbnail` bytes that came *inside* the message.
 *     Around 40px and blurry, but it needs no network at all and almost every video
 *     has one. This is the rung that replaced fallback initials.
 *
 * A plain class rather than a `data class`: [inlinePreview] is a `ByteArray`, whose
 * generated `equals` compares by reference, which would silently break Coil's
 * request equality. [equals] below compares the ids and the bytes' content.
 */
class PosterSource(
    val localPath: String?,
    val posterFileId: Int?,
    val previewFileId: Int?,
    val thumbnailFileId: Int?,
    val originalFileId: Int?,
    val inlinePreview: ByteArray?,
    val remoteFileId: String?,
    val kind: TelegramMediaKind,
    /**
     * Ask for the original file rather than a rung of the ladder.
     *
     * Only the full-screen photo viewer sets this. It can mean downloading several
     * megabytes, which is exactly right for one picture being looked at and exactly
     * wrong for forty cells in a grid.
     */
    val preferOriginal: Boolean = false,
) {
    /** True when there is nothing to draw and the caller should use fallback art. */
    val isEmpty: Boolean
        get() = localPath == null &&
            posterFileId == null &&
            previewFileId == null &&
            thumbnailFileId == null &&
            inlinePreview == null &&
            !(preferOriginal && originalFileId != null)

    /** The same artwork, but resolved at full resolution. For the photo viewer. */
    fun atOriginalResolution(): PosterSource = PosterSource(
        localPath = localPath,
        posterFileId = posterFileId,
        previewFileId = previewFileId,
        thumbnailFileId = thumbnailFileId,
        originalFileId = originalFileId,
        inlinePreview = inlinePreview,
        remoteFileId = remoteFileId,
        kind = kind,
        preferOriginal = true,
    )

    /**
     * The rungs to try, in order, for a target of the given size.
     *
     * One function, used by both the fetcher and the keyer, because the cache key
     * has to name the rung that will actually be fetched. Two copies of this decision
     * would mean one image cached under another image's key.
     */
    fun plan(wantsLarge: Boolean): List<Rung> = buildList {
        localPath?.let { add(Rung.Local(it)) }
        if (preferOriginal) originalFileId?.let { add(Rung.Remote(it, original = true)) }
        if (wantsLarge) {
            previewFileId?.let { add(Rung.Remote(it, original = false)) }
            posterFileId?.let { add(Rung.Remote(it, original = false)) }
            thumbnailFileId?.let { add(Rung.Remote(it, original = false)) }
        } else {
            posterFileId?.let { add(Rung.Remote(it, original = false)) }
            thumbnailFileId?.let { add(Rung.Remote(it, original = false)) }
            previewFileId?.let { add(Rung.Remote(it, original = false)) }
        }
        inlinePreview?.takeIf { it.isNotEmpty() }?.let { add(Rung.Inline(it)) }
    }

    /** One way of getting at an item's pixels. */
    sealed interface Rung {
        /** Its cache key. Names the bytes, so two rows sharing a file share an entry. */
        val cacheKey: String

        data class Local(val path: String) : Rung {
            // Poster files are written under a name that changes when the frame does
            // (see PosterStore), so the path alone is a sufficient version.
            override val cacheKey: String get() = "tg-local-$path"
        }

        data class Remote(val fileId: Int, val original: Boolean) : Rung {
            override val cacheKey: String
                get() = if (original) "tg-orig-$fileId" else "tg-file-$fileId"
        }

        class Inline(val bytes: ByteArray) : Rung {
            override val cacheKey: String get() = "tg-mini-${bytes.contentHashCode()}"
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PosterSource) return false
        return localPath == other.localPath &&
            posterFileId == other.posterFileId &&
            previewFileId == other.previewFileId &&
            thumbnailFileId == other.thumbnailFileId &&
            originalFileId == other.originalFileId &&
            remoteFileId == other.remoteFileId &&
            kind == other.kind &&
            preferOriginal == other.preferOriginal &&
            inlinePreview.contentEquals(other.inlinePreview)
    }

    override fun hashCode(): Int {
        var result = localPath?.hashCode() ?: 0
        result = 31 * result + (posterFileId ?: 0)
        result = 31 * result + (previewFileId ?: 0)
        result = 31 * result + (thumbnailFileId ?: 0)
        result = 31 * result + (originalFileId ?: 0)
        result = 31 * result + (remoteFileId?.hashCode() ?: 0)
        result = 31 * result + kind.hashCode()
        result = 31 * result + preferOriginal.hashCode()
        result = 31 * result + (inlinePreview?.contentHashCode() ?: 0)
        return result
    }

    companion object {
        /**
         * Target width above which the large rung is worth its bytes.
         *
         * A two-column 16:9 cell is around 530 real pixels on a 1080p phone, so the
         * threshold sits below that deliberately: the small rung is ~320px, and
         * upscaling it by 1.7× is precisely what "low quality" described. Only the
         * three-plus-column grids and the small shelf cards stay on it.
         */
        const val LARGE_TARGET_PX = 400

        fun of(row: LibraryRow) = PosterSource(
            localPath = row.posterPath,
            posterFileId = row.posterFileId,
            previewFileId = row.previewFileId,
            thumbnailFileId = row.thumbnailFileId,
            originalFileId = row.fileId,
            inlinePreview = row.minithumbnail,
            remoteFileId = row.remoteFileId,
            kind = if (row.isVideo) TelegramMediaKind.VIDEO else TelegramMediaKind.PHOTO,
        )

        fun of(entity: MediaEntity) = PosterSource(
            localPath = entity.posterPath,
            posterFileId = entity.posterFileId,
            previewFileId = entity.previewFileId,
            thumbnailFileId = entity.thumbnailFileId,
            originalFileId = entity.fileId,
            inlinePreview = entity.minithumbnail,
            remoteFileId = entity.remoteFileId,
            kind = if (MediaType.fromStored(entity.type) == MediaType.PHOTO) {
                TelegramMediaKind.PHOTO
            } else {
                TelegramMediaKind.VIDEO
            },
        )
    }
}

/**
 * True when the request's target is big enough to justify the large rung.
 *
 * An undefined dimension counts as large: the only things that ask for an
 * unconstrained image here are the player's poster and the photo viewer.
 */
internal fun Options.wantsLargeRung(): Boolean {
    val longest = maxOf(
        size.width.pxOrElse { Int.MAX_VALUE },
        size.height.pxOrElse { Int.MAX_VALUE },
    )
    return longest >= PosterSource.LARGE_TARGET_PX
}

/** True when neither dimension was resolved — i.e. `Size.ORIGINAL`. */
internal fun Options.hasUnboundedTarget(): Boolean =
    size.width is Dimension.Undefined && size.height is Dimension.Undefined
/**
 * Cache key.
 *
 * Keyed on the *rung that will actually be fetched*, not on the item, so two rows
 * sharing a forwarded photo share one entry — and so the same item requested small
 * and requested large are two entries rather than one that fights itself. When only
 * inline bytes exist the key is their content hash, which is stable across launches
 * where a session file id is not.
 */
class PosterKeyer : Keyer<PosterSource> {
    override fun key(data: PosterSource, options: Options): String =
        data.plan(options.wantsLargeRung()).firstOrNull()?.cacheKey ?: "tg-none"
}

/**
 * Resolves a [PosterSource] into bytes, best rung first.
 *
 * Returns a [SourceResult] rather than an already-decoded bitmap so Coil still owns
 * decoding — which means it applies the target's size, samples down to it, and pools
 * the result. Handing Coil a finished bitmap gives up all three and holds a
 * full-size decode per visible cell.
 */
class PosterFetcher(
    private val data: PosterSource,
    private val options: Options,
    private val gateway: TelegramGateway,
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        for (rung in data.plan(options.wantsLargeRung())) {
            val result = when (rung) {
                is PosterSource.Rung.Local -> fromFile(rung.path)
                is PosterSource.Rung.Remote -> fromTelegram(rung)
                is PosterSource.Rung.Inline -> fromBytes(rung.bytes)
            }
            if (result != null) return result
        }
        return null
    }

    private fun fromFile(path: String): FetchResult? {
        val file = File(path)
        if (!file.isFile || file.length() <= 0L) return null
        return SourceResult(
            source = ImageSource(file = file.toOkioPath()),
            mimeType = null,
            dataSource = DataSource.DISK,
        )
    }

    private suspend fun fromTelegram(rung: PosterSource.Rung.Remote): FetchResult? {
        // A null path, or a path TDLib has not filled in yet, falls through to the
        // next rung rather than drawing nothing.
        val path = pathFor(rung) ?: return null
        return fromFile(path)
    }

    private fun fromBytes(bytes: ByteArray): FetchResult = SourceResult(
        source = ImageSource(
            source = Buffer().apply { write(bytes) },
            context = options.context,
        ),
        mimeType = "image/jpeg",
        // MEMORY, not DISK or NETWORK: these bytes arrived inside the message and
        // were already in the database row.
        dataSource = DataSource.MEMORY,
    )

    private suspend fun pathFor(rung: PosterSource.Rung.Remote): String? {
        val download: suspend (Int) -> String? = { id ->
            if (rung.original) {
                gateway.downloadOriginal(id).valueOrNull
            } else {
                gateway.downloadThumbnail(id).valueOrNull
            }
        }

        download(rung.fileId)?.let { return it }

        // The session id was stale. Re-resolve from the persistent id and retry
        // once — twice would just be a loop with extra steps. The kind matters:
        // TDLib validates a remote reference against the file type it was asked for.
        val remote = data.remoteFileId ?: return null
        val refreshed = gateway.fileIdForRemoteId(remote, data.kind).valueOrNull ?: return null
        return download(refreshed)
    }

    class Factory(private val gateway: TelegramGateway) : Fetcher.Factory<PosterSource> {
        override fun create(
            data: PosterSource,
            options: Options,
            imageLoader: ImageLoader,
        ): Fetcher = PosterFetcher(data, options, gateway)
    }
}
