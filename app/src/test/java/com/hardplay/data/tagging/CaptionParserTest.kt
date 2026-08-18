package com.hardplay.data.tagging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The caption parser runs unattended over every indexed message, and a bad tag has
 * to be found and deleted by hand. So the cases that matter most here are the
 * *negative* ones: what it must refuse to tag.
 */
class CaptionParserTest {

    // ------------------------------------------------------------------ tags

    @Test
    fun `hashtags become tags`() {
        val tags = CaptionParser.tags("Harbour lights #nightshoot #cityscape")
        assertTrue(tags.contains("Nightshoot"))
        assertTrue(tags.contains("Cityscape"))
    }

    @Test
    fun `underscores in hashtags become spaces`() {
        assertTrue(CaptionParser.tags("#behind_the_scenes").contains("Behind The Scenes"))
    }

    @Test
    fun `technical tokens are canonicalised to one spelling`() {
        // The whole point: 4k, 4K, UHD and 2160p must collapse to a single chip,
        // otherwise the filter sheet grows four entries for one concept.
        listOf("shot in 4k", "shot in 4K", "shot in UHD", "shot in 2160p").forEach { caption ->
            assertEquals(
                "failed for: $caption",
                listOf("4K"),
                CaptionParser.tags(caption),
            )
        }
    }

    @Test
    fun `bracketed segments become tags`() {
        val tags = CaptionParser.tags("Cold Open [Director's cut]")
        assertTrue(tags.any { it.contains("Director", ignoreCase = true) })
    }

    @Test
    fun `key value lines split on list separators`() {
        val tags = CaptionParser.tags("Title\nGenre: Ambient, Cityscape / Nocturne")
        assertTrue(tags.contains("Ambient"))
        assertTrue(tags.contains("Cityscape"))
        assertTrue(tags.contains("Nocturne"))
    }

    @Test
    fun `a plausible year is kept`() {
        assertTrue(CaptionParser.tags("Rain on Glass (2019)").contains("2019"))
    }

    @Test
    fun `bare numbers that are not years are refused`() {
        val tags = CaptionParser.tags("Take 47 of 128")
        assertFalse(tags.contains("47"))
        assertFalse(tags.contains("128"))
    }

    @Test
    fun `urls and mentions never become tags`() {
        val tags = CaptionParser.tags("Watch here https://t.me/somechannel/42 — @somechannel")
        assertTrue(
            "leaked a url or mention into $tags",
            tags.none { it.contains("t.me", true) || it.contains("somechannel", true) },
        )
    }

    @Test
    fun `hashtags inside a url are not harvested`() {
        // A fragment identifier is not a hashtag, and treating it as one produces a
        // tag from a query string.
        val tags = CaptionParser.tags("see https://example.com/a#section2")
        assertFalse(tags.contains("Section2"))
    }

    @Test
    fun `stopwords are refused`() {
        val tags = CaptionParser.tags("#the #video #download #join")
        assertTrue("kept a stopword: $tags", tags.isEmpty())
    }

    @Test
    fun `sentence fragments are refused`() {
        val tags = CaptionParser.tags("[this is a whole sentence that is not a category]")
        assertTrue("kept a sentence: $tags", tags.isEmpty())
    }

    @Test
    fun `an empty caption yields no tags`() {
        assertTrue(CaptionParser.tags("").isEmpty())
        assertTrue(CaptionParser.tags("   \n  ").isEmpty())
    }

    @Test
    fun `tag count is capped`() {
        val caption = (1..40).joinToString(" ") { "#tag$it" }
        assertTrue(CaptionParser.tags(caption).size <= 10)
    }

    @Test
    fun `duplicate spellings collapse`() {
        val tags = CaptionParser.tags("#Nightshoot #nightshoot [NIGHTSHOOT]")
        assertEquals(1, tags.count { it.equals("nightshoot", ignoreCase = true) })
    }

    @Test
    fun `existing capitalisation is preserved`() {
        // "BTS" must not become "Bts".
        assertTrue(CaptionParser.tags("#BTS").contains("BTS"))
    }

    // ----------------------------------------------------------------- title

    @Test
    fun `title takes the first meaningful line`() {
        assertEquals(
            "Harbour Lights",
            CaptionParser.title("Harbour Lights\n#4k #hdr", "fallback"),
        )
    }

    @Test
    fun `a caption of only hashtags falls back`() {
        // Otherwise the poster is titled with its own metadata.
        assertEquals("Clip #7", CaptionParser.title("#4k #hdr #new", "Clip #7"))
    }

    @Test
    fun `a caption of only a link falls back`() {
        assertEquals("Clip #7", CaptionParser.title("https://t.me/x/1", "Clip #7"))
    }

    @Test
    fun `blank caption falls back`() {
        assertEquals("Still #3", CaptionParser.title("", "Still #3"))
    }

    @Test
    fun `long titles are cut at a word boundary`() {
        val long = "Harbour lights over the eastern breakwater at the end of a very long " +
            "winter evening with the tide coming in"
        val title = CaptionParser.title(long, "fallback")
        assertTrue("too long: ${title.length}", title.length <= 100)
        assertTrue("should be elided", title.endsWith("…"))
        // A cut inside a word looks like a rendering fault rather than an ellipsis.
        assertFalse(title.removeSuffix("…").endsWith(" "))
        assertTrue(long.startsWith(title.removeSuffix("…")))
    }

    @Test
    fun `leading punctuation is trimmed from the title`() {
        assertEquals("Cold Open", CaptionParser.title("— Cold Open", "fallback"))
    }

    @Test
    fun `internal whitespace in the title is collapsed`() {
        assertEquals("Cold   Open".replace(Regex("\\s+"), " "), CaptionParser.title("Cold   Open", "f"))
    }
}
