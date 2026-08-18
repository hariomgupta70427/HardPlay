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
 * Archivo is a variable font with `wght` (100–900) and `wdth` (62–125) axes.
 * Display styles run slightly narrow (wdth 88) and very heavy, which is what
 * makes headlines read as editorial rather than as a default sans-serif.
 */

private const val WIDTH_NARROW = 88f

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
 * Narrow Archivo for display type. Registered under the same FontWeight slots
 * so `fontWeight` still selects within the family; the width axis is what
 * differs. Declared as its own family because a single FontFamily cannot hold
 * two faces at the same weight.
 */
val ArchivoNarrow = FontFamily(
    archivo(500, WIDTH_NARROW), archivo(600, WIDTH_NARROW),
    archivo(700, WIDTH_NARROW), archivo(800, WIDTH_NARROW),
    archivo(900, WIDTH_NARROW),
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
    /** Poster/hero titles. Narrow, black weight, negative tracking. */
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
)

val HardPlayType = HardPlayTypography(
    display = TextStyle(
        fontFamily = ArchivoNarrow,
        fontWeight = FontWeight.Black,
        fontSize = 40.sp,
        // A hair over the font size rather than exactly it. Set-solid is right for a
        // one-line masthead and collides on a two-line one, which `ScreenHeader` now
        // allows — a black narrow face has enough ascender and descender to touch.
        lineHeight = 42.sp,
        letterSpacing = (-1.2).sp,
        lineHeightStyle = SnugLineHeight,
    ),
    displaySmall = TextStyle(
        fontFamily = ArchivoNarrow,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 28.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.7).sp,
        lineHeightStyle = SnugLineHeight,
    ),
    headline = TextStyle(
        fontFamily = ArchivoNarrow,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.4).sp,
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
    ),
    bodySmall = TextStyle(
        fontFamily = Archivo,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 17.sp,
    ),
    label = TextStyle(
        fontFamily = Archivo,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.2.sp,
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
        fontWeight = FontWeight.Bold,
        fontSize = 10.sp,
        lineHeight = 12.sp,
        letterSpacing = 1.6.sp,
    ),
    editorial = TextStyle(
        fontFamily = InstrumentSerif,
        fontStyle = FontStyle.Italic,
        fontWeight = FontWeight.Normal,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = 0.sp,
    ),
    editorialSmall = TextStyle(
        fontFamily = InstrumentSerif,
        fontStyle = FontStyle.Italic,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 20.sp,
    ),
    timecode = TextStyle(
        fontFamily = Archivo,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.2.sp,
        fontFeatureSettings = "tnum",
    ),
    timecodeSmall = TextStyle(
        fontFamily = Archivo,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        lineHeight = 13.sp,
        letterSpacing = 0.2.sp,
        fontFeatureSettings = "tnum",
    ),
)
