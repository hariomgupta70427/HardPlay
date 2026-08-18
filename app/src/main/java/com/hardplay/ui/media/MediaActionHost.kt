package com.hardplay.ui.media

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.LocalOffer
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.hardplay.core.Format
import com.hardplay.data.db.projection.LibraryRow
import com.hardplay.playback.ExternalOpen
import com.hardplay.ui.components.Hairline
import com.hardplay.ui.components.SheetHandle
import com.hardplay.ui.image.PosterSource
import com.hardplay.ui.player.TagEditorSheet
import com.hardplay.ui.theme.HardPlayTheme
import com.hardplay.ui.theme.Space

/**
 * Draws whichever per-item sheet is open.
 *
 * A single composable so a screen adds the whole feature in one line and cannot wire
 * half of it. Renders nothing at all when no item is selected, so it costs a screen
 * that never opens the menu precisely nothing.
 *
 * @param onOpenItem the sheet's own "Play" row. Passed in rather than navigated from
 *   here because navigation belongs to the screen that owns the back stack.
 */
@Composable
fun MediaActionHost(
    viewModel: MediaActionsViewModel,
    onOpenItem: (Long) -> Unit,
) {
    val mode by viewModel.mode.collectAsStateWithLifecycle()
    val row by viewModel.row.collectAsStateWithLifecycle()
    val tags by viewModel.itemTags.collectAsStateWithLifecycle()
    val external by viewModel.external.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()

    val current = row ?: return

    when (mode) {
        MediaActionsViewModel.Mode.NONE -> Unit

        MediaActionsViewModel.Mode.ACTIONS -> MediaActionSheet(
            row = current,
            tagCount = tags.size,
            external = external,
            notice = notice,
            onDismiss = viewModel::dismiss,
            onPlay = {
                viewModel.dismiss()
                onOpenItem(current.localId)
            },
            onToggleSaved = viewModel::toggleSaved,
            onSetViewed = viewModel::setViewed,
            onEditTags = viewModel::editTags,
            onCopyCaption = viewModel::copyCaption,
            onOpenExternally = viewModel::openExternally,
        )

        MediaActionsViewModel.Mode.TAGS -> TagEditorSheet(
            title = current.title,
            caption = current.caption,
            tags = tags,
            onAdd = viewModel::addTag,
            onRemove = viewModel::removeTag,
            suggest = viewModel::suggestTags,
            // Back to the actions rather than closing outright: the editor was reached
            // from that sheet, and dropping the user on the grid loses their place.
            onDismiss = viewModel::backToActions,
        )
    }
}

/**
 * The per-card overflow menu.
 *
 * A bottom sheet, not a `DropdownMenu`. A menu anchored to a grid cell fights the
 * scroll it is attached to, has nowhere to go when the cell is near an edge, and
 * arrives in Material's own container styling — while a sheet has room to show *what*
 * is being acted on, which is what stops "Mark as watched" being a guess about which
 * of forty cells was tapped.
 */
@Composable
private fun MediaActionSheet(
    row: LibraryRow,
    tagCount: Int,
    external: ExternalOpen.State,
    notice: String?,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    onToggleSaved: () -> Unit,
    onSetViewed: (Boolean) -> Unit,
    onEditTags: () -> Unit,
    onCopyCaption: () -> Unit,
    onOpenExternally: (android.content.Context) -> Unit,
) {
    val colors = HardPlayTheme.colors
    val context = LocalContext.current
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
                .navigationBarsPadding(),
        ) {
            ItemHeader(row)

            if (notice != null) {
                Text(
                    text = notice,
                    style = HardPlayTheme.type.labelSmall,
                    color = colors.accent,
                    modifier = Modifier.padding(
                        start = Space.gutter,
                        end = Space.gutter,
                        bottom = Space.sm,
                    ),
                )
            }

            Hairline(inset = true)

            ActionRow(
                icon = Icons.Rounded.PlayArrow,
                title = if (row.resumeFraction > 0f) "Resume" else "Play",
                subtitle = if (row.resumeFraction > 0f) {
                    "From ${Format.durationMs(row.positionMs ?: 0L)}"
                } else {
                    null
                },
                onClick = onPlay,
            )

            ActionRow(
                icon = if (row.isFavourite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                title = if (row.isFavourite) "Remove from saved" else "Save for later",
                tint = if (row.isFavourite) colors.accent else null,
                onClick = onToggleSaved,
            )

            ActionRow(
                icon = if (row.unseen) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                title = if (row.unseen) "Mark as watched" else "Mark as unwatched",
                subtitle = if (row.unseen) null else "Clears the resume position",
                onClick = { onSetViewed(row.unseen) },
            )

            ActionRow(
                icon = Icons.Rounded.LocalOffer,
                title = "Edit tags",
                subtitle = when (tagCount) {
                    0 -> "None yet"
                    1 -> "1 tag"
                    else -> "$tagCount tags"
                },
                tint = if (tagCount > 0) colors.accent else null,
                onClick = onEditTags,
            )

            // Honest about its own precondition. Telegram serves this file in ranges,
            // so until the whole thing is on disk there is no file to hand over —
            // offering the action anyway would produce another player opening a sparse
            // file and failing halfway through, which reads as HardPlay's bug.
            ActionRow(
                icon = Icons.AutoMirrored.Rounded.OpenInNew,
                title = "Open in another app",
                subtitle = when (external) {
                    is ExternalOpen.State.Ready -> "Choose a player"
                    is ExternalOpen.State.Partial ->
                        "Only ${(external.fraction * 100).toInt()}% downloaded — play it through first"
                    ExternalOpen.State.Absent -> "Not downloaded yet — play it first"
                },
                enabled = external is ExternalOpen.State.Ready,
                onClick = { onOpenExternally(context) },
            )

            ActionRow(
                icon = Icons.Rounded.ContentCopy,
                title = "Copy caption",
                enabled = row.caption.isNotBlank(),
                onClick = onCopyCaption,
            )

            Box(Modifier.height(Space.lg))
        }
    }
}

/**
 * What is being acted on.
 *
 * The artwork is here for orientation, not decoration: a menu of six verbs with no
 * subject is a menu you have to trust you tapped the right cell for.
 */
@Composable
private fun ItemHeader(row: LibraryRow) {
    val colors = HardPlayTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = Space.gutter, end = Space.gutter, bottom = Space.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        Box(
            Modifier
                .width(72.dp)
                .aspectRatio(16f / 9f)
                .clip(HardPlayTheme.shapes.poster)
                .background(colors.surface),
        ) {
            AsyncImage(
                model = PosterSource.of(row).takeIf { !it.isEmpty },
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = row.title,
                style = HardPlayTheme.type.title,
                color = colors.type,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOfNotNull(
                    row.channelTitle.takeIf { it.isNotBlank() },
                    Format.duration(row.durationSeconds),
                    Format.bytes(row.fileSizeBytes).takeIf { it != "—" },
                ).joinToString("  ·  "),
                style = HardPlayTheme.type.labelSmall,
                color = colors.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun ActionRow(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    subtitle: String? = null,
    enabled: Boolean = true,
    tint: Color? = null,
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
            tint = tint ?: colors.typeDim,
            modifier = Modifier.size(19.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(text = title, style = HardPlayTheme.type.title, color = colors.type)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = HardPlayTheme.type.bodySmall,
                    color = colors.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
        }
    }
}
