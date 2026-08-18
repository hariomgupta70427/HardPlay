package com.hardplay.data.model

/**
 * The shape of a card in the library grid.
 *
 * Configurable, and defaulting to [WIDE], because the 2:3 poster the app started
 * with was the wrong default for the content: a channel of landscape video in a
 * portrait cell letterboxes every thumbnail into a thin strip with dead space above
 * and below it, and squeezes the caption into whatever is left. Three portrait
 * columns on a phone made both problems worse.
 *
 * [WIDE] matches how the video was actually shot, so the frame fills the cell and
 * the title gets a full-width line under it. The other two stay available because a
 * library is not always video — a channel of stills genuinely reads better in
 * [POSTER] or [SQUARE].
 */
enum class CardAspect(val label: String, val ratio: Float) {
    /** 16:9. Matches landscape video, which is the common case. */
    WIDE("16:9", 16f / 9f),

    /** 2:3. Film-poster proportions, for artwork and vertical stills. */
    POSTER("2:3", 2f / 3f),

    /** 1:1. Dense and neutral; good for mixed content. */
    SQUARE("1:1", 1f),
    ;

    /**
     * Columns that read comfortably at a given width.
     *
     * Per-aspect rather than one shared rule: a 16:9 cell needs roughly twice the
     * width of a 2:3 cell to show the same amount of picture, so three wide columns
     * on a phone would undo the point of switching.
     */
    fun columnsFor(widthDp: Float): Int = when (this) {
        WIDE -> when {
            widthDp < 560 -> 2
            widthDp < 900 -> 3
            widthDp < 1300 -> 4
            else -> 5
        }
        POSTER -> when {
            widthDp < 380 -> 2
            widthDp < 620 -> 3
            widthDp < 900 -> 4
            widthDp < 1200 -> 5
            else -> 6
        }
        SQUARE -> when {
            widthDp < 420 -> 2
            widthDp < 700 -> 3
            widthDp < 1000 -> 4
            else -> 5
        }
    }

    companion object {
        fun fromOrdinal(value: Int): CardAspect = entries.getOrElse(value) { WIDE }
    }
}
