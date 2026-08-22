package com.hardplay.ui.discover

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import com.hardplay.core.Format
import com.hardplay.data.db.projection.LibraryRow
import com.hardplay.data.db.projection.TagFacet
import com.hardplay.data.model.TypeFilter
import com.hardplay.ui.components.EmptyState
import com.hardplay.ui.components.GhostIconButton
import com.hardplay.ui.components.HardPlayTextField
import com.hardplay.ui.components.HardPlayTopBar
import com.hardplay.ui.components.MediaGrid
import com.hardplay.ui.components.MediaGridSkeleton
import com.hardplay.ui.components.ScreenHeader
import com.hardplay.ui.components.SearchGlyph
import com.hardplay.ui.components.Shelf
import com.hardplay.ui.components.TagChip
import com.hardplay.ui.media.MediaActionHost
import com.hardplay.ui.media.MediaActionsViewModel
import com.hardplay.ui.theme.HardPlaySurface
import com.hardplay.ui.theme.HardPlayTheme
import com.hardplay.ui.theme.Motion
import com.hardplay.ui.theme.Space

/**
 * Discover — search, and what to watch when you have not decided.
 *
 * The screen has two bodies and the query chooses between them. With text typed it is
 * a results grid; with nothing typed it is five shelves and a tag cloud, which is the
 * state it will be in most of the time. Treating the empty query as the *primary*
 * state is the whole design: a search tab that shows a blank page until you type is a
 * text field wearing a destination's clothes.
 *
 * The recommendation shelves are honest about themselves. Ranking is tag overlap
 * against recent playback, computed in SQL over what is already indexed — no model, no
 * request, nothing leaving the device (PRD §9). The screen says so once, in its
 * subtitle, because that is a feature rather than a disclaimer.
 */
@Composable
fun DiscoverScreen(
    onOpenItem: (Long) -> Unit,
    onOpenTag: (Long) -> Unit,
    modifier: Modifier = Modifier,
    sharedScope: SharedTransitionScope? = null,
    visibilityScope: AnimatedVisibilityScope? = null,
    viewModel: DiscoverViewModel = hiltViewModel(),
    actions: MediaActionsViewModel = hiltViewModel(),
) {
    val text by viewModel.text.collectAsStateWithLifecycle()
    val typeFilter by viewModel.typeFilter.collectAsStateWithLifecycle()
    val searching by viewModel.searching.collectAsStateWithLifecycle()
    val shelves by viewModel.shelves.collectAsStateWithLifecycle()
    val tags by viewModel.tags.collectAsStateWithLifecycle()
    val totals by viewModel.totals.collectAsStateWithLifecycle()
    val resultCount by viewModel.resultCount.collectAsStateWithLifecycle()
    val cardAspect by viewModel.cardAspect.collectAsStateWithLifecycle()
    val forcedColumns by viewModel.gridColumns.collectAsStateWithLifecycle()

    val results = viewModel.results.collectAsLazyPagingItems()

    // One list state per body. Sharing a single state between a LazyColumn and a
    // LazyVerticalGrid would carry a shelf scroll position into the results grid,
    // which lands the user halfway down a list they have not seen.
    val shelfState = rememberLazyListState()
    val gridState = rememberLazyGridState()

    val scrolled by remember {
        derivedStateOf {
            if (searching) {
                gridState.firstVisibleItemIndex > 0 || gridState.firstVisibleItemScrollOffset > 8
            } else {
                shelfState.firstVisibleItemIndex > 0 || shelfState.firstVisibleItemScrollOffset > 8
            }
        }
    }

    HardPlaySurface(modifier = modifier.fillMaxSize(), bloom = true) {
        Column(Modifier.fillMaxSize()) {
            HardPlayTopBar(
                title = "Discover",
                scrolled = scrolled,
                // The masthead lives in the scrolling content and scrolls away; the bar
                // picks the name up exactly as it leaves, so there is one title on
                // screen at a time rather than two.
                showTitle = scrolled,
            )

            SearchRow(
                text = text,
                typeFilter = typeFilter,
                resultCount = resultCount,
                searching = searching,
                onTextChange = viewModel::onTextChange,
                onClear = viewModel::clearText,
                onSetType = viewModel::setTypeFilter,
            )

            Box(Modifier.weight(1f)) {
                val itemCount = totals?.itemCount
                when {
                    // Null means the first database read has not landed. Drawing nothing
                    // for that frame is right; drawing "nothing to discover yet" would
                    // be a false statement flashed on every visit to the tab.
                    itemCount == null -> Box(Modifier.fillMaxSize())

                    itemCount == 0 -> EmptyLibrary()

                    searching -> ResultsBody(
                        results = results,
                        text = text,
                        columns = forcedColumns,
                        aspectRatio = cardAspect.ratio,
                        columnsFor = { width -> cardAspect.columnsFor(width) },
                        gridState = gridState,
                        onOpenItem = onOpenItem,
                        onMenu = { row -> actions.open(row.localId) },
                        sharedScope = sharedScope,
                        visibilityScope = visibilityScope,
                    )

                    else -> ShelvesBody(
                        shelves = shelves,
                        tags = tags,
                        itemCount = itemCount,
                        listState = shelfState,
                        onOpenItem = onOpenItem,
                        onOpenTag = onOpenTag,
                        onMenu = { row -> actions.open(row.localId) },
                    )
                }
            }
        }
    }

    MediaActionHost(viewModel = actions, onOpenItem = onOpenItem)
}

