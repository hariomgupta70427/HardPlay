package com.hardplay.ui.theme

import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Sharp, architectural radii. Pill-shaped chips and 16dp cards are the two
 * fastest ways to make an app look like a Material template, so neither is used
 * here: posters sit at 3dp, chips and buttons at 4dp.
 *
 * The radii encode a rule rather than a preference: **controls are sharper than
 * surfaces.** Anything you press is 3–4dp; anything that holds content is 7dp; only
 * sheets get a generous radius, because they need to read as a surface arriving from
 * off-screen. Buttons and cards previously shared 6dp, which is not a system — it is
 * one number used twice, and it left a button looking like a small card.
 */
@Immutable
data class HardPlayShapes(
    /** Chips, tag pills, small badges. */
    val chip: RoundedCornerShape = RoundedCornerShape(4.dp),
    /** Poster art and any image tile. The sharpest thing on screen, so art reads as art. */
    val poster: RoundedCornerShape = RoundedCornerShape(3.dp),
    /** Cards, panels, inputs — things that hold content. */
    val card: RoundedCornerShape = RoundedCornerShape(7.dp),
    /** Buttons. Matches [chip]: everything you press shares one radius. */
    val button: RoundedCornerShape = RoundedCornerShape(4.dp),
    /** Dialogs and inline modals. */
    val dialog: RoundedCornerShape = RoundedCornerShape(12.dp),
    /** Bottom sheets — top corners only. */
    val sheet: RoundedCornerShape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    /** Fully round: avatars, the scrubber thumb. Never a control container. */
    val round: RoundedCornerShape = RoundedCornerShape(percent = 50),
)

val HardPlayShapeSet = HardPlayShapes()

/** Mapped onto Material3 so the handful of M3 components we use inherit it. */
internal val MaterialShapes = Shapes(
    extraSmall = RoundedCornerShape(3.dp),
    small = RoundedCornerShape(4.dp),
    medium = RoundedCornerShape(7.dp),
    large = RoundedCornerShape(12.dp),
    extraLarge = RoundedCornerShape(20.dp).copy(
        bottomStart = CornerSize(0.dp),
        bottomEnd = CornerSize(0.dp),
    ),
)

/**
 * Spacing scale.
 *
 * 4dp base, and — this is the part that matters — **geometric above `md`, not
 * arithmetic**. The rungs were 2/4/8/12/16/20/28/40, where 12 → 16 → 20 step by a flat
 * quarter each time. A near-linear middle is what makes "uniform 16dp everywhere" the
 * default outcome of every layout decision: reaching for the next rung up buys 4dp,
 * which is not enough to read as a different kind of gap, so everything collapses onto
 * one value and the page loses its grouping.
 *
 * 16 → 24 → 36 → 56 halves-again each time, so the big rungs are unmistakably
 * *between-group* gaps and the small ones are unmistakably *within-group*. Screens
 * already reach for `xxl`/`xxxl` at exactly the places groups meet, so widening those
 * three rungs is what puts air between the shelves without editing a single screen.
 */
@Immutable
object Space {
    val xxs: Dp = 2.dp
    val xs: Dp = 4.dp
    val sm: Dp = 8.dp
    val md: Dp = 12.dp
    val lg: Dp = 16.dp

    /** First of the between-group rungs. */
    val xl: Dp = 24.dp
    val xxl: Dp = 36.dp
    val xxxl: Dp = 56.dp

    /** Standard screen side padding. */
    val gutter: Dp = 16.dp

    /** Gap between poster grid cells. Deliberately tight — dense reads premium. */
    val gridGap: Dp = 8.dp

    /** Divider *height*. A real 1dp so a separator survives on a 1x display. */
    val hairline: Dp = 1.dp

    /**
     * Border *width*, and deliberately not [hairline].
     *
     * `Modifier.border` special-cases [Dp.Hairline] to exactly one physical pixel, so
     * this is a third of a dp on a 3x phone — a true hairline rather than the 3px slab
     * that `1.dp` draws. Every outlined control in the app has an edge; at 3px they
     * read as boxes drawn around things, at 1px they read as an etched line, and that
     * difference is most of what "fine" means in an interface.
     *
     * Not used for dividers: `Modifier.height(Dp.Hairline)` is a zero-height box.
     */
    val stroke: Dp = Dp.Hairline

    /**
     * Minimum touch target — 48dp, the platform figure, not the 44dp that was here.
     *
     * The 4dp was not a rounding difference in practice: it was the reason the switch
     * shipped at 40×22 and the chips at 30, because a scale that says 44 is already
     * negotiating.
     */
    val touch: Dp = 48.dp
}
