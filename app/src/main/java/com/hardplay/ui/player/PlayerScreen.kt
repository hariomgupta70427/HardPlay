package com.hardplay.ui.player

import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.TextureView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.LocalOffer
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.exoplayer.ExoPlayer
import coil.compose.AsyncImage
import com.hardplay.core.Format
import com.hardplay.data.db.entity.TagEntity
import com.hardplay.playback.ExternalOpen
import com.hardplay.ui.components.BufferingOverlay
import com.hardplay.ui.components.EmberButton
import com.hardplay.ui.components.GhostIconButton
import com.hardplay.ui.components.MetaChip
import com.hardplay.ui.components.Notice
import com.hardplay.ui.components.TagChip
import com.hardplay.ui.components.rememberHaptics
import com.hardplay.ui.nav.sharedPosterModifier
import com.hardplay.ui.theme.HardPlayTheme
import com.hardplay.ui.theme.Motion
import com.hardplay.ui.theme.Space
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Playback, and the full-resolution photo viewer (PRD §6.2 §4).
 *
 * All chrome is Compose over a bare video view. Media3's `PlayerView` is not used:
 * it ships a full control surface in Material styling, and the PRD is explicit that
 * the controls, the buffering mark and the skip feedback are custom.
 *
 * The bottom bar carries transport and time and nothing else. Speed, track selection,
 * opening elsewhere and picture-in-picture live in [PlayerOptionsSheet] one tap away,
 * because every control added to the chrome is a control sitting on top of the thing
 * being watched.
 */
