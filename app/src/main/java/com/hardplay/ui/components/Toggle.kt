package com.hardplay.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import com.hardplay.ui.theme.HardPlayTheme
import com.hardplay.ui.theme.Motion
import com.hardplay.ui.theme.Space

/**
 * Settings controls.
 *
 * The switch is custom for the same reason the chips are: Material's is a pill with
 * a circular thumb and is recognised instantly, and a settings screen is long enough
 * that one stock control repeated fifteen times sets the tone for the whole app.
 * This is a 4dp-radius track with a square knob, ember when on.
 */

private val TrackWidth = 40.dp
private val TrackHeight = 22.dp
private val KnobSize = 16.dp

@Composable
fun HardPlaySwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = HardPlayTheme.colors
    val haptics = rememberHaptics()
    val interaction = remember { MutableInteractionSource() }

    val knobOffset by animateDpAsState(
        targetValue = if (checked) TrackWidth - KnobSize - 3.dp else 3.dp,
        animationSpec = Motion.quick(),
        label = "switchKnob",
    )
    val fillAlpha by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = Motion.quick(),
        label = "switchFill",
    )

    Box(
        modifier
            .size(width = TrackWidth, height = TrackHeight)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(RoundedCornerShape(4.dp))
            .background(colors.surfaceSunken)
            .border(Space.hairline, colors.hairline, RoundedCornerShape(4.dp))
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
            ) {
                haptics.tick()
                onCheckedChange(!checked)
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        // The ember fill grows in behind the knob rather than the knob changing
        // colour, so the "on" state reads at a glance in a long list.
        Box(
            Modifier
                .fillMaxWidth()
                .height(TrackHeight)
                .alpha(fillAlpha)
                .background(colors.emberGradient),
        )
        Box(
            Modifier
                .offset(x = knobOffset)
                .size(KnobSize)
                .clip(RoundedCornerShape(3.dp))
                .background(if (checked) SolidColor(colors.onAccent) else SolidColor(colors.typeDim)),
        )
    }
}

/**
 * A settings row.
 *
 * The whole row is the target, not just the control — a 40dp switch is a small thing
 * to hit one-handed, and there is nothing else a tap on the row could mean.
 */
@Composable
fun SettingRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val colors = HardPlayTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = Space.gutter, vertical = Space.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = HardPlayTheme.type.title,
                color = colors.type,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = HardPlayTheme.type.bodySmall,
                    color = colors.muted,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        trailing?.invoke()
    }
}

/** Group heading inside a settings list. */
@Composable
fun SettingGroup(title: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Box(Modifier.height(Space.lg))
        Row(
            Modifier.padding(start = Space.gutter, end = Space.gutter, bottom = Space.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            Box(
                Modifier
                    .width(12.dp)
                    .height(2.dp)
                    .background(HardPlayTheme.colors.emberGradient),
            )
            Text(
                text = title.uppercase(),
                style = HardPlayTheme.type.overline,
                color = HardPlayTheme.colors.muted,
            )
        }
    }
}
