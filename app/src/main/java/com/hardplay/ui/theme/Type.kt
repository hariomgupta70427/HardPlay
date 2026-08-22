package com.hardplay.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp
import com.hardplay.R

/**
 * Archivo (variable) for everything structural, Instrument Serif Italic for
 * editorial accents.
 *
 * Both are bundled in res/font. Downloadable Google Fonts would mean a runtime
 * request to fonts.gstatic.com, which breaks the "nothing leaves the device
 * except to Telegram" guarantee in PRD §9 — so this is a hard requirement, not
 * a preference.
 *
 * ## The width axis
 *
 * Archivo's `wdth` axis runs **62 to 125** (verified against the bundled TTF's `fvar`
 * table; `wght` runs 100–900). The scale below uses it as a *ramp*: the larger the
 * type, the narrower the face —
 *
 *   display / displaySmall  wdth 72   the masthead voice
 *   headline                wdth 84   bar titles
 *   everything else         wdth 100  UI and body
 *
 * This is how newspaper and poster typography has always worked, and it is the single
 * cheapest thing available here that a default sans-serif system cannot do. It was
 * previously set to 88 for all three display rungs, which is within the width the eye
 * reads as "the normal face, slightly squeezed" — so the app was paying for a variable
 * font and spending it on nothing. At 72 and 44sp the masthead occupies about the same
 * *width* as the old 40sp at 88 while standing 10% taller and reading as a deliberate
 * condensed display face.
 *
 * ## Contrast
 *
 * The scale is deliberately hollow in the middle. 44sp/900 against 10sp/800 is a 4.4×
 * size ratio, and there is nothing between 21sp and 30sp because nothing needs to be
 * there. The previous scale had six styles inside 11–15sp — `title` 15/600, `body`
 * 14/400, `label` 13/600, `titleSmall` 13/600, `bodySmall` 12/400, `labelSmall` 11/500
 * — two of them identical in size *and* weight. That crowding is what makes a screen
 * read as one undifferentiated grey block no matter how good the colours are.
 *
 * ## OpenType
 *
 * The bundled Archivo carries `tnum`, `lnum`, `case`, `zero`, `frac` and the ordinals.
 * Three are used: `tnum` + `lnum` on every numeric readout, and `case` on the tracked
 * caps styles, which raises hyphens, parentheses and figures to cap height. A tracked
 * all-caps label with baseline-height punctuation is a small wrongness that is very
 * hard to name and very easy to feel.
 */

private const val WIDTH_CONDENSED = 72f
private const val WIDTH_NARROW = 84f

/** Tabular, lining figures — for anything the eye compares column-wise. */
private const val FEAT_NUM = "tnum, lnum"

/** Case-sensitive forms — for tracked all-caps. */
private const val FEAT_CAPS = "case, lnum, tnum"

private fun archivo(
    weight: Int,
    width: Float = 100f,
): Font = Font(
    resId = R.font.archivo_variable,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(
        FontVariation.weight(weight),
        FontVariation.width(width),
    ),
)

/** Normal-width Archivo for UI and body copy. */
val Archivo = FontFamily(
    archivo(400), archivo(500), archivo(600),
    archivo(700), archivo(800), archivo(900),
)

/**
 * Narrow Archivo for bar titles — the rung between the masthead and the UI.
 *
 * Registered under the same FontWeight slots so `fontWeight` still selects within the
 * family; the width axis is what differs. Declared as its own family because a single
 * FontFamily cannot hold two faces at the same weight.
 */
val ArchivoNarrow = FontFamily(
    archivo(600, WIDTH_NARROW), archivo(700, WIDTH_NARROW), archivo(800, WIDTH_NARROW),
)

/** Condensed Archivo, display sizes only. Below ~24sp this width starts to cost legibility. */
val ArchivoCondensed = FontFamily(
    archivo(700, WIDTH_CONDENSED), archivo(800, WIDTH_CONDENSED), archivo(900, WIDTH_CONDENSED),
)

val InstrumentSerif = FontFamily(
    Font(R.font.instrument_serif_regular, FontWeight.Normal),
    Font(R.font.instrument_serif_italic, FontWeight.Normal, FontStyle.Italic),
)

/** Trim the extra leading Compose adds, so tight display type stays tight. */
private val SnugLineHeight = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.Both,
)

/**
 * Styles the Material type scale has no slot for. Reached through
 * `HardPlayTheme.type`.
 */