@Composable
fun PlayerScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    localId: Long = -1L,
    sharedScope: SharedTransitionScope? = null,
    visibilityScope: AnimatedVisibilityScope? = null,
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val tags by viewModel.itemTags.collectAsStateWithLifecycle()
    val saved by viewModel.isFavourite.collectAsStateWithLifecycle()
    val viewed by viewModel.isViewed.collectAsStateWithLifecycle()
    val tracks by viewModel.tracks.collectAsStateWithLifecycle()
    val external by viewModel.external.collectAsStateWithLifecycle()

    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val pip = LocalPipController.current
    val inPip = pip?.inPipMode == true

    val zoom = rememberZoomPanState(MAX_ZOOM)

    var chromeVisible by remember { mutableStateOf(true) }
    var optionsOpen by remember { mutableStateOf(false) }
    var tagsOpen by remember { mutableStateOf(false) }
    var fullscreen by remember { mutableStateOf(false) }
    var resumeNoticeVisible by remember { mutableStateOf(false) }
    var textureView by remember { mutableStateOf<TextureView?>(null) }
    var externalFailed by remember { mutableStateOf(false) }

    // Any interaction restarts the idle countdown.
    var lastInteraction by remember { mutableLongStateOf(0L) }
    fun poke() {
        chromeVisible = true
        lastInteraction += 1
    }

    /** The ratio the stage fits to: a video's frame, or a still's own dimensions. */
    val stageAspect = ui.videoAspect.takeIf { it.isFinite() && it > 0f } ?: (16f / 9f)
    LaunchedEffect(stageAspect) { zoom.onContentAspectChanged(stageAspect) }

    // Auto-hide. Keyed on the interaction counter *and* on playing: chrome must not
    // vanish while paused, because a paused player with no controls looks frozen.
    LaunchedEffect(lastInteraction, ui.playing, optionsOpen, inPip) {
        if (inPip) {
            // A 200dp window with a scrubber and a back button in it reads as broken
            // rather than compact, so PiP has no chrome at all.
            chromeVisible = false
            return@LaunchedEffect
        }
        if (!ui.playing || optionsOpen) return@LaunchedEffect
        delay(Motion.ChromeIdle.toLong())
        chromeVisible = false
    }

    // Said once, then gone. Resuming silently mid-file looks like the app lost the
    // beginning; a permanent badge would be clutter on every rewatch.
    LaunchedEffect(ui.resumedFromMs) {
        if (ui.resumedFromMs <= 0L) return@LaunchedEffect
        resumeNoticeVisible = true
        delay(RESUME_NOTICE_MS)
        resumeNoticeVisible = false
    }

    // Keep the frame that is already on screen as this item's artwork. Telegram gives
    // a video one small thumbnail and no larger rung, so this is the only way video
    // ever looks sharp in a full-width cell — and it costs nothing.
    LaunchedEffect(ui.firstFrameRendered) {
        if (!ui.firstFrameRendered) return@LaunchedEffect
        // onRenderedFirstFrame fires when the decoder produced a frame; the
        // TextureView needs a compositor pass after that before getBitmap has
        // anything in it, and returns null until then.
        delay(FRAME_CAPTURE_DELAY_MS)
        // getBitmap allocates, and throws on a view that has lost its surface. Artwork
        // is never worth taking playback down for.
        viewModel.captureFrameOnce(runCatching { textureView?.bitmap }.getOrNull())
    }

    // Auto-enter PiP only while something is actually playing: a home gesture from a
    // paused player should leave, not spawn a frozen floating window.
    LaunchedEffect(pip, ui.playing, ui.isPhoto, stageAspect) {
        if (ui.isPhoto) return@LaunchedEffect
        pip?.setAutoEnter(enabled = ui.playing, aspectRatio = stageAspect)
    }
    DisposableEffect(pip) {
        onDispose { pip?.setAutoEnter(enabled = false, aspectRatio = 16f / 9f) }
    }

    ImmersiveMode(enabled = fullscreen && !inPip)
    OrientationLock(landscape = fullscreen && !inPip)
    KeepScreenOn(active = ui.playing && !inPip)

    // Back unwinds one state at a time — zoom, then fullscreen — rather than leaving
    // the screen from a zoomed-in fullscreen frame with no way to see where you were.
    BackHandler(enabled = zoom.zoomed || fullscreen) {
        when {
            zoom.zoomed -> scope.launch { zoom.reset() }
            else -> {
                fullscreen = false
                scope.launch { zoom.reset() }
            }
        }
    }

    Box(
        modifier
            .fillMaxSize()
            // Pure black behind video, not the app's ink black: any lift here shows
            // as a grey border around letterboxed content.
            .background(Color.Black)
            .onSizeChanged(zoom::onViewportChanged),
    ) {
        if (ui.isPhoto) {
            PhotoStage(
                ui = ui,
                zoom = zoom,
                previewModifier = sharedPosterModifier(sharedScope, visibilityScope, localId),
            )
        } else {
            VideoStage(
                player = viewModel.player,
                aspect = stageAspect,
                zoom = zoom,
                onTextureView = { textureView = it },
            )

            // The arriving half of the grid → player shared element. It is the poster,
            // not the video surface: the surface is blank until the decoder produces a
            // frame, and animating into a black rectangle reads as a broken transition.
            // It fades out the moment there is a real frame to hand over to.
            AnimatedVisibility(
                visible = !ui.firstFrameRendered,
                enter = fadeIn(Motion.fade()),
                exit = fadeOut(tween(durationMillis = Motion.Standard, easing = Motion.Smooth)),
            ) {
                AsyncImage(
                    model = ui.poster,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .then(sharedPosterModifier(sharedScope, visibilityScope, localId)),
                )
            }
        }

        if (!inPip) {
            StageGestures(
                zoom = zoom,
                isPhoto = ui.isPhoto,
                skipSeconds = ui.skipSeconds,
                onToggleChrome = {
                    // Hiding must not poke: poke() forces chromeVisible = true, so the
                    // toggle undid itself and only the idle timer could ever hide the
                    // controls. Showing restarts the timer; hiding just hides.
                    if (chromeVisible) chromeVisible = false else poke()
                },
                onSkipBack = { viewModel.skipBackward(); haptics.tick(); poke() },
                onSkipForward = { viewModel.skipForward(); haptics.tick(); poke() },
            )
        }

        if (!ui.isPhoto && ui.buffering && ui.error == null) {
            BufferingOverlay(
                caption = if (ui.bufferedMs > 0) {
                    "Buffering — ${Format.durationMs(ui.bufferedMs - ui.positionMs)} ahead"
                } else {
                    "Reaching HardPlay…"
                },
            )
        }

        if (!inPip) {
            AnimatedVisibility(
                visible = chromeVisible || ui.error != null,
                enter = fadeIn(Motion.fade()),
                exit = fadeOut(Motion.fade()),
            ) {
                Chrome(
                    ui = ui,
                    tags = tags,
                    saved = saved,
                    fullscreen = fullscreen,
                    onBack = onBack,
                    onToggleSaved = { viewModel.toggleFavourite(); haptics.confirm(); poke() },
                    onTogglePlay = { viewModel.togglePlay(); poke() },
                    onScrub = { fraction ->
                        viewModel.scrubTo((fraction * ui.durationMs).toLong())
                        poke()
                    },
                    onScrubEnd = { viewModel.commitScrub(); poke() },
                    onFullscreenToggle = {
                        fullscreen = !fullscreen
                        if (!fullscreen) scope.launch { zoom.reset() }
                        poke()
                    },
                    onTagsToggle = { tagsOpen = true; poke() },
                    onOptions = {
                        viewModel.refreshExternalState()
                        externalFailed = false
                        optionsOpen = true
                        poke()
                    },
                    onRetry = viewModel::retry,
                )
            }

            AnimatedVisibility(
                visible = resumeNoticeVisible,
                enter = fadeIn(Motion.fade()),
                exit = fadeOut(Motion.fade()),
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                ResumedNotice(positionMs = ui.resumedFromMs)
            }
        }
    }

    if (optionsOpen) {
        PlayerOptionsSheet(
            ui = ui,
            tracks = tracks,
            viewed = viewed,
            externalLabel = externalLabel(external, externalFailed),
            externalEnabled = external is ExternalOpen.State.Ready,
            pipSupported = pip?.supported == true,
            onDismiss = { optionsOpen = false; poke() },
            onSpeedSelect = { viewModel.setSpeed(it); haptics.tick() },
            onSelectTrack = { viewModel.selectTrack(it); haptics.tick() },
            onSubtitlesOff = { viewModel.disableSubtitles(); haptics.tick() },
            onSetViewed = { wanted ->
                viewModel.setViewed(wanted)
                haptics.confirm()
                optionsOpen = false
            },
            onOpenExternally = {
                val launched = viewModel.openExternally(context)
                externalFailed = !launched
                if (launched) optionsOpen = false
            },
            onEnterPip = {
                optionsOpen = false
                pip?.enter(stageAspect)
            },
            onEditTags = { optionsOpen = false; tagsOpen = true },
        )
    }

    if (tagsOpen) {
        TagEditorSheet(
            title = ui.title,
            caption = ui.caption,
            tags = tags,
            onAdd = viewModel::addTag,
            onRemove = viewModel::removeTag,
            suggest = viewModel::suggestTags,
            onDismiss = { tagsOpen = false },
        )
    }
}

