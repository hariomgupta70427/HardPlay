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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hardplay.ui.theme.HardPlayTheme
import com.hardplay.ui.theme.Motion
import com.hardplay.ui.theme.Space

/**
 * Buttons.
 *
 * The primary button is the only place in the app where the ember gradient fills
 * a shape, which is what makes it read as *the* action on a screen without
 * needing to be large. Press feedback is a scale-down rather than a ripple: a
 * ripple over a gradient turns to mud, and the scale reads as physical.
 */

private val ButtonHeight = 48.dp
private val ButtonHeightSmall = 38.dp

@Composable
private fun rememberPressScale(interaction: MutableInteractionSource): Float {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.965f else 1f,
        animationSpec = Motion.quick(),
        label = "press",
    )
    return scale
}

/** The single strongest action on a screen. Ember gradient, bone-dark label. */
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
    val haptics = rememberHaptics()
    val interaction = remember { MutableInteractionSource() }
    val scale = rememberPressScale(interaction)

    Row(
        modifier = modifier
            .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
            .height(if (small) ButtonHeightSmall else ButtonHeight)
            .scale(scale)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(HardPlayTheme.shapes.button)
            .background(colors.emberGradient)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
            ) {
                haptics.press()
                onClick()
            }
            .padding(horizontal = Space.xl),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = colors.onAccent,
                modifier = Modifier.size(18.dp),
            )
            Box(Modifier.size(Space.sm))
        }
        Text(
            text = text,
            style = HardPlayTheme.type.label,
            color = colors.onAccent,
        )
    }
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
    val haptics = rememberHaptics()
    val interaction = remember { MutableInteractionSource() }
    val scale = rememberPressScale(interaction)
    val contentColor = if (destructive) colors.danger else colors.type
    val borderColor = if (destructive) colors.danger.copy(alpha = 0.5f) else colors.border

    Row(
        modifier = modifier
            .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
            .height(if (small) ButtonHeightSmall else ButtonHeight)
            .scale(scale)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(HardPlayTheme.shapes.button)
            .border(BorderStroke(Space.hairline, borderColor), HardPlayTheme.shapes.button)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
            ) {
                haptics.press()
                onClick()
            }
            .padding(horizontal = Space.xl),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(18.dp))
            Box(Modifier.size(Space.sm))
        }
        Text(text = text, style = HardPlayTheme.type.label, color = contentColor)
    }
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

    Box(
        modifier = modifier
            .height(ButtonHeightSmall)
            .clip(HardPlayTheme.shapes.button)
            .alpha(if (enabled) 1f else 0.4f)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled) {
                haptics.press()
                onClick()
            }
            .padding(horizontal = Space.md),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = HardPlayTheme.type.label, color = colors.typeDim)
    }
}

/**
 * Bare icon button. Used for player chrome and top-bar actions, where a filled
 * or outlined container would clutter the frame.
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
    val scale = rememberPressScale(interaction)

    Box(
        modifier = modifier
            .defaultMinSize(Space.touch, Space.touch)
            .scale(scale)
            .clip(HardPlayTheme.shapes.round)
            .alpha(if (enabled) 1f else 0.35f)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled) {
                haptics.tick()
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint ?: colors.type,
            modifier = Modifier.size(size),
        )
    }
}
