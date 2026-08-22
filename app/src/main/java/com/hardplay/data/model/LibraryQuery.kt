package com.hardplay.data.model

import com.hardplay.data.db.entity.MediaType

/**
 * How the library grid is ordered.
 *
 * **The ordinals are load-bearing.** `MediaDao.pageLibrary` selects a branch of
 * its `ORDER BY CASE` ladder by this ordinal, so reordering these constants
 * silently changes what the sort control does. Append; never insert.
 */
enum class LibrarySort(val label: String) {
    NEWEST("Newest"),
    OLDEST("Oldest"),
    LARGEST("Largest"),
    LONGEST("Longest"),
    TITLE("A–Z"),
    SAVED("Recently saved"),
    RECENT("Recently played"),

    /**
     * A different order every time the app is opened — the default.
     *
     * A library of this size read newest-first means the same few hundred items are the
     * only ones ever seen, and the far end might as well not be indexed. Shuffle makes
     * the whole library reachable by scrolling, which is the point of having it.
     *
     * The order is stable *within* a launch and different *between* launches, and that
     * distinction is not cosmetic: paging asks for offsets from the same query
     * repeatedly, so an order that re-rolled per query — which is what a bare
     * `ORDER BY random()` gives you — would repeat and skip items as you scroll. The
     * seed therefore lives for the process; see `LibraryRepository.shuffleSeed`.
     *
     * **Appended deliberately.** The ordinal is what `SettingsStore` persists and what
     * the `CASE :sort` ladder in `MediaDao` switches on, so inserting this anywhere but
     * the end would silently re-point every saved preference at a different sort.
     */
    SHUFFLE("Shuffle"),
    ;

    companion object {
        fun fromOrdinal(value: Int): LibrarySort = entries.getOrElse(value) { SHUFFLE }
    }
}

/** The type filter's three states. `null` [type] means "everything". */enum class TypeFilter(val label: String, val type: MediaType?) {
    ALL("All", null),
    VIDEO("Video", MediaType.VIDEO),
    PHOTO("Photo", MediaType.PHOTO),
}

/**
 * Everything that narrows the library, in one value.
 *
 * Held as a single immutable object so the ViewModel can map one `StateFlow` of
 * it into the pager. Nine separate flows would mean nine chances to rebuild the
 * Pager mid-scroll and throw away the user's position.
 */
data class LibraryQuery(
    val text: String = "",
    val sort: LibrarySort = LibrarySort.NEWEST,
    val typeFilter: TypeFilter = TypeFilter.ALL,
    /** Empty means every enabled channel. */
    val sourceIds: Set<Long> = emptySet(),
    /** ANDed: each added tag narrows the result. */
    val tagIds: Set<Long> = emptySet(),
    val unseenOnly: Boolean = false,
    val favouritesOnly: Boolean = false,
    /**
     * Fold away screenshots that only exist to preview the video beside them.
     *
     * Mirrors the setting rather than being one itself, so the value travels with the
     * query and the grid, its result count and its facet counts can never disagree
     * about whether stills are in scope.
     */
    val hidePairedStills: Boolean = true,
) {
    val isFiltered: Boolean
        get() = text.isNotBlank() ||
            typeFilter != TypeFilter.ALL ||
            sourceIds.isNotEmpty() ||
            tagIds.isNotEmpty() ||
            unseenOnly ||
            favouritesOnly

    /** Count of active narrowing facets, for the filter button's badge. */
    val activeFacetCount: Int
        get() = tagIds.size +
            sourceIds.size +
            (if (typeFilter != TypeFilter.ALL) 1 else 0) +
            (if (unseenOnly) 1 else 0) +
            (if (favouritesOnly) 1 else 0)
}
