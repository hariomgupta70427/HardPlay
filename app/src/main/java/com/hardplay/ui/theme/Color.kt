package com.hardplay.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

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
    /** The one permitted gradient. Accent only — never chrome, never a card fill. */
    val emberGradient: Brush
        get() = Brush.horizontalGradient(listOf(accent, accentHigh))

    val emberGradientVertical: Brush
        get() = Brush.verticalGradient(listOf(accentHigh, accent))

    /**
     * Bottom-up scrim for poster art, so bone text stays legible over any frame.
     * Three stops rather than two — a linear fade leaves a visible hard edge.
     */
    val posterScrim: Brush
        get() = Brush.verticalGradient(
            0.0f to Color.Transparent,
            0.55f to bg.copy(alpha = 0.35f),
            0.78f to bg.copy(alpha = 0.78f),
            1.0f to bg.copy(alpha = 0.96f),
        )

    /** Top scrim for player chrome and immersive headers. */
    val topScrim: Brush
        get() = Brush.verticalGradient(
            listOf(bg.copy(alpha = 0.92f), Color.Transparent),
        )
}

val HardPlayDarkColors = HardPlayColors(
    bg = Palette.InkBlack,
    bgRaised = Color(0xFF0E0A0E),
    surface = Palette.Oxblood,
    surfaceRaised = Color(0xFF241016),
    surfaceSunken = Color(0xFF12070C),
    hairline = Color(0xFF2A1A20),
    border = Color(0xFF3D2229),
    accent = Palette.EmberLow,
    accentHigh = Palette.EmberHigh,
    accentSunken = Color(0xFF7A2818),
    onAccent = Color(0xFF1A0B10),
    type = Palette.Bone,
    typeDim = Color(0xFFB8AFA6),
    muted = Palette.AshRose,
    danger = Color(0xFFC4321F),
    scrim = Color(0xE608070A),
    grain = Palette.Bone,
)
