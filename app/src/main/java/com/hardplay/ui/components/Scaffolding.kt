package com.hardplay.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
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
 *
 * ## The one spatial rule, stated once
 *
 * **Tight within a group, generous between them** — and every header in this file now
 * has *asymmetric* vertical padding to enforce it. `SectionHeader` was `vertical =
 * Space.md`: 12dp above, 12dp below, so a shelf heading was exactly as close to the
 * shelf it labelled as to the one it did not. That single symmetric value, repeated
 * down every screen, is what makes a layout read as "uniform 16dp everywhere" no matter
 * how carefully the type is set — grouping is spatial before it is anything else, and a
 * symmetric gap communicates no grouping at all.
 *
 * ## Where the ember is spent
 *
 * The gradient marks three things and nothing else: the **primary action**, the app's
 * **mark** ([EmberRule], [SheetHandle]), and **progress**. Everything else that needs to
 * be accented takes solid `accent` — states, badges, carets, edges. Section eyebrows,
 * which appear five times on a Discover screen, are `muted`; only the once-per-screen
 * masthead eyebrow in [ScreenHeader] is ember. Five ember eyebrows and an ember
 * everything-else is not an accent, it is a colour scheme.
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
                .padding(horizontal = Space.gutter, vertical = Space.sm),
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
                    // `muted`, not accent. This eyebrow is the *second* copy of the
                    // screen's name — the masthead already said it in ember — and the
                    // bar is chrome, which is the last place the accent should live.
                    Text(
                        text = overline.uppercase(),
                        style = HardPlayTheme.type.overline,
                        color = colors.muted,
                    )
                    Box(Modifier.height(Space.xxs))
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
 * display type earns its keep: a 21sp bar title on every screen is uniform and
 * anonymous, while 44sp condensed black over an ember eyebrow reads as a masthead.
 *
 * Internal rhythm 12 / 4 / 8, external 24 below. Mark and eyebrow and title are one
 * object and are spaced like one; the gap to the content beneath is three times the
 * largest gap inside.
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
            .padding(top = Space.xs, bottom = Space.xl),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(Modifier.weight(1f)) {
            // The app's mark above the eyebrow, the same one the empty states and the
            // login hero use. It is what ties five screens into one object.
            EmberRule()
            Box(Modifier.height(Space.md))
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
                Box(Modifier.height(Space.sm))
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
 * The eyebrow is tiny tracked caps; the title is condensed and heavy. That pairing —
 * rather than one 20sp semibold line — is what makes a list read as edited.
 *
 * 16 above, 8 below: the heading belongs to what follows it.
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
            .padding(
                start = Space.gutter,
                end = Space.gutter,
                top = Space.lg,
                bottom = Space.sm,
            ),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(Modifier.weight(1f)) {
            if (overline != null) {
                Text(
                    text = overline.uppercase(),
                    style = HardPlayTheme.type.overline,
                    color = colors.muted,
                )
                Box(Modifier.height(Space.xs))
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
 * The headline is Instrument Serif italic at 28sp. An empty library is the one screen
 * with nothing to look at, so it's the one place the editorial voice earns its keep —
 * and it keeps "No results" from reading like a 404.
 *
 * The gaps are deliberately uneven — 16 / 8 / 12 / 24. A single `spacedBy` was doing all
 * four, which put the mark as far from the eyebrow as the body copy is from the button,
 * and a stack of equal gaps has no hierarchy in it no matter what the type is doing.
 *
 * **Left-aligned, and that is the point.** Every one of these was centre-stacked — mark,
 * eyebrow, headline, body and button all on the middle axis, at 56dp of side padding that
 * belonged to no grid. A centred column of text over a dead screen is the single most
 * recognisable "generated interface" shape there is, and the app has ten of them; it also
 * fought the rest of the product, where every masthead, shelf and card is set from the
 * left. Ranging left lets the ember rule act as a leading mark the eye starts from, puts
 * the headline on the same axis as the content that will replace it, and means arriving at
 * an empty screen no longer feels like being handed an error dialog.
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
            // The screen gutter, not the old 56dp. Generous side padding is what a
            // centred block needs to stop looking adrift; a left-ranged one wants to
            // sit on the same axis as everything else on the screen.
            .padding(horizontal = Space.gutter, vertical = Space.xxxl),
        horizontalAlignment = Alignment.Start,
    ) {
        // A short ember rule instead of an illustration, or an icon in a circle.
        EmberRule()
        Box(Modifier.height(Space.lg))
        if (overline != null) {
            Text(
                text = overline.uppercase(),
                style = HardPlayTheme.type.overline,
                color = colors.muted,
            )
            Box(Modifier.height(Space.sm))
        }
        Text(
            text = headline,
            style = HardPlayTheme.type.editorial,
            color = colors.type,
        )
        if (body != null) {
            Box(Modifier.height(Space.md))
            Text(
                text = body,
                style = HardPlayTheme.type.body,
                color = colors.muted,
                // A measure, not a centred block: ~46 characters is the comfortable
                // reading width, and left-ranged text needs the cap to keep the ragged
                // edge from running the full width of a tablet.
                modifier = Modifier.widthIn(max = 340.dp),
            )
        }
        if (action != null) {
            Box(Modifier.height(Space.xl))
            action()
        }
    }
}

/**
 * A quiet inline notice — a sync failure, a demo-mode note.
 *
 * Severity is carried by an ember edge and by weight, not by a hue. The palette
 * has no amber or red to spend here, and inventing one would break the rule that
 * matters most in this design system.
 *
 * The edge is now an *edge*: flush to the container and full height, rather than a 28dp
 * stub floating inside 12dp of padding, which read as a stray tick mark. `IntrinsicSize.Min`
 * is what lets a 2dp child fill a wrap-height row — without it `fillMaxHeight` resolves
 * against an infinite constraint and quietly draws nothing.
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
            .height(IntrinsicSize.Min)
            .clip(HardPlayTheme.shapes.card)
            .background(colors.surface)
            .border(
                Space.stroke,
                if (emphasis) colors.accentEdge else colors.hairline,
                HardPlayTheme.shapes.card,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(2.dp)
                .fillMaxHeight()
                .background(if (emphasis) colors.accent else colors.border),
        )
        Row(
            Modifier
                .weight(1f)
                .padding(horizontal = Space.md, vertical = Space.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.md),
        ) {
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
}

/**
 * The app's mark: a short ember rule.
 *
 * One shape, one width, wherever the app needs to sign a block of content — above a
 * screen's masthead, above an empty state's headline, at the top of the login hero, and
 * beside a settings group heading. It is shared rather than drawn inline because four
 * call sites had already picked four different widths for the same mark, and a signature
 * that changes size is not a signature.
 *
 * Deliberately not an icon or an illustration. A 24×2dp gradient bar costs nothing,
 * scales to any density, and cannot look like clip art. One of the three places in the
 * app permitted to draw the gradient.
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
 * It is now the mark at the mark's width, rather than a 34dp bar that resembled it.
 * Every sheet in the app is signed with the same shape the mastheads are.
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
        EmberRule()
    }
}

/**
 * Counter badge for the filter button's active-facet count.
 *
 * 3dp radius rather than a circle: a round badge on a square-cornered app is the
 * kind of inconsistency that registers without being noticed. Solid accent, not the
 * gradient — a badge marks a count, and the gradient is reserved for the primary
 * action, the mark and progress.
 *
 * `defaultMinSize` keeps a one-digit badge from collapsing into a sliver narrower than
 * it is tall, which is the difference between a badge and a smudge.
 */
@Composable
fun CountBadge(count: Int, modifier: Modifier = Modifier) {
    if (count <= 0) return
    val colors = HardPlayTheme.colors
    Box(
        modifier
            .defaultMinSize(minWidth = 16.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(colors.accent)
            .padding(horizontal = Space.xs, vertical = Space.xxs),
        contentAlignment = Alignment.Center,
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
