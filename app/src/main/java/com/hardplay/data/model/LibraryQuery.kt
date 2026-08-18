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
    ;

    companion object {
        fun fromOrdinal(value: Int): LibrarySort = entries.getOrElse(value) { NEWEST }
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
