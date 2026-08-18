package com.hardplay.sync

import androidx.room.withTransaction
import com.hardplay.data.db.HardPlayDatabase
import com.hardplay.data.db.dao.ChannelDao
import com.hardplay.data.db.dao.MediaDao
import com.hardplay.data.db.dao.SyncStateDao
import com.hardplay.data.db.entity.ChannelEntity
import com.hardplay.data.db.entity.MediaEntity
import com.hardplay.data.db.entity.SyncStateEntity
import com.hardplay.data.prefs.SettingsStore
import com.hardplay.data.repo.TagRepository
import com.hardplay.data.tagging.CaptionParser
import com.hardplay.di.IoDispatcher
import com.hardplay.telegram.GatewayError
import com.hardplay.telegram.GatewayResult
import com.hardplay.telegram.TelegramAuthState
import com.hardplay.telegram.TelegramGateway
import com.hardplay.telegram.TelegramMediaKind
import com.hardplay.telegram.TelegramMessage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The indexing engine (PRD §5.2, §8).
 *
 * Reads channel history and writes metadata. It never downloads media — the whole
 * point of the design is that a 300 GB library costs a few megabytes of local
 * index, and playback fetches bytes on demand.
 *
 * ## Two cursors, walked in one direction
 *
 * Telegram history can only be paged newest-to-oldest, so both jobs are the same
 * walk started from different places:
 *
 *  * **Head** picks up what was posted since last time. It starts at the newest
 *    message and stops the moment it reaches an id already indexed.
 *  * **Tail** is the first-run backfill. It resumes from the oldest id indexed so
 *    far and keeps descending.
 *
 * The tail is *budgeted* rather than run to completion. A channel with 20 000
 * posts would otherwise hold the first launch hostage; instead each run consumes
 * a bounded number of pages, persists its cursor, and the next run continues. That
 * is why [SyncStateEntity] stores two ids instead of one, and why an interrupted
 * first index costs nothing.
 */
