package com.hardplay.ui.history

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import com.hardplay.core.Format
import com.hardplay.ui.components.EmptyState
import com.hardplay.ui.components.GhostButton
import com.hardplay.ui.components.GhostIconButton
import com.hardplay.ui.components.HardPlayTopBar
import com.hardplay.ui.components.MediaGrid
import com.hardplay.ui.components.MediaGridSkeleton
import com.hardplay.ui.components.QuietButton
import com.hardplay.ui.components.ScreenHeader
import com.hardplay.ui.components.Shelf
import com.hardplay.ui.components.SheetHandle
import com.hardplay.ui.media.MediaActionHost
import com.hardplay.ui.media.MediaActionsViewModel
import com.hardplay.ui.theme.HardPlaySurface
import com.hardplay.ui.theme.HardPlayTheme
import com.hardplay.ui.theme.Space

/**
 * Watch history, with rewatched items shelved above it.
 *
 * The two lists answer different questions — "what did I open" and "what do I keep
 * coming back to" — which is why the shelf is not simply the same list sorted
 * differently.
 */
@Composable
fun HistoryScreen(
    onOpenItem: (Long) -> Unit,
    onBrowseLibrary: () -> Unit,
    modifier: Modifier = Modifier,
    sharedScope: SharedTransitionScope? = null,
    visibilityScope: AnimatedVisibilityScope? = null,
    viewModel: HistoryViewModel = hiltViewModel(),
    actions: MediaActionsViewModel = hiltViewModel(),
) {
    val count by viewModel.count.collectAsStateWithLifecycle()
    val mostWatched by viewModel.mostWatched.collectAsStateWithLifecycle()
    val forcedColumns by viewModel.gridColumns.collectAsStateWithLifecycle()
    val cardAspect by viewModel.cardAspect.collectAsStateWithLifecycle()

    val items = viewModel.items.collectAsLazyPagingItems()
    val gridState = rememberLazyGridState()
    var confirmingClear by remember { mutableStateOf(false) }

    val scrolled by remember {
        derivedStateOf {
            gridState.firstVisibleItemIndex > 0 || gridState.firstVisibleItemScrollOffset > 8
        }
    }
    val empty = items.itemCount == 0

    HardPlaySurface(modifier = modifier.fillMaxSize(), bloom = false) {
        Column(Modifier.fillMaxSize()) {
            HardPlayTopBar(
                title = "History",
                overline = historyOverline(count),
                scrolled = scrolled,
                showTitle = scrolled || empty,
                actions = {
                    // Only offered when there is something to clear. A permanently
                    // available destructive control on an empty list is noise.
                    if (!empty) {
                        GhostIconButton(
                            icon = Icons.Rounded.DeleteSweep,
                            contentDescription = "Clear watch history",
                            onClick = { confirmingClear = true },
                        )
                    }
                },
            )

            BoxWithConstraints(Modifier.weight(1f)) {
                val columns = forcedColumns.takeIf { it > 0 }
                    ?: cardAspect.columnsFor(maxWidth.value)

                when {
                    empty && items.loadState.refresh is LoadState.Loading ->
                        MediaGridSkeleton(columns, cardAspect.ratio)

                    empty -> HistoryEmptyState(onBrowseLibrary = onBrowseLibrary)

                    else -> MediaGrid(
                        items = items,
                        columns = columns,
                        aspect = cardAspect.ratio,
                        onOpen = onOpenItem,
                        onMenu = { row -> actions.open(row.localId) },
                        gridState = gridState,
                        sharedScope = sharedScope,
                        visibilityScope = visibilityScope,
                        topPadding = 0.dp,
                        header = {
                            Column {
                                ScreenHeader(
                                    title = "History",
                                    overline = historyOverline(count),
                                    subtitle = "Everything you have opened, most recent first.",
                                )
                                Shelf(
                                    key = "most-watched",
                                    title = "Watched more than once",
                                    overline = "Most watched",
                                    rows = mostWatched,
                                    onOpen = onOpenItem,
                                    onMenu = { row -> actions.open(row.localId) },
                                    // Inside the grid's own gutter, so the shelf must not
                                    // add another — a component indenting itself while the
                                    // cards below it sit at 16dp is exactly the drift the
                                    // shared component exists to prevent.
                                    gutter = 0.dp,
                                    cardWidth = 152.dp,
                                    // The shelf follows the user's card shape rather than
                                    // its own default, so a library set to 2:3 does not get
                                    // one stray row of 16:9 cards above the grid.
                                    aspect = cardAspect.ratio,
                                    modifier = Modifier.padding(bottom = Space.lg),
                                )
                            }
                        },
                    )
                }
            }
        }
    }

    if (confirmingClear) {
        ClearHistorySheet(
            itemCount = count,
            onConfirm = {
                viewModel.clearHistory()
                confirmingClear = false
            },
            onDismiss = { confirmingClear = false },
        )
    }

    MediaActionHost(viewModel = actions, onOpenItem = onOpenItem)
}

private fun historyOverline(count: Int): String =
    if (count == 0) "Nothing watched" else "${Format.count(count)} watched"

@Composable
private fun HistoryEmptyState(onBrowseLibrary: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        EmptyState(
            overline = "Nothing watched",
            headline = "No history yet.",
            body = "Open anything and it appears here, with where you left off " +
                "and how often you have been back.",
            action = { GhostButton(text = "Browse the library", onClick = onBrowseLibrary) },
        )
    }
}

/**
 * Confirmation for clearing history.
 *
 * A sheet rather than an `AlertDialog`, which arrives in Material's container styling
 * on an otherwise custom app. It states what is actually lost — one table feeds
 * history, the resume positions, the unseen markers and the most-watched shelf, so
 * "clear history" quietly empties four things — because a confirmation that only says
 * "are you sure?" is a confirmation nobody reads.
 *
 * Screen-wide rather than per-row, which is why it is a sheet at all: there is no one
 * item to point at. The source manager's removal confirms inline on its row instead,
 * because there being no doubt about *which* row is about to be destroyed matters more
 * there than the modality does.
 */
@Composable
private fun ClearHistorySheet(
    itemCount: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
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
                // A ModalBottomSheet is its own window, so unlike content sitting in
                // the screen's Column this one does own the navigation-bar inset.
                .navigationBarsPadding()
                .padding(horizontal = Space.gutter),
        ) {
            Text(
                text = "CLEAR HISTORY",
                style = HardPlayTheme.type.overline,
                color = colors.accent,
            )
            Box(Modifier.height(Space.xs))
            Text(
                text = "Forget what you have watched?",
                style = HardPlayTheme.type.headline,
                color = colors.type,
            )
            Box(Modifier.height(Space.sm))
            Text(
                text = "This drops the resume position and play count for " +
                    "${Format.count(itemCount)} items, so the continue-watching shelf " +
                    "and Most watched empty with it and everything reads as unseen " +
                    "again. The library, your tags and your saved items are untouched.",
                style = HardPlayTheme.type.body,
                color = colors.muted,
            )
            Box(Modifier.height(Space.xl))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.sm),
            ) {
                // The destructive action sits far from the thumb's resting place and the
                // safe one is first, so a reflexive tap keeps the history.
                QuietButton(text = "Keep it", onClick = onDismiss)
                Box(Modifier.weight(1f))
                GhostButton(
                    text = "Clear everything",
                    onClick = onConfirm,
                    destructive = true,
                    small = true,
                )
            }
            Box(Modifier.height(Space.xxl))
        }
    }
}
