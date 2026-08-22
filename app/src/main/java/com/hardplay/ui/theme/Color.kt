package com.hardplay.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * Oxblood & Ember.
 *
 * Five base colours, everything else derived from them. The rule is absolute:
 * no hue enters this file that is not already in the palette. That means no
 * green "success", no blue "info", no purple anything. States are carried by
 * weight, opacity and iconography instead — which is also why the app doesn't
 * look like every other Material template.
 *
 *   bg      #08070A  ink black
 *   surface #1A0B10  oxblood
 *   accent  #FF4D2E -> #FF8A3D  ember (the only gradient in the app)
 *   type    #F5F0E8  bone
 *   muted   #6B5C5F  ash rose
 */
object Palette {
    val InkBlack = Color(0xFF08070A)
    val Oxblood = Color(0xFF1A0B10)
    val EmberLow = Color(0xFFFF4D2E)
    val EmberHigh = Color(0xFFFF8A3D)
    val Bone = Color(0xFFF5F0E8)
    val AshRose = Color(0xFF6B5C5F)
}

/**
 * Every role below is a [lerp] between two [Palette] entries rather than a hand-picked
 * hex, for two reasons.
 *
 * It is *provably* in palette — a reviewer can read the factor instead of pasting the
 * hex into a colour picker. And Compose's [lerp] interpolates in Oklab, so a ladder
 * built from even factors has even *perceptual* steps. Hand-picked steps were what the
 * ladder had before, and an unevenly spaced surface ladder is one of the reasons a dark
 * UI reads as assembled rather than designed: `surfaceRaised` was a bigger jump from
 * `surface` than `bgRaised` was from `bg`, so a nav bar and a card looked like they
 * belonged to different systems.
 */
private fun mix(a: Color, b: Color, t: Float): Color = lerp(a, b, t)

@Immutable
data class HardPlayColors(
    /** Page background. Almost black, faintly warm so it never reads as blue-grey. */
    val bg: Color,
    /** Background one step up — nav bars, inset panels. */
    val bgRaised: Color,
    /** Cards, sheets, inputs. */
    val surface: Color,
    /** Surface one step up — pressed cards, nested rows. */
    val surfaceRaised: Color,
    /** Pressed/held state for surfaces. */
    val surfaceSunken: Color,
    /** 1px separators. Never a full-strength line; hairlines only. */
    val hairline: Color,
    /** Stronger border — focused inputs, selected outlines. */
    val border: Color,

    val accent: Color,
    val accentHigh: Color,
    /** Ember dimmed for large fills where full ember would shout. */
    val accentSunken: Color,
    /** Text/icon colour that sits legibly on an ember fill. */
    val onAccent: Color,

    /** Primary text. Bone, not pure white — pure white on near-black glares. */
    val type: Color,
    /** Secondary text. */
    val typeDim: Color,
    /** Tertiary text, placeholders, disabled. */
    val muted: Color,

    /** Destructive actions. A darkened ember, not a new red. */
    val danger: Color,
    /** Full-bleed dim behind sheets and dialogs. */
    val scrim: Color,
    /** Film grain speck colour. */
    val grain: Color,
) {
    /**
     * The one permitted gradient. Accent only — never chrome, never a card fill.
     *
     * Cached rather than rebuilt per read: the poster grid reads [posterScrim] once per
     * cell per recomposition, and a `get()` allocated four `Color.copy` calls and a
     * `Brush` every time.
     *
     * Scarcity is semantic, not decorative. The gradient marks exactly three things:
     * the **primary action** (`EmberButton`), the app's **mark** (`EmberRule`,
     * `SheetHandle`), and **progress** (`BufferingMark`). Everything else that needs
     * accenting takes solid [accent] — selected states, badges, carets, edges, the
     * switch track. It was previously filling every selected chip, every switch and
     * every badge in the app, which did not make the app more ember; it left the
     * gradient meaning nothing, and left the one actual primary action on a screen with
     * nothing to be louder than.
     *
     * (The two stops are also visually indistinguishable across 30–40dp, so most of
     * those uses were paying for a gradient nobody could see.)
     */
    val emberGradient: Brush by lazy {
        Brush.horizontalGradient(listOf(accent, accentHigh))
    }

    val emberGradientVertical: Brush by lazy {
        Brush.verticalGradient(listOf(accentHigh, accent))
    }

    /**
     * Bottom-up scrim for poster art, so bone text stays legible over any frame.
     * Three stops rather than two — a linear fade leaves a visible hard edge.
     */
    val posterScrim: Brush by lazy {
        Brush.verticalGradient(
            0.0f to Color.Transparent,
            0.55f to bg.copy(alpha = 0.35f),
            0.78f to bg.copy(alpha = 0.78f),
            1.0f to bg.copy(alpha = 0.96f),
        )
    }

    /** Top scrim for player chrome and immersive headers. */
    val topScrim: Brush by lazy {
        Brush.verticalGradient(listOf(bg.copy(alpha = 0.92f), Color.Transparent))
    }

    // ---------------------------------------------------------------- state layers
    //
    // With five colours there is no second hue to spend on state, so selected /
    // pressed / focused / disabled are each a *different channel*: a wash, an edge,
    // a weight, a fill removed. Named here rather than written as `accent.copy(0.16f)`
    // at eleven call sites, because the moment two of those call sites disagree the
    // states stop reading as one system.

    /**
     * Selected fill. Ember at a wash — enough to separate a selected chip from its
     * neighbours across a wrapping row, quiet enough that twenty selected chips don't
     * turn the sheet orange. Pairs with [accentEdge] and a heavier label; one channel
     * on its own is never enough to make selection unmistakable.
     */
    val accentWash: Color get() = accent.copy(alpha = 0.15f)

    /** The hairline ember edge that marks selection and focus. */
    val accentEdge: Color get() = accent.copy(alpha = 0.72f)

    /** Ember at the strength of a divider — for a selected row's leading rule. */
    val accentHairline: Color get() = accent.copy(alpha = 0.34f)

    /**
     * Press wash. Bone at a few percent, drawn flat across the whole control by
     * [FlatPressIndication] — no expanding circle, which is the single most
     * recognisable Android gesture there is.
     */
    val pressWash: Color get() = type.copy(alpha = 0.06f)

    /** Keyboard/D-pad focus, half the press strength so the two are distinguishable. */
    val focusWash: Color get() = type.copy(alpha = 0.03f)

    /**
     * Disabled controls lose their *fill*, not their legibility. Fading a button to
     * 40% alpha leaves an ember smear that still looks tappable and a label at 2:1;
     * swapping the fill for a flat surface and the label for [muted] says "not now"
     * without ever making a control hard to read. One of these shipped at 14%.
     */
    val disabledFill: Color get() = surfaceRaised
    val onDisabled: Color get() = muted
}

