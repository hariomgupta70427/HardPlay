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
 * here: posters sit at 3dp, chips at 4dp. Only sheets get a generous radius,
 * because they need to read as a surface arriving from off-screen.
 */
@Immutable
data class HardPlayShapes(
    /** Chips, tag pills, small badges. */
    val chip: RoundedCornerShape = RoundedCornerShape(4.dp),
    /** Poster art and any image tile. */
    val poster: RoundedCornerShape = RoundedCornerShape(3.dp),
    /** Cards, panels, inputs. */
    val card: RoundedCornerShape = RoundedCornerShape(6.dp),
    /** Buttons. */
    val button: RoundedCornerShape = RoundedCornerShape(6.dp),
    /** Dialogs and inline modals. */
    val dialog: RoundedCornerShape = RoundedCornerShape(12.dp),
    /** Bottom sheets — top corners only. */
    val sheet: RoundedCornerShape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    /** Fully round: avatars, the scrubber thumb. */
    val round: RoundedCornerShape = RoundedCornerShape(percent = 50),
)

val HardPlayShapeSet = HardPlayShapes()

/** Mapped onto Material3 so the handful of M3 components we use inherit it. */
internal val MaterialShapes = Shapes(
    extraSmall = RoundedCornerShape(3.dp),
    small = RoundedCornerShape(4.dp),
    medium = RoundedCornerShape(6.dp),
    large = RoundedCornerShape(12.dp),
    extraLarge = RoundedCornerShape(20.dp).copy(
        bottomStart = CornerSize(0.dp),
        bottomEnd = CornerSize(0.dp),
    ),
)

/**
 * Spacing scale. A 4dp base with no half-steps — inconsistent gaps are the
 * other tell of a generated layout.
 */
@Immutable
object Space {
    val xxs: Dp = 2.dp
    val xs: Dp = 4.dp
    val sm: Dp = 8.dp
    val md: Dp = 12.dp
    val lg: Dp = 16.dp
    val xl: Dp = 20.dp
    val xxl: Dp = 28.dp
    val xxxl: Dp = 40.dp

    /** Standard screen side padding. */
    val gutter: Dp = 16.dp

    /** Gap between poster grid cells. Deliberately tight — dense reads premium. */
    val gridGap: Dp = 8.dp

    /** Hairline thickness. */
    val hairline: Dp = 1.dp

    /** Minimum touch target. */
    val touch: Dp = 44.dp
}
