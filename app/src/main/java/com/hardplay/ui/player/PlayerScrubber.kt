package com.hardplay.ui.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.hardplay.ui.components.rememberHaptics
import com.hardplay.ui.theme.HardPlayTheme
import com.hardplay.ui.theme.Motion

/**
 * The seek bar.
 *
 * Three things a `Slider` cannot do, all of which matter here:
 *
 *  * Show the **buffered** extent as a third band. On this transport the gap
 *    between watched and downloaded is the single most useful thing on screen —
 *    it's the difference between "seeking here is instant" and "seeking here will
 *    stall".
 *  * Grow on touch instead of showing a thumb. A 2dp line that becomes a 5dp line
 *    under the finger reads as a film strip; a circular thumb reads as Material.
 *  * Tick haptically as it crosses each percent, so scrubbing has texture without
 *    looking at the timecode.
 *
 * @param onScrub called continuously during the drag, for the timecode readout.
 * @param onScrubEnd called once at release; only this performs the actual seek.
 *   Seeking per drag frame would issue a TDLib range request per frame.
 */
@Composable
fun PlayerScrubber(
    progress: Float,
    bufferedProgress: Float,
    onScrub: (Float) -> Unit,
    onScrubEnd: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = HardPlayTheme.colors
    val haptics = rememberHaptics()

    var dragging by remember { mutableStateOf(false) }
    var width by remember { mutableFloatStateOf(1f) }
    var lastTickPercent by remember { mutableFloatStateOf(-1f) }

    val trackHeight by animateFloatAsState(
        targetValue = if (dragging) 5f else 2f,
        animationSpec = Motion.quick(),
        label = "scrubberHeight",
    )

    /** Shared by drag and tap so a tap lands exactly where a drag to the same x would. */
    fun report(x: Float) {
        val fraction = (x / width).coerceIn(0f, 1f)
        onScrub(fraction)
        val percent = (fraction * 100f).toInt().toFloat()
        if (percent != lastTickPercent) {
            lastTickPercent = percent
            haptics.tick()
        }
    }

    Box(
        modifier
            .fillMaxWidth()
            // A 28dp touch target over a 2dp line: the visual can be hairline-thin
            // while still being grabbable, which is the entire trick.
            .height(28.dp)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onPress = { offset ->
                        width = size.width.toFloat()
                        dragging = true
                        report(offset.x)
                        // Wait for the release so a tap-to-seek commits, rather
                        // than leaving the bar stuck mid-drag.
                        tryAwaitRelease()
                        dragging = false
                        onScrubEnd()
                    },
                )
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectDragGestures(
                    onDragStart = { offset ->
                        width = size.width.toFloat()
                        dragging = true
                        report(offset.x)
                    },
                    onDragEnd = {
                        dragging = false
                        onScrubEnd()
                    },
                    onDragCancel = {
                        dragging = false
                        onScrubEnd()
                    },
                    onDrag = { change, _ ->
                        report(change.position.x)
                    },
                )
            }
            .drawWithCache {
                width = size.width
                val radius = CornerRadius(1.dp.toPx())
                onDrawBehind {
                    val h = trackHeight.dp.toPx()
                    val top = (size.height - h) / 2f

                    // Track.
                    drawRoundRect(
                        color = colors.type.copy(alpha = 0.16f),
                        topLeft = Offset(0f, top),
                        size = Size(size.width, h),
                        cornerRadius = radius,
                    )

                    // Buffered — how far a seek is free.
                    drawRoundRect(
                        color = colors.type.copy(alpha = 0.34f),
                        topLeft = Offset(0f, top),
                        size = Size(size.width * bufferedProgress.coerceIn(0f, 1f), h),
                        cornerRadius = radius,
                    )

                    // Played.
                    drawRoundRect(
                        brush = colors.emberGradient,
                        topLeft = Offset(0f, top),
                        size = Size(size.width * progress.coerceIn(0f, 1f), h),
                        cornerRadius = radius,
                    )

                    // Head: a 2dp vertical tick, the same marker used for "unseen"
                    // on a poster and for the next OTP cell. One idea, three places.
                    val headX = (size.width * progress.coerceIn(0f, 1f))
                        .coerceIn(0f, size.width - 2.dp.toPx())
                    val headHeight = if (dragging) 16.dp.toPx() else 10.dp.toPx()
                    drawRoundRect(
                        color = colors.type,
                        topLeft = Offset(headX, (size.height - headHeight) / 2f),
                        size = Size(2.dp.toPx(), headHeight),
                        cornerRadius = radius,
                    )
                }
            },
    )
}
