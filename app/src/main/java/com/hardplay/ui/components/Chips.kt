package com.hardplay.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.unit.dp
import com.hardplay.ui.theme.HardPlayTheme
import com.hardplay.ui.theme.Motion
import com.hardplay.ui.theme.Space

/**
 * Chips.
 *
 * Square-ish, 4dp radius, hairline outline when unselected and an ember fill
 * when selected. Pill-shaped chips are the fastest visual shortcut to "Material
 * default", so they aren't used anywhere in this app.
 */

private val ChipHeight = 30.dp

/**
 * A tag in the filter sheet or on a poster's detail row.
 *
 * @param count live match count. Shown in tabular figures so a column of chips
 *   with different counts stays visually aligned.
 */
@Composable
fun TagChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    count: Int? = null,
    enabled: Boolean = true,
) {
    val colors = HardPlayTheme.colors
    val haptics = rememberHaptics()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = Motion.quick(),
        label = "chipPress",
    )

    val contentColor = when {
        !enabled -> colors.muted
        selected -> colors.onAccent
        else -> colors.typeDim
    }

    Row(
        modifier = modifier
            .height(ChipHeight)
            .scale(scale)
            .clip(HardPlayTheme.shapes.chip)
            .then(
                if (selected) {
                    Modifier.background(colors.emberGradient)
                } else {
                    Modifier
                        .background(colors.surfaceRaised)
                        .border(Space.hairline, colors.hairline, HardPlayTheme.shapes.chip)
                },
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
            ) {
                haptics.tick()
                onClick()
            }
            .padding(horizontal = Space.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Text(text = label, style = HardPlayTheme.type.labelSmall, color = contentColor)
        if (count != null) {
            Text(
                text = count.toString(),
                style = HardPlayTheme.type.timecodeSmall,
                color = if (selected) colors.onAccent.copy(alpha = 0.7f) else colors.muted,
            )
        }
    }
}

/**
 * Playback speed selector. Uses the timecode style because these are numbers
 * being compared against each other, and proportional digits make 1.25 and 1.75
 * different widths.
 */
@Composable
fun SpeedChip(
    speed: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
) {
    val colors = HardPlayTheme.colors
    val haptics = rememberHaptics()
    val interaction = remember { MutableInteractionSource() }

    // "1x" rather than "1.0x"; "1.25x" keeps its decimals.
    val label = remember(speed) {
        val trimmed = if (speed % 1f == 0f) speed.toInt().toString() else speed.toString()
        "${trimmed}x"
    }

    Box(
        modifier = modifier
            .height(ChipHeight)
            .clip(HardPlayTheme.shapes.chip)
            .then(
                if (selected) {
                    Modifier.background(colors.emberGradient)
                } else {
                    Modifier.background(colors.surface.copy(alpha = 0.9f))
                },
            )
            .clickable(interactionSource = interaction, indication = null) {
                haptics.tick()
                onClick()
            }
            .padding(horizontal = Space.md),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = HardPlayTheme.type.timecodeSmall,
            color = if (selected) colors.onAccent else colors.typeDim,
        )
    }
}

/**
 * Non-interactive metadata badge — "4K", "HDR", "1.8 GB", a source name.
 * Deliberately quiet: these are facts, not controls.
 */
@Composable
fun MetaChip(
    label: String,
    modifier: Modifier = Modifier,
    emphasised: Boolean = false,
) {
    val colors = HardPlayTheme.colors
    val borderColor = if (emphasised) colors.accent.copy(alpha = 0.6f) else colors.hairline
    Box(
        modifier = modifier
            .clip(HardPlayTheme.shapes.chip)
            .border(Space.hairline, borderColor, HardPlayTheme.shapes.chip)
            .padding(horizontal = Space.sm, vertical = 3.dp),
    ) {
        Text(
            text = label.uppercase(),
            style = HardPlayTheme.type.overline,
            color = if (emphasised) colors.accent else colors.muted,
        )
    }
}
