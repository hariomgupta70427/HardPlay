package com.hardplay.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hardplay.ui.theme.HardPlayTheme
import com.hardplay.ui.theme.Motion
import com.hardplay.ui.theme.Space

/**
 * The buffering mark.
 *
 * An ember light travelling the perimeter of a film frame, with registration
 * ticks at the edge midpoints. Determinate mode fills the perimeter from the
 * top-left clockwise; indeterminate mode runs a comet with a fading tail.
 *
 * The PRD is explicit that a stock `CircularProgressIndicator` is not
 * acceptable, and it's right to be: the spinner is the single most recognisable
 * "this is a default Android app" tell, and this screen shows it every time a
 * 300MB file starts streaming.
 */
private const val TAIL_FRACTION = 0.24f
private const val TAIL_STEPS = 6
private const val CYCLE_MS = 1250

@Composable
fun BufferingMark(
    modifier: Modifier = Modifier,
    markSize: Dp = 40.dp,
    /** `null` for indeterminate. */
    progress: Float? = null,
    strokeWidth: Dp = 2.dp,
) {
    val colors = HardPlayTheme.colors

    val transition = rememberInfiniteTransition(label = "buffering")
    val head by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(CYCLE_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "head",
    )

    // Determinate progress is animated so a chunk landing doesn't snap the arc.
    val animatedProgress by animateFloatAsState(
        targetValue = progress ?: 0f,
        animationSpec = tween(Motion.Standard, easing = Motion.Smooth),
        label = "progress",
    )

    Box(
        modifier
            .size(markSize)
            .drawWithCache {
                val stroke = strokeWidth.toPx()
                val inset = stroke / 2f
                val frame = Path().apply {
                    addRoundRect(
                        RoundRect(
                            rect = Rect(inset, inset, size.width - inset, size.height - inset),
                            cornerRadius = CornerRadius(2.dp.toPx()),
                        ),
                    )
                }
                val measure = PathMeasure().apply { setPath(frame, forceClosed = true) }
                val total = measure.length
                // Reused across draws — allocating a Path per frame on the
                // buffering path is exactly the kind of churn that shows up as
                // jank while the player is also decoding.
                val scratch = Path()
                val tickLength = 3.dp.toPx()

                onDrawBehind {
                    drawPath(
                        path = frame,
                        color = colors.hairline,
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )
                    drawRegistrationTicks(colors.hairline, stroke, tickLength)

                    if (progress == null) {
                        val end = head * total
                        val tail = total * TAIL_FRACTION
                        // Discrete tail steps rather than a gradient stroke: a
                        // brush along a rounded-rect path smears at the corners.
                        for (step in 0 until TAIL_STEPS) {
                            val t = step.toFloat() / TAIL_STEPS
                            val segEnd = end - tail * t
                            val segStart = segEnd - tail / TAIL_STEPS
                            strokeSegment(
                                measure = measure,
                                total = total,
                                scratch = scratch,
                                from = segStart,
                                to = segEnd,
                                brush = colors.emberGradient,
                                width = stroke,
                                alpha = 1f - t * 0.86f,
                            )
                        }
                    } else if (animatedProgress > 0f) {
                        strokeSegment(
                            measure = measure,
                            total = total,
                            scratch = scratch,
                            from = 0f,
                            to = total * animatedProgress.coerceIn(0f, 1f),
                            brush = colors.emberGradient,
                            width = stroke,
                            alpha = 1f,
                        )
                    }
                }
            },
    )
}

/**
 * Draws `from`..`to` along the path, wrapping past the start. [PathMeasure]
 * clamps rather than wraps, so a segment straddling the origin needs two calls.
 */
private fun DrawScope.strokeSegment(
    measure: PathMeasure,
    total: Float,
    scratch: Path,
    from: Float,
    to: Float,
    brush: Brush,
    width: Float,
    alpha: Float,
) {
    val style = Stroke(width = width, cap = StrokeCap.Round)
    var start = from
    if (start < 0f) {
        scratch.reset()
        measure.getSegment(total + start, total, scratch, true)
        drawPath(scratch, brush, alpha = alpha, style = style)
        start = 0f
    }
    if (to > start) {
        scratch.reset()
        measure.getSegment(start, to, scratch, true)
        drawPath(scratch, brush, alpha = alpha, style = style)
    }
}

/** Four short inward ticks at the edge midpoints — a viewfinder, not a spinner. */
private fun DrawScope.drawRegistrationTicks(
    color: androidx.compose.ui.graphics.Color,
    width: Float,
    length: Float,
) {
    val cx = size.width / 2f
    val cy = size.height / 2f
    val cap = StrokeCap.Butt
    drawLine(color, Offset(cx, 0f), Offset(cx, length), width, cap)
    drawLine(color, Offset(cx, size.height - length), Offset(cx, size.height), width, cap)
    drawLine(color, Offset(0f, cy), Offset(length, cy), width, cap)
    drawLine(color, Offset(size.width - length, cy), Offset(size.width, cy), width, cap)
}

/**
 * Full-bleed buffering state for the player. The caption is set in the tabular
 * timecode style so a counting byte figure doesn't shift width as it climbs.
 */
@Composable
fun BufferingOverlay(
    modifier: Modifier = Modifier,
    progress: Float? = null,
    caption: String? = null,
) {
    val colors = HardPlayTheme.colors
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Space.md),
        ) {
            BufferingMark(progress = progress, markSize = 44.dp)
            if (caption != null) {
                Text(
                    text = caption,
                    style = HardPlayTheme.type.timecodeSmall,
                    color = colors.muted,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
