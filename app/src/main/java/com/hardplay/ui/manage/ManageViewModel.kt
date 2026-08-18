package com.hardplay.ui.manage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hardplay.BuildConfig
import com.hardplay.data.db.entity.ChannelEntity
import com.hardplay.data.db.entity.SyncStateEntity
import com.hardplay.data.db.projection.LibraryTotals
import com.hardplay.data.repo.ChannelRepository
import com.hardplay.data.repo.LibraryRepository
import com.hardplay.sync.LibraryIndexer
import com.hardplay.sync.SyncMode
import com.hardplay.sync.SyncProgress
import com.hardplay.telegram.TelegramGateway
import com.hardplay.ui.settings.SettingsUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * One source, with everything the manager needs to say about it.
 *
 * The channel row and its sync row are combined here rather than in the composable.
 * Collecting two lists into the UI and pairing them there means one recomposition can
 * see a channel that its sync state does not yet mention, and the row flickers through
 * "not indexed yet" every time either table is written.
 */
data class ManagedSource(
    val chatId: Long,
    val title: String,
    val enabled: Boolean,
    val indexedCount: Int,
    val backfillComplete: Boolean,
    /** Epoch **millis**, unlike Telegram's dates. Converted at the point of display. */
    val lastSyncAt: Long,
    val lastError: String?,
)

data class ManageUiState(
    val sources: List<ManagedSource> = emptyList(),
    /**
     * Library size — **null until the first read lands**.
     *
     * Nullable rather than seeded with zeroes, because zeroes are not "unknown", they
     * are a claim. This screen's whole job is to state facts about the index, so opening
     * it by announcing an empty library and then correcting itself a frame later is the
     * one thing it must not do. Null draws no strip at all for that frame.
     */
    val totals: LibraryTotals? = null,
    val engineSummary: String = "",
    val isDemo: Boolean = false,
) {
    /**
     * True while any *visible* source still has history left to walk.
     *
     * Scoped to enabled channels on purpose: a source the user has switched off is not
     * in the library, so nagging about its unfinished backfill would be asking them to
     * spend bandwidth on rows they have chosen not to see.
     */
    val hasUnfinishedBackfill: Boolean
        get() = sources.any { it.enabled && !it.backfillComplete }
}

@HiltViewModel
class ManageViewModel @Inject constructor(
    private val channels: ChannelRepository,
    private val indexer: LibraryIndexer,
    library: LibraryRepository,
    gateway: TelegramGateway,
) : ViewModel() {

    /**
     * Borrowed rather than restated.
     *
     * `SettingsUiState.engineSummary` already turns the three build-state booleans into
     * plain language for every combination, and it is the one string in the app that
     * answers "why can't I stream anything?". Copying the five sentences here would
     * guarantee that one screen eventually contradicts the other.
     */
    private val engineSummary: String = SettingsUiState(
        hasTdlib = BuildConfig.HAS_TDLIB,
        hasCredentials = BuildConfig.HAS_TELEGRAM_CREDENTIALS,
        isDemo = gateway.isDemo,
        versionName = BuildConfig.VERSION_NAME,
    ).engineSummary

    private val isDemo: Boolean = gateway.isDemo

    val ui: StateFlow<ManageUiState> = combine(
        channels.observeAll(),
        channels.observeSyncStates(),
        library.totals(),
    ) { channelList, syncStates, totals ->
        val syncByChat = syncStates.associateBy { it.chatId }
        ManageUiState(
            sources = channelList.map { channel -> merge(channel, syncByChat[channel.chatId]) },
            totals = totals,
            engineSummary = engineSummary,
            isDemo = isDemo,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ManageUiState())

    val syncProgress: StateFlow<SyncProgress> = indexer.progress

    /**
     * Which source is asking to be confirmed for removal.
     *
     * Held in the ViewModel rather than in the row so that scrolling the pending row
     * out of view and back does not silently cancel a confirmation the user is mid-way
     * through reading.
     */
    private val _pendingRemoval = MutableStateFlow<Long?>(null)
    val pendingRemoval: StateFlow<Long?> = _pendingRemoval.asStateFlow()

    fun askToRemove(chatId: Long) { _pendingRemoval.value = chatId }

    fun cancelRemoval() { _pendingRemoval.value = null }

    fun confirmRemoval() {
        val chatId = _pendingRemoval.value ?: return
        _pendingRemoval.value = null
        viewModelScope.launch { channels.remove(chatId) }
    }

    /** Hides a source from the library without discarding its index. */
    fun setEnabled(chatId: Long, enabled: Boolean) {
        viewModelScope.launch { channels.setEnabled(chatId, enabled) }
    }

    /**
     * Advance the first-run backfill by one budget of history pages.
     *
     * Telegram can only page history backwards, so a large channel cannot be indexed in
     * one run without holding the app hostage. Each press spends a bounded number of
     * pages and persists its cursor; the indexer drops a second request rather than
     * queueing it, so a double tap costs nothing.
     */
    fun keepIndexing() {
        viewModelScope.launch { indexer.sync(SyncMode.BACKFILL) }
    }

    /**
     * Re-read titles from Telegram.
     *
     * Channels get renamed, and a source list showing last month's names looks stale in
     * a way that reads as broken. Deliberately does not touch `enabled` or the sort
     * order — those are the user's.
     */
    fun refreshSources() {
        viewModelScope.launch { channels.refreshMetadata() }
    }

    private fun merge(channel: ChannelEntity, sync: SyncStateEntity?) = ManagedSource(
        chatId = channel.chatId,
        title = channel.title,
        enabled = channel.enabled,
        indexedCount = sync?.indexedCount ?: 0,
        // Absent sync state means the channel was added but never synced, which is
        // "not finished" rather than "finished with nothing".
        backfillComplete = sync?.backfillComplete == true,
        lastSyncAt = sync?.lastSyncAt ?: 0L,
        lastError = sync?.lastError?.takeIf { it.isNotBlank() },
    )
}
