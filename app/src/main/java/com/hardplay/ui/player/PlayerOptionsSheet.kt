package com.hardplay.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.LocalOffer
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hardplay.core.Format
import com.hardplay.data.prefs.SettingsStore
import com.hardplay.ui.components.Hairline
import com.hardplay.ui.components.SheetHandle
import com.hardplay.ui.components.SpeedChip
import com.hardplay.ui.theme.HardPlayTheme
import com.hardplay.ui.theme.Space

/**
 * Everything the player can do that is not transport.
 *
 * One sheet rather than six icons along the bottom edge. The chrome sits over the
 * picture, and every control added to it is a control covering the thing being
 * watched — so the bar keeps play, time and the scrubber, and the rest lives one tap
 * away where it has room to explain itself.
 *
 * Notably absent: a quality menu. A Telegram video is a single file at a single
 * resolution — there is no adaptive ladder to switch between — so the sheet states
 * the resolution as a fact instead of offering a control that could not change it.
 * Every video player has that menu, which is exactly why leaving it out has to be
 * deliberate and said out loud.
 */
@Composable
fun PlayerOptionsSheet(
    ui: PlayerUiState,
    tracks: PlayerTrackState,
    viewed: Boolean,
    externalLabel: String,
    externalEnabled: Boolean,
    pipSupported: Boolean,
    onDismiss: () -> Unit,
    onSpeedSelect: (Float) -> Unit,
    onSelectTrack: (PlayerTrack) -> Unit,
    onSubtitlesOff: () -> Unit,
    onSetViewed: (Boolean) -> Unit,
    onOpenExternally: () -> Unit,
    onEnterPip: () -> Unit,
    onEditTags: () -> Unit,
) {
    val colors = HardPlayTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.bgRaised,
        contentColor = colors.type,
        scrimColor = colors.scrim,
        shape = HardPlayTheme.shapes.sheet,
        dragHandle = { SheetHandle() },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                // Bounded and scrollable: a file with eight subtitle tracks would
                // otherwise push the actions off the bottom of the screen.
                .heightIn(max = 620.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            SheetTitle(title = ui.title)

            if (!ui.isPhoto) {
                SheetSection("Speed") {
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                        SettingsStore.SPEED_CHOICES.forEach { speed ->
                            SpeedChip(
                                speed = speed,
                                selected = ui.speed == speed,
                                onClick = { onSpeedSelect(speed) },
                            )
                        }
                    }
                }

                AudioSection(tracks = tracks, onSelectTrack = onSelectTrack)
                SubtitleSection(
                    tracks = tracks,
                    onSelectTrack = onSelectTrack,
                    onSubtitlesOff = onSubtitlesOff,
                )
            }

            SheetSection(if (ui.isPhoto) "This image" else "This file") {
                Text(
                    text = fileSummary(ui),
                    style = HardPlayTheme.type.bodySmall,
                    color = colors.muted,
                )
            }

            Hairline(inset = true)

            SheetActionRow(
                icon = if (viewed) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                title = if (viewed) "Mark as unwatched" else "Mark as watched",
                subtitle = if (viewed) "Clears the resume position" else null,
                onClick = { onSetViewed(!viewed) },
            )

            SheetActionRow(
                icon = Icons.Rounded.LocalOffer,
                title = "Edit tags",
                onClick = onEditTags,
            )

            SheetActionRow(
                icon = Icons.AutoMirrored.Rounded.OpenInNew,
                title = "Open in another app",
                subtitle = externalLabel,
                enabled = externalEnabled,
                onClick = onOpenExternally,
            )

            if (pipSupported && !ui.isPhoto) {
                SheetActionRow(
                    icon = Icons.Rounded.PictureInPictureAlt,
                    title = "Picture-in-picture",
                    subtitle = "Keep playing in a floating window",
                    onClick = onEnterPip,
                )
            }

            Box(Modifier.height(Space.lg))
        }
    }
}

/**
 * The honest answer to "where is the quality menu".
 *
 * Says what the file *is* — one rendition at one resolution, as Telegram stores it —
 * rather than implying a choice that does not exist.
 */
