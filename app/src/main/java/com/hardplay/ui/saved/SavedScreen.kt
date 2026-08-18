package com.hardplay.ui.saved

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import com.hardplay.ui.components.HardPlayTopBar
import com.hardplay.ui.components.MediaGrid
import com.hardplay.ui.components.MediaGridSkeleton
import com.hardplay.ui.components.ScreenHeader
import com.hardplay.ui.media.MediaActionHost
import com.hardplay.ui.media.MediaActionsViewModel
import com.hardplay.ui.theme.HardPlaySurface

/**
 * Saved items.
 *
 * The one list in the app the user assembled themselves, which is why it is a tab and
 * not a filter chip: a curated shelf that lives inside a filter sheet is a shelf
 * nobody remembers they have.
 */
@Composable
fun SavedScreen(
    onOpenItem: (Long) -> Unit,
    onBrowseLibrary: () -> Unit,
    modifier: Modifier = Modifier,
    sharedScope: SharedTransitionScope? = null,
    visibilityScope: AnimatedVisibilityScope? = null,
    viewModel: SavedViewModel = hiltViewModel(),
    actions: MediaActionsViewModel = hiltViewModel(),
) {
    val count by viewModel.count.collectAsStateWithLifecycle()
    val forcedColumns by viewModel.gridColumns.collectAsStateWithLifecycle()
    val cardAspect by viewModel.cardAspect.collectAsStateWithLifecycle()

    val items = viewModel.items.collectAsLazyPagingItems()
    val gridState = rememberLazyGridState()

    val scrolled by remember {
        derivedStateOf {
            gridState.firstVisibleItemIndex > 0 || gridState.firstVisibleItemScrollOffset > 8
        }
    }
    val empty = items.itemCount == 0

    HardPlaySurface(modifier = modifier.fillMaxSize(), bloom = true) {
        Column(Modifier.fillMaxSize()) {
            HardPlayTopBar(
                title = "Saved",
                overline = savedOverline(count),
                scrolled = scrolled,
                // The big heading lives in the grid and scrolls away, so the bar keeps
                // its title hidden until then — one title on screen at a time. With no
                // grid to hold a heading, the bar has to carry it.
                showTitle = scrolled || empty,
            )

            BoxWithConstraints(Modifier.weight(1f)) {
                val columns = forcedColumns.takeIf { it > 0 }
                    ?: cardAspect.columnsFor(maxWidth.value)

                when {
                    empty && items.loadState.refresh is LoadState.Loading ->
                        MediaGridSkeleton(columns, cardAspect.ratio)

                    empty -> SavedEmptyState(onBrowseLibrary = onBrowseLibrary)

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
                            ScreenHeader(
                                title = "Saved",
                                overline = savedOverline(count),
                                subtitle = "Kept by hand, newest first.",
                            )
                        },
                    )
                }
            }
        }
    }

    MediaActionHost(viewModel = actions, onOpenItem = onOpenItem)
}

private fun savedOverline(count: Int): String =
    if (count == 0) "Nothing kept" else "${Format.count(count)} kept"

/**
 * Says how to save something.
 *
 * An empty Saved tab is the one place where "no items" is genuinely the user not
 * knowing the feature exists, so naming both affordances is the useful thing to do
 * rather than restating that the list is empty.
 */
@Composable
private fun SavedEmptyState(onBrowseLibrary: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        EmptyState(
            overline = "Nothing saved",
            headline = "Nothing kept yet.",
            body = "Tap the heart while something is playing, or open a card's " +
                "three-dot menu and choose Save for later. It lands here, newest first.",
            action = { GhostButton(text = "Browse the library", onClick = onBrowseLibrary) },
        )
    }
}