@Singleton
class LibraryIndexer @Inject constructor(
    private val gateway: TelegramGateway,
    private val database: HardPlayDatabase,
    private val mediaDao: MediaDao,
    private val channelDao: ChannelDao,
    private val syncStateDao: SyncStateDao,
    private val tagRepository: TagRepository,
    private val settings: SettingsStore,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    private val _progress = MutableStateFlow<SyncProgress>(SyncProgress.Idle)
    val progress: StateFlow<SyncProgress> = _progress.asStateFlow()

    /**
     * One sync at a time, and a second request is *dropped* rather than queued.
     * Pull-to-refresh, app resume and the WorkManager job can all fire within a
     * second of each other; running two walks concurrently would have them fight
     * over the same cursors and double the load on a rate-limited account.
     */
    private val running = Mutex()

    suspend fun sync(mode: SyncMode): SyncSummary = withContext(io) {
        if (!running.tryLock()) return@withContext SyncSummary.alreadyRunning()
        try {
            runSync(mode)
        } finally {
            running.unlock()
        }
    }

    private suspend fun runSync(mode: SyncMode): SyncSummary {
        gateway.start()
        if (gateway.authState.first() !is TelegramAuthState.Ready) {
            _progress.value = SyncProgress.Idle
            return SyncSummary.notAuthenticated()
        }

        val channels = channelDao.all().filter { it.enabled }
        if (channels.isEmpty()) {
            _progress.value = SyncProgress.Idle
            return SyncSummary.empty()
        }

        val autoTag = settings.settings.first().autoTagCaptions
        val budget = PageBudget(mode.pageBudget)
        var added = 0
        var refreshed = 0
        var failure: SyncSummary.Failure? = null

        for ((index, channel) in channels.withIndex()) {
            syncStateDao.insertIgnoring(SyncStateEntity(chatId = channel.chatId))

            _progress.value = SyncProgress.Running(
                channelTitle = channel.title,
                channelIndex = index,
                channelCount = channels.size,
                indexed = added,
                phase = SyncPhase.HEAD,
            )

            when (val result = syncChannel(channel, mode, budget, autoTag, added, index, channels.size)) {
                is ChannelOutcome.Ok -> {
                    added += result.added
                    refreshed += result.refreshed
                }
                is ChannelOutcome.Stopped -> {
                    added += result.added
                    refreshed += result.refreshed
                    failure = result.failure
                    // A flood-wait applies to the account, not the channel, so
                    // moving on to the next one would only deepen it.
                    if (result.failure.error == GatewayError.FLOOD_WAIT) break
                }
            }
            if (budget.isExhausted) break
        }

        _progress.value = failure
            ?.let { SyncProgress.Failed(it.message, it.retryAfterSeconds) }
            ?: SyncProgress.Done(added = added, refreshed = refreshed, at = System.currentTimeMillis())

        return SyncSummary(
            added = added,
            refreshed = refreshed,
            failure = failure,
            budgetExhausted = budget.isExhausted,
        )
    }

    private suspend fun syncChannel(
        channel: ChannelEntity,
        mode: SyncMode,
        budget: PageBudget,
        autoTag: Boolean,
        indexedSoFar: Int,
        channelIndex: Int,
        channelCount: Int,
    ): ChannelOutcome {
        var state = syncStateDao.byId(channel.chatId) ?: SyncStateEntity(chatId = channel.chatId)
        var added = 0
        var refreshed = 0

        suspend fun report(phase: SyncPhase) {
            _progress.value = SyncProgress.Running(
                channelTitle = channel.title,
                channelIndex = channelIndex,
                channelCount = channelCount,
                indexed = indexedSoFar + added,
                phase = phase,
            )
        }

        // ------------------------------------------------------------ head
        if (mode.walksHead && state.newestIndexedMessageId > 0L) {
            val floor = state.newestIndexedMessageId
            var cursor = 0L
            var newestSeen = floor

            while (budget.take()) {
                report(SyncPhase.HEAD)
                val page = when (val result = gateway.fetchHistory(channel.chatId, cursor, PAGE_SIZE)) {
                    is GatewayResult.Success -> result.value
                    is GatewayResult.Failure -> {
                        syncStateDao.recordError(channel.chatId, result.message)
                        return ChannelOutcome.Stopped(added, refreshed, result.toFailure())
                    }
                }
                if (page.inspected == 0) break

                // Every message the page inspected, not just the media ones — see
                // TelegramHistoryPage.newestMessageId for why that distinction is
                // what stops the floor from sticking below a run of text posts.
                newestSeen = maxOf(newestSeen, page.newestMessageId)
                val fresh = page.messages.filter { it.messageId > floor }
                val written = write(fresh, autoTag)
                added += written.added
                refreshed += written.refreshed

                cursor = page.oldestMessageId
                // The page reached back into already-indexed history, so
                // everything older is known. Stop rather than re-walk it.
                if (page.reachedEnd || cursor <= floor || cursor == 0L) break
            }

            state = state.copy(newestIndexedMessageId = maxOf(state.newestIndexedMessageId, newestSeen))
            syncStateDao.upsert(state)
        }

        // ------------------------------------------------------------ tail
        if (mode.walksTail && !state.backfillComplete) {
            var cursor = state.oldestIndexedMessageId
            var newest = state.newestIndexedMessageId
            var complete = false

            while (budget.take()) {
                report(SyncPhase.BACKFILL)
                val page = when (val result = gateway.fetchHistory(channel.chatId, cursor, PAGE_SIZE)) {
                    is GatewayResult.Success -> result.value
                    is GatewayResult.Failure -> {
                        syncStateDao.recordError(channel.chatId, result.message)
                        syncStateDao.upsert(
                            state.copy(oldestIndexedMessageId = cursor, newestIndexedMessageId = newest),
                        )
                        return ChannelOutcome.Stopped(added, refreshed, result.toFailure())
                    }
                }

                if (page.inspected == 0) {
                    // No more history above this cursor: the walk is done.
                    complete = true
                    break
                }

                // First ever sync: this page *is* the newest history, so it seeds
                // the head cursor too. Without this the next incremental run would
                // have no floor and would re-walk the entire channel.
                if (newest == 0L) {
                    newest = page.messages.maxOfOrNull { it.messageId }
                        ?: page.oldestMessageId
                }

                val written = write(page.messages, autoTag)
                added += written.added
                refreshed += written.refreshed

                cursor = page.oldestMessageId
                if (page.reachedEnd || cursor <= 1L) {
                    complete = true
                    break
                }
                // A courtesy pause between pages. Telegram tolerates a tight loop
                // right up until it doesn't, and a flood-wait costs far more than
                // these milliseconds.
                delay(PAGE_PAUSE_MS)
            }

            state = state.copy(
                oldestIndexedMessageId = cursor,
                newestIndexedMessageId = newest,
                backfillComplete = complete,
            )
            syncStateDao.upsert(state)
        }

        val indexedCount = mediaDao.countForChannel(channel.chatId)
        syncStateDao.upsert(
            state.copy(
                indexedCount = indexedCount,
                lastSyncAt = System.currentTimeMillis(),
                lastError = null,
            ),
        )
        return ChannelOutcome.Ok(added, refreshed)
    }

    /**
     * Persist a page.
     *
     * One transaction per page rather than per message: 100 individual
     * transactions on a page would each fsync and each invalidate every query
     * observing the table, so the grid behind the sync would re-query 100 times.
     */
    private suspend fun write(messages: List<TelegramMessage>, autoTag: Boolean): WriteResult {
        if (messages.isEmpty()) return WriteResult(0, 0)
        val now = System.currentTimeMillis()

        // Poster pairing is computed per page, before anything is written: you cannot
        // tell that a message is a screenshot *for* another message until you have
        // seen both. A video whose still landed on the previous page misses out —
        // roughly one in a hundred at this page size, and a later re-walk catches it.
        val pairing = PosterPairing.pair(messages)

        return database.withTransaction {
            var added = 0
            var refreshed = 0
            for (message in messages) {
                val existed = mediaDao.findLocalId(message.chatId, message.messageId) != null
                val localId = mediaDao.upsert(
                    message.toEntity(
                        now = now,
                        posterFileId = pairing.posterFor[message.messageId],
                        posterForMessageId = pairing.stillServes[message.messageId],
                    ),
                )
                if (localId <= 0L) continue
                if (existed) refreshed++ else added++

                if (autoTag) {
                    // upsert clears tagsParsed when a caption changed, so this both
                    // tags new items and re-tags edited ones.
                    val entity = mediaDao.byId(localId)
                    if (entity != null && !entity.tagsParsed) {
                        tagRepository.applyAutoTags(localId, entity.caption)
                    }
                }
            }
            WriteResult(added = added, refreshed = refreshed)
        }
    }

    private fun TelegramMessage.toEntity(
        now: Long,
        posterFileId: Int?,
        posterForMessageId: Long?,
    ) = MediaEntity(
        chatId = chatId,
        messageId = messageId,
        type = when (kind) {
            TelegramMediaKind.VIDEO -> com.hardplay.data.db.entity.MediaType.VIDEO.name
            TelegramMediaKind.PHOTO -> com.hardplay.data.db.entity.MediaType.PHOTO.name
        },
        caption = caption,
        // Derived once, at index time. Deriving it per frame while scrolling would
        // run a regex chain for every visible cell.
        title = CaptionParser.title(caption, fallbackTitle(this)),
        date = date,
        durationSeconds = durationSeconds,
        width = width,
        height = height,
        thumbnailFileId = thumbnailFileId,
        previewFileId = previewFileId,
        // Never set from a sync: a decoded frame comes from the player or a one-off
        // decode, and `MediaDao.upsert` carries the existing value forward.
        posterPath = null,
        minithumbnail = minithumbnail,
        posterFileId = posterFileId,
        posterForMessageId = posterForMessageId,
        fileId = fileId,
        remoteFileId = remoteFileId,
        remoteUniqueId = remoteUniqueId,
        fileSizeBytes = fileSizeBytes,
        tagsParsed = false,
        lastSyncedAt = now,
    )

    /** Captions are frequently empty; a bare "#41" beats a blank card. */
    private fun fallbackTitle(message: TelegramMessage): String = when (message.kind) {
        TelegramMediaKind.VIDEO -> "Clip #${message.messageId}"
        TelegramMediaKind.PHOTO -> "Still #${message.messageId}"
    }

    private fun GatewayResult.Failure.toFailure() = SyncSummary.Failure(
        error = error,
        message = message,
        retryAfterSeconds = retryAfterSeconds,
    )

    private data class WriteResult(val added: Int, val refreshed: Int)

    private sealed interface ChannelOutcome {
        data class Ok(val added: Int, val refreshed: Int) : ChannelOutcome
        data class Stopped(
            val added: Int,
            val refreshed: Int,
            val failure: SyncSummary.Failure,
        ) : ChannelOutcome
    }

    /** Pages left to spend this run, shared across channels. */
    private class PageBudget(private var remaining: Int) {
        val isExhausted: Boolean get() = remaining <= 0

        /** @return false when the run is out of budget. */
        fun take(): Boolean {
            if (remaining <= 0) return false
            remaining--
            return true
        }
    }

    private companion object {
        /** TDLib caps a history page near 100 whatever is asked. */
        const val PAGE_SIZE = 100
        const val PAGE_PAUSE_MS = 120L
    }
}

