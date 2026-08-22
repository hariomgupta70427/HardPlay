package com.hardplay.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hardplay.ui.theme.HardPlayTheme
import com.hardplay.ui.theme.Motion
import com.hardplay.ui.theme.Space

/**
 * Buttons.
 *
 * Three things carry the identity here.
 *
 * **The label is tracked all-caps.** `type.label` is 12sp/700 at 0.096em and every call
 * site below sets its text in caps. Sentence-case button labels are the correct choice
 * for a productivity app and the wrong one for this app: a cinema sets "NOW SHOWING",
 * and against a 44sp condensed masthead a 12sp tracked micro-label is the other end of
 * a real typographic system rather than one more mid-weight line.
 *
 * **The ember gradient fills exactly one shape in the app, and it is [EmberButton].**
 * That is what lets it read as *the* action on a screen without being large. Press
 * feedback is a scale rather than a ripple: a ripple over a gradient turns to mud, and
 * the scale reads as physical.
 *
 * **Disabled removes the fill, never the legibility.** Every button here used to be
 * `.alpha(0.4f)` when disabled, which on a gradient leaves an ember smear that still
 * looks pressable and a label at roughly 2:1. Now the fill is swapped for a flat
 * surface and the label for `onDisabled`, which is ~5:1 and unambiguous. The app has
 * already shipped one control faded to 14%; that is a class of bug, not an incident.
 */

private val ButtonHeight = 48.dp
private val ButtonHeightSmall = 40.dp

/**
 * Trailing letter-spacing has to come back off the end padding.
 *
 * `letterSpacing` is applied *after* every glyph including the last, so a tracked label
 * carries ~1sp of invisible space on its right that the left never has. Centred text
 * hides it; an icon-plus-label row does not, and neither does a narrow button, where the
 * label sits visibly left of centre. One dp, and it is the difference between a button
 * that looks set and one that looks placed.
 */
private val TrackingTrim = 1.dp

/**
 * Press scale, animated asymmetrically: down inside the window where the brain still
 * credits the finger, up slowly enough to be seen. A single spec for both directions —
 * which is what one `animateFloatAsState` gives you — has to choose between a laggy
 * press and a snapped release.
 */
@Composable
private fun rememberPressScale(
    interaction: MutableInteractionSource,
    pressedScale: Float = Motion.PressScaleControl,
): Float {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = if (pressed) Motion.pressDown() else Motion.pressUp(),
        label = "press",
    )
    return scale
}

/**
 * The shared body of [EmberButton] and [GhostButton].
 *
 * They were two near-identical 45-line copies, and had already drifted: the ghost
 * variant had picked up a `destructive` branch and neither had the touch-target fix.
 *
 * The touch box and the visible box are separate. A `small` button is 40dp of paint
 * inside 48dp of target — the alternative is a 40dp (previously 38dp) tap area, and
 * "small" is never a reason to be harder to hit.
 */
@Composable
private fun ActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    icon: ImageVector?,
    fillWidth: Boolean,
    small: Boolean,
    fill: Brush?,
    border: Color?,
    contentColor: Color,
    pressWash: Color?,
) {
    val haptics = rememberHaptics()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale = rememberPressScale(interaction)
    val shape = HardPlayTheme.shapes.button

    Box(
        modifier = modifier
            .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
            .heightIn(min = Space.touch)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
            ) {
                haptics.press()
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
                .height(if (small) ButtonHeightSmall else ButtonHeight)
                .scale(scale)
                .clip(shape)
                .then(if (fill != null) Modifier.background(fill) else Modifier)
                .then(
                    // The pressed wash is a second channel on top of the scale. On an
                    // outlined button the scale alone is nearly invisible, because
                    // there is no filled edge for the eye to track.
                    if (pressWash != null && pressed) Modifier.background(pressWash) else Modifier,
                )
                .then(
                    if (border != null) {
                        Modifier.border(BorderStroke(Space.stroke, border), shape)
                    } else {
                        Modifier
                    },
                )
                .padding(
                    start = if (small) Space.lg else Space.xl,
                    end = (if (small) Space.lg else Space.xl) - TrackingTrim,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(16.dp),
                )
                Box(Modifier.size(Space.sm))
            }
            Text(
                text = text.uppercase(),
                style = HardPlayTheme.type.label,
                color = contentColor,
            )
        }
    }
}