/** Why "open in another app" is or is not available right now. */
private fun externalLabel(state: ExternalOpen.State, failed: Boolean): String = when {
    failed -> "No app on this device opens that file"
    state is ExternalOpen.State.Ready -> "Choose a player"
    // Telegram serves this file in ranges, so a URI over the sparse copy opens in the
    // other player and then fails partway through — which reads as HardPlay's bug.
    state is ExternalOpen.State.Partial ->
        "Only ${(state.fraction * 100).toInt()}% downloaded — play it through first"
    else -> "Not downloaded yet — play it through first"
}

// --------------------------------------------------------------------- stages

/**
 * The video surface.
 *
 * `TextureView`, not `SurfaceView`, and that is a real trade. `SurfaceView` is the
 * better choice for HDR — it can hand a 10-bit buffer straight to the display
 * pipeline — but it lives in its own window layer, so it does not transform reliably
 * with its Compose parent. Pinch-to-zoom and pan are named requirements (PRD §6.2),
 * and a gesture that silently does nothing is a worse defect than HDR being
 * tone-mapped, so the transformable view wins.
 */
@Composable
private fun VideoStage(
    player: ExoPlayer,
    aspect: Float,
    zoom: ZoomPanState,
    onTextureView: (TextureView) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        // Which axis to fill has to be chosen. `fillMaxWidth().aspectRatio()` derives
        // the height from the width, so anything taller than the viewport overflows and
        // a vertical clip is cropped top and bottom with no way to reach the rest.
        val viewportAspect = if (maxHeight.value > 0f) maxWidth / maxHeight else aspect
        val fitted = if (aspect >= viewportAspect) {
            Modifier.fillMaxWidth().aspectRatio(aspect)
        } else {
            Modifier.fillMaxHeight().aspectRatio(aspect)
        }

        AndroidView(
            factory = { context -> TextureView(context).also(onTextureView) },
            update = { view -> player.setVideoTextureView(view) },
            modifier = fitted.zoomPan(zoom),
        )
    }
}

