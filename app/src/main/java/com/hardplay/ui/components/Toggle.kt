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
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
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
 *
 * The track is 44×24 of paint inside a 48dp target. The paint is not the target: the app
 * shipped once with the switch as a **40×22dp tap area**, and the fix at the time was to
 * make the row clickable — correct as far as it went, but it left the control itself
 * still failing on any screen that forgot to pass `onClick`. Both are handled now.
 */

private val TrackWidth = 44.dp
private val TrackHeight = 24.dp
private val KnobSize = 18.dp
private val KnobInset = 3.dp

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
    val shape = RoundedCornerShape(4.dp)

    val knobOffset by animateDpAsState(
        targetValue = if (checked) TrackWidth - KnobSize - KnobInset else KnobInset,
        animationSpec = Motion.standard(),
        label = "switchKnob",
    )
    val fillAlpha by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = Motion.quick(),
        label = "switchFill",
    )

    Box(
        modifier
            .defaultMinSize(Space.touch, Space.touch)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
            ) {
                haptics.tick()
                onCheckedChange(!checked)
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(width = TrackWidth, height = TrackHeight)
                .clip(shape)
                .background(if (enabled) colors.surfaceSunken else colors.disabledFill)
                .border(Space.stroke, colors.hairline, shape),
            contentAlignment = Alignment.CenterStart,
        ) {
            // The fill grows in behind the knob rather than the knob changing colour, so
            // the "on" state reads at a glance down a long list.
            //
            // Solid accent, not the gradient: across 44dp the two ember stops are 30 hue
            // degrees apart over a few pixels, which is a gradient nobody can see. The
            // gradient is spent on surfaces wide enough to show it.
            Box(
                Modifier
                    .size(width = TrackWidth, height = TrackHeight)
                    .alpha(fillAlpha)
                    .background(if (enabled) colors.accent else colors.disabledFill),
            )
            Box(
                Modifier
                    .offset(x = knobOffset)
                    .size(KnobSize)
                    .clip(RoundedCornerShape(3.dp))
                    .background(
                        when {
                            !enabled -> colors.onDisabled
                            checked -> colors.onAccent
                            // `muted`, not `typeDim`: an off switch with a near-bone knob
                            // is the brightest thing in the row and reads as on.
                            else -> colors.muted
                        },
                    ),
            )
        }
    }
}

/**
 * A settings row.
 *
 * The whole row is the target, not just the control — there is nothing else a tap on the
 * row could mean, and a 44dp switch is a small thing to hit one-handed.
 *
 * `heightIn(min = touch)` matters for the rows *without* a subtitle: a 20dp title inside
 * 12dp of padding is a 44dp row, so the shortest rows in the list were the ones failing
 * the target rule.
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
    val haptics = rememberHaptics()
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = Space.touch)
            .then(
                if (onClick != null) {
                    // Default indication on purpose: this is where the theme's flat
                    // press wash lands, which is what makes a settings row press like
                    // the buttons above it instead of like a stock list item.
                    Modifier.clickable {
                        haptics.tick()
                        onClick()
                    }
                } else {
                    Modifier
                },
            )
            .padding(horizontal = Space.gutter, vertical = Space.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.lg),
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
                    modifier = Modifier.padding(top = Space.xxs),
                )
            }
        }
        trailing?.invoke()
    }
}

/**
 * Group heading inside a settings list.
 *
 * Where the spatial rule is stated most plainly: **36dp above, 8dp below.** A group
 * heading belongs to the rows under it, so the gap that separates it from the previous
 * group has to be several times the gap that binds it to its own content. Both were 16
 * and 8 before, which is close enough to equal that a settings screen read as one
 * undifferentiated list with occasional small print in it.
 *
 * Signed with the shared [EmberRule] at its canonical width rather than a private 12dp
 * bar — the mark is the same mark everywhere or it isn't a mark.
 */
@Composable
fun SettingGroup(title: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Box(Modifier.height(Space.xxl))
        Row(
            Modifier.padding(start = Space.gutter, end = Space.gutter, bottom = Space.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            EmberRule()
            Text(
                text = title.uppercase(),
                style = HardPlayTheme.type.overline,
                color = HardPlayTheme.colors.muted,
            )
        }
    }
}