/** The single strongest action on a screen. Ember gradient, oxblood label. */
@Composable
fun EmberButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    fillWidth: Boolean = false,
    small: Boolean = false,
) {
    val colors = HardPlayTheme.colors
    ActionButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        icon = icon,
        fillWidth = fillWidth,
        small = small,
        fill = if (enabled) colors.emberGradient else SolidColor(colors.disabledFill),
        // Disabled gains the hairline a ghost button has: without a fill it needs an
        // edge, or it stops looking like a control at all.
        border = if (enabled) null else colors.hairline,
        contentColor = if (enabled) colors.onAccent else colors.onDisabled,
        // Nothing washes over the gradient — it is already the brightest thing on the
        // screen and a bone overlay only desaturates it.
        pressWash = null,
    )
}

/** Secondary action. Hairline outline, bone label, no fill. */
@Composable
fun GhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    fillWidth: Boolean = false,
    small: Boolean = false,
    destructive: Boolean = false,
) {
    val colors = HardPlayTheme.colors
    ActionButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        icon = icon,
        fillWidth = fillWidth,
        small = small,
        fill = null,
        border = when {
            !enabled -> colors.hairline
            destructive -> colors.danger
            else -> colors.border
        },
        // Destructive labels take `accent`, not `danger`. Danger is ember darkened to
        // 22% black, which measures 4.0:1 on ink black and therefore fails AA at 12sp;
        // full ember is 6.1:1. The darker tone stays where it belongs — on the edge,
        // where a 1px line has no contrast requirement to meet.
        contentColor = when {
            !enabled -> colors.onDisabled
            destructive -> colors.accent
            else -> colors.type
        },
        pressWash = colors.pressWash,
    )
}

/** Tertiary action — text only. For "skip", "not now", "cancel". */
@Composable
fun QuietButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = HardPlayTheme.colors
    val haptics = rememberHaptics()
    val interaction = remember { MutableInteractionSource() }
    val scale = rememberPressScale(interaction)

    Box(
        modifier = modifier
            .heightIn(min = Space.touch)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled) {
                haptics.press()
                onClick()
            }
            .padding(horizontal = Space.md),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text.uppercase(),
            style = HardPlayTheme.type.label,
            color = if (enabled) colors.typeDim else colors.onDisabled,
            modifier = Modifier.scale(scale),
        )
    }
}

/**
 * Bare icon button. Used for player chrome and top-bar actions, where a filled
 * or outlined container would clutter the frame.
 *
 * No clip: with `indication = null` and no background there is nothing for a shape to
 * cut, and the one it had was `shapes.round` — a pill token inside a control, in an app
 * whose whole radius rule is that controls are square-ish.
 */
@Composable
fun GhostIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color? = null,
    size: Dp = 22.dp,
) {
    val colors = HardPlayTheme.colors
    val haptics = rememberHaptics()
    val interaction = remember { MutableInteractionSource() }
    val scale = rememberPressScale(interaction, Motion.PressScaleSmall)

    Box(
        modifier = modifier
            .defaultMinSize(Space.touch, Space.touch)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled) {
                haptics.tick()
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            // Dimmed to `onDisabled` rather than faded: a 0.35 alpha icon on ink black
            // is a ghost, and an icon nobody can see is not a disabled control, it is a
            // missing one.
            tint = if (enabled) (tint ?: colors.type) else colors.onDisabled,
            modifier = Modifier
                .size(size)
                .scale(scale),
        )
    }
}
