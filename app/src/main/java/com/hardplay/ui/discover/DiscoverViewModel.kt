package com.hardplay.ui.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.hardplay.data.db.projection.LibraryRow
import com.hardplay.data.db.projection.LibraryTotals
import com.hardplay.data.db.projection.TagFacet
import com.hardplay.data.model.CardAspect
import com.hardplay.data.model.LibraryQuery
import com.hardplay.data.model.TypeFilter
import com.hardplay.data.prefs.SettingsStore
import com.hardplay.data.repo.LibraryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import javax.inject.Inject

/**
 * Discover's state.
 *
 * Two screens in one, chosen by whether anything has been typed. That is deliberate:
 * an empty query is the *interesting* state here — it is the "what should I watch"
 * screen — so it gets the shelves rather than a blank page with a hint under it.
 *
 * Nothing in this file reaches the network. Every shelf is a SQL query over what has
 * already been indexed, and the recommendation ranking is tag overlap against recent
 * playback (`MediaDao.observeBecauseYouWatched`). That is the whole recommender, and
 * it is the reason the screen can promise nothing leaves the device (PRD §9).
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class DiscoverViewModel @Inject constructor(
    private val library: LibraryRepository,
    settings: SettingsStore,
) : ViewModel() {

    private val _text = MutableStateFlow("")

    /**
     * Drives the text field directly, undebounced.
     *
     * The debounce belongs between here and the database, never between the keystroke
     * and the glyph: a field that lags 220ms behind the finger feels broken in a way
     * no amount of query tuning makes up for.
     */
    val text: StateFlow<String> = _text.asStateFlow()

    private val _typeFilter = MutableStateFlow(TypeFilter.ALL)
    val typeFilter: StateFlow<TypeFilter> = _typeFilter.asStateFlow()

    /**
     * The query the database actually sees.
     *
     * A `StateFlow`, so the pager, the count and the body-switch all read *one* chain.
     * Three independent collections of a cold flow would each carry their own debounce
     * and their own settings subscription, and could land a frame apart — which shows
     * up as a count that briefly disagrees with the grid under it.
     *
     * `hidePairedStills` is mirrored from settings rather than defaulted, so Discover
     * and the Library can never disagree about whether a screenshot posted next to its
     * video counts as an item — a search that found rows the grid folds away would look
     * like two different libraries.
     */
    private val settled: StateFlow<LibraryQuery> = combine(
        _text.debounce { if (it.isBlank()) 0L else SEARCH_DEBOUNCE_MS },
        _typeFilter,
        settings.settings.map { it.hidePairedStills }.distinctUntilChanged(),
    ) { text, type, hidePairedStills ->
        LibraryQuery(
            text = text,
            typeFilter = type,
            hidePairedStills = hidePairedStills,
        )
    }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryQuery())

    /**
     * Which body to draw.
     *
     * Derived from the **settled** query, not from the live text, and that is the
     * difference between a clean switch and a visible glitch: the results pager is
     * built from the settled query, and with an empty query it holds the entire
     * library. Switching on the first keystroke would therefore flash every item in
     * the library for 220ms before the filtered set arrived. Chrome and content change
     * together instead.
     */
    val searching: StateFlow<Boolean> = settled
        .map { it.text.isNotBlank() }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val results: Flow<PagingData<LibraryRow>> = settled
        .flatMapLatest { library.pager(it) }
        // Keeps loaded pages across navigating to the player and back. Without it,
        // returning from playback refetches page one and drops the scroll position.
        .cachedIn(viewModelScope)

    val resultCount: StateFlow<Int> = settled
        .flatMapLatest { library.count(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /**
     * All five shelves as one value.
     *
     * Combined rather than collected separately so the screen can answer "is there
     * anything to recommend at all" in one read, and so five rows arriving together
     * cost one recomposition instead of five.
     */
    val shelves: StateFlow<DiscoverShelves> = combine(
        library.continueWatching(),
        library.becauseYouWatched(),
        library.mostWatched(),
        library.unseen(),
        library.rediscover(),
    ) { continuing, recommended, mostWatched, unseen, rediscover ->
        DiscoverShelves(
            continueWatching = continuing,
            recommended = recommended,
            mostWatched = mostWatched,
            unseen = unseen,
            rediscover = rediscover,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DiscoverShelves())

    val tags: StateFlow<List<TagFacet>> = library.allTagFacets()
        .map { facets -> facets.filter { it.itemCount > 0 }.take(MAX_TAG_CHIPS) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Library size — **null until the first read lands**.
     *
     * Nullable rather than seeded with zeroes on purpose. A zero-valued initial would
     * make "there is nothing to discover yet" the literal truth for the frame or two
     * before Room answers, so every visit to this tab would open with a flash of the
     * empty-library message. Null means "not known", and the screen draws nothing for
     * that frame instead of drawing something false.
     */
    val totals: StateFlow<LibraryTotals?> = library.totals()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val cardAspect: StateFlow<CardAspect> = settings.settings
        .map { it.cardAspect }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CardAspect.WIDE)

    val gridColumns: StateFlow<Int> = settings.settings
        .map { it.gridColumns }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    fun onTextChange(value: String) = _text.update { value }

    fun clearText() {
        _text.value = ""
        // The type filter is part of *searching*, not of browsing, so clearing the
        // query has to clear it too — otherwise the shelves come back silently
        // narrowed to one media kind with no visible control saying so.
        _typeFilter.value = TypeFilter.ALL
    }

    fun setTypeFilter(filter: TypeFilter) = _typeFilter.update { filter }

    private companion object {
        /** Matches the library's, so the two screens feel like one search. */
        const val SEARCH_DEBOUNCE_MS = 220L

        /**
         * Enough to browse, few enough to compose in one pass. A library with 400 tags
         * would otherwise lay out 400 chips above the fold for a control nobody reads
         * past the first two rows of.
         */
        const val MAX_TAG_CHIPS = 40
    }
}

/** The five shelves, as one immutable snapshot. */
data class DiscoverShelves(
    val continueWatching: List<LibraryRow> = emptyList(),
    val recommended: List<LibraryRow> = emptyList(),
    val mostWatched: List<LibraryRow> = emptyList(),
    val unseen: List<LibraryRow> = emptyList(),
    val rediscover: List<LibraryRow> = emptyList(),
) {
    val isEmpty: Boolean
        get() = continueWatching.isEmpty() &&
            recommended.isEmpty() &&
            mostWatched.isEmpty() &&
            unseen.isEmpty() &&
            rediscover.isEmpty()

    /**
     * True once there is playback history for the ranking to work from.
     *
     * Distinguishes "your library is empty" from "you haven't watched anything yet",
     * which need completely different copy: the first is a setup problem and the second
     * is simply how a new library starts.
     */
    val hasHistory: Boolean
        get() = continueWatching.isNotEmpty() ||
            recommended.isNotEmpty() ||
            mostWatched.isNotEmpty()
}
