package com.hardplay.data.tagging

/**
 * Rule-based caption parsing (PRD §5.3, and the locked decision in CLAUDE.md).
 *
 * The design constraint is that this runs against captions nobody wrote for a
 * parser. So every rule is a *positive* signal — an explicit hashtag, a bracketed
 * segment, a known technical token, a `Key: value` line — and anything that isn't
 * recognised is left alone rather than guessed at. Over-tagging is worse than
 * under-tagging here: a wrong tag has to be found and removed by hand, while a
 * missing one is one tap in the tag editor, which the PRD ships anyway.
 *
 * Nothing in this file touches the network. On-device only, no model, no API.
 */
object CaptionParser {

    private const val MAX_TAGS = 10
    private const val MAX_TAG_LENGTH = 28
    private const val MIN_TAG_LENGTH = 2
    private const val MAX_TITLE_LENGTH = 96

    /** Stripped before anything else — never a tag, never part of a title. */
    private val URL = Regex("""\b(?:https?://|www\.|t\.me/)\S+""", RegexOption.IGNORE_CASE)
    private val MENTION = Regex("""(?<![\w/])@[A-Za-z0-9_]{3,}""")

    // `\p{M}` alongside letters and digits so scripts that hang vowel marks off a
    // consonant survive tokenising — in Devanagari, `रात` is र + ा + त where the
    // middle character is a combining mark, and excluding it splits the word.
    private val HASHTAG = Regex("""#([\p{L}\p{N}\p{M}_]{2,32})""")
    private val BRACKETED = Regex("""[\[\{(]\s*([^\[\]{}()]{2,32}?)\s*[\]\})]""")
    private val KEY_VALUE = Regex(
        """(?m)^\s*(?:genre|tags?|category|categories|cast|studio|series|quality)\s*[:：]\s*(.+)$""",
        RegexOption.IGNORE_CASE,
    )
    private val YEAR = Regex("""(?<!\d)(19[5-9]\d|20[0-4]\d)(?!\d)""")

    /**
     * Technical descriptors worth a tag wherever they appear. Matched
     * case-insensitively against whole tokens, then emitted in the canonical
     * casing on the right so "4k", "4K" and "4-K" become one chip.
     */
    private val TECHNICAL = mapOf(
        "4k" to "4K", "uhd" to "4K", "2160p" to "4K",
        "1080p" to "1080p", "fullhd" to "1080p",
        "720p" to "720p", "hd" to "720p",
        "hdr" to "HDR", "hdr10" to "HDR", "dolbyvision" to "Dolby Vision", "dv" to "HDR",
        "60fps" to "60FPS", "120fps" to "120FPS",
        "bluray" to "BluRay", "brrip" to "BluRay", "remux" to "Remux",
        "webdl" to "WEB-DL", "webrip" to "WEB-DL",
        "x265" to "HEVC", "hevc" to "HEVC", "x264" to "H.264",
        "hindi" to "Hindi", "english" to "English", "dual" to "Dual Audio",
        "uncut" to "Uncut", "uncensored" to "Uncensored",
    )

    /**
     * Words that survive the other rules but carry no filtering value. Kept
     * deliberately short — a big stopword list starts eating real tags.
     */
    private val STOPWORDS = setOf(
        "the", "and", "for", "with", "from", "this", "that", "your", "you",
        "new", "full", "video", "watch", "part", "vol", "ep", "episode",
        "download", "link", "click", "here", "join", "channel", "subscribe",
        "com", "net", "org", "www", "http", "https", "mkv", "mp4", "avi",
    )

    data class Parsed(
        /** Display title for the poster card. Never blank. */
        val title: String,
        /** Ordered by confidence: hashtags first, inferred tokens last. */
        val tags: List<String>,
    )

    fun parse(caption: String, fallbackTitle: String): Parsed = Parsed(
        title = title(caption, fallbackTitle),
        tags = tags(caption),
    )