/**
 * Every pointer event for the stage, in one place.
 *
 * One layer, because it has to be. The tap detector consumes the initial down, and a
 * transform detector that sees a consumed change abandons the gesture — so a tap layer
 * drawn *over* the stage (which is what this was) meant the surface below never
 * received a pointer at all and pinch-to-zoom silently did nothing. Both detectors
 * live on the same node here, with the transform declared second so it is the inner
 * node and runs first.
 */
@Composable
private fun StageGestures(
    zoom: ZoomPanState,
    isPhoto: Boolean,
    skipSeconds: Int,
    onToggleChrome: () -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var ripple by remember { mutableStateOf<SkipRipple?>(null) }
    LaunchedEffect(ripple) {
        if (ripple != null) {
            delay(520)
            ripple = null
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .pointerInput(isPhoto) {
                detectTapGestures(
                    onTap = { onToggleChrome() },
                    onDoubleTap = { position ->
                        if (isPhoto) {
                            scope.launch { zoom.toggleZoom(position) }
                            return@detectTapGestures
                        }
                        // Left and right thirds only. A full-width double-tap would
                        // make every second tap on the centre a skip, which is where
                        // the play button is.
                        val third = size.width / 3f
                        when {
                            position.x < third -> {
                                ripple = SkipRipple(forward = false)
                                onSkipBack()
                            }
                            position.x > third * 2 -> {
                                ripple = SkipRipple(forward = true)
                                onSkipForward()
                            }
                            else -> scope.launch { zoom.toggleZoom(position) }
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                // panZoomLock: a two-finger twist on a video is almost always an
                // imprecise pinch, and rotating the frame is not an offered feature.
                detectTransformGestures(panZoomLock = true) { centroid, pan, gestureZoom, _ ->
                    zoom.onTransform(centroid, pan, gestureZoom)
                }
            },
    ) {
        val current = ripple
        if (current != null) {
            SkipFeedback(
                seconds = skipSeconds,
                forward = current.forward,
                modifier = Modifier
                    .align(if (current.forward) Alignment.CenterEnd else Alignment.CenterStart),
            )
        }
    }
}

private data class SkipRipple(val forward: Boolean)

/**
 * `10s` with an ember rule, fading as it goes. No stock ripple.
 *
 * An [Animatable] driven from a `LaunchedEffect` rather than `animateFloatAsState`:
 * this composable appears already at its start value and animates once toward zero,
 * which a target-based animation cannot express — it would have nothing to animate
 * from.
 */
@Composable
private fun SkipFeedback(seconds: Int, forward: Boolean, modifier: Modifier = Modifier) {
    val colors = HardPlayTheme.colors
    val fade = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        fade.animateTo(0f, tween(durationMillis = 500, easing = Motion.EmberOut))
    }
    Column(
        modifier
            .padding(horizontal = Space.xxl)
            .graphicsLayer { alpha = fade.value },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = if (forward) "+$seconds" else "−$seconds",
            style = HardPlayTheme.type.display,
            color = colors.type,
        )
        Box(
            Modifier
                .width(24.dp)
                .height(2.dp)
                .background(colors.emberGradient),
        )
    }
}

// --------------------------------------------------------------------- chrome

@Composable
private fun Chrome(
    ui: PlayerUiState,
    tags: List<TagEntity>,
    saved: Boolean,
    fullscreen: Boolean,
    onBack: () -> Unit,
    onToggleSaved: () -> Unit,
    onTogglePlay: () -> Unit,
    onScrub: (Float) -> Unit,
    onScrubEnd: () -> Unit,
    onFullscreenToggle: () -> Unit,
    onTagsToggle: () -> Unit,
    onOptions: () -> Unit,
    onRetry: () -> Unit,
) {
    val colors = HardPlayTheme.colors

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding(),
    ) {
        TopRow(
            ui = ui,
            tags = tags,
            saved = saved,
            onBack = onBack,
            onToggleSaved = onToggleSaved,
            onTagsToggle = onTagsToggle,
            // A photo has no bottom bar to hang controls off, so its options button
            // belongs up here rather than nowhere.
            trailing = {
                if (ui.isPhoto) {
                    GhostIconButton(
                        icon = Icons.Rounded.MoreVert,
                        contentDescription = "Options",
                        onClick = onOptions,
                    )
                }
            },
        )

        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            if (ui.error != null) {
                Column(
                    Modifier.padding(horizontal = Space.xxl),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Space.lg),
                ) {
                    Notice(text = ui.error, emphasis = true)
                    EmberButton(text = "Retry", icon = Icons.Rounded.Replay, onClick = onRetry)
                }
            } else if (!ui.isPhoto) {
                // Centre transport. Large tap target, no container.
                GhostIconButton(
                    icon = if (ui.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (ui.playing) "Pause" else "Play",
                    onClick = onTogglePlay,
                    size = 44.dp,
                    modifier = Modifier.size(76.dp),
                )
            }
        }

        if (ui.isPhoto) {
            // A still is a first-class library item, so it keeps its caption and its
            // tags. Suppressing them — which this screen used to do for anything that
            // was not a video — made half the library feel like a dead end.
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(colors.posterScrim)
                    .padding(horizontal = Space.gutter),
            ) {
                CaptionPanel(caption = ui.caption, tags = tags, onTagsToggle = onTagsToggle)
            }
        } else {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(colors.posterScrim)
                    .padding(horizontal = Space.gutter, vertical = Space.sm),
            ) {
                PlayerScrubber(
                    progress = ui.progress,
                    bufferedProgress = ui.bufferedProgress,
                    onScrub = onScrub,
                    onScrubEnd = onScrubEnd,
                    enabled = ui.durationMs > 0,
                )

                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = Format.position(ui.displayPositionMs, ui.durationMs),
                        style = HardPlayTheme.type.timecode,
                        color = colors.type,
                    )
                    Text(
                        text = " / ${Format.position(ui.durationMs, ui.durationMs)}",
                        style = HardPlayTheme.type.timecode,
                        color = colors.muted,
                    )
                    Box(Modifier.weight(1f))
                    // The speed reads back as text rather than as a lit-up icon: it is
                    // a value, and a value is worth stating.
                    if (ui.speed != 1f) {
                        Text(
                            text = Format.speed(ui.speed),
                            style = HardPlayTheme.type.timecodeSmall,
                            color = colors.accent,
                            modifier = Modifier.padding(end = Space.xs),
                        )
                    }
                    GhostIconButton(
                        icon = Icons.Rounded.MoreVert,
                        contentDescription = "Options",
                        onClick = onOptions,
                    )
                    GhostIconButton(
                        icon = if (fullscreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                        contentDescription = if (fullscreen) "Exit fullscreen" else "Fullscreen",
                        onClick = onFullscreenToggle,
                    )
                }

                // Caption and tags, collapsed away in fullscreen where the point is
                // the picture.
                if (!fullscreen) {
                    CaptionPanel(caption = ui.caption, tags = tags, onTagsToggle = onTagsToggle)
                }
            }
        }
    }
}

