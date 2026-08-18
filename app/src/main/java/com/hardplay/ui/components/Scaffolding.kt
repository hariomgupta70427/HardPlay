package com.hardplay.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hardplay.ui.theme.HardPlayTheme
import com.hardplay.ui.theme.Motion
import com.hardplay.ui.theme.Space

/**
 * Shared screen furniture.
 *
 * Written after the screens that use it rather than before, which is why each
 * piece has exactly the parameters it turned out to need. Guessing at this layer
 * first is how component libraries end up with eleven unused slots per component.
 */

/**
 * Screen header.
 *
 * Not `TopAppBar`: Material's version brings its own container colour, its own
 * 64dp height and a title style keyed to `MaterialTheme.typography`, so half of
 * using it would be overriding it. This is a row with an inset.
 *
 * @param scrolled fades in a background and a hairline. Passing `false` lets the
 *   header sit transparently over poster art at the top of a scroll, which is what
 *   makes the library read as a catalogue rather than as a form.
 * @param showTitle fade the title and overline in and out. Screens that carry a
 *   [ScreenHeader] inside their scrolling content pass `scrolled` here, so the name of
 *   the screen appears in the bar exactly as the big one leaves — one title on screen
 *   at a time, which is the difference between a collapsing header and two headings.
 */
@Composable
fun HardPlayTopBar(
    title: String,
    modifier: Modifier = Modifier,
    overline: String? = null,
    scrolled: Boolean = false,
    showTitle: Boolean = true,
    onBack: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
) {
    val colors = HardPlayTheme.colors
    val chromeAlpha by animateFloatAsState(
        targetValue = if (scrolled) 1f else 0f,
        animationSpec = Motion.fade(),
        label = "topBarChrome",
    )
    val titleAlpha by animateFloatAsState(
        targetValue = if (showTitle) 1f else 0f,
        animationSpec = Motion.fade(),
        label = "topBarTitle",
    )

    Box(modifier.fillMaxWidth()) {
        Box(
            Modifier
                .matchParentSize()
                .alpha(chromeAlpha)
                .background(colors.bgRaised),
        )

        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars.only(WindowInsetsSides.Top))
                .padding(horizontal = Space.gutter, vertical = Space.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                GhostIconButton(
                    icon = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Back",
                    onClick = onBack,
                    modifier = Modifier.padding(end = Space.xs),
                )
            }

            Column(
                Modifier
                    .weight(1f)
                    .alpha(titleAlpha),
            ) {
                if (overline != null) {
                    Text(
                        text = overline.uppercase(),
                        style = HardPlayTheme.type.overline,
                        color = colors.accent,
                    )
                    Box(Modifier.height(3.dp))
                }
                Text(
                    text = title,
                    style = HardPlayTheme.type.headline,
                    color = colors.type,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(Space.xxs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                actions()
            }
        }

        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(Space.hairline)
                .alpha(chromeAlpha)
                .background(colors.hairline),
        )
    }
}

/**
 * The oversized heading a screen opens with.
 *
 * Lives *inside* the scrolling content rather than in the bar, so it scrolls away and
 * hands the screen's name over to [HardPlayTopBar] on the way out. That is where the
 * display type earns its keep: a 22sp bar title on every screen is uniform and
 * anonymous, while 40sp narrow black over an ember eyebrow reads as a masthead.
 *
 * @param subtitle Instrument Serif italic. One editorial line per screen, no more —
 *   it is a voice, and a voice that talks constantly is noise.
 */
@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    overline: String? = null,
    subtitle: String? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val colors = HardPlayTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .padding(top = Space.xs, bottom = Space.lg),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(Modifier.weight(1f)) {
            // The app's mark above the eyebrow, the same one the empty states and the
            // login hero use. It is what ties five screens into one object.
            EmberRule()
            Box(Modifier.height(Space.sm))
            if (overline != null) {
                Text(
                    text = overline.uppercase(),
                    style = HardPlayTheme.type.overline,
                    color = colors.accent,
                )
                Box(Modifier.height(Space.xs))
            }
            Text(
                text = title,
                style = HardPlayTheme.type.display,
                color = colors.type,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Box(Modifier.height(Space.xs))
                Text(
                    text = subtitle,
                    style = HardPlayTheme.type.editorialSmall,
                    color = colors.muted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            Box(Modifier.padding(start = Space.md)) { trailing() }
        }
    }
}

/**
 * Section label above a shelf or a group of rows.
 *
 * The eyebrow is ember and tiny; the title is narrow and heavy. That pairing —
 * rather than one 20sp semibold line — is what makes a list read as edited.
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    overline: String? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val colors = HardPlayTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Space.gutter, vertical = Space.md),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(Modifier.weight(1f)) {
            if (overline != null) {
                Text(
                    text = overline.uppercase(),
                    style = HardPlayTheme.type.overline,
                    color = colors.accent,
                )
                Box(Modifier.height(4.dp))
            }
            Text(
                text = title,
                style = HardPlayTheme.type.displaySmall,
                color = colors.type,
            )
        }
        trailing?.invoke()
    }
}

/**
 * Empty and error states.
 *
 * The headline is Instrument Serif italic. An empty library is the one screen with
 * nothing to look at, so it's the one place the editorial voice earns its keep —
 * and it keeps "No results" from reading like a 404.
 */
