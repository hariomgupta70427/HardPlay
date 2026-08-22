package com.hardplay.data.repo

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.hardplay.data.db.FtsQuery
import com.hardplay.data.db.dao.MediaDao
import com.hardplay.data.db.dao.TagDao
import com.hardplay.data.db.projection.LibraryRow
import com.hardplay.data.db.projection.LibraryTotals
import com.hardplay.data.db.projection.TagFacet
import com.hardplay.data.model.LibraryQuery
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * Reads for the library screen.
 *
 * Every method takes the whole [LibraryQuery] rather than loose arguments, so the
 * grid, the result count and the filter sheet's facet counts are always describing
 * the same view. Passing the pieces separately is how those three drift apart.
 */
@Singleton
class LibraryRepository @Inject constructor(
    private val mediaDao: MediaDao,
    private val tagDao: TagDao,
) {

    /**
     * Seed for [com.hardplay.data.model.LibrarySort.SHUFFLE] — one per app launch.
     *
     * A field on a `@Singleton` is precisely the lifetime wanted: the order holds still
     * for as long as the process lives, so paging can walk it without repeating or
     * skipping rows, and it is different the next time the app is opened. That is the
     * whole feature — a library read newest-first only ever shows its newest few hundred
     * items, and the rest might as well not be indexed.
     *
     * `ORDER BY random()` is the obvious version and it is wrong: SQLite re-evaluates it
     * per query, so page two would come from a different permutation than page one and
     * the grid would show duplicates and holes as it scrolled. Multiplying a stable
     * `localId` by a per-process seed is a deterministic permutation instead.
     *
     * Never zero: `localId * 0` collapses every row to the same key and the shuffle
     * silently degrades to the `date DESC, localId DESC` tie-breaker.
     */
    private val shuffleSeed: Int = Random.nextInt(1, Int.MAX_VALUE)

    fun pager(query: LibraryQuery): Flow<PagingData<LibraryRow>> {
        val args = QueryArgs.from(query)
        return Pager(
            config = PagingConfig(
                pageSize = PAGE_SIZE,
                // Two rows of lead time at three columns. Enough that a fast flick
                // lands on loaded content, small enough that a filter change
                // doesn't fetch hundreds of rows nobody will see.
                prefetchDistance = PAGE_SIZE / 2,
                // Placeholders on: unloaded cells become nulls the grid draws as
                // shimmer skeletons, so scrolling ahead shows the shape of what's
                // coming instead of stopping at a hard edge.
                enablePlaceholders = true,
                initialLoadSize = PAGE_SIZE,
                maxSize = PAGE_SIZE * 6,
            ),
            pagingSourceFactory = {
                mediaDao.pageLibrary(
                    sourceIds = args.sourceIds,
                    sourceCount = args.sourceIds.size,
                    type = args.type,
                    hasQuery = args.hasQuery,
                    ftsMatch = args.ftsMatch,
                    tagIds = args.tagIds,
                    tagCount = args.tagIds.size,
                    unseenOnly = args.unseenOnly,
                    favouritesOnly = args.favouritesOnly,
                    hidePairedStills = args.hidePairedStills,
                    sort = query.sort.ordinal,
                    shuffleSeed = shuffleSeed,
                )
            },
        ).flow
    }

    fun count(query: LibraryQuery): Flow<Int> {
        val args = QueryArgs.from(query)
        return mediaDao.countLibrary(
            sourceIds = args.sourceIds,
            sourceCount = args.sourceIds.size,
            type = args.type,
            hasQuery = args.hasQuery,
            ftsMatch = args.ftsMatch,
            tagIds = args.tagIds,
            tagCount = args.tagIds.size,
            unseenOnly = args.unseenOnly,
            favouritesOnly = args.favouritesOnly,
            hidePairedStills = args.hidePairedStills,
        )
    }

    /**
     * Facet counts are deliberately blind to the tag selection itself — a chip
     * has to keep showing what selecting it *would* find, otherwise every count
     * except the chosen one collapses to zero the moment you tap.
     */
    fun tagFacets(query: LibraryQuery): Flow<List<TagFacet>> {
        val args = QueryArgs.from(query)
        return tagDao.observeFacets(
            sourceIds = args.sourceIds,
            sourceCount = args.sourceIds.size,
            type = args.type,
            hasQuery = args.hasQuery,
            ftsMatch = args.ftsMatch,
        )
    }

    /**
     * Every tag with its whole-library count, for Discover's tag cloud.
     *
     * Unfiltered on purpose: the cloud is a way *into* the library, so a count that
     * shifted with whatever filter the grid happens to be holding would be describing
     * a view the user cannot see from here.
     */
    fun allTagFacets(): Flow<List<TagFacet>> = tagDao.observeFacets(
        sourceIds = emptyList(),
        sourceCount = 0,
        type = null,
        hasQuery = 0,
        ftsMatch = null,
    )

    fun totals(): Flow<LibraryTotals> = mediaDao.observeTotals()

    fun continueWatching(): Flow<List<LibraryRow>> = mediaDao.observeContinueWatching()

    fun mostWatched(): Flow<List<LibraryRow>> = mediaDao.observeMostWatched()

    fun becauseYouWatched(): Flow<List<LibraryRow>> = mediaDao.observeBecauseYouWatched()

    fun unseen(): Flow<List<LibraryRow>> = mediaDao.observeUnseen()

    fun rediscover(): Flow<List<LibraryRow>> = mediaDao.observeRediscover()

    fun observeRow(localId: Long): Flow<LibraryRow?> = mediaDao.observeRow(localId)

    suspend fun row(localId: Long): LibraryRow? = mediaDao.row(localId)

    fun savedCount(): Flow<Int> = mediaDao.observeFavouriteCount()

    fun historyCount(): Flow<Int> = mediaDao.observeHistoryCount()

    /**
     * Saved items, newest first.
     *
     * Its own pager rather than [pager] with `favouritesOnly` set: the Saved tab is
     * not a filtered library, it is a list ordered by *when you saved it*, and
     * routing it through the shared query would leave it obeying the library's sort
     * control — so changing the grid's sort would silently reshuffle Saved.
     */
    fun savedPager(): Flow<PagingData<LibraryRow>> = Pager(
        config = shelfPagingConfig(),
        pagingSourceFactory = { mediaDao.pageFavourites() },
    ).flow

    /** Recently opened, newest first. Same reasoning as [savedPager]. */
    fun historyPager(): Flow<PagingData<LibraryRow>> = Pager(
        config = shelfPagingConfig(),
        pagingSourceFactory = { mediaDao.pageHistory() },
    ).flow

    /**
     * Paging for the fixed-order lists.
     *
     * Placeholders stay on for the same reason the library keeps them: an unloaded
     * cell becomes a null the grid draws as a skeleton, so a fast flick lands on the
     * shape of what is coming rather than on a hard edge.
     */
    private fun shelfPagingConfig() = PagingConfig(
        pageSize = PAGE_SIZE,
        prefetchDistance = PAGE_SIZE / 2,
        enablePlaceholders = true,
        initialLoadSize = PAGE_SIZE,
        maxSize = PAGE_SIZE * 6,
    )

    suspend fun videoQueue(sourceIds: Set<Long>): List<Long> =
        mediaDao.videoIdsInOrder(sourceIds.toList(), sourceIds.size)

    /**
     * The query's parameters, flattened once.
     *
     * The FTS clause is skipped entirely when the text yields no searchable term.
     * A `MATCH ''` would not merely return nothing — FTS4 raises on it, which
     * would take the screen down as soon as someone typed a lone punctuation mark.
     */
    private data class QueryArgs(
        val sourceIds: List<Long>,
        val type: String?,
        val hasQuery: Int,
        val ftsMatch: String?,
        val tagIds: List<Long>,
        val unseenOnly: Int,
        val favouritesOnly: Int,
        val hidePairedStills: Int,
    ) {
        companion object {
            fun from(query: LibraryQuery): QueryArgs {
                val match = FtsQuery.forInput(query.text)
                return QueryArgs(
                    sourceIds = query.sourceIds.toList(),
                    type = query.typeFilter.type?.name,
                    hasQuery = if (match != null) 1 else 0,
                    ftsMatch = match,
                    tagIds = query.tagIds.toList(),
                    unseenOnly = if (query.unseenOnly) 1 else 0,
                    favouritesOnly = if (query.favouritesOnly) 1 else 0,
                    hidePairedStills = if (query.hidePairedStills) 1 else 0,
                )
            }
        }
    }

    private companion object {
        const val PAGE_SIZE = 48
    }
}
