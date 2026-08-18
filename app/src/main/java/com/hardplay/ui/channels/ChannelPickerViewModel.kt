package com.hardplay.ui.channels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hardplay.data.db.entity.ChannelEntity
import com.hardplay.data.prefs.SettingsStore
import com.hardplay.data.repo.ChannelRepository
import com.hardplay.sync.LibraryIndexer
import com.hardplay.sync.SyncMode
import com.hardplay.telegram.GatewayResult
import com.hardplay.telegram.TelegramChat
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Choosing which channels the library is built from.
 *
 * Two ways in, because neither covers the real cases alone: the account's channel
 * list, and a handle or invite link typed by hand. A private channel with no
 * username can be missing from the list TDLib has cached but still perfectly
 * readable by chat id, and a channel joined minutes ago often hasn't propagated
 * into the main list yet.
 */
@HiltViewModel
class ChannelPickerViewModel @Inject constructor(
    private val channels: ChannelRepository,
    private val indexer: LibraryIndexer,
    private val settings: SettingsStore,
) : ViewModel() {

    private val _ui = MutableStateFlow(ChannelPickerUiState())
    val ui: StateFlow<ChannelPickerUiState> = _ui.asStateFlow()

    /** Already-added channels, so the list can show them as such rather than offering them twice. */
    val existing: StateFlow<List<ChannelEntity>> = channels.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        refresh()
    }

    fun refresh() {
        if (_ui.value.loading) return
        _ui.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            when (val result = channels.discover()) {
                is GatewayResult.Success -> _ui.update {
                    it.copy(loading = false, available = result.value, error = null)
                }
                is GatewayResult.Failure -> _ui.update {
                    it.copy(loading = false, error = result.message)
                }
            }
        }
    }

    fun onQueryChange(value: String) = _ui.update { it.copy(manualQuery = value, error = null) }

    fun toggle(chatId: Long) = _ui.update { state ->
        state.copy(
            selected = if (chatId in state.selected) {
                state.selected - chatId
            } else {
                state.selected + chatId
            },
        )
    }

    /**
     * Resolve a handle, link or raw chat id and drop it into the list, selected.
     *
     * Resolved channels are prepended rather than merged in place, so the thing the
     * user just typed is visibly at the top instead of sorted somewhere into a list
     * of forty.
     */
    fun resolveManual() {
        val query = _ui.value.manualQuery.trim()
        if (query.isEmpty() || _ui.value.resolving) return

        _ui.update { it.copy(resolving = true, error = null) }
        viewModelScope.launch {
            when (val result = channels.resolve(query)) {
                is GatewayResult.Success -> _ui.update { state ->
                    val chat = result.value
                    state.copy(
                        resolving = false,
                        manualQuery = "",
                        available = listOf(chat) + state.available.filterNot { it.chatId == chat.chatId },
                        selected = state.selected + chat.chatId,
                        error = null,
                    )
                }
                is GatewayResult.Failure -> _ui.update {
                    it.copy(resolving = false, error = result.message)
                }
            }
        }
    }

    /**
     * Commit the selection and start indexing.
     *
     * [onDone] fires as soon as the rows are written, *before* indexing finishes.
     * The library screen shows sync progress in place, so holding the user on a
     * modal picker while a first backfill runs would hide the one screen that has
     * something to report.
     */
    fun confirm(onDone: () -> Unit) {
        val chosen = _ui.value.available.filter { it.chatId in _ui.value.selected }
        if (chosen.isEmpty()) return

        _ui.update { it.copy(saving = true) }
        viewModelScope.launch {
            channels.addAll(chosen)
            settings.setOnboardingComplete(true)
            _ui.update { it.copy(saving = false, selected = emptySet()) }
            onDone()
            // Deliberately after onDone: this suspends for as long as the first
            // backfill pages take.
            indexer.sync(SyncMode.BACKFILL)
        }
    }

    fun remove(chatId: Long) {
        viewModelScope.launch { channels.remove(chatId) }
    }

    fun setEnabled(chatId: Long, enabled: Boolean) {
        viewModelScope.launch { channels.setEnabled(chatId, enabled) }
    }
}

data class ChannelPickerUiState(
    val loading: Boolean = false,
    val resolving: Boolean = false,
    val saving: Boolean = false,
    val available: List<TelegramChat> = emptyList(),
    val selected: Set<Long> = emptySet(),
    val manualQuery: String = "",
    val error: String? = null,
) {
    val canConfirm: Boolean get() = selected.isNotEmpty() && !saving
}
