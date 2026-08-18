package com.hardplay.ui.image

import android.content.Context
import android.graphics.Bitmap
import com.hardplay.data.db.dao.MediaDao
import com.hardplay.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * The decoded-artwork directory.
 *
 * Exists because Telegram gives a video **one** thumbnail — typically 320px or less —
 * and no size ladder to climb. A photo can be asked for at a larger rung; a video
 * cannot, so the only way its cell ever looks sharp is to decode a frame and keep it.
 * This owns the keeping.
 *
 * `filesDir`, not `cacheDir`, and that is the whole reason this is a class rather than
 * two lines inside the harvester: the path is recorded in `media.posterPath`, so if the
 * OS reclaimed the file behind the row's back every affected cell would draw nothing
 * until something noticed. Under `filesDir` the row and the file agree, and eviction is
 * a decision the app makes rather than one made to it.
 */
@Singleton
class PosterStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mediaDao: MediaDao,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    private val directory: File get() = File(context.filesDir, DIRECTORY)

    /**
     * Store [bitmap] as this item's artwork and point the row at it.
     *
     * The caller keeps ownership of [bitmap]; it is read and never recycled here, since
     * the player hands over a frame it may still be drawing.
     *
     * @return the new path, or null if anything went wrong. Artwork is never worth a
     *   crash: a failure here leaves the row on the rung it was already using.
     */
    suspend fun write(localId: Long, bitmap: Bitmap): String? = withContext(io) {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return@withContext null

        runCatching {
            directory.mkdirs()

            // The filename carries a timestamp, and that is load-bearing rather than
            // tidy: `PosterSource.Rung.Local.cacheKey` is derived from the path, so a
            // stable name would leave Coil serving the *previous* frame out of its
            // memory cache for the life of the process. A new name is a new key.
            val target = File(directory, "$localId-${System.currentTimeMillis()}.jpg")

            // Written aside and renamed into place. A crash midway through a direct
            // write would leave a truncated JPEG that the row already points at, which
            // draws as a broken image rather than as a missing one.
            val scratch = File(directory, "${target.name}$SCRATCH_SUFFIX")
            val scaled = scaleToFit(bitmap)
            try {
                scratch.outputStream().use { out ->
                    if (!scaled.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)) {
                        error("JPEG encode failed for $localId")
                    }
                }
            } finally {
                // Only the copy. `scaleToFit` returns the argument unchanged when no
                // scaling was needed, and recycling that would destroy the caller's
                // bitmap while it is still on screen.
                if (scaled !== bitmap) scaled.recycle()
            }

            if (!scratch.renameTo(target)) {
                scratch.delete()
                error("Could not move artwork into place for $localId")
            }

            // Row first, old files second. Reversed, a crash in between would leave the
            // row naming a file that had already been deleted.
            mediaDao.setPosterPath(localId, target.absolutePath)
            pruneOthers(localId, keep = target.name)

            target.absolutePath
        }.getOrNull()
    }

    /**
     * Delete every decoded frame on disk.
     *
     * Files only. The rows still name them, so this is the storage half of a reset and
     * the caller is expected to null `media.posterPath` as well — a missing file falls
     * through to the next rung rather than breaking, so the two halves are safe in
     * either order, but leaving rows pointing at nothing wastes a fetch per cell.
     */
    suspend fun clearFiles(): Long = withContext(io) {
        val files = directory.listFiles() ?: return@withContext 0L
        var freed = 0L
        files.forEach { file ->
            val size = file.length()
            if (file.delete()) freed += size
        }
        freed
    }

    /** Bytes currently held, for the storage readout. */
    suspend fun sizeBytes(): Long = withContext(io) {
        directory.listFiles()?.sumOf { it.length() } ?: 0L
    }

    private fun pruneOthers(localId: Long, keep: String) {
        val prefix = "$localId-"
        directory.listFiles()?.forEach { file ->
            // The dash makes the prefix unambiguous: item 1 cannot match item 12.
            if (file.name.startsWith(prefix) && file.name != keep) file.delete()
        }
    }

    private fun scaleToFit(bitmap: Bitmap): Bitmap {
        val (width, height) = scaledDimensions(bitmap.width, bitmap.height, MAX_EDGE_PX)
        if (width == bitmap.width && height == bitmap.height) return bitmap
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    private companion object {
        const val DIRECTORY = "posters"
        const val SCRATCH_SUFFIX = ".part"

        /**
         * Long edge cap. A full-width card on a 1440p phone is about 1440 real pixels,
         * so 1280 is sharp everywhere in the app while keeping a frame around 150–300 KB
         * rather than the megabyte a 1080p screenshot encodes to.
         */
        const val MAX_EDGE_PX = 1280

        /** 88 is where JPEG stops paying for itself on photographic frames. */
        const val QUALITY = 88
    }
}

/**
 * Target size for a frame, capped on its long edge.
 *
 * Pulled out as a pure function because it is exactly the sort of arithmetic that fails
 * quietly: get it wrong and every poster in the grid is subtly squashed, which reads as
 * bad artwork rather than as a bug. Never upscales — a 480p frame stays 480p rather than
 * being blown up into a soft 1280.
 */
internal fun scaledDimensions(width: Int, height: Int, maxEdge: Int): Pair<Int, Int> {
    if (width <= 0 || height <= 0 || maxEdge <= 0) return 1 to 1
    val longEdge = maxOf(width, height)
    if (longEdge <= maxEdge) return width to height
    val scale = maxEdge.toDouble() / longEdge
    return (width * scale).roundToInt().coerceAtLeast(1) to
        (height * scale).roundToInt().coerceAtLeast(1)
}