@Composable
private fun TopRow(
    ui: PlayerUiState,
    tags: List<TagEntity>,
    saved: Boolean,
    onBack: () -> Unit,
    onToggleSaved: () -> Unit,
    onTagsToggle: () -> Unit,
    trailing: @Composable () -> Unit,
) {
    val colors = HardPlayTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.topScrim)
            .padding(horizontal = Space.sm, vertical = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GhostIconButton(
            icon = Icons.AutoMirrored.Rounded.ArrowBack,
            contentDescription = "Back",
            onClick = onBack,
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = ui.title,
                style = HardPlayTheme.type.title,
                color = colors.type,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                Format.resolution(ui.width, ui.height)?.let {
                    MetaChip(it, emphasised = it == "4K")
                }
                MetaChip(Format.bytes(ui.sizeBytes))
            }
        }
        GhostIconButton(
            icon = if (saved) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
            contentDescription = if (saved) "Remove from saved" else "Save",
            onClick = onToggleSaved,
            tint = if (saved) colors.accent else colors.type,
        )
        GhostIconButton(
            icon = Icons.Rounded.LocalOffer,
            contentDescription = "Tags",
            onClick = onTagsToggle,
            tint = if (tags.isEmpty()) colors.muted else colors.accent,
        )
        trailing()
    }
}

