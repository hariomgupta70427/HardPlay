package com.hardplay.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.MoreVert
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.hardplay.ui.theme.HardPlayTheme
import com.hardplay.ui.theme.Motion
import com.hardplay.ui.theme.Space
import com.hardplay.ui.theme.filmGrain

/** 2:3, the film-poster ratio. The default only for [PosterSkeleton]. */
const val POSTER_ASPECT = 2f / 3f

/**
 * The library's primary object.
 *
 * Deliberately restrained: art, a title, one line of metadata. Everything that
 * *could* go on a card — badges, ratings, stacked chips, a play button overlay —
 * is left off, because a dense grid of quiet cards reads as a catalogue while a
 * grid of busy cards reads as a content farm.
 *
 * The four corners of the art each own one thing, and which thing is not arbitrary:
 * **state on the left** (the unseen tick, the saved heart), **facts and actions on the
 * right** (the duration, the overflow control). Duration at the bottom right in
 * particular is the convention every video app has settled on, and putting it anywhere
 * else costs recognition for nothing.
 *
 * @param aspect cell shape, from `CardAspect`. Landscape video in a portrait cell
 *   letterboxes into a thin strip, so this is a real setting rather than a constant.
 * @param artModifier applied to the art box only, so callers can attach a
 *   `sharedElement` for the grid -> player transition without the title
 *   travelling with it.
 * @param titleLines also *reserves* this many lines. A shelf of cards whose titles
 *   wrap to different depths has ragged bottoms, which is one of the most reliable
 *   tells that a grid was assembled rather than designed.
 * @param onMenu when non-null, a three-dot overflow control sits in the art's top
 *   right corner. It replaces the unseen tick that used to live there — the tick
 *   moves to the top *left*, so the card can carry both a state and an action.
 */
