package com.hardplay.ui.components

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemKey
import com.hardplay.core.Format
import com.hardplay.data.db.projection.LibraryRow
import com.hardplay.ui.image.PosterSource
import com.hardplay.ui.nav.sharedPosterModifier
import com.hardplay.ui.theme.Space

/**
 * The media grid, once.
 *
 * Library, Saved, History and search results are all the same object in the same
 * shape, and they were on their way to being four grids with four sets of padding.
 * They share this instead, which means the single-column treatment, the skeletons and
 * the shared-element hand-off are implemented once and cannot drift between tabs.
 *
 * @param columns 1 turns each cell into a *list row*: full-width art, two title lines
 *   and a real metadata line. At two or more columns that same text wraps to mush, so
 *   this is a genuine layout switch rather than a width change.
 * @param onMenu when non-null, every cell carries the three-dot overflow control.
 * @param header spans the full width above the first row — a hero, a shelf, a notice.
 */
@Composable
fun MediaGrid(
    items: LazyPagingItems<LibraryRow>,
    columns: Int,
    aspect: Float,
    onOpen: (Long) -> Unit,
    modifier: Modifier = Modifier,
    onMenu: ((LibraryRow) -> Unit)? = null,
    gridState: LazyGridState = rememberLazyGridState(),
    sharedScope: SharedTransitionScope? = null,
    visibilityScope: AnimatedVisibilityScope? = null,
    topPadding: Dp = Space.sm,
    bottomPadding: Dp = Space.xxxl,
    header: (@Composable () -> Unit)? = null,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns.coerceAtLeast(1)),
        state = gridState,
        contentPadding = PaddingValues(
            start = Space.gutter,
            end = Space.gutter,
            top = topPadding,
            bottom = bottomPadding,
        ),
        horizontalArrangement = Arrangement.spacedBy(Space.gridGap),
        verticalArrangement = Arrangement.spacedBy(Space.lg),
        modifier = modifier.fillMaxSize(),
    ) {
        if (header != null) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "grid-header") { header() }
        }

        items(
            count = items.itemCount,
            key = items.itemKey { it.localId },
        ) { index ->
            val row = items[index]
            if (row == null) {
                // Placeholders are enabled, so an unloaded cell is a null — drawn as
                // the same skeleton the initial load uses, which is what keeps the
                // grid from ending at a hard edge mid-flick. The line count has to
                // match the real cell's, or the grid visibly reflows by a line's
                // height the moment a page lands.
                PosterSkeleton(aspect = aspect, titleLines = if (columns == 1) 2 else 1)
            } else {
                MediaCell(
                    row = row,
                    aspect = aspect,
                    singleColumn = columns == 1,
                    onOpen = onOpen,
                    onMenu = onMenu,
                    sharedScope = sharedScope,
                    visibilityScope = visibilityScope,
                )
            }
        }
    }
}

@Composable
private fun MediaCell(
    row: LibraryRow,
    aspect: Float,
    singleColumn: Boolean,
    onOpen: (Long) -> Unit,
    onMenu: ((LibraryRow) -> Unit)?,
    sharedScope: SharedTransitionScope?,
    visibilityScope: AnimatedVisibilityScope?,
) {
    PosterCard(
        title = row.title,
        onClick = { onOpen(row.localId) },
        aspect = aspect,
        saved = row.isFavourite,
        titleLines = if (singleColumn) 2 else 1,
        thumbnail = PosterSource.of(row).takeIf { !it.isEmpty },
        durationLabel = Format.duration(row.durationSeconds),
        sourceLabel = if (singleColumn) rowMetaLine(row) else row.channelTitle,
        resumeFraction = row.resumeFraction,
        unseen = row.unseen,
        onMenu = onMenu?.let { menu -> { menu(row) } },
        // Only the art travels. Letting the title ride along would stretch type
        // during the transition, which looks like a rendering fault.
        artModifier = sharedPosterModifier(sharedScope, visibilityScope, row.localId),
    )
}

/** A grid of shimmer placeholders, for the initial load. */
@Composable
fun MediaGridSkeleton(
    columns: Int,
    aspect: Float,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns.coerceAtLeast(1)),
        contentPadding = PaddingValues(
            start = Space.gutter,
            end = Space.gutter,
            top = Space.sm,
            bottom = Space.xxxl,
        ),
        horizontalArrangement = Arrangement.spacedBy(Space.gridGap),
        verticalArrangement = Arrangement.spacedBy(Space.lg),
        modifier = modifier.fillMaxSize(),
        userScrollEnabled = false,
    ) {
        items(count = (columns.coerceAtLeast(1) * 4).coerceAtMost(12)) {
            PosterSkeleton(aspect = aspect, titleLines = if (columns == 1) 2 else 1)
        }
    }
}

/**
 * The metadata line under a single-column card.
 *
 * Channel, duration, size and age on one middle-dot-separated line — the shape a video
 * list has settled on everywhere, and readable at a glance without a second row of
 * chips. Empty parts are dropped rather than left as stray separators.
 */
fun rowMetaLine(row: LibraryRow): String = listOfNotNull(
    row.channelTitle.takeIf { it.isNotBlank() },
    Format.duration(row.durationSeconds),
    Format.bytes(row.fileSizeBytes).takeIf { it != "—" },
    Format.relativeDate(row.date).takeIf { it != "—" },
).joinToString("  ·  ")