    /**
     * The first line that reads like a name.
     *
     * Hashtag and link spam is stripped first, because a caption whose first
     * line is `#4k #hdr #new` would otherwise title the poster with its own
     * metadata.
     */
    fun title(caption: String, fallback: String): String {
        val cleaned = caption
            .replace(URL, " ")
            .replace(MENTION, " ")
            .replace(HASHTAG, " ")
            .lineSequence()
            .map { line -> line.trim().trim('.', '-', '–', '—', '*', '_', '·', '•', '|') .trim() }
            .firstOrNull { line -> line.length >= 2 && line.any(Char::isLetterOrDigit) }
            ?.replace(Regex("\\s+"), " ")

        val chosen = cleaned?.takeIf { it.isNotBlank() } ?: fallback
        return if (chosen.length <= MAX_TITLE_LENGTH) {
            chosen
        } else {
            // Cut at a word edge; a title severed mid-word looks like a bug.
            val cut = chosen.lastIndexOf(' ', MAX_TITLE_LENGTH)
            (if (cut > MAX_TITLE_LENGTH / 2) chosen.take(cut) else chosen.take(MAX_TITLE_LENGTH))
                .trimEnd() + "…"
        }
    }

    fun tags(caption: String): List<String> {
        if (caption.isBlank()) return emptyList()
        val body = caption.replace(URL, " ").replace(MENTION, " ")

        // LinkedHashMap keyed on the normalised form: preserves the confidence
        // ordering above while collapsing "#4K" and "[4k]" into one tag.
        val found = LinkedHashMap<String, String>()

        fun offer(raw: String) {
            if (found.size >= MAX_TAGS) return
            val tag = clean(raw) ?: return
            found.putIfAbsent(tag.lowercase(), tag)
        }

        // 1. Explicit hashtags — the author's own labels, so trusted first.
        HASHTAG.findAll(body).forEach { offer(it.groupValues[1].replace('_', ' ')) }

        // 2. `Genre: Action, Drama` lines, split on list separators.
        KEY_VALUE.findAll(body).forEach { match ->
            match.groupValues[1].split(',', '/', '|', '•', ';').forEach(::offer)
        }

        // 3. Bracketed segments, which captions use for exactly this purpose.
        BRACKETED.findAll(body).forEach { offer(it.groupValues[1]) }

        // 4. Known technical descriptors anywhere in the text.
        body.split(Regex("[^\\p{L}\\p{N}\\p{M}]+")).forEach { token ->
            TECHNICAL[token.lowercase()]?.let(::offer)
        }

        // 5. A year, which is the one bare number worth keeping.
        YEAR.find(body)?.let { offer(it.value) }

        return found.values.toList()
    }

    /** @return the tag in display form, or null if it isn't worth keeping. */
    private fun clean(raw: String): String? {
        val trimmed = raw.trim().trim('#', '-', '_', '.', '·', '•', '*', '"', '\'')
            .replace(Regex("\\s+"), " ")
            .trim()

        if (trimmed.length !in MIN_TAG_LENGTH..MAX_TAG_LENGTH) return null
        if (!trimmed.any(Char::isLetterOrDigit)) return null
        if (trimmed.lowercase() in STOPWORDS) return null

        // A bare number is noise unless rule 5 already recognised it as a year.
        if (trimmed.all(Char::isDigit) && !YEAR.matches(trimmed)) return null

        // More than four words is a sentence fragment, not a category.
        if (trimmed.count { it == ' ' } >= 4) return null

        return TECHNICAL[trimmed.lowercase().replace(" ", "")] ?: titleCase(trimmed)
    }

    /**
     * Capitalise words while leaving anything that already has internal capitals
     * or digits alone — so "BTS", "4K" and "McQueen" survive intact.
     */
    private fun titleCase(value: String): String = value
        .split(' ')
        .joinToString(" ") { word ->
            when {
                word.isEmpty() -> word
                word.any { it.isUpperCase() || it.isDigit() } -> word
                else -> word.replaceFirstChar(Char::uppercaseChar)
            }
        }
}