/** "Resumed from 12:41" — the ember rule, then the timecode. Said once. */
@Composable
private fun ResumedNotice(positionMs: Long) {
    val colors = HardPlayTheme.colors
    Row(
        Modifier
            .safeDrawingPadding()
            .padding(top = RESUME_NOTICE_TOP_INSET)
            .background(colors.surface.copy(alpha = 0.92f), HardPlayTheme.shapes.chip)
            .border(Space.hairline, colors.hairline, HardPlayTheme.shapes.chip)
            .padding(horizontal = Space.md, vertical = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Box(
            Modifier
                .width(2.dp)
                .height(12.dp)
                .background(colors.emberGradientVertical),
        )
        Text(
            text = "Resumed from ${Format.durationMs(positionMs)}",
            style = HardPlayTheme.type.timecodeSmall,
            color = colors.typeDim,
        )
    }
}

@Composable
private fun CaptionPanel(
    caption: String,
    tags: List<TagEntity>,
    onTagsToggle: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val colors = HardPlayTheme.colors

    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = Space.sm, bottom = Space.md),
        verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        if (caption.isNotBlank()) {
            Text(
                text = caption,
                style = HardPlayTheme.type.bodySmall,
                color = colors.typeDim,
                maxLines = if (expanded) 12 else 2,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (expanded) {
                            Modifier
                                .height(120.dp)
                                .verticalScroll(rememberScrollState())
                        } else {
                            Modifier
                        },
                    )
                    .pointerInput(Unit) {
                        detectTapGestures { expanded = !expanded }
                    },
            )
        }
        if (tags.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                tags.take(4).forEach { tag ->
                    TagChip(label = tag.name, onClick = onTagsToggle)
                }
                if (tags.size > 4) {
                    TagChip(label = "+${tags.size - 4}", onClick = onTagsToggle)
                }
            }
        }
    }
}

// -------------------------------------------------------------- window effects

/** Hides the system bars while fullscreen, and puts them back on the way out. */
@Composable
private fun ImmersiveMode(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(enabled, view) {
        val window = (view.context as? Activity)?.window ?: return@DisposableEffect onDispose {}
        val controller = WindowCompat.getInsetsController(window, view)
        if (enabled) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

/**
 * Orientation lock for the fullscreen toggle (PRD §6.2 §4).
 *
 * Restores `UNSPECIFIED` rather than a remembered previous value: the activity is
 * declared unspecified in the manifest, so that *is* the previous value, and reading
 * it back can return a resolved concrete orientation instead of the declared one.
 */
@Composable
private fun OrientationLock(landscape: Boolean) {
    val context = LocalContext.current
    DisposableEffect(landscape) {
        val activity = context as? Activity ?: return@DisposableEffect onDispose {}
        activity.requestedOrientation = if (landscape) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        onDispose {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
}

/** Keeps the display on while playing, and releases it the moment playback stops. */
@Composable
private fun KeepScreenOn(active: Boolean) {
    val view = LocalView.current
    DisposableEffect(active, view) {
        view.keepScreenOn = active
        onDispose { view.keepScreenOn = false }
    }
}

private const val MAX_ZOOM = 6f
private const val RESUME_NOTICE_MS = 3_600L

/** One compositor pass after the decoder's first frame; `getBitmap` is null before it. */
private const val FRAME_CAPTURE_DELAY_MS = 400L

/** Clear of the top row, so the notice reads as a second line rather than a collision. */
private val RESUME_NOTICE_TOP_INSET = 68.dp