@Composable
fun PosterCard(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    artModifier: Modifier = Modifier,
    aspect: Float = POSTER_ASPECT,
    thumbnail: Any? = null,
    durationLabel: String? = null,
    sourceLabel: String? = null,
    /** 0f..1f watched position. Anything above 0 draws the resume bar. */
    resumeFraction: Float = 0f,
    unseen: Boolean = false,
    saved: Boolean = false,
    titleLines: Int = 2,
    onMenu: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val colors = HardPlayTheme.colors
    val haptics = rememberHaptics()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) Motion.PressScale else 1f,
        animationSpec = Motion.quick(),
        label = "posterPress",
    )

    Column(
        modifier = modifier
            .scale(scale)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = {
                    haptics.press()
                    onClick()
                },
                onLongClick = onLongClick?.let {
                    {
                        haptics.hold()
                        it()
                    }
                },
            ),
    ) {
        Box(
            artModifier
                .fillMaxWidth()
                .aspectRatio(aspect)
                .clip(HardPlayTheme.shapes.poster)
                .background(colors.surface),
        ) {
            PosterFallbackArt(title)

            if (thumbnail != null) {
                val context = LocalContext.current
                val request = remember(thumbnail) {
                    ImageRequest.Builder(context)
                        .data(thumbnail)
                        .crossfade(Motion.Standard)
                        .build()
                }
                AsyncImage(
                    model = request,
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            // Shorter than it was, and that is the point.
            //
            // It ran to 42% of the cell, tuned when every card was 2:3 and when the
            // badges were bare text that depended on it to stay legible. They each carry
            // their own plate now, so the scrim's only remaining jobs are seating the art
            // against the caption and keeping the resume bar visible on a bright frame —
            // neither of which needs half the picture dimmed. Still a fraction rather
            // than a fixed height, because it has to hold at one column and at five.
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(ScrimFraction)
                    .background(colors.posterScrim),
            )

            // Hairline inside the crop, so adjacent cards stay separated even
            // when both posters are nearly black at the edges.
            Box(
                Modifier
                    .fillMaxSize()
                    .border(Space.hairline, colors.hairline.copy(alpha = 0.6f), HardPlayTheme.shapes.poster),
            )

            // Unseen marker: a 2dp ember tick, not a badge with a word in it.
            if (unseen) {
                Box(
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(Space.sm)
                        .width(2.dp)
                        .height(14.dp)
                        .background(colors.emberGradientVertical),
                )
            }

            if (saved) {
                Icon(
                    imageVector = Icons.Rounded.Favorite,
                    contentDescription = "Saved",
                    tint = colors.accent,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(Space.sm)
                        .size(13.dp),
                )
            }

            if (durationLabel != null) {
                // Plated, not bare. Bone text straight onto the art vanished over any
                // bright frame — a white sky or a lit interior — and a timecode you
                // have to hunt for is worse than no timecode. A 3dp rect, never a
                // pill: the pill is the Material tell this design system avoids.
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(Space.sm)
                        .clip(HardPlayTheme.shapes.poster)
                        .background(colors.bg.copy(alpha = 0.66f))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                ) {
                    Text(
                        text = durationLabel,
                        style = HardPlayTheme.type.timecodeSmall,
                        color = colors.type,
                    )
                }
            }

            if (onMenu != null) {
                // A 34dp target around a 26dp plate.
                //
                // Two separate decisions. The gesture is on the *outer* box, because
                // hanging it off the plate is what a nested icon button does — and
                // `Modifier.size` defeats that button's own `defaultMinSize`, so you get
                // the small plate *and* a 26dp target, the worst of both.
                //
                // And 34dp rather than the 44dp minimum, deliberately. A three-column
                // 16:9 cell is only about 62dp tall, so a 44dp corner target would cover
                // most of the top half of the card and swallow taps meant to open the
                // item. A secondary control on a dense grid is worth 34dp; the primary
                // action keeps the rest.
                val menuInteraction = remember { MutableInteractionSource() }
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .size(MenuTarget)
                        .clickable(
                            interactionSource = menuInteraction,
                            indication = null,
                        ) {
                            haptics.tick()
                            onMenu()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        // Scrimmed behind the glyph rather than tinted: a bare icon
                        // over a bright frame disappears, and a filled container would
                        // put a Material chip on every cell of the grid.
                        Modifier
                            .size(MenuPlate)
                            .clip(HardPlayTheme.shapes.chip)
                            .background(colors.bg.copy(alpha = 0.45f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.MoreVert,
                            contentDescription = "More options for $title",
                            tint = colors.type,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }

            // Last, so it sits above the scrim and above both bottom badges. A resume
            // bar under the scrim is a resume bar nobody can see on a dark frame.
            if (resumeFraction > 0f) {
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(colors.hairline),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(resumeFraction.coerceIn(0f, 1f))
                            .fillMaxHeight()
                            .background(colors.emberGradient),
                    )
                }
            }
        }

        Column(Modifier.padding(top = Space.sm)) {
            Text(
                text = title,
                style = HardPlayTheme.type.title,
                color = colors.type,
                minLines = titleLines,
                maxLines = titleLines,
                overflow = TextOverflow.Ellipsis,
            )
            if (sourceLabel != null) {
                Text(
                    text = sourceLabel,
                    style = HardPlayTheme.type.labelSmall,
                    color = colors.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

/**
 * Shown behind the art while it loads, and permanently for items Telegram has no
 * thumbnail for. An oversized Instrument Serif italic initial on oxblood, with
 * grain — it looks like a decision rather than a missing image.
 *
 * The glyph is a constant, and it was briefly cell-relative instead. That version read
 * better at both extremes of the grid but needed a `BoxWithConstraints` *per cell*, and
 * a subcomposition per cell on the one screen the app is judged on for scroll
 * smoothness is a poor trade for a mark that is covered by real artwork in almost every
 * cell. 44sp rather than the 56sp it started at: a three-column 16:9 cell is only about
 * 62dp tall, and the larger glyph clipped there.
 */
@Composable
private fun PosterFallbackArt(title: String) {
    val colors = HardPlayTheme.colors
    val initial = remember(title) {
        title.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "·"
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    listOf(colors.surfaceRaised, colors.surfaceSunken),
                ),
            )
            .filmGrain(0.06f),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initial,
            style = HardPlayTheme.type.editorial.copy(
                fontSize = FallbackGlyph,
                // The style's own 30sp line height would clip a glyph larger than it —
                // a changed font size has to carry its leading with it.
                lineHeight = FallbackGlyph * 1.06f,
            ),
            color = colors.muted.copy(alpha = 0.55f),
        )
    }
}

/** Enough to seat the art and carry the resume bar; short enough not to dim the frame. */
private const val ScrimFraction = 0.34f

/** The visible plate behind the overflow glyph, inside a [MenuTarget] gesture area. */
private val MenuPlate = 26.dp

/** See the note at the overflow control for why this is not the 44dp minimum. */
private val MenuTarget = 34.dp

/** The fallback initial. See [PosterFallbackArt] for why it is a constant. */
private val FallbackGlyph = 44.sp
