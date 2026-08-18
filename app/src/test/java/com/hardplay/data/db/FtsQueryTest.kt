package com.hardplay.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These are correctness tests, not politeness tests.
 *
 * FTS4 parses its MATCH argument as a query language, so an unescaped `"`, `*`,
 * `-`, `^`, `:` or a bare `OR` raises SQLiteException. The library screen runs a
 * MATCH on every keystroke, which means any hole here is a crash the user triggers
 * by typing an ordinary character.
 */
class FtsQueryTest {

    @Test
    fun `plain words become prefix terms`() {
        assertEquals("harbour* lights*", FtsQuery.forInput("harbour lights"))
    }

    @Test
    fun `blank input yields null rather than an empty match`() {
        // MATCH '' is a syntax error, so the caller must be told to skip the clause
        // entirely — not handed something falsy-but-present.
        assertNull(FtsQuery.forInput(""))
        assertNull(FtsQuery.forInput("    "))
    }

    @Test
    fun `punctuation-only input yields null`() {
        assertNull(FtsQuery.forInput("--"))
        assertNull(FtsQuery.forInput("\"\""))
        assertNull(FtsQuery.forInput("*"))
        assertNull(FtsQuery.forInput("^:()"))
    }

    @Test
    fun `fts operators are stripped, not escaped`() {
        // Each of these previously took the library screen down mid-typing.
        val hostile = listOf(
            "Ford — 2:1 (final)",
            "\"quoted\"",
            "a AND b",
            "a OR b",
            "NEAR/3",
            "-negated",
            "col:value",
            "wild*card",
            "^anchor",
        )
        hostile.forEach { input ->
            val result = FtsQuery.forInput(input)
            if (result != null) {
                assertTrue(
                    "leaked an operator for '$input' -> '$result'",
                    result.none { it in "\"-^:()/" },
                )
                // Exactly one trailing star per term, and stars nowhere else.
                result.split(' ').forEach { term ->
                    assertTrue("bad term '$term'", term.endsWith("*"))
                    assertEquals("stray star in '$term'", 1, term.count { it == '*' })
                }
            }
        }
    }

    @Test
    fun `digits survive as searchable terms`() {
        assertEquals("2019*", FtsQuery.forInput("2019"))
        assertEquals("4k*", FtsQuery.forInput("4k"))
    }

    @Test
    fun `non-latin scripts are preserved`() {
        // The tokenizer splits on non-letters in any script, so Devanagari and
        // Cyrillic words must come through as terms rather than be discarded.
        val result = FtsQuery.forInput("रात шум")
        assertEquals("रात* шум*", result)
    }

    @Test
    fun `term count is capped so a pasted paragraph cannot build a huge match`() {
        val paragraph = (1..200).joinToString(" ") { "word$it" }
        val terms = FtsQuery.forInput(paragraph)!!.split(' ')
        assertTrue("too many terms: ${terms.size}", terms.size <= 12)
    }

    @Test
    fun `mixed separators collapse to single spaces`() {
        assertEquals("a* b*", FtsQuery.forInput("a  ,;  b"))
    }
}
