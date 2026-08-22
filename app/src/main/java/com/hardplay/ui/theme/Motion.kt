package com.hardplay.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

/**
 * Motion is where "premium" is actually earned or lost. Three rules:
 *
 *  1. Everything decelerates. A symmetric ease (Material's default
 *     FastOutSlowIn) reads as software; a hard-out curve reads as physical.
 *  2. Nothing takes 300ms because 300ms was the default. Small elements move
 *     fast, large surfaces move slow, and shared-element transitions get their
 *     own spring so they settle rather than stop.
 *  3. **A press is not symmetric.** Touch feedback has to land inside the ~100ms
 *     window where the brain still attributes it to the finger, then release slowly
 *     enough to be seen. One `tween` used for both directions — which is what a single
 *     `animateFloatAsState(spec = quick())` gives you — either makes the press feel
 *     laggy or makes the release feel like a snap. See [pressDown] / [pressUp].
 */
object Motion {
    /** Primary curve — quick departure, long settle. */
    val Ember: Easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)

    /** For elements leaving; mirror of Ember so exits feel like exits. */
    val EmberOut: Easing = CubicBezierEasing(0.7f, 0f, 0.84f, 0f)

    /** Symmetric, for cross-fades and colour changes where motion isn't implied. */
    val Smooth: Easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

    const val Instant = 90
    const val Quick = 140
    const val Standard = 240
    const val Emphasized = 420
    const val Sheet = 320
    /** Player chrome auto-hide delay. */
    const val ChromeIdle = 3200

    /** Press-down and press-release, in ms. */
    const val PressIn = 70
    const val PressOut = 260

    fun <T> quick(): FiniteAnimationSpec<T> = tween(Quick, easing = Ember)
    fun <T> standard(): FiniteAnimationSpec<T> = tween(Standard, easing = Ember)
    fun <T> emphasized(): FiniteAnimationSpec<T> = tween(Emphasized, easing = Ember)
    fun <T> fade(): FiniteAnimationSpec<T> = tween(Standard, easing = Smooth)

    /** Finger goes down: near-linear and over before it can be perceived as animation. */
    fun <T> pressDown(): FiniteAnimationSpec<T> = tween(PressIn, easing = LinearOutSlowInEasing)

    /** Finger comes up: long enough to be watched, on the app's own curve. */
    fun <T> pressUp(): FiniteAnimationSpec<T> = tween(PressOut, easing = Ember)

    /**
     * Shared-element bounds spring for grid -> player. Low stiffness with no
     * bounce: the poster should feel heavy, like it has mass, not springy.
     */
    val sharedBounds: FiniteAnimationSpec<androidx.compose.ui.geometry.Rect> =
        spring(dampingRatio = 0.9f, stiffness = 380f)

    val sizeSpring: FiniteAnimationSpec<IntSize> =
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 420f)

    val offsetSpring: FiniteAnimationSpec<IntOffset> =
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 420f)

    /**
     * Press-down scale, by element size.
     *
     * Two values rather than one because the *perceived* depth of a press is the
     * absolute distance the edges move, not the ratio: 0.97 on a 160dp poster shifts
     * its edge by 2.4dp and reads as a press, while 0.97 on a 34dp chip shifts it by
     * half a dp and reads as nothing at all.
     *
     * There were three magic numbers for this gesture before — 0.972 sitting here
     * unused, 0.965 hardcoded in Buttons, 0.94 in Chips — which is how a design system
     * ends up with three different presses and no way to notice.
     */
    const val PressScale = 0.972f
    const val PressScaleControl = 0.962f
    const val PressScaleSmall = 0.94f

    /** Shimmer sweep period. Slow enough to read as light, not as a loading bar. */
    const val ShimmerPeriod = 1450

    /** Grain re-roll rate. ~12fps: film-like, and cheap. */
    const val GrainFrameMs = 83L
}