private fun fileSummary(ui: PlayerUiState): String {
    val parts = buildList {
        // The exact figures first, and the tier only when the exact ones are missing —
        // "1920×1080 · 1080p" says one thing twice.
        val exact = ui.renditionLabel
        if (exact != null) add(exact) else Format.resolution(ui.width, ui.height)?.let { add(it) }
        Format.bytes(ui.sizeBytes).takeIf { it != "—" }?.let { add(it) }
    }
    val head = parts.joinToString("  ·  ").ifBlank { "Size unknown" }
    return if (ui.isPhoto) {
        head
    } else {
        "$head\nTelegram stores one rendition per video, so there is no quality to switch."
    }
}

@Composable
private fun AudioSection(
    tracks: PlayerTrackState,
    onSelectTrack: (PlayerTrack) -> Unit,
) {
    val colors = HardPlayTheme.colors
    when {
        tracks.audio.isEmpty() -> Unit

        // A menu of one is a menu that cannot be used. Stating the fact is more
        // useful, and it tells the reader the app did look.
        !tracks.hasAudioChoice -> SheetSection("Audio") {
            val only = tracks.audio.first()
            Text(
                text = listOfNotNull("One audio track", only.detail).joinToString("  ·  "),
                style = HardPlayTheme.type.bodySmall,
                color = colors.muted,
            )
        }

        else -> SheetSection("Audio", padded = false) {
            tracks.audio.forEach { track ->
                SelectableRow(
                    label = track.label,
                    detail = track.detail,
                    selected = track.selected,
                    onClick = { onSelectTrack(track) },
                )
            }
        }
    }
}

@Composable
private fun SubtitleSection(
    tracks: PlayerTrackState,
    onSelectTrack: (PlayerTrack) -> Unit,
    onSubtitlesOff: () -> Unit,
) {
    if (!tracks.hasSubtitles) return
    SheetSection("Subtitles", padded = false) {
        SelectableRow(
            label = "Off",
            detail = null,
            selected = tracks.subtitlesOff,
            onClick = onSubtitlesOff,
        )
        tracks.subtitles.forEach { track ->
            SelectableRow(
                label = track.label,
                detail = track.detail,
                selected = track.selected,
                onClick = { onSelectTrack(track) },
            )
        }
    }
}

/**
 * A choice in a list of choices.
 *
 * The ember bar carries the selection, matching the sort sheet. A radio button would
 * be the one piece of stock Material on an otherwise custom surface, which is exactly
 * the tell this design system exists to avoid.
 */
@Composable
private fun SelectableRow(
    label: String,
    detail: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = HardPlayTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Space.gutter, vertical = Space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(2.dp)
                .height(18.dp)
                .background(
                    if (selected) colors.emberGradientVertical else SolidColor(colors.hairline),
                ),
        )
        Box(Modifier.width(Space.md))
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = HardPlayTheme.type.title,
                color = if (selected) colors.type else colors.typeDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (detail != null) {
                Text(
                    text = detail,
                    style = HardPlayTheme.type.labelSmall,
                    color = colors.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (selected) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun SheetActionRow(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    val colors = HardPlayTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.38f)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = Space.gutter, vertical = Space.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.typeDim,
            modifier = Modifier.size(19.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(text = title, style = HardPlayTheme.type.title, color = colors.type)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = HardPlayTheme.type.bodySmall,
                    color = colors.muted,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
        }
    }
}

@Composable
private fun SheetTitle(title: String) {
    val colors = HardPlayTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = Space.gutter, end = Space.gutter, bottom = Space.sm),
    ) {
        Text(text = "OPTIONS", style = HardPlayTheme.type.overline, color = colors.accent)
        Box(Modifier.height(4.dp))
        Text(
            text = title,
            style = HardPlayTheme.type.headline,
            color = colors.type,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * @param padded false for sections whose content is full-bleed rows, which supply
 *   their own horizontal inset and would otherwise be indented twice.
 */
@Composable
private fun SheetSection(
    title: String,
    padded: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colors = HardPlayTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = Space.md, bottom = Space.sm),
        verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Text(
            text = title.uppercase(),
            style = HardPlayTheme.type.overline,
            color = colors.muted,
            modifier = Modifier.padding(horizontal = Space.gutter),
        )
        if (padded) {
            Box(Modifier.padding(horizontal = Space.gutter)) { content() }
        } else {
            content()
        }
    }
}