val HardPlayDarkColors = HardPlayColors(
    bg = Palette.InkBlack,
    // Nav bars and the scrolled top bar: oxblood pulled most of the way back to ink,
    // so chrome separates from the page without becoming a card.
    bgRaised = mix(Palette.InkBlack, Palette.Oxblood, 0.45f),
    surface = Palette.Oxblood,
    // A *bone* lift, not an ember one. The previous #241016 leaned toward the accent,
    // which meant every raised surface in the app was faintly on its way to orange and
    // the actual accent had less room to register.
    surfaceRaised = mix(Palette.Oxblood, Palette.Bone, 0.055f),
    surfaceSunken = mix(Palette.Oxblood, Palette.InkBlack, 0.45f),
    hairline = mix(Palette.Oxblood, Palette.Bone, 0.105f),
    border = mix(Palette.Oxblood, Palette.Bone, 0.22f),
    accent = Palette.EmberLow,
    accentHigh = Palette.EmberHigh,
    accentSunken = mix(Palette.EmberLow, Palette.InkBlack, 0.55f),
    onAccent = Palette.Oxblood,
    type = Palette.Bone,
    typeDim = mix(Palette.Bone, Palette.AshRose, 0.42f),
    // Ash rose *lifted toward bone*, and this one is not a taste call.
    //
    // Pure #6B5C5F on #08070A measures 3.4:1. `colors.muted` is the most-used text
    // colour in the app — captions, counts, subtitles, placeholders, roughly sixty
    // call sites, nearly all of them at 12sp — so the palette entry as written fails
    // WCAG AA (4.5:1) everywhere it is used for text. Blends are explicitly permitted
    // and this one lands at ~5:1 on ink and ~4.7:1 on oxblood.
    //
    // It is also the cheap-looking end of low contrast: confident secondary text is
    // most of what separates a considered dark UI from a dim one. `Palette.AshRose`
    // itself is untouched for the decorative cases that want 3:1.
    muted = mix(Palette.AshRose, Palette.Bone, 0.24f),
    danger = mix(Palette.EmberLow, Palette.InkBlack, 0.22f),
    scrim = Palette.InkBlack.copy(alpha = 0.90f),
    grain = Palette.Bone,
)
