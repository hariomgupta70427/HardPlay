package com.hardplay.ui.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import com.hardplay.core.Format
import com.hardplay.data.db.entity.ChannelEntity
import com.hardplay.data.db.projection.TagFacet
import com.hardplay.data.model.LibraryQuery
import com.hardplay.data.model.TypeFilter
import com.hardplay.sync.SyncPhase
import com.hardplay.sync.SyncProgress
import com.hardplay.ui.components.BufferingMark
import com.hardplay.ui.components.CountBadge
import com.hardplay.ui.components.EmptyState
import com.hardplay.ui.components.GhostButton
import com.hardplay.ui.components.GhostIconButton
import com.hardplay.ui.components.HardPlayTopBar
import com.hardplay.ui.components.MediaGrid
import com.hardplay.ui.components.MediaGridSkeleton
import com.hardplay.ui.components.Notice
import com.hardplay.ui.components.QuietButton
import com.hardplay.ui.components.ScreenHeader
import com.hardplay.ui.components.Shelf
import com.hardplay.ui.components.TagChip
import com.hardplay.ui.media.MediaActionHost
import com.hardplay.ui.media.MediaActionsViewModel
import com.hardplay.ui.theme.HardPlaySurface
import com.hardplay.ui.theme.HardPlayTheme
import com.hardplay.ui.theme.Space

/**
 * The library (PRD §6.2 §1).
 *
 * A poster grid, and as little else as the job allows. The chrome is one row: search,
 * filter, sort, settings. Everything that narrows the grid lives in a sheet rather
 * than in a permanent filter bar, because a filter bar costs vertical space on every
 * screen to serve the few seconds someone spends filtering.
 *
 * Search is a *tab* now rather than a field that unfolds here. With nothing typed it
 * is the recommendation screen, and that is a destination rather than an empty state.
 * A tag tapped over there arrives through `LibraryFocus` rather than as a route
 * argument, so this tab keeps its scroll position and its loaded pages.
 */
