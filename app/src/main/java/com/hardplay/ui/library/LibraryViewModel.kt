package com.hardplay.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.hardplay.data.db.entity.ChannelEntity
import com.hardplay.data.db.projection.LibraryRow
import com.hardplay.data.db.projection.LibraryTotals
import com.hardplay.data.db.projection.TagFacet
import com.hardplay.data.model.CardAspect
import com.hardplay.data.model.LibraryQuery
import com.hardplay.data.model.LibrarySort
import com.hardplay.data.model.TypeFilter
import com.hardplay.data.prefs.SettingsStore
import com.hardplay.data.repo.ChannelRepository
import com.hardplay.data.repo.LibraryRepository
import com.hardplay.sync.LibraryIndexer
import com.hardplay.sync.SyncMode
import com.hardplay.sync.SyncProgress
import com.hardplay.telegram.TelegramGateway
import com.hardplay.ui.nav.LibraryFocus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The library screen's state.
 *
 * The whole screen hangs off one [LibraryQuery] flow. Grid, result count and facet
 * counts are all derived from it, which is what keeps the count in the header
 * honest about the grid underneath it.
 *
 * Search text is debounced before it reaches the database but *not* before it
 * reaches the text field — the field is driven by [query] directly, so typing is
 * never laggy, while FTS runs once the typing stops.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val library: LibraryRepository,
    private val channels: ChannelRepository,
    private val indexer: LibraryIndexer,
    private val settings: SettingsStore,
    private val libraryFocus: LibraryFocus,
    gateway: TelegramGateway,
) : ViewModel() {

    val isDemo: Boolean = gateway.isDemo

    private val _query = MutableStateFlow(LibraryQuery())
    val query: StateFlow<LibraryQuery> = _query.asStateFlow()

    private val _sheet = MutableStateFlow(LibrarySheet.NONE)
    val sheet: StateFlow<LibrarySheet> = _sheet.asStateFlow()

    /**
     * Debounce only the text. A tag tap or a sort change is a deliberate act and
     * should land immediately; keystrokes are not, and each one would otherwise
     * rebuild the Pager and reset the scroll position.
     */
    private val settledQuery: Flow<LibraryQuery> = _query
        .debounce { if (it.text.isBlank()) 0L else SEARCH_DEBOUNCE_MS }
        .distinctUntilChanged()

    val items: Flow<PagingData<LibraryRow>> = settledQuery
        .flatMapLatest { library.pager(it) }
        // cachedIn keeps loaded pages across configuration changes and across
        // navigating to the player and back. Without it, returning from playback
        // re-fetches page one and drops the user at the top of the grid.
        .cachedIn(viewModelScope)

    val resultCount: StateFlow<Int> = settledQuery
        .flatMapLatest { library.count(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val tagFacets: StateFlow<List<TagFacet>> = settledQuery
        .flatMapLatest { library.tagFacets(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Library size — **null until the first read lands**.
     *
     * Nullable rather than seeded with zeroes, because zeroes are not "unknown" — they
     * are a specific claim, and the header would state it for the frame or two before
     * Room answers. Every entry to the tab would open by saying the library is empty and
     * then correcting itself. Null draws nothing for that frame instead.
     */
    val totals: StateFlow<LibraryTotals?> = library.totals()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val sources: StateFlow<List<ChannelEntity>> = channels.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val continueWatching: StateFlow<List<LibraryRow>> = library.continueWatching()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val syncProgress: StateFlow<SyncProgress> = indexer.progress

    val gridColumns: StateFlow<Int> = settings.settings
        .map { it.gridColumns }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val cardAspect: StateFlow<CardAspect> = settings.settings
        .map { it.cardAspect }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CardAspect.WIDE)

    /**
     * Show only one tag, replacing whatever was selected.
     *
     * Discover's tag cloud sends a tap here. Replacing rather than adding is the point:
     * arriving from a "Browse by tag" chip and landing on an empty grid because two
     * unrelated tags are now ANDed would look like the tag had no items in it.
     */
    fun applyTag(tagId: Long) = _query.update {
        it.copy(tagIds = setOf(tagId), favouritesOnly = false, unseenOnly = false)
    }

    init {
        // Keep the query in step with the settings that shape it. A collector rather
        // than a one-shot read, so toggling "fold paired stills" in Settings is
        // reflected in the grid on the way back without a restart.
        viewModelScope.launch {
            settings.settings.collect { appSettings ->
                _query.update {
                    it.copy(
                        sort = appSettings.librarySort,
                        hidePairedStills = appSettings.hidePairedStills,
                    )
                }
            }
        }
        viewModelScope.launch { indexer.sync(SyncMode.HEAD) }

        // A tag handed over from Discover. Consumed rather than observed: it is a request
        // made once, and re-applying it every time this ViewModel is recreated would
        // silently re-impose a filter the user had already cleared.
        viewModelScope.launch {
            libraryFocus.tagId.filterNotNull().collect { tagId ->
                applyTag(tagId)
                libraryFocus.clear()
            }
        }
    }

    fun toggleFavouritesOnly() = _query.update { it.copy(favouritesOnly = !it.favouritesOnly) }

    // --------------------------------------------------------------- filters

    fun setSort(sort: LibrarySort) {
        _query.update { it.copy(sort = sort) }
        viewModelScope.launch { settings.setLibrarySort(sort) }
    }

    fun setTypeFilter(filter: TypeFilter) = _query.update { it.copy(typeFilter = filter) }

    fun toggleTag(tagId: Long) = _query.update { current ->
        current.copy(
            tagIds = if (tagId in current.tagIds) current.tagIds - tagId else current.tagIds + tagId,
        )
    }

    fun toggleSource(chatId: Long) = _query.update { current ->
        current.copy(
            sourceIds = if (chatId in current.sourceIds) {
                current.sourceIds - chatId
            } else {
                current.sourceIds + chatId
            },
        )
    }

    fun toggleUnseenOnly() = _query.update { it.copy(unseenOnly = !it.unseenOnly) }

    /** Clears the facets but keeps the search text and the sort — those are not filters. */
    fun clearFilters() = _query.update {
        it.copy(
            tagIds = emptySet(),
            sourceIds = emptySet(),
            typeFilter = TypeFilter.ALL,
            unseenOnly = false,
            favouritesOnly = false,
        )
    }

    fun openSheet(sheet: LibrarySheet) { _sheet.value = sheet }

    fun dismissSheet() { _sheet.value = LibrarySheet.NONE }

    // ------------------------------------------------------------------ sync

    fun refresh() {
        viewModelScope.launch { indexer.sync(SyncMode.HEAD) }
    }

    /** Continue the first-run backfill by one budget's worth of pages. */
    fun keepIndexing() {
        viewModelScope.launch { indexer.sync(SyncMode.BACKFILL) }
    }

    /**
     * Playback order for the item being opened, so the player can advance without
     * reaching back into a PagingSource it does not own.
     */
    suspend fun videoQueue(): List<Long> = library.videoQueue(_query.value.sourceIds)

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 220L
    }
}

/** Which bottom sheet is up. One enum, so two can never be open at once. */
enum class LibrarySheet { NONE, FILTERS, SORT }
