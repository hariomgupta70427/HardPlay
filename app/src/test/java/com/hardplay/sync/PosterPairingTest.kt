package com.hardplay.sync

import com.hardplay.telegram.TelegramMediaKind
import com.hardplay.telegram.TelegramMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A *wrong* poster is worse than a missing one — it looks like the app cannot tell
 * its own items apart — so most of these tests are about what must not be paired.
 */
class PosterPairingTest {

    private fun video(
        id: Long,
        date: Long = 1_000,
        thumb: Int? = null,
        mini: ByteArray? = null,
        album: Long = 0,
    ) = message(id, TelegramMediaKind.VIDEO, date, thumb, null, mini, album)

    private fun photo(
        id: Long,
        date: Long = 1_000,
        thumb: Int? = 900,
        preview: Int? = null,
        album: Long = 0,
    ) = message(id, TelegramMediaKind.PHOTO, date, thumb, preview, null, album)

    private fun message(
        id: Long,
        kind: TelegramMediaKind,
        date: Long,
        thumb: Int?,
        preview: Int?,
        mini: ByteArray?,
        album: Long,
    ) = TelegramMessage(
        messageId = id,
        chatId = -100,
        date = date,
        caption = "",
        kind = kind,
        fileId = id.toInt(),
        remoteFileId = "r$id",
        remoteUniqueId = "u$id",
        fileSizeBytes = 1_000,
        thumbnailFileId = thumb,
        previewFileId = preview,
        minithumbnail = mini,
        durationSeconds = if (kind == TelegramMediaKind.VIDEO) 60 else null,
        width = 1920,
        height = 1080,
        mimeType = null,
        albumId = album,
    )

    @Test
    fun `screenshot posted just before a bare video becomes its poster`() {
        val result = PosterPairing.pair(listOf(photo(id = 10, thumb = 900), video(id = 11)))
        assertEquals(900, result.posterFor[11L])
        // And the still is recorded as serving that video, so the grid can fold it.
        assertEquals(11L, result.stillServes[10L])
    }

    @Test
    fun `an album id beats adjacency`() {
        // A closer photo by id, but the album says otherwise. The album wins.
        val result = PosterPairing.pair(
            listOf(
                photo(id = 20, thumb = 700, album = 5),
                photo(id = 30, thumb = 800),
                video(id = 31, album = 5),
            ),
        )
        assertEquals(700, result.posterFor[31L])
    }

    @Test
    fun `a video with its own thumbnail is left alone`() {
        val result = PosterPairing.pair(listOf(photo(id = 10), video(id = 11, thumb = 555)))
        assertTrue(result.posterFor.isEmpty())
        assertTrue(result.stillServes.isEmpty())
    }

    @Test
    fun `a video with only inline preview bytes is left alone`() {
        // A minithumbnail is enough to draw something, so no still is consumed.
        val result = PosterPairing.pair(
            listOf(photo(id = 10), video(id = 11, mini = byteArrayOf(1, 2, 3))),
        )
        assertTrue(result.posterFor.isEmpty())
    }

    @Test
    fun `a distant photo is not paired`() {
        val result = PosterPairing.pair(listOf(photo(id = 1), video(id = 40)))
        assertNull(result.posterFor[40L])
    }

    @Test
    fun `a photo posted hours apart is not paired`() {
        // Adjacent ids but a big time gap: a backfill can put unrelated posts next
        // to each other, and pairing those would mislabel the library.
        val result = PosterPairing.pair(
            listOf(photo(id = 10, date = 1_000), video(id = 11, date = 99_000)),
        )
        assertNull(result.posterFor[11L])
    }

    @Test
    fun `the earlier still wins when a video sits between two`() {
        // "still, video, still, video" must not pair each video with the *next*
        // item's still, which would label the whole library one row out.
        val result = PosterPairing.pair(
            listOf(photo(id = 10, thumb = 100), video(id = 11), photo(id = 12, thumb = 200)),
        )
        assertEquals(100, result.posterFor[11L])
    }

    @Test
    fun `one still is not claimed by two videos`() {
        val result = PosterPairing.pair(
            listOf(video(id = 10), photo(id = 11, thumb = 300), video(id = 12)),
        )
        assertEquals(1, result.stillServes.size)
    }

    @Test
    fun `the still's larger rung is preferred when it has one`() {
        // A borrowed still is the video's *only* artwork, and it is shown full-width
        // as well as in a small cell. Taking the grid rung would mean nothing in the
        // app could ever draw that item sharply.
        val result = PosterPairing.pair(
            listOf(photo(id = 10, thumb = 900, preview = 1_900), video(id = 11)),
        )
        assertEquals(1_900, result.posterFor[11L])
    }

    @Test
    fun `pages without photos or without orphan videos do nothing`() {
        assertTrue(PosterPairing.pair(listOf(video(id = 1), video(id = 2))).posterFor.isEmpty())
        assertTrue(PosterPairing.pair(listOf(photo(id = 1), photo(id = 2))).posterFor.isEmpty())
        assertTrue(PosterPairing.pair(emptyList()).posterFor.isEmpty())
    }
}