@Composable
fun LibraryScreen(
    onOpenItem: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSources: () -> Unit,
    onOpenSearch: () -> Unit,
    modifier: Modifier = Modifier,
    sharedScope: SharedTransitionScope? = null,
    visibilityScope: AnimatedVisibilityScope? = null,
    viewModel: LibraryViewModel = hiltViewModel(),
    actions: MediaActionsViewModel = hiltViewModel(),
) {
    val colors = HardPlayTheme.colors

    val query by viewModel.query.collectAsStateWithLifecycle()
    val resultCount by viewModel.resultCount.collectAsStateWithLifecycle()
    val totals by viewModel.totals.collectAsStateWithLifecycle()
    val facets by viewModel.tagFacets.collectAsStateWithLifecycle()
    val sources by viewModel.sources.collectAsStateWithLifecycle()
    val continueWatching by viewModel.continueWatching.collectAsStateWithLifecycle()
    val sync by viewModel.syncProgress.collectAsStateWithLifecycle()
    val sheet by viewModel.sheet.collectAsStateWithLifecycle()
    val forcedColumns by viewModel.gridColumns.collectAsStateWithLifecycle()
    val cardAspect by viewModel.cardAspect.collectAsStateWithLifecycle()

    val items = viewModel.items.collectAsLazyPagingItems()
    val gridState = rememberLazyGridState()

    // Chrome only gains its background — and its title — once content is behind it.
    val scrolled by remember {
        derivedStateOf { gridState.firstVisibleItemIndex > 0 || gridState.firstVisibleItemScrollOffset > 8 }
    }

    val refreshing = sync is SyncProgress.Running
    val pullState = rememberPullToRefreshState()

    HardPlaySurface(modifier = modifier.fillMaxSize(), bloom = true) {
        Column(Modifier.fillMaxSize()) {
            HardPlayTopBar(
                title = "Library",
                overline = libraryOverline(totals?.itemCount, resultCount, query.isFiltered),
                scrolled = scrolled,
                // The big heading lives in the grid and scrolls away; the bar takes
                // over exactly as it leaves, so there is one title on screen at a time.
                showTitle = scrolled,
                actions = {
                    GhostIconButton(
                        icon = Icons.Rounded.Search,
                        contentDescription = "Search",
                        onClick = onOpenSearch,
                    )
                    Box {
                        GhostIconButton(
                            icon = Icons.Rounded.FilterList,
                            contentDescription = "Filter",
                            onClick = { viewModel.openSheet(LibrarySheet.FILTERS) },
                            tint = if (query.activeFacetCount > 0) colors.accent else null,
                        )
                        CountBadge(
                            count = query.activeFacetCount,
                            modifier = Modifier.align(Alignment.TopEnd),
                        )
                    }
                    GhostIconButton(
                        icon = Icons.Rounded.SwapVert,
                        contentDescription = "Sort",
                        onClick = { viewModel.openSheet(LibrarySheet.SORT) },
                    )
                    GhostIconButton(
                        icon = Icons.Rounded.Settings,
                        contentDescription = "Settings",
                        onClick = onOpenSettings,
                    )
                },
            )

            SyncNotice(
                progress = sync,
                modifier = Modifier.padding(horizontal = Space.gutter),
            )

            if (viewModel.isDemo) {
                Box(Modifier.padding(horizontal = Space.gutter, vertical = Space.xs)) {
                    Notice(
                        text = "Demo library — generated metadata and artwork. " +
                            "Build TDLib and sign in to stream real files.",
                    )
                }
            }

            ActiveFilterRow(
                query = query,
                facets = facets,
                sources = sources,
                onToggleTag = viewModel::toggleTag,
                onToggleSource = viewModel::toggleSource,
                onSetType = viewModel::setTypeFilter,
                onToggleUnseen = viewModel::toggleUnseenOnly,
                onClear = viewModel::clearFilters,
            )

            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = viewModel::refresh,
                state = pullState,
                modifier = Modifier.weight(1f),
                indicator = {
                    EmberRefreshIndicator(
                        pullFraction = pullState.distanceFraction,
                        refreshing = refreshing,
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                },
            ) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val columns = forcedColumns.takeIf { it > 0 }
                        ?: cardAspect.columnsFor(maxWidth.value)

                    when {
                        items.itemCount == 0 && items.loadState.refresh is LoadState.Loading ->
                            MediaGridSkeleton(columns, cardAspect.ratio)

                        items.itemCount == 0 -> LibraryEmptyState(
                            query = query,
                            hasSources = sources.isNotEmpty(),
                            onClearFilters = viewModel::clearFilters,
                            onOpenSources = onOpenSources,
                        )

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
                                        title = "Library",
                                        overline = libraryOverline(
                                            totals?.itemCount,
                                            resultCount,
                                            query.isFiltered,
                                        ),
                                        subtitle = librarySubtitle(
                                            totals?.videoCount ?: 0,
                                            totals?.totalBytes ?: 0L,
                                        ),
                                    )
                                    Shelf(
                                        key = "continue",
                                        title = "Pick up where you left off",
                                        overline = "Continue",
                                        rows = continueWatching,
                                        onOpen = onOpenItem,
                                        onMenu = { row -> actions.open(row.localId) },
                                        // The shelf sits inside the grid's own gutter,
                                        // so it must not add another one — a shared
                                        // component indenting itself 32dp while the
                                        // posters below sit at 16 is the sort of drift
                                        // this component exists to prevent.
                                        gutter = 0.dp,
                                        cardWidth = 152.dp,
                                        modifier = Modifier.padding(bottom = Space.lg),
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    MediaActionHost(viewModel = actions, onOpenItem = onOpenItem)

    when (sheet) {
        LibrarySheet.FILTERS -> FilterSheet(
            query = query,
            facets = facets,
            sources = sources,
            onToggleTag = viewModel::toggleTag,
            onToggleSource = viewModel::toggleSource,
            onSetType = viewModel::setTypeFilter,
            onToggleUnseen = viewModel::toggleUnseenOnly,
            onToggleSaved = viewModel::toggleFavouritesOnly,
            onClear = viewModel::clearFilters,
            onDismiss = viewModel::dismissSheet,
        )

        LibrarySheet.SORT -> SortSheet(
            current = query.sort,
            onSelect = { viewModel.setSort(it); viewModel.dismissSheet() },
            onDismiss = viewModel::dismissSheet,
        )

        LibrarySheet.NONE -> Unit
    }
}

/**
 * `1,842 items` / `61 of 1,842`.
 *
 * Null total means the first database read has not landed, and the honest answer is to
 * say nothing rather than to claim the library is empty and correct it a frame later.
 */
private fun libraryOverline(total: Int?, showing: Int, filtered: Boolean): String? = when {
    total == null -> null
    total == 0 -> "Empty"
    filtered -> "${Format.count(showing)} of ${Format.count(total)}"
    else -> "${Format.count(total)} items"
}

/**
 * The one editorial line on the screen.
 *
 * States a fact rather than a slogan. "1,204 videos · 3.1 TB" is the thing a library
 * of this kind is actually impressive for, and it costs nothing to say.
 */
private fun librarySubtitle(videoCount: Int, totalBytes: Long): String? {
    if (videoCount <= 0) return null
    val size = Format.bytes(totalBytes).takeIf { it != "—" }
    return listOfNotNull(
        "${Format.count(videoCount)} videos",
        size,
    ).joinToString(" · ")
}

/**
 * Pull-to-refresh indicator.
 *
 * The buffering mark, scaled by pull distance. Material's default indicator is a
 * circular spinner in a container — precisely the stock look the PRD rules out, and
 * it would be the one piece of default Android left on the main screen.
 */
@Composable
private fun EmberRefreshIndicator(
    pullFraction: Float,
    refreshing: Boolean,
    modifier: Modifier = Modifier,
) {
    val visible = refreshing || pullFraction > 0.02f
    val scale by animateFloatAsState(
        targetValue = if (refreshing) 1f else pullFraction.coerceIn(0f, 1f),
        label = "pullScale",
    )
    if (!visible) return

    Box(
        modifier
            .padding(top = Space.md)
            .graphicsLayer {
                translationY = pullFraction.coerceIn(0f, 1.5f) * 56.dp.toPx()
            }
            .scale(0.6f + scale * 0.4f)
            .alpha(if (refreshing) 1f else scale),
        contentAlignment = Alignment.Center,
    ) {
        BufferingMark(
            markSize = 30.dp,
            progress = if (refreshing) null else pullFraction.coerceIn(0f, 1f),
        )
    }
}

@Composable
private fun SyncNotice(
    progress: SyncProgress,
    modifier: Modifier = Modifier,
) {
    when (progress) {
        is SyncProgress.Running -> Box(modifier.padding(vertical = Space.xs)) {
            Notice(
                text = when (progress.phase) {
                    SyncPhase.HEAD -> "Checking ${progress.channelTitle} for new posts…"
                    SyncPhase.BACKFILL ->
                        "Indexing ${progress.channelTitle} — ${Format.count(progress.indexed)} so far"
                },
                action = { BufferingMark(markSize = 18.dp, strokeWidth = 1.5.dp) },
            )
        }

        is SyncProgress.Failed -> Box(modifier.padding(vertical = Space.xs)) {
            Notice(text = progress.message, emphasis = true)
        }

        is SyncProgress.Done -> if (progress.added > 0) {
            Box(modifier.padding(vertical = Space.xs)) {
                Notice(text = "Added ${Format.count(progress.added)} new items.")
            }
        } else Unit

        SyncProgress.Idle -> Unit
    }
}

/**
 * The active facets, inline above the grid.
 *
 * Shown outside the sheet on purpose: a filter you cannot see is a filter you forget
 * you set, and then the library looks broken because half of it is missing.
 */
@Composable
private fun ActiveFilterRow(
    query: LibraryQuery,
    facets: List<TagFacet>,
    sources: List<ChannelEntity>,
    onToggleTag: (Long) -> Unit,
    onToggleSource: (Long) -> Unit,
    onSetType: (TypeFilter) -> Unit,
    onToggleUnseen: () -> Unit,
    onClear: () -> Unit,
) {
    AnimatedVisibility(
        visible = query.activeFacetCount > 0,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
    ) {
        LazyRow(
            Modifier
                .fillMaxWidth()
                .padding(vertical = Space.sm),
            contentPadding = PaddingValues(horizontal = Space.gutter),
            horizontalArrangement = Arrangement.spacedBy(Space.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (query.typeFilter != TypeFilter.ALL) {
                item {
                    TagChip(
                        label = query.typeFilter.label,
                        selected = true,
                        onClick = { onSetType(TypeFilter.ALL) },
                    )
                }
            }
            if (query.unseenOnly) {
                item { TagChip(label = "Unseen", selected = true, onClick = onToggleUnseen) }
            }
            items(sources.filter { it.chatId in query.sourceIds }, key = { "src-${it.chatId}" }) { source ->
                TagChip(
                    label = source.title,
                    selected = true,
                    onClick = { onToggleSource(source.chatId) },
                )
            }
            items(facets.filter { it.id in query.tagIds }, key = { "tag-${it.id}" }) { facet ->
                TagChip(
                    label = facet.name,
                    selected = true,
                    onClick = { onToggleTag(facet.id) },
                )
            }
            item { QuietButton(text = "Clear", onClick = onClear) }
        }
    }
}

@Composable
private fun LibraryEmptyState(
    query: LibraryQuery,
    hasSources: Boolean,
    onClearFilters: () -> Unit,
    onOpenSources: () -> Unit,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when {
            !hasSources -> EmptyState(
                overline = "No sources",
                headline = "Nothing to show yet.",
                body = "Add a Telegram channel and HardPlay will index its " +
                    "captions, tags and thumbnails.",
                action = { GhostButton(text = "Add a channel", onClick = onOpenSources) },
            )

            query.isFiltered -> EmptyState(
                overline = "No matches",
                headline = "Nothing fits those filters.",
                body = "Selected tags have to all be present on an item.",
                action = { GhostButton(text = "Clear filters", onClick = onClearFilters) },
            )

            else -> EmptyState(
                overline = "Indexing",
                headline = "The library is still filling.",
                body = "Pull down to check for new posts.",
            )
        }
    }
}
