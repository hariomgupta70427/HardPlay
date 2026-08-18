package com.hardplay.ui.theme

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * Film grain.
 *
 * A tiling noise bitmap composited over the UI at very low alpha. It earns its
 * cost twice over: on an OLED panel a flat #08070A fill shows visible banding
 * wherever a scrim fades, and grain dithers that away — while also giving the
 * app a photographic quality that flat digital surfaces never have.
 *
 * Three 128px tiles, generated once per process from a fixed seed and shared by
 * every screen. The animated variant re-rolls the tile at ~12fps rather than
 * every frame: real film grain doesn't update at 120Hz, and only the draw phase
 * is invalidated.
 */
private const val TILE_PX = 128
private const val TILE_COUNT = 3

/** Below 0.03 it's invisible; above 0.08 it reads as a dirty screen. */
const val GRAIN_DEFAULT = 0.045f

/**
 * Fixed seed, so the grain is byte-identical on every launch and every device.
 * It's part of the app's identity, not random noise.
 */
private val grainTiles: List<ImageBitmap> by lazy {
    val random = Random(0x0B105EED)
    List(TILE_COUNT) {
        val pixels = IntArray(TILE_PX * TILE_PX)
        for (i in pixels.indices) {
            // Light and dark specks in equal measure. Dark specks are nearly
            // invisible on ink black by themselves, but without them the layer
            // reads as a uniform lightening haze instead of as texture.
            val alpha = random.nextInt(0, 256) shl 24
            pixels[i] = if (random.nextBoolean()) alpha or 0x00F5F0E8 else alpha
        }
        Bitmap.createBitmap(pixels, TILE_PX, TILE_PX, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
}

private val grainBrushes: List<ShaderBrush> by lazy {
    grainTiles.map { ShaderBrush(ImageShader(it, TileMode.Repeated, TileMode.Repeated)) }
}

/** Static grain drawn over this element's content. */
fun Modifier.filmGrain(intensity: Float = GRAIN_DEFAULT): Modifier = drawWithCache {
    val brush = grainBrushes[0]
    onDrawWithContent {
        drawContent()
        drawRect(brush = brush, alpha = intensity)
    }
}

/**
 * Grain that re-rolls at ~12fps. `frame` is read inside the draw lambda, so the
 * ticker invalidates drawing without recomposing anything.
 */
@Composable
fun Modifier.animatedFilmGrain(intensity: Float = GRAIN_DEFAULT): Modifier {
    var frame by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(Motion.GrainFrameMs)
            frame = (frame + 1) % TILE_COUNT
        }
    }
    return drawWithCache {
        onDrawWithContent {
            drawContent()
            drawRect(brush = grainBrushes[frame], alpha = intensity)
        }
    }
}

/**
 * Radial darkening toward the corners. Pairs with grain: together they turn a
 * flat dark screen into something that looks lit rather than filled.
 */
fun Modifier.vignette(
    strength: Float = 0.55f,
    color: Color = Palette.InkBlack,
): Modifier = drawWithCache {
    val brush = Brush.radialGradient(
        0.45f to Color.Transparent,
        1.0f to color.copy(alpha = strength),
        center = Offset(size.width / 2f, size.height / 2f),
        radius = maxOf(size.width, size.height) * 0.78f,
    )
    onDrawWithContent {
        drawContent()
        drawRect(brush)
    }
}

/**
 * An ember bloom bled in from off the top-left corner at very low alpha, so the
 * screen reads as having a light source somewhere above it.
 */
fun Modifier.emberBloom(
    accent: Color,
    strength: Float = 0.10f,
): Modifier = drawWithCache {
    val brush = Brush.radialGradient(
        0f to accent.copy(alpha = strength),
        1f to Color.Transparent,
        center = Offset(size.width * 0.12f, -size.height * 0.05f),
        radius = size.minDimension * 1.1f,
    )
    onDrawBehind { drawRect(brush) }
}

/**
 * The standard app background. Use this rather than a `Box` with a background
 * colour, so every screen picks up identical grain and bloom.
 */
@Composable
fun HardPlaySurface(
    modifier: Modifier = Modifier,
    grain: Float = GRAIN_DEFAULT,
    bloom: Boolean = true,
    animatedGrain: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = HardPlayTheme.colors
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bg)
            .then(if (bloom) Modifier.emberBloom(colors.accent) else Modifier)
            .then(
                if (animatedGrain) Modifier.animatedFilmGrain(grain)
                else Modifier.filmGrain(grain),
            ),
    ) {
        content()
    }
}
