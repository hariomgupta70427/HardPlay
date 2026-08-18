package com.hardplay.ui.image

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The frame-scaling arithmetic.
 *
 * Worth real tests because its failure mode is silent: get the aspect wrong and every
 * decoded poster in the grid is subtly squashed, which reads as bad source artwork
 * rather than as a bug — nobody files that, they just conclude the app looks cheap.
 */
class PosterStoreTest {

    @Test
    fun `a landscape frame is clamped on its long edge`() {
        assertEquals(1280 to 720, scaledDimensions(1920, 1080, 1280))
    }

    @Test
    fun `a portrait frame is clamped on its long edge too`() {
        // The cap is on the *long* edge, not on width. Clamping width would leave a
        // vertical clip taller than the cap it was supposed to obey.
        assertEquals(720 to 1280, scaledDimensions(1080, 1920, 1280))
    }

    @Test
    fun `a square frame stays square`() {
        assertEquals(1280 to 1280, scaledDimensions(2000, 2000, 1280))
    }

    @Test
    fun `a frame already inside the cap is untouched`() {
        // Never upscale: a 480p source blown up to 1280 is a bigger file that looks
        // worse than the frame it came from.
        assertEquals(854 to 480, scaledDimensions(854, 480, 1280))
    }

    @Test
    fun `a frame exactly at the cap is untouched`() {
        assertEquals(1280 to 720, scaledDimensions(1280, 720, 1280))
    }

    @Test
    fun `an extreme aspect ratio never rounds an edge away to zero`() {
        // 4000x9 scales to 1280x2.88; rounding that down to 0 would make
        // Bitmap.createScaledBitmap throw, and the poster would be lost to an
        // exception rather than to a decision.
        val (width, height) = scaledDimensions(4000, 9, 1280)
        assertEquals(1280, width)
        assertEquals(3, height)
    }

    @Test
    fun `a degenerate size falls back rather than dividing by zero`() {
        assertEquals(1 to 1, scaledDimensions(0, 0, 1280))
        assertEquals(1 to 1, scaledDimensions(-10, 100, 1280))
        assertEquals(1 to 1, scaledDimensions(100, 100, 0))
    }
}
