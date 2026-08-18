package com.hardplay.ui.nav

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import com.hardplay.core.Format
import com.hardplay.ui.components.rememberHaptics
import com.hardplay.ui.theme.HardPlayTheme
import com.hardplay.ui.theme.Motion
import com.hardplay.ui.theme.Space

/**
 * The bottom bar.
 *
 * Not `NavigationBar`. Material's version brings an 80dp height, a pill-shaped
 * indicator behind the selected icon, a ripple, and label colours keyed to
 * `colorScheme` — and it is the single most recognisable "this is a stock Android
 * app" surface there is. On a screen the user looks at constantly, that one component
 * would set the tone for everything above it.
 *
 * What replaces it: a hairline top edge, a 2dp ember tick that slides in above the
 * selected tab, and colour interpolated rather than switched. The tick is the same
 * marker the unseen poster badge and the scrubber head use, which is what makes the
 * app feel like one object instead of five screens.
 *
 * @param counts optional figure per tab — Saved and History earn one, because "how
 *   much is in here" is the question you would otherwise open the tab to answer.
 */
@Composable
fun HardPlayBottomBar(
    current: HomeTab,
    onSelect: (HomeTab) -> Unit,
    modifier: Modifier = Modifier,
    counts: Map<HomeTab, Int> = emptyMap(),
) {
    val colors = HardPlayTheme.colors

    Column(
        modifier
            .background(colors.bgRaised)
            .navigationBarsPadding(),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(Space.hairline)
                .background(colors.hairline),
        )
        Row(
            Modifier.height(BarHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HomeTab.entries.forEach { tab ->
                BottomBarItem(
                    tab = tab,
                    selected = tab == current,
                    count = counts[tab]?.takeIf { it > 0 },
                    onClick = { onSelect(tab) },
                    modifier = Modifier
                        .weight(1f)
                        .height(BarHeight),
                )
            }
        }
    }
}

@Composable
private fun BottomBarItem(
    tab: HomeTab,
    selected: Boolean,
    count: Int?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = HardPlayTheme.colors
    val haptics = rememberHaptics()
    val interaction = remember { MutableInteractionSource() }

    // Interpolated rather than switched, so a tab change reads as one movement
    // instead of three things happening at once.
    val emphasis by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = Motion.standard(),
        label = "tabEmphasis",
    )
    val tickWidth by animateDpAsState(
        targetValue = if (selected) TickWidth else 0.dp,
        animationSpec = Motion.standard(),
        label = "tabTick",
    )
    val contentColor = lerp(colors.muted, colors.accent, emphasis)

    Column(
        modifier.clickable(
            interactionSource = interaction,
            // No ripple: a ripple inside a 56dp cell washes across the whole tab and
            // is the loudest Material gesture in the app.
            indication = null,
        ) {
            haptics.tick()
            onClick()
        },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // The tick sits at the top edge of the item, reading as a tab marker rather
        // than as decoration under the label.
        Box(
            Modifier
                .width(tickWidth)
                .height(2.dp)
                .background(colors.emberGradient),
        )
        Box(Modifier.height(Space.md))
        Icon(
            imageVector = tab.icon,
            contentDescription = tab.label,
            tint = contentColor,
            modifier = Modifier.size(19.dp),
        )
        Box(Modifier.height(3.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = tab.label,
                style = HardPlayTheme.type.labelSmall,
                color = contentColor,
            )
            if (count != null) {
                Text(
                    text = Format.count(count),
                    style = HardPlayTheme.type.timecodeSmall,
                    color = colors.muted,
                )
            }
        }
        Box(Modifier.height(Space.xs))
    }
}

/**
 * 56dp of content, plus the system inset.
 *
 * Below Material's 80dp because the labels are 11sp Archivo rather than 12sp Roboto
 * in a 48dp pill, and because vertical space on the library screen is poster art.
 */
private val BarHeight = 56.dp
private val TickWidth = 18.dp