@Immutable
data class HardPlayTypography(
    /** Poster/hero titles. Condensed, black weight, negative tracking. */
    val display: TextStyle,
    val displaySmall: TextStyle,
    /** Screen titles. */
    val headline: TextStyle,
    /** Card titles, list rows. */
    val title: TextStyle,
    val titleSmall: TextStyle,
    val body: TextStyle,
    val bodySmall: TextStyle,
    /** Buttons and chips. */
    val label: TextStyle,
    val labelSmall: TextStyle,
    /** Wide-tracked all-caps eyebrow above section headers. */
    val overline: TextStyle,
    /** Instrument Serif Italic — pull quotes, empty states, section eyebrows. */
    val editorial: TextStyle,
    val editorialSmall: TextStyle,
    /**
     * Durations, positions, sizes. Tabular figures so a running timecode does
     * not jitter as the digits change — the single most visible difference
     * between a considered player UI and a careless one.
     */
    val timecode: TextStyle,
    val timecodeSmall: TextStyle,
) {
    /**
     * Big tabular figures — OTP cells, a standalone count.
     *
     * Derived from [timecode] rather than declared, so it cannot drift out of the
     * tabular/lining feature set. The OTP field used to patch `timecode.copy(fontSize
     * = 20.sp)` inline, which is the same thing said less durably.
     */
    val numeral: TextStyle
        get() = timecode.copy(
            fontSize = 22.sp,
            lineHeight = 26.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.sp,
        )
}

val HardPlayType = HardPlayTypography(
    display = TextStyle(
        fontFamily = ArchivoCondensed,
        fontWeight = FontWeight.Black,
        fontSize = 44.sp,
        // A hair over the font size rather than exactly it. Set-solid is right for a
        // one-line masthead and collides on a two-line one, which `ScreenHeader` now
        // allows — a black condensed face has enough ascender and descender to touch.
        lineHeight = 46.sp,
        // -0.036em. Condensed heavy display type needs more negative tracking than
        // normal-width does, because the sidebearings come in with the glyphs.
        letterSpacing = (-1.6).sp,
        lineHeightStyle = SnugLineHeight,
        fontFeatureSettings = FEAT_NUM,
    ),
    displaySmall = TextStyle(
        fontFamily = ArchivoCondensed,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 30.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.9).sp,
        lineHeightStyle = SnugLineHeight,
        fontFeatureSettings = FEAT_NUM,
    ),
    headline = TextStyle(
        fontFamily = ArchivoNarrow,
        fontWeight = FontWeight.Bold,
        fontSize = 21.sp,
        lineHeight = 25.sp,
        letterSpacing = (-0.35).sp,
        fontFeatureSettings = FEAT_NUM,
    ),
    title = TextStyle(
        fontFamily = Archivo,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        // 1.33, up from 1.27. Card titles now *reserve* two lines — shelves and the
        // one-column list rows both ask for them — and a wrapped title at the tighter
        // leading read as one cramped block rather than as two lines of a sentence.
        lineHeight = 20.sp,
        letterSpacing = (-0.1).sp,
    ),
    titleSmall = TextStyle(
        fontFamily = Archivo,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 17.sp,
    ),
    body = TextStyle(
        fontFamily = Archivo,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 21.sp,
        // Bone on ink black blooms slightly — light type on a dark ground always does.
        // A hair of positive tracking counteracts it; the alternative is a heavier
        // weight, which costs more than it buys at body size.
        letterSpacing = 0.1.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = Archivo,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        letterSpacing = 0.1.sp,
    ),
    // Buttons and chips: 12sp/700 at +0.096em, set in caps by every call site in
    // Buttons.kt. Tracked caps on an action is what a cinema does with "NOW SHOWING"
    // and what a Material template never does — and at 12sp it also puts real distance
    // between a button's label and a 15sp card title, which 13sp/600 did not.
    label = TextStyle(
        fontFamily = Archivo,
        fontWeight = FontWeight.Bold,
        fontSize = 12.sp,
        lineHeight = 15.sp,
        letterSpacing = 1.15.sp,
        fontFeatureSettings = FEAT_CAPS,
    ),
    labelSmall = TextStyle(
        fontFamily = Archivo,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.3.sp,
    ),
    overline = TextStyle(
        fontFamily = Archivo,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 10.sp,
        lineHeight = 12.sp,
        // 0.18em. Below about 0.12em tracked caps read as shouting rather than as a
        // label; this is the far end of that, which is where the authority is.
        letterSpacing = 1.8.sp,
        fontFeatureSettings = FEAT_CAPS,
    ),
    editorial = TextStyle(
        fontFamily = InstrumentSerif,
        fontStyle = FontStyle.Italic,
        fontWeight = FontWeight.Normal,
        // 28, up from 24. Instrument Serif is a display face with display
        // proportions — small caps height, long extenders — and at 24sp it read as
        // an apologetic aside rather than as the app's voice.
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.3).sp,
    ),
    editorialSmall = TextStyle(
        fontFamily = InstrumentSerif,
        fontStyle = FontStyle.Italic,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 21.sp,
    ),
    timecode = TextStyle(
        fontFamily = Archivo,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.2.sp,
        fontFeatureSettings = FEAT_NUM,
    ),
    timecodeSmall = TextStyle(
        fontFamily = Archivo,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        lineHeight = 13.sp,
        letterSpacing = 0.2.sp,
        fontFeatureSettings = FEAT_NUM,
    ),
)
