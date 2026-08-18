package com.hardplay.ui.nav

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.hardplay.ui.theme.Motion

/**
 * The grid → player shared element.
 *
 * The PRD calls this out as *the* detail that decides whether the app reads as a
 * real product or as a wrapper (§6.3), and it is: a poster that grows into the
 * player tells the user where they are, while a cross-fade tells them nothing.
 *
 * The scopes are nullable so a screen can be rendered outside a
 * `SharedTransitionLayout` — previews, the design gallery — without a second code
 * path. Absent scopes mean no transition, not a crash.
 */
private val PosterBounds = BoundsTransform { _, _ -> Motion.sharedBounds }

/** Stable across both screens: this string *is* the identity of the transition. */
fun posterTransitionKey(localId: Long): String = "poster-$localId"

@Composable
fun sharedPosterModifier(
    shared: SharedTransitionScope?,
    visibility: AnimatedVisibilityScope?,
    localId: Long,
): Modifier {
    if (shared == null || visibility == null) return Modifier
    return with(shared) {
        Modifier.sharedElement(
            state = rememberSharedContentState(key = posterTransitionKey(localId)),
            animatedVisibilityScope = visibility,
            boundsTransform = PosterBounds,
        )
    }
}
