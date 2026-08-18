package com.hardplay.ui.player

import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Picture-in-picture, as the player is allowed to see it.
 *
 * An interface rather than a direct call into the activity, for the same reason the
 * shared-transition scopes are nullable: the player has to compose in places where
 * there is no activity able to enter PiP — a preview, the design gallery — and a
 * screen that crashes outside its host is a screen nobody can review.
 *
 * [inPipMode] is backed by snapshot state in the implementation, so reading it from a
 * composable recomposes when the window mode changes. That matters because *all*
 * chrome has to disappear in PiP: a 200dp window with a back button and a scrubber in
 * it looks broken rather than compact.
 */
@Stable
interface PipController {
    /** False on devices and profiles without the feature. Never assume it is there. */
    val supported: Boolean

    val inPipMode: Boolean

    /** Enter now, sized to [aspectRatio] (width / height). */
    fun enter(aspectRatio: Float)

    /**
     * Ask the system to enter PiP by itself when the user leaves.
     *
     * Only meaningful from API 31; below that the app has to catch the leave hint
     * itself. Both paths live behind this one call so the player does not have to
     * know which one it got.
     */
    fun setAutoEnter(enabled: Boolean, aspectRatio: Float)
}

/**
 * Null outside the activity. Absent means "no picture-in-picture", not a crash.
 */
val LocalPipController = staticCompositionLocalOf<PipController?> { null }
