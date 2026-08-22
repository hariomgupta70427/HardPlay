package com.hardplay.playback

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The URI is the only channel through which the streaming source learns which row it
 * is playing, and therefore the only thing that lets it repair a refused file id.
 *
 * A dropped `localId` would not fail loudly. Playback would work for everything whose
 * stored id happened to still be live, and fail permanently for everything else — which
 * is exactly the bug this parameter was added to fix, so it is worth a test that would
 * notice the parameter going missing again.
 *
 * Instrumented rather than a unit test because it exercises `android.net.Uri`, whose
 * escaping is the interesting part: a remote file id is base64-ish and contains
 * characters that are structural in a URI. Stubbing that out would test nothing.
 *
 * Method names are camelCase rather than the backtick-with-spaces form the unit tests
 * use: D8 rejects spaces in a SimpleName below DEX 040, so an instrumented test written
 * that way fails the *build* rather than the test.
 */
@RunWith(AndroidJUnit4::class)
class TelegramMediaUriTest {

    @Test
    fun roundTripsEveryField() {
        val uri = TelegramMediaUri.build(
            fileId = 4321,
            sizeBytes = 987_654_321L,
            remoteFileId = "BAADAgADYwAD_abc-123==",
            localId = 77L,
        )

        val parsed = TelegramMediaUri.parse(uri)

        assertEquals(4321, parsed?.fileId)
        assertEquals(987_654_321L, parsed?.sizeBytes)
        assertEquals("BAADAgADYwAD_abc-123==", parsed?.remoteFileId)
        assertEquals(77L, parsed?.localId)
    }

    /** Remote ids are base64-ish and contain `+`, `/` and `=`; none may be mangled. */
    @Test
    fun roundTripsARemoteIdThroughTheStringForm() {
        val remote = "AgACAgIAAxkBAAI+/z9a+b/c==?&x"
        val text = TelegramMediaUri.buildString(
            fileId = 1,
            sizeBytes = 2,
            remoteFileId = remote,
            localId = 3,
        )

        assertEquals(remote, TelegramMediaUri.parse(text)?.remoteFileId)
    }

    @Test
    fun rejectsAUriFromAnotherScheme() {
        assertNull(TelegramMediaUri.parse("https://example.com/1?size=2"))
        assertNull(TelegramMediaUri.parse("tg://other/1?size=2"))
    }

    /**
     * A missing `local` parameter reads as 0 rather than throwing, because 0 is
     * exactly what the repair path treats as "no row to consult" and falls back on
     * the remote id for.
     */
    @Test
    fun treatsAnAbsentLocalIdAsUnknownRatherThanFailing() {
        val parsed = TelegramMediaUri.parse("tg://file/9?size=10")

        assertEquals(9, parsed?.fileId)
        assertEquals(0L, parsed?.localId)
    }
}
