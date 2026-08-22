package com.hardplay.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The app's press feedback: a flat wash across the whole control, no expanding circle.
 *
 * This exists because of what `Modifier.clickable { }` does when nobody supplies an
 * indication — it reaches for `LocalIndication`, and whatever that resolves to is then
 * the press feedback on every hand-rolled row, tile and tap target in the app. The
 * app's *own* primitives all pass `indication = null` and animate a scale instead, so
 * the gap was invisible from inside this package and very visible on the screens: a
 * settings row, a channel row, a shelf tile all flashed with a stock treatment while
 * the buttons next to them did something else entirely.
 *
 * A radial ripple is the single most recognisable Android gesture there is, and it is
 * also wrong for this palette — an expanding circle of ember over oxblood turns to mud
 * halfway through its own animation. A flat bone wash at 6% reads on ink black, costs
 * one `drawRect`, has no origin to animate from and therefore no direction to be wrong
 * about.
 *
 * Asymmetric by construction: [Motion.pressDown] on the way in so the wash lands with
 * the finger, [Motion.pressUp] on the way out so the release can be watched.
 *
 * Provided as `LocalIndication` by `HardPlayTheme`, which means every `clickable` in
 * every screen file gets it without those files knowing this class exists.
 */
class FlatPressIndication(
    private val color: Color,
    private val pressAlpha: Float,
    private val focusAlpha: Float,
) : IndicationNodeFactory {

    override fun create(interactionSource: InteractionSource): DelegatableNode =
        WashNode(interactionSource, color, pressAlpha, focusAlpha)

    // Compose compares indications to decide whether to recreate the node. Identity
    // equality would rebuild every press layer in the app on each theme read.
    override fun equals(other: Any?): Boolean =
        other is FlatPressIndication &&
            color == other.color &&
            pressAlpha == other.pressAlpha &&
            focusAlpha == other.focusAlpha

    override fun hashCode(): Int {
        var result = color.hashCode()
        result = 31 * result + pressAlpha.hashCode()
        result = 31 * result + focusAlpha.hashCode()
        return result
    }

    private class WashNode(
        private val interactionSource: InteractionSource,
        private val color: Color,
        private val pressAlpha: Float,
        private val focusAlpha: Float,
    ) : Modifier.Node(), DrawModifierNode {

        private val wash = Animatable(0f)
        private var animation: Job? = null

        override fun onAttach() {
            coroutineScope.launch {
                // Counted rather than boolean: a control can be pressed and focused at
                // once, and a pointer that leaves and re-enters emits an unbalanced
                // pair often enough that a flag gets stuck lit.
                var presses = 0
                var focuses = 0
                var hovers = 0
                interactionSource.interactions.collect { interaction ->
                    when (interaction) {
                        is PressInteraction.Press -> presses++
                        is PressInteraction.Release -> presses--
                        is PressInteraction.Cancel -> presses--
                        is FocusInteraction.Focus -> focuses++
                        is FocusInteraction.Unfocus -> focuses--
                        is HoverInteraction.Enter -> hovers++
                        is HoverInteraction.Exit -> hovers--
                    }
                    val target = when {
                        presses > 0 -> pressAlpha
                        focuses > 0 || hovers > 0 -> focusAlpha
                        else -> 0f
                    }
                    val rising = target > wash.value
                    animation?.cancel()
                    animation = launch {
                        wash.animateTo(
                            targetValue = target,
                            animationSpec = if (rising) Motion.pressDown() else Motion.pressUp(),
                        )
                    }
                }
            }
        }

        override fun ContentDrawScope.draw() {
            drawContent()
            val alpha = wash.value
            if (alpha > 0f) drawRect(color = color, alpha = alpha)
        }
    }
}
