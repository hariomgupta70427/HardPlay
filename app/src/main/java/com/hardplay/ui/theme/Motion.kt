package com.hardplay.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

/**
 * Motion is where "premium" is actually earned or lost. Two rules:
 *
 *  1. Everything decelerates. A symmetric ease (Material's default
 *     FastOutSlowIn) reads as software; a hard-out curve reads as physical.
 *  2. Nothing takes 300ms because 300ms was the default. Small elements move
 *     fast, large surfaces move slow, and shared-element transitions get their
 *     own spring so they settle rather than stop.
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

    fun <T> quick(): FiniteAnimationSpec<T> = tween(Quick, easing = Ember)
    fun <T> standard(): FiniteAnimationSpec<T> = tween(Standard, easing = Ember)
    fun <T> emphasized(): FiniteAnimationSpec<T> = tween(Emphasized, easing = Ember)
    fun <T> fade(): FiniteAnimationSpec<T> = tween(Standard, easing = Smooth)

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

    /** Poster press-down scale. Subtle — 0.97, not 0.9. */
    const val PressScale = 0.972f

    /** Shimmer sweep period. Slow enough to read as light, not as a loading bar. */
    const val ShimmerPeriod = 1450

    /** Grain re-roll rate. ~12fps: film-like, and cheap. */
    const val GrainFrameMs = 83L
}
