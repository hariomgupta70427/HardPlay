package com.hardplay.data.db

/**
 * Turns whatever the user typed into a valid FTS4 MATCH expression.
 *
 * This is a correctness requirement, not politeness: FTS4 treats `"`, `*`, `-`,
 * `^`, `:`, `(`, `)` and the bare words AND/OR/NOT/NEAR as query syntax, so a
 * search for `Ford — 2:1 (final)` raises SQLiteException mid-keystroke and takes
 * the screen down with it. Every token is reduced to letters and digits, then
 * given a trailing `*` so results appear while the word is still being typed.
 */
object FtsQuery {

    /**
     * Anything that isn't part of a word is a separator.
     *
     * `\p{M}` — combining marks — is in the keep-set alongside letters and digits,
     * and it has to be. In Devanagari, `रात` is र + ा + त where the middle character
     * is a spacing combining mark (category Mc), not a letter; without `\p{M}` it
     * reads as a separator and the word is split into two meaningless fragments.
     * The same applies to Arabic, Thai, Tamil and every other script that hangs
     * vowels off a consonant.
     */
    private val SEPARATORS = Regex("[^\\p{L}\\p{N}\\p{M}]+")

    /** Guard against a paste of prose turning into a 900-term MATCH. */
    private const val MAX_TERMS = 12

    /**
     * @return a MATCH expression, or null when the input has no searchable
     *   token — in which case the caller must skip the FTS clause entirely
     *   rather than match on an empty string.
     */
    fun forInput(raw: String): String? {
        val terms = raw.split(SEPARATORS)
            .asSequence()
            .filter { it.isNotBlank() }
            .take(MAX_TERMS)
            .toList()
        if (terms.isEmpty()) return null

        // Implicit AND between terms: typing a second word should narrow the
        // list, which is what everyone expects from a search field.
        return terms.joinToString(" ") { "$it*" }
    }
}