/** What a given run is allowed to do. */
enum class SyncMode(
    val walksHead: Boolean,
    val walksTail: Boolean,
    /** Total history pages this run may fetch, across all channels. */
    val pageBudget: Int,
) {
    /** Pull-to-refresh. New posts only, so it returns in about a second. */
    HEAD(walksHead = true, walksTail = false, pageBudget = 6),

    /** First-run indexing, and the "keep indexing" action. */
    BACKFILL(walksHead = false, walksTail = true, pageBudget = 40),

    /** Background job: catch up on both ends, modestly. */
    FULL(walksHead = true, walksTail = true, pageBudget = 24),
}

enum class SyncPhase { HEAD, BACKFILL }

sealed interface SyncProgress {
    data object Idle : SyncProgress

    data class Running(
        val channelTitle: String,
        val channelIndex: Int,
        val channelCount: Int,
        val indexed: Int,
        val phase: SyncPhase,
    ) : SyncProgress

    data class Done(val added: Int, val refreshed: Int, val at: Long) : SyncProgress

    data class Failed(val message: String, val retryAfterSeconds: Int) : SyncProgress
}

data class SyncSummary(
    val added: Int,
    val refreshed: Int,
    val failure: Failure?,
    val budgetExhausted: Boolean,
) {
    val succeeded: Boolean get() = failure == null

    /** True when there is more history to walk and it's worth scheduling again. */
    val hasMoreWork: Boolean get() = budgetExhausted && failure == null

    data class Failure(
        val error: GatewayError,
        val message: String,
        val retryAfterSeconds: Int,
    )

    companion object {
        fun empty() = SyncSummary(0, 0, null, budgetExhausted = false)

        fun alreadyRunning() = SyncSummary(0, 0, null, budgetExhausted = false)

        fun notAuthenticated() = SyncSummary(
            added = 0,
            refreshed = 0,
            failure = Failure(
                GatewayError.NOT_AUTHENTICATED,
                "Not signed in to Telegram.",
                retryAfterSeconds = 0,
            ),
            budgetExhausted = false,
        )
    }
}
