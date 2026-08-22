package com.hardplay.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hardplay.ui.theme.HardPlayTheme
import com.hardplay.ui.theme.Motion
import com.hardplay.ui.theme.Space

/**
 * Skeletons.
 *
 * A shimmer that sweeps at a slight diagonal and only lightens by a few percent
 * — it should read as light moving across a surface, not as a progress bar.
 * The animated value is read inside the draw lambda so the sweep costs a draw
 * invalidation per frame and nothing more.
 */
@Composable
fun Modifier.shimmer(shape: Shape? = null): Modifier {
    val colors = HardPlayTheme.colors
    val transition = rememberInfiniteTransition(label = "shimmer")
    val progress = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            // Keyframes with a dwell at the end, not a plain linear tween.
            //
            // `RepeatMode.Restart` over a linear tween snaps the band back to the left
            // edge the instant it leaves the right one, and that discontinuity is
            // visible: the effect reads as a conveyor belt rather than as light passing
            // over a surface. Holding at 1f for the tail of each cycle puts a beat of
            // stillness between sweeps, which is what a highlight actually looks like —
            // and it hides the reset, because nothing is moving when it happens.
            animation = keyframes {
                durationMillis = Motion.ShimmerPeriod
                0f at 0 using LinearEasing
                1f at Motion.ShimmerPeriod - ShimmerDwellMs using LinearEasing
                1f at Motion.ShimmerPeriod
            },
            repeatMode = RepeatMode.Restart,
        ),
        label = "sweep",
    )

    val base = colors.surface
    val highlight = colors.surfaceRaised

    return this
        .then(if (shape != null) Modifier.clip(shape) else Modifier)
        .drawWithCache {
            val bandWidth = size.width * 0.7f
            val travel = size.width + bandWidth
            onDrawBehind {
                drawRect(base)
                // Read the animation here, not in the cache block: keeps this to
                // the draw phase.
                val x = -bandWidth + travel * progress.value
                drawRect(
                    brush = Brush.linearGradient(
                        0f to base.copy(alpha = 0f),
                        0.5f to highlight,
                        1f to base.copy(alpha = 0f),
                        start = Offset(x, 0f),
                        end = Offset(x + bandWidth, size.height),
                    ),
                )
            }
        }
}

/** A single placeholder line — use for titles and metadata rows. */
@Composable
fun ShimmerLine(
    width: Dp,
    height: Dp = 11.dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .width(width)
            .height(height)
            .shimmer(BarShape),
    )
}

/** Full-width placeholder line, for body copy blocks. */
@Composable
fun ShimmerLineFill(
    height: Dp = 11.dp,
    fraction: Float = 1f,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxWidth(fraction)
            .height(height)
            .shimmer(BarShape),
    )
}

/**
 * Poster grid placeholder.
 *
 * Matches [PosterCard]'s art box and its text block *by height*, so the grid does not
 * reflow when real data lands — a jump at that moment is the most obvious way a
 * library screen feels cheap.
 *
 * The slot heights are read from the type scale rather than written down here. They
 * were constants first, and constants are how a placeholder silently stops matching
 * the thing it stands in for the next time a line height is tuned by a single point.
 *
 * @param titleLines match the card's `titleLines`. The card *reserves* that many lines
 *   whether or not the title fills them, so a one-line placeholder under a two-line
 *   card is a visible shift.
 */
@Composable
fun PosterSkeleton(
    modifier: Modifier = Modifier,
    aspect: Float = POSTER_ASPECT,
    titleLines: Int = 1,
) {
    val type = HardPlayTheme.type
    val density = LocalDensity.current
    val titleSlot = with(density) { type.title.lineHeight.toDp() }
    val metaSlot = with(density) { type.labelSmall.lineHeight.toDp() }

    Column(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(aspect)
                .shimmer(HardPlayTheme.shapes.poster),
        )
        Column(Modifier.padding(top = Space.sm)) {
            repeat(titleLines.coerceAtLeast(1)) { line ->
                Box(Modifier.height(titleSlot), contentAlignment = Alignment.CenterStart) {
                    // A short second line: a placeholder with two equal full-width bars
                    // reads as a paragraph rather than as a wrapped title.
                    ShimmerLineFill(height = 11.dp, fraction = if (line == 0) 0.92f else 0.54f)
                }
            }
            Box(Modifier.height(2.dp))
            Box(Modifier.height(metaSlot), contentAlignment = Alignment.CenterStart) {
                ShimmerLine(width = 46.dp, height = 9.dp)
            }
        }
    }
}

/**
 * 2dp, not the 4dp control radius these bars used to borrow.
 *
 * A placeholder line is 9–11dp tall, so a 4dp radius rounds nearly half its height at
 * each end and the bar arrives as a lozenge — the pill shape this design system does
 * not use, on the one screen a new user sees first.
 */
private val BarShape = RoundedCornerShape(2.dp)

/**
 * Stillness at the end of each sweep, in milliseconds.
 *
 * Long enough to read as a pause between pulses rather than as a stutter, and it is
 * what conceals the band's reset to the left edge.
 */
private const val ShimmerDwellMs = 340
