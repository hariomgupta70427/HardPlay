package com.hardplay.ui.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.hardplay.ui.components.BufferingOverlay
import com.hardplay.ui.theme.Motion

/**
 * The photo viewer.
 *
 * Two images, one on top of the other, and the reason for that is the whole point of
 * this file. A full-resolution Telegram photo is megabytes fetched on demand, so the
 * screen shows the rung the grid already has *immediately* and cross-fades the real
 * thing in when it decodes. The alternative — asking for the original and waiting —
 * gives a black screen for several seconds; and the version before that asked for the
 * grid thumbnail and simply upscaled it, which is what got reported as "not original
 * quality".
 *
 * The small request is small **on purpose**: `PosterSource` picks its rung from the
 * requested size, so asking for [PREVIEW_TARGET_PX] resolves to the same file the grid
 * cached and appears without a network round trip at all.
 */
@Composable
internal fun PhotoStage(
    ui: PlayerUiState,
    zoom: ZoomPanState,
    modifier: Modifier = Modifier,
    /**
     * Attached to the cheap rung, because that is the image the grid was showing — so
     * it is the one the shared-element transition has to grow. Putting the key on the
     * full-resolution layer instead would animate into an empty frame.
     */
    previewModifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val poster = ui.poster

    val previewRequest = remember(poster) {
        poster?.let {
            ImageRequest.Builder(context)
                .data(it)
                .size(PREVIEW_TARGET_PX)
                .build()
        }
    }

    val fullRequest = remember(poster) {
        poster?.let {
            ImageRequest.Builder(context)
                .data(it.atOriginalResolution())
                // A cap, not a target to grow into: Coil never upscales, so a photo
                // smaller than this decodes at its own size. It bounds the one case
                // that would hurt — a 4000px original decoded to a 60 MB bitmap.
                .size(FULL_TARGET_PX)
                .build()
        }
    }

    val fullPainter = rememberAsyncImagePainter(model = fullRequest)
    val painterState = fullPainter.state
    val ready = painterState is AsyncImagePainter.State.Success

    // The real aspect, as soon as the decode knows it. The indexed dimensions are the
    // fallback and are sometimes absent; bounding a pan against the viewport instead
    // of the picture lets a tall photo be dragged into its own letterbox.
    LaunchedEffect(painterState) {
        val intrinsic = fullPainter.intrinsicSize
        if (intrinsic.isSpecified && intrinsic.height > 0f) {
            zoom.onContentAspectChanged(intrinsic.width / intrinsic.height)
        }
    }

    val fullAlpha by animateFloatAsState(
        targetValue = if (ready) 1f else 0f,
        animationSpec = Motion.fade(),
        label = "fullResFade",
    )

    Box(modifier.fillMaxSize()) {
        // Kept underneath until the full image has actually arrived, rather than
        // swapped out on request start: a cross-fade from a blurry picture reads as
        // the image sharpening, while a swap reads as a flash of nothing.
        if (fullAlpha < 1f && previewRequest != null) {
            AsyncImage(
                model = previewRequest,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .zoomPan(zoom)
                    .then(previewModifier),
            )
        }

        if (fullRequest != null) {
            // Always composed, never gated on `ready`. An AsyncImagePainter resolves
            // its size from the first draw, so a painter that is never drawn is a
            // request that never starts — and the full-resolution image would never
            // load at all.
            Image(
                painter = fullPainter,
                contentDescription = ui.title,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(fullAlpha)
                    .zoomPan(zoom),
            )
        }

        if (!ready && poster != null) {
            BufferingOverlay(
                // Determinate once TDLib has told us the size. Zero would draw an
                // empty frame, so the indeterminate comet covers that first moment.
                progress = ui.photoProgress.takeIf { it > 0f },
                caption = "Full resolution…",
            )
        }
    }
}

/** Matches the grid's rung, so the first paint comes out of Coil's cache. */
private const val PREVIEW_TARGET_PX = 320

/**
 * Upper bound on the decode, not a request for upscaling.
 *
 * Telegram's largest photo rung is around 2560px, so in practice this caps nothing and
 * simply stops a hand-uploaded original from being decoded at full size into memory.
 */
private const val FULL_TARGET_PX = 2560