// ----------------------------------------------------------------------- chrome

/**
 * The field, and the two controls that only make sense while searching.
 *
 * The type chips and the result count appear on the first keystroke and leave when the
 * query is cleared. Kept out of the shelves state deliberately: a media-kind filter
 * silently narrowing the recommendation shelves, with no control on screen to say so,
 * is the sort of invisible state that makes a library look like it has lost rows.
 */
@Composable
private fun SearchRow(
    text: String,
    typeFilter: TypeFilter,
    resultCount: Int,
    searching: Boolean,
    onTextChange: (String) -> Unit,
    onClear: () -> Unit,
    onSetType: (TypeFilter) -> Unit,
) {
    val colors = HardPlayTheme.colors
    val focus = LocalFocusManager.current

    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = Space.gutter, end = Space.gutter, bottom = Space.sm),
    ) {
        HardPlayTextField(
            value = text,
            onValueChange = onTextChange,
            placeholder = "Captions and tags",
            imeAction = ImeAction.Search,
            // Results are live, so the action key has nothing to submit — dropping
            // focus to get the keyboard out of the way is the only useful thing it
            // can do.
            onImeAction = { focus.clearFocus() },
            leading = { focused -> SearchGlyph(active = focused || text.isNotEmpty()) },
            trailing = {
                if (text.isNotEmpty()) {
                    GhostIconButton(
                        icon = Icons.Rounded.Close,
                        contentDescription = "Clear search",
                        onClick = onClear,
                        size = 16.dp,
                        tint = colors.muted,
                    )
                }
            },
        )

        AnimatedVisibility(
            visible = searching,
            enter = expandVertically(Motion.standard()) + fadeIn(Motion.fade()),
            exit = shrinkVertically(Motion.quick()) + fadeOut(Motion.fade()),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = Space.sm),
                horizontalArrangement = Arrangement.spacedBy(Space.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TypeFilter.entries.forEach { filter ->
                    TagChip(
                        label = filter.label,
                        selected = typeFilter == filter,
                        onClick = { onSetType(filter) },
                    )
                }
                Box(Modifier.weight(1f))
                // The count carries the weight; the word does not.
                //
                // This was one grey `"12 found"` at the end of the filter row — a
                // sentence in the muted colour, sitting where the eye had no reason to
                // go, contributing nothing to the composition. Splitting it lets the
                // number read as a figure in bone with tabular digits (so it does not
                // reflow as you type) against a tracked micro-caps label. Same
                // information, but it now looks like a readout rather than a leftover.
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = Format.count(resultCount),
                        style = HardPlayTheme.type.numeral,
                        color = colors.type,
                    )
                    Text(
                        text = "FOUND",
                        style = HardPlayTheme.type.overline,
                        color = colors.muted,
                        modifier = Modifier.padding(start = Space.xs, bottom = 1.dp),
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------ the shelves

/** One shelf's copy and contents, so five of them read as edited rather than looped. */
private data class ShelfSpec(
    val key: String,
    val overline: String,
    val title: String,
    val rows: List<LibraryRow>,
)

@Composable
private fun ShelvesBody(
    shelves: DiscoverShelves,
    tags: List<TagFacet>,
    itemCount: Int,
    listState: LazyListState,
    onOpenItem: (Long) -> Unit,
    onOpenTag: (Long) -> Unit,
    onMenu: (LibraryRow) -> Unit,
) {
    val specs = listOf(
        ShelfSpec("dsc-continue", "Continue", "Pick up where you left off", shelves.continueWatching),
        ShelfSpec("dsc-foryou", "For you", "More like what you've watched", shelves.recommended),
        ShelfSpec("dsc-most", "Most watched", "You keep coming back to these", shelves.mostWatched),
        ShelfSpec("dsc-unseen", "New to you", "Haven't got to these yet", shelves.unseen),
        ShelfSpec("dsc-old", "Rediscover", "From the far end of the library", shelves.rediscover),
    )

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = Space.xxxl),
        verticalArrangement = Arrangement.spacedBy(Space.xxl),
    ) {
        item(key = "dsc-header") {
            Box(Modifier.padding(horizontal = Space.gutter)) {
                ScreenHeader(
                    title = "Discover",
                    overline = "${Format.count(itemCount)} items",
                    subtitle = "Ranked from the tags on what you have already watched — " +
                        "on this device, and nowhere else.",
                )
            }
        }

        // Only non-empty shelves are emitted at all. `Shelf` suppresses itself when
        // its rows are empty, but a zero-height item still takes a slot in
        // `spacedBy`, which would leave a run of blank gaps on a fresh library.
        specs.filter { it.rows.isNotEmpty() }.forEach { spec ->
            item(key = spec.key) {
                Shelf(
                    key = spec.key,
                    title = spec.title,
                    overline = spec.overline,
                    rows = spec.rows,
                    onOpen = onOpenItem,
                    onMenu = onMenu,
                )
            }
        }

        if (shelves.isEmpty) {
            item(key = "dsc-nohistory") { NoHistoryYet() }
        }

        if (tags.isNotEmpty()) {
            item(key = "dsc-tags") { TagCloud(tags = tags, onOpenTag = onOpenTag) }
        }
    }
}

