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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hardplay.ui.theme.HardPlayTheme
import com.hardplay.ui.theme.Motion
import com.hardplay.ui.theme.Space

/**
 * Chips.
 *
 * Square-ish, 4dp radius. Pill-shaped chips are the fastest visual shortcut to
 * "Material default", so they aren't used anywhere in this app.
 *
 * ## Selection without the gradient
 *
 * A selected chip used to be filled with the ember gradient. Two things were wrong with
 * that. The gradient is the app's one loudest surface and it was appearing on every
 * selected filter, every speed, every tag — twenty of them in a filter sheet, which
 * leaves nothing for the actual primary action to be louder than. And across 34dp the
 * two ember stops are visually indistinguishable, so it was a gradient nobody could
 * even see.
 *
 * Selection is now **four quiet channels at once**: an ember wash, an ember hairline
 * edge, a 2dp ember rule down the leading edge, and the label going from 600 to bold
 * bone. Any one of those alone would be too subtle; together they are unmistakable
 * across a wrapping row, and they cost no new hue. The 2dp leading rule is the same mark
 * `Notice` uses for emphasis and `SettingGroup` uses for a group — one idea, three
 * places, which is what makes a set of components read as one system.
 *
 * ## Height
 *
 * 34dp of paint inside a 48dp target. The chip was a 30dp tap area, which is under the
 * platform minimum on the control the app has the most of. In a wrapping cloud the extra
 * height lands as air between rows, which is the right place for it.
 */

private val ChipHeight = 34.dp
private val SelectedRule = 2.dp

@Composable
private fun chipLabelStyle(selected: Boolean): TextStyle {
    val base = HardPlayTheme.type.titleSmall
    return if (selected) base.copy(fontWeight = FontWeight.Bold) else base
}

/**
 * The chip body — fill, edge, leading rule — shared by [TagChip] and [SpeedChip] so the
 * two cannot disagree about what "selected" looks like. They already did: one used
 * `surfaceRaised` plus a hairline when unselected and the other used `surface` at 90%
 * with no edge at all.
 */
@Composable
private fun ChipShell(
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = HardPlayTheme.colors
    val haptics = rememberHaptics()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) Motion.PressScaleSmall else 1f,
        animationSpec = if (pressed) Motion.pressDown() else Motion.pressUp(),
        label = "chipPress",
    )
    val shape = HardPlayTheme.shapes.chip

    Box(
        modifier = modifier
            .heightIn(min = Space.touch)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
            ) {
                haptics.tick()
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .height(ChipHeight)
                .scale(scale)
                .clip(shape)
                .background(if (selected) colors.accentWash else colors.surfaceRaised)
                .then(
                    if (pressed && !selected) Modifier.background(colors.pressWash) else Modifier,
                )
                .border(
                    Space.stroke,
                    if (selected) colors.accentEdge else colors.hairline,
                    shape,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selected) {
                Box(
                    Modifier
                        .width(SelectedRule)
                        .fillMaxHeight()
                        .background(colors.accent),
                )
            }
            Row(
                modifier = Modifier.padding(
                    start = if (selected) Space.sm else Space.md,
                    // Tracked and tabular content both carry trailing sidebearing; take
                    // a dp back so the label sits optically centred.
                    end = Space.md - 1.dp,
                ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.sm),
            ) {
                content()
            }
        }
    }
}

/**
 * A tag in the filter sheet or on a poster's detail row.
 *
 * The label is *not* uppercased, unlike every other micro-label in the app. Tag names
 * are user content — they come out of caption parsing and the tag editor — and setting
 * someone's data in caps is a decision about their words rather than about the frame
 * around them. Authority comes from weight here instead.
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
    val contentColor = when {
        !enabled -> colors.onDisabled
        selected -> colors.type
        else -> colors.typeDim
    }

    ChipShell(
        selected = selected,
        enabled = enabled,
        onClick = onClick,
        modifier = modifier,
    ) {
        Text(text = label, style = chipLabelStyle(selected), color = contentColor)
        if (count != null) {
            Text(
                text = count.toString(),
                style = HardPlayTheme.type.timecodeSmall,
                color = if (selected) colors.accent else colors.muted,
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

    // "1x" rather than "1.0x"; "1.25x" keeps its decimals.
    val label = remember(speed) {
        val trimmed = if (speed % 1f == 0f) speed.toInt().toString() else speed.toString()
        "${trimmed}x"
    }

    ChipShell(
        selected = selected,
        enabled = true,
        onClick = onClick,
        modifier = modifier,
    ) {
        Text(
            text = label,
            style = HardPlayTheme.type.timecode.copy(
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            ),
            color = if (selected) colors.type else colors.typeDim,
        )
    }
}

/**
 * Non-interactive metadata badge — "4K", "HDR", "1.8 GB", a source name.
 * Deliberately quiet: these are facts, not controls.
 *
 * `type.overline` carries `case` and `tnum`, so "1.84 GB" gets cap-height figures that
 * line up with the letters instead of sitting a hair low — which is exactly the sort of
 * detail nobody sees and everybody feels.
 */
@Composable
fun MetaChip(
    label: String,
    modifier: Modifier = Modifier,
    emphasised: Boolean = false,
) {
    val colors = HardPlayTheme.colors
    Box(
        modifier = modifier
            .clip(HardPlayTheme.shapes.chip)
            .border(
                Space.stroke,
                if (emphasised) colors.accentEdge else colors.hairline,
                HardPlayTheme.shapes.chip,
            )
            .padding(start = Space.sm, end = Space.sm - 1.dp, top = Space.xxs, bottom = Space.xxs),
    ) {
        Text(
            text = label.uppercase(),
            style = HardPlayTheme.type.overline,
            color = if (emphasised) colors.accent else colors.muted,
        )
    }
}
