package com.hardplay.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The identity check that closes the *quiet* half of the "Source error" bug.
 *
 * A TDLib session file id can resolve to a valid but entirely unrelated file after its
 * file database is rebuilt, and TDLib then serves that file without any error at all —
 * so nothing is refused, nothing is repaired, and the only symptom is the demuxer
 * failing to recognise the container. Comparing the size TDLib is serving against the
 * size the row recorded is what makes that case detectable, so the exact edges of the
 * comparison are worth pinning down.
 */
class SizeMismatchTest {

    /** The real one, from the device: a 240 MB video row served a 35 KB profile photo. */
    @Test
    fun `spots a wrong file`() {
        assertTrue(sizeMismatch(declaredBytes = 240_128_341, servingBytes = 35_244))
    }

    /** The other real one: a 397-byte document where a video should be. */
    @Test
    fun `spots a tiny document standing in for a video`() {
        assertTrue(sizeMismatch(declaredBytes = 191_487_695, servingBytes = 397))
    }

    @Test
    fun `passes the matching case`() {
        assertFalse(sizeMismatch(declaredBytes = 240_128_341, servingBytes = 240_128_341))
    }

    /**
     * Zero means "not known yet", not "empty", and it turns up on both sides — TDLib
     * before it has the file, and a row indexed when only an estimate was available.
     * Treating unknown as a mismatch would re-resolve through the message on every open
     * of every such file, which is a round trip in the hot path for no benefit.
     */
    @Test
    fun `treats an unknown size as no evidence either way`() {
        assertFalse(sizeMismatch(declaredBytes = 0, servingBytes = 240_128_341))
        assertFalse(sizeMismatch(declaredBytes = 240_128_341, servingBytes = 0))
        assertFalse(sizeMismatch(declaredBytes = 0, servingBytes = 0))
    }

    /**
     * Exact, not tolerant. A single byte of difference is a different file: the row's
     * figure comes from the message and TDLib reports the same figure for the same file,
     * so there is no legitimate source of drift to absorb.
     */
    @Test
    fun `is exact rather than tolerant`() {
        assertTrue(sizeMismatch(declaredBytes = 240_128_341, servingBytes = 240_128_340))
    }
}