/**
 * The tag cloud.
 *
 * Every chip is a way into the library rather than a filter applied here, which is why
 * tapping one leaves for the Library tab: a third grid that happened to be filtered
 * would be the same screen again, with its own bugs.
 */
@Composable
private fun TagCloud(tags: List<TagFacet>, onOpenTag: (Long) -> Unit) {
    val colors = HardPlayTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.gutter),
    ) {
        Text(
            text = "BROWSE BY TAG",
            style = HardPlayTheme.type.overline,
            color = colors.accent,
        )
        Box(Modifier.height(4.dp))
        Text(
            text = "Whatever the captions gave away",
            style = HardPlayTheme.type.displaySmall,
            color = colors.type,
        )
        Box(Modifier.height(Space.md))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Space.xs),
            verticalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
            tags.forEach { facet ->
                TagChip(
                    label = facet.name,
                    count = facet.itemCount,
                    onClick = { onOpenTag(facet.id) },
                )
            }
        }
    }
}

// ------------------------------------------------------------------- the results

@Composable
private fun ResultsBody(
    results: LazyPagingItems<LibraryRow>,
    text: String,
    columns: Int,
    aspectRatio: Float,
    columnsFor: (Float) -> Int,
    gridState: LazyGridState,
    onOpenItem: (Long) -> Unit,
    onMenu: (LibraryRow) -> Unit,
    sharedScope: SharedTransitionScope?,
    visibilityScope: AnimatedVisibilityScope?,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val resolved = columns.takeIf { it > 0 } ?: columnsFor(maxWidth.value)

        when {
            results.itemCount == 0 && results.loadState.refresh is LoadState.Loading ->
                MediaGridSkeleton(columns = resolved, aspect = aspectRatio)

            results.itemCount == 0 -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                EmptyState(
                    overline = "No matches",
                    headline = "Nothing for “$text”.",
                    body = "Search reads captions and tags, and matches from the start of " +
                        "a word. A shorter one usually finds more.",
                )
            }

            else -> MediaGrid(
                items = results,
                columns = resolved,
                aspect = aspectRatio,
                onOpen = onOpenItem,
                onMenu = onMenu,
                gridState = gridState,
                sharedScope = sharedScope,
                visibilityScope = visibilityScope,
            )
        }
    }
}

// -------------------------------------------------------------- empty states

@Composable
private fun EmptyLibrary() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        EmptyState(
            overline = "Nothing indexed",
            headline = "There is nothing to discover yet.",
            // No button: adding a source lives in Manage, and a second entry point to
            // it here would be one more thing to keep in step for no gain.
            body = "Add a channel from the Manage tab and HardPlay will index its " +
                "captions, tags and artwork.",
        )
    }
}

@Composable
private fun NoHistoryYet() {
    // No gutter of its own: EmptyState already carries generous horizontal padding,
    // and adding the screen gutter on top of it squeezes the copy into a column.
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        EmptyState(
            overline = "No history yet",
            headline = "Recommendations need something to go on.",
            body = "Watch a few things and this fills with items that share their tags. " +
                "That is worked out here, on the device, from the library you already have.",
        )
    }
}