@Composable
fun EmptyState(
    headline: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    overline: String? = null,
    action: @Composable (() -> Unit)? = null,
) {
    val colors = HardPlayTheme.colors
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Space.xxl, vertical = Space.xxxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        // A short ember rule instead of an illustration, or an icon in a circle.
        EmberRule()
        if (overline != null) {
            Text(
                text = overline.uppercase(),
                style = HardPlayTheme.type.overline,
                color = colors.muted,
                textAlign = TextAlign.Center,
            )
        }
        Text(
            text = headline,
            style = HardPlayTheme.type.editorial,
            color = colors.type,
            textAlign = TextAlign.Center,
        )
        if (body != null) {
            Text(
                text = body,
                style = HardPlayTheme.type.body,
                color = colors.muted,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 320.dp),
            )
        }
        if (action != null) {
            Box(Modifier.padding(top = Space.sm)) { action() }
        }
    }
}

/**
 * A quiet inline notice — a sync failure, a demo-mode note.
 *
 * Severity is carried by an ember edge and by weight, not by a hue. The palette
 * has no amber or red to spend here, and inventing one would break the rule that
 * matters most in this design system.
 */
@Composable
fun Notice(
    text: String,
    modifier: Modifier = Modifier,
    emphasis: Boolean = false,
    onDismiss: (() -> Unit)? = null,
    action: @Composable (() -> Unit)? = null,
) {
    val colors = HardPlayTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .clip(HardPlayTheme.shapes.card)
            .background(colors.surface)
            .border(
                Space.hairline,
                if (emphasis) colors.accent.copy(alpha = 0.55f) else colors.hairline,
                HardPlayTheme.shapes.card,
            )
            .padding(horizontal = Space.md, vertical = Space.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        Box(
            Modifier
                .width(2.dp)
                .height(28.dp)
                .background(
                    if (emphasis) colors.emberGradientVertical else SolidColor(colors.border),
                ),
        )
        Text(
            text = text,
            style = HardPlayTheme.type.bodySmall,
            color = colors.typeDim,
            modifier = Modifier.weight(1f),
        )
        action?.invoke()
        if (onDismiss != null) {
            GhostIconButton(
                icon = Icons.Rounded.Close,
                contentDescription = "Dismiss",
                onClick = onDismiss,
                size = 16.dp,
                tint = colors.muted,
            )
        }
    }
}

/**
 * The app's mark: a short ember rule.
 *
 * One shape, one width, wherever the app needs to sign a block of content — above a
 * screen's masthead, above an empty state's headline, at the top of the login hero. It
 * is shared rather than drawn inline because three call sites had already picked three
 * different widths for the same mark, and a signature that changes size is not a
 * signature.
 *
 * Deliberately not an icon or an illustration. A 24×2dp gradient bar costs nothing,
 * scales to any density, and cannot look like clip art.
 */
@Composable
fun EmberRule(modifier: Modifier = Modifier, width: Dp = EmberRuleWidth) {
    Box(
        modifier
            .width(width)
            .height(2.dp)
            .background(HardPlayTheme.colors.emberGradient),
    )
}

val EmberRuleWidth: Dp = 24.dp

/** 1px separator at the theme's hairline colour. Never a full-strength line. */
@Composable
fun Hairline(modifier: Modifier = Modifier, inset: Boolean = false) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = if (inset) Space.gutter else 0.dp)
            .height(Space.hairline)
            .background(HardPlayTheme.colors.hairline),
    )
}

/**
 * The drag handle every bottom sheet in the app uses.
 *
 * A 2dp ember bar rather than Material's grey lozenge, which is one of the two or three
 * shapes that instantly identify a stock Android app. Shared rather than reimplemented
 * per sheet: it had reached three private copies across the filter, action and
 * confirmation sheets, and the third one was already a slightly different width — which
 * is exactly how a design system stops being one.
 *
 * Pass it as `dragHandle = { SheetHandle() }`.
 */
@Composable
fun SheetHandle(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(top = Space.md, bottom = Space.sm),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .width(34.dp)
                .height(2.dp)
                .background(HardPlayTheme.colors.emberGradient),
        )
    }
}

/**
 * Counter badge for the filter button's active-facet count.
 *
 * 3dp radius rather than a circle: a round badge on a square-cornered app is the
 * kind of inconsistency that registers without being noticed.
 */
@Composable
fun CountBadge(count: Int, modifier: Modifier = Modifier) {
    if (count <= 0) return
    val colors = HardPlayTheme.colors
    Box(
        modifier
            .clip(RoundedCornerShape(3.dp))
            .background(colors.emberGradient)
            .padding(horizontal = 4.dp, vertical = 1.dp),
    ) {
        Text(
            text = count.toString(),
            style = HardPlayTheme.type.timecodeSmall,
            color = colors.onAccent,
        )
    }
}

/** Leading search glyph, sized and tinted for [HardPlayTextField]. */
@Composable
fun SearchGlyph(active: Boolean, modifier: Modifier = Modifier) {
    Icon(
        imageVector = Icons.Rounded.Search,
        contentDescription = null,
        tint = if (active) HardPlayTheme.colors.accent else HardPlayTheme.colors.muted,
        modifier = modifier.size(18.dp),
    )
}
