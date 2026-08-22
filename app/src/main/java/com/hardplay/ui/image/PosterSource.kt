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
import com.hardplay.data.repo.MediaFileRepair
import com.hardplay.data.repo.MediaFileRole
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
     * The row these ids came from, so a refused download can be repaired.
     *
     * Carried because every id above is perishable and `localId` is not. Without it
     * a stale id had nowhere to go but the next rung down, which is how opening an
     * old photo ended up displaying its 40px inline preview at full screen — the
     * "even after opening it, quality is very low" report. See `MediaFileRepair`.
     */
    val localId: Long = 0L,
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
        localId = localId,
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
        // The original sits *above* the decoded frame, not below it.
        //
        // `PosterStore` caps a frame at 1280px on its long edge, so when a caller has
        // explicitly asked for full resolution — only the photo viewer does — serving it
        // a downscaled JPEG is the very defect `preferOriginal` was added to fix. The
        // frame stays as the rung underneath, which is what the viewer cross-fades from.
        if (preferOriginal) {
            originalFileId?.let {
                add(remote(it, MediaFileRole.ORIGINAL, Rung.Slot.ORIGINAL))
            }
        }
        localPath?.let { add(Rung.Local(it)) }
        if (wantsLarge) {
            previewFileId?.let { add(remote(it, MediaFileRole.PREVIEW, Rung.Slot.PREVIEW)) }
            posterFileId?.let { add(remote(it, MediaFileRole.THUMBNAIL, Rung.Slot.POSTER)) }
            thumbnailFileId?.let { add(remote(it, MediaFileRole.THUMBNAIL, Rung.Slot.THUMBNAIL)) }
        } else {
            posterFileId?.let { add(remote(it, MediaFileRole.THUMBNAIL, Rung.Slot.POSTER)) }
            thumbnailFileId?.let { add(remote(it, MediaFileRole.THUMBNAIL, Rung.Slot.THUMBNAIL)) }
            previewFileId?.let { add(remote(it, MediaFileRole.PREVIEW, Rung.Slot.PREVIEW)) }
        }
        inlinePreview?.takeIf { it.isNotEmpty() }?.let { add(Rung.Inline(it)) }
    }

    private fun remote(
        fileId: Int,
        role: MediaFileRole,
        slot: Rung.Slot,
    ) = Rung.Remote(fileId = fileId, role = role, slot = slot, owner = localId)

    /** One way of getting at an item's pixels. */
    sealed interface Rung {
        /**
         * Its cache key.
         *
         * Must be stable across process launches and across a TDLib re-login, because
         * Coil's disk cache is. See [Remote.cacheKey] for what happens when it is not.
         */
        val cacheKey: String

        data class Local(val path: String) : Rung {
            // Poster files are written under a name that changes when the frame does
            // (see PosterStore), so the path alone is a sufficient version.
            override val cacheKey: String get() = "tg-local-$path"
        }

        /**
         * @param role which of the row's ids this is, so that a repair can hand back
         *   the replacement for *this* rung rather than for the media file. A poster
         *   paired from a neighbouring message is asked for as a thumbnail: it is
         *   another row's artwork, and the repair for it is that row's business.
         * @param slot which artwork *position* this is, which is not the same question
         *   as [role] — a borrowed poster and the item's own thumbnail are both repaired
         *   as thumbnails but are two different pictures, and the cache has to tell them
         *   apart.
         * @param owner the `media.localId` this artwork belongs to. 0 when the source was
         *   not built from a row, e.g. the design gallery.
         */
        data class Remote(
            val fileId: Int,
            val role: MediaFileRole,
            val slot: Slot,
            val owner: Long,
        ) : Rung {
            /**
             * True when this rung is the media file itself rather than a rung of
             * Telegram's size ladder, so the fetcher asks `downloadOriginal` for it.
             *
             * Derived rather than passed. It was a separate `original: Boolean`
             * constructor flag, which is one more thing that can disagree with [slot]
             * for no benefit — the two were always set together.
             */
            val original: Boolean get() = slot == Slot.ORIGINAL

            /**
             * Keyed on the **item and slot**, never on the file id.
             *
             * This was `"tg-file-$fileId"`, and that was a correctness bug rather than a
             * tuning mistake. A TDLib `fileId` is session-scoped — the model says so, and
             * `MediaDao.upsert` says "TDLib hands out fresh session-scoped file ids after
             * a re-login" — so the integer is re-issued to a *different* file whenever the
             * file database is rebuilt. Coil's disk cache is 512 MB, lives in `cacheDir`,
             * and is never cleared by anything in this app, so those entries outlive the
             * ids that named them: after a re-login, id 500 names some other item's
             * picture and every cell holding it draws artwork belonging to a different
             * video. Because the photo viewer's key was built the same way
             * (`"tg-orig-$fileId"`), opening the item showed the same wrong image — which
             * is exactly how it was reported.
             *
             * `(localId, slot)` is durable: it survives a re-login, a repair rewriting the
             * row's ids, and a rebuild of TDLib's database, because it names the *item's*
             * artwork rather than the handle currently pointing at it. That is also why a
             * repair no longer poisons the cache — it used to store the healed bytes under
             * the stale id's key, leaving an entry that was both wrong and permanent.
             *
             * The one thing given up is de-duplication between two rows that share a
             * forwarded file: they now cache separately. That was a nicety, and it was
             * being paid for with cross-item collisions.
             */
            override val cacheKey: String
                get() = if (owner > 0L) "tg-item-$owner-$slot" else "tg-file-$fileId"
        }

        /**
         * Which artwork position a [Remote] rung is.
         *
         * Distinct from [MediaFileRole] because two slots share one role: a borrowed
         * still and the item's own thumbnail are both *repaired* as thumbnails, and
         * collapsing them in the cache key would let whichever one resolved first answer
         * for the other.
         */
        enum class Slot { ORIGINAL, PREVIEW, POSTER, THUMBNAIL }

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
            localId == other.localId &&
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
        result = 31 * result + localId.hashCode()
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
            localId = row.localId,
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
            localId = entity.localId,
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
 * Keyed on the *rung that will actually be fetched*, so the same item requested small
 * and requested large are two entries rather than one that fights itself. The keyer has
 * to reach the same decision as the fetcher, which is why both call [PosterSource.plan]
 * rather than each working it out.
 *
 * What a rung's key must never contain is a TDLib session file id — see
 * [PosterSource.Rung.Remote.cacheKey] for the collision that caused.
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
    private val repair: MediaFileRepair,
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

        // The stored id was refused. Re-read the message, which is the only repair
        // that fixes an expired file reference — and which writes the fresh ids back
        // to the row, so the next cell to want this item does not pay for it again.
        //
        // Falling straight through to the next rung instead, as this used to, is why
        // an old photo opened as its 40px inline preview blown up to full screen: the
        // original was perfectly available, the handle for it had simply gone stale.
        //
        // The retry is *not* conditional on the id having changed. It used to be
        // (`takeIf { it != rung.fileId }`), which discarded the commonest repair of all:
        // what expires here is the file reference inside the id, and re-reading the
        // message refreshes it in place, so TDLib rightly hands back the same integer —
        // now downloadable. Skipping the retry on that basis dropped the item to a lower
        // rung, or to fallback art, when it was already fixed.
        repair.repair(data.localId, rung.role)?.let { healed ->
            download(healed)?.let { return it }
        }

        // Last resort. Offline, so it cannot refresh a reference, but it costs nothing
        // and covers a row that is no longer in the library. The kind matters: TDLib
        // validates a remote reference against the file type it was asked for.
        val remote = data.remoteFileId ?: return null
        val refreshed = gateway.fileIdForRemoteId(remote, data.kind).valueOrNull ?: return null
        return download(refreshed)
    }

    class Factory(
        private val gateway: TelegramGateway,
        private val repair: MediaFileRepair,
    ) : Fetcher.Factory<PosterSource> {
        override fun create(
            data: PosterSource,
            options: Options,
            imageLoader: ImageLoader,
        ): Fetcher = PosterFetcher(data, options, gateway, repair)
    }
}
