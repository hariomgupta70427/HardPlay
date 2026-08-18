package com.hardplay.ui.player

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntSize
import com.hardplay.ui.theme.Motion
import androidx.compose.ui.geometry.lerp as lerpOffset
import androidx.compose.ui.util.lerp as lerpFloat

/**
 * Bounded zoom and pan for the video surface and the photo viewer.
 *
 * Written as a state holder rather than a handful of `mutableStateOf`s in the screen
 * because the bounding is the whole point and it needs three facts at once — the
 * viewport, the content's aspect, and the current scale. The previous implementation
 * applied a raw pan delta straight to `translationX/Y`, which let the picture be
 * dragged off-screen and left with no gesture that brings it back. A viewer you can
 * lose the image in is worse than one that does not pan at all.
 *
 * The transform is anchored on the gesture centroid, so a pinch keeps the point under
 * the fingers where it is. Anchoring on the centre instead — which is what you get by
 * only writing `scale` — makes the picture slide out from under the fingers as it
 * grows, and reads as the app fighting the gesture.
 */
@Stable
class ZoomPanState(private val maxScale: Float) {

    private var currentScale by mutableFloatStateOf(1f)
    private var currentOffset by mutableStateOf(Offset.Zero)

    /** Full extent the content is laid out in, in pixels. */
    private var viewport by mutableStateOf(Size.Zero)

    /** Width / height of the picture actually drawn. 0 while unknown. */
    private var contentAspect by mutableFloatStateOf(0f)

    val scale: Float get() = currentScale
    val offset: Offset get() = currentOffset

    /** Past 1× the gesture layer owns dragging, and a dismiss gesture must not. */
    val zoomed: Boolean get() = currentScale > ZOOMED_EPSILON

    fun onViewportChanged(size: IntSize) {
        viewport = Size(size.width.toFloat(), size.height.toFloat())
        // Rotating, or entering PiP, can shrink the viewport under a pan that was
        // legal a moment ago. Re-clamping here is what stops the image being left
        // half off the new edge.
        currentOffset = clamp(currentOffset, currentScale)
    }

    fun onContentAspectChanged(aspect: Float) {
        if (!aspect.isFinite() || aspect <= 0f) return
        contentAspect = aspect
        currentOffset = clamp(currentOffset, currentScale)
    }

    /**
     * One frame of a pinch/drag.
     *
     * @param centroid in the gesture layer's own coordinates.
     */
    fun onTransform(centroid: Offset, pan: Offset, zoom: Float) {
        val previous = currentScale
        val next = (previous * zoom).coerceIn(1f, maxScale)
        val ratio = if (previous <= 0f) 1f else next / previous
        val anchored = anchoredOffset(centroid, ratio) + pan
        currentScale = next
        currentOffset = clamp(anchored, next)
    }

    /** Double-tap: 1× ↔ [DOUBLE_TAP_SCALE], anchored on the tap. */
    suspend fun toggleZoom(at: Offset) {
        val goingIn = !zoomed
        val target = if (goingIn) DOUBLE_TAP_SCALE.coerceAtMost(maxScale) else 1f
        val ratio = if (currentScale <= 0f) 1f else target / currentScale
        val destination = if (goingIn) {
            clamp(anchoredOffset(at, ratio), target)
        } else {
            Offset.Zero
        }
        animateTo(target, destination)
    }

    /** Back to 1× under animation — leaving fullscreen, or dismissing a photo. */
    suspend fun reset() {
        if (currentScale == 1f && currentOffset == Offset.Zero) return
        animateTo(1f, Offset.Zero)
    }

    /**
     * Where the translation has to move so the point under [centroid] stays put.
     *
     * With `transformOrigin` at the centre, a content point `p` lands at
     * `centre + (p - centre) * s + t`. Solving that for the same screen point at a new
     * scale gives `t' = c * (1 - ratio) + t * ratio`, with `c` measured from the centre.
     */
    private fun anchoredOffset(centroid: Offset, ratio: Float): Offset {
        val fromCentre = centroid - Offset(viewport.width / 2f, viewport.height / 2f)
        return fromCentre * (1f - ratio) + currentOffset * ratio
    }

    /**
     * The picture's laid-out size before scaling.
     *
     * Both stages fit their content inside the viewport, so this is the letterboxed
     * box rather than the viewport itself — and using the viewport instead would let a
     * 21:9 video be panned into its own black bars.
     */
    private fun drawnSize(): Size {
        if (viewport.width <= 0f || viewport.height <= 0f) return Size.Zero
        val aspect = contentAspect
        if (aspect <= 0f) return viewport
        val viewportAspect = viewport.width / viewport.height
        return if (aspect >= viewportAspect) {
            Size(viewport.width, viewport.width / aspect)
        } else {
            Size(viewport.height * aspect, viewport.height)
        }
    }

    private fun clamp(candidate: Offset, atScale: Float): Offset {
        val drawn = drawnSize()
        if (drawn.width <= 0f || drawn.height <= 0f) return Offset.Zero
        val limitX = ((drawn.width * atScale - viewport.width) / 2f).coerceAtLeast(0f)
        val limitY = ((drawn.height * atScale - viewport.height) / 2f).coerceAtLeast(0f)
        return Offset(
            x = candidate.x.coerceIn(-limitX, limitX),
            y = candidate.y.coerceIn(-limitY, limitY),
        )
    }

    private suspend fun animateTo(targetScale: Float, targetOffset: Offset) {
        val fromScale = currentScale
        val fromOffset = currentOffset
        animate(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = tween(Motion.Standard, easing = Motion.Ember),
        ) { fraction, _ ->
            currentScale = lerpFloat(fromScale, targetScale, fraction)
            // Clamped every frame rather than only at the end: the limit itself grows
            // with the scale, so an end-clamped animation crosses illegal states on
            // the way and shows the image sliding past its own edge.
            currentOffset = clamp(lerpOffset(fromOffset, targetOffset, fraction), currentScale)
        }
    }

    private companion object {
        const val ZOOMED_EPSILON = 1.01f
        const val DOUBLE_TAP_SCALE = 2.5f
    }
}

@Composable
fun rememberZoomPanState(maxScale: Float): ZoomPanState =
    remember(maxScale) { ZoomPanState(maxScale) }

/**
 * Apply the transform in the draw phase.
 *
 * The lambda form of `graphicsLayer`, not the parameter form, and that is a real
 * difference here: reading `scale` and `offset` inside the block confines a gesture to
 * invalidating the layer, while passing them as arguments would recompose the video
 * surface on every pointer event.
 */
fun Modifier.zoomPan(zoom: ZoomPanState): Modifier = graphicsLayer {
    scaleX = zoom.scale
    scaleY = zoom.scale
    translationX = zoom.offset.x
    translationY = zoom.offset.y
}
