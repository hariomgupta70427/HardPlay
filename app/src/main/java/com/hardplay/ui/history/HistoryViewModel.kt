package com.hardplay.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.hardplay.data.db.projection.LibraryRow
import com.hardplay.data.model.CardAspect
import com.hardplay.data.prefs.SettingsStore
import com.hardplay.data.repo.LibraryRepository
import com.hardplay.data.repo.PlaybackRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HistoryViewModel @Inject constructor(
    library: LibraryRepository,
    settings: SettingsStore,
    private val playback: PlaybackRepository,
) : ViewModel() {

    /** `cachedIn`, or returning from the player refetches page one and loses the scroll. */
    val items: Flow<PagingData<LibraryRow>> = library.historyPager().cachedIn(viewModelScope)

    /**
     * Rewatched items only.
     *
     * The query behind this requires `playCount > 1` on purpose — everything you have
     * ever opened has a count of one, so including those would make this shelf a second
     * copy of the list underneath it. It stays genuinely empty until something has been
     * watched twice, and `Shelf` drawing nothing in that case is the intended behaviour.
     */
    val mostWatched: StateFlow<List<LibraryRow>> = library.mostWatched()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val count: StateFlow<Int> = library.historyCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val gridColumns: StateFlow<Int> = settings.settings
        .map { it.gridColumns }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val cardAspect: StateFlow<CardAspect> = settings.settings
        .map { it.cardAspect }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CardAspect.WIDE)

    /**
     * Wipes every resume position and play count.
     *
     * Genuinely destructive and not recoverable from Telegram, which is why the screen
     * asks first: this is the same table the continue-watching shelf, the unseen marker
     * and Most watched all read from, so clearing it empties four things at once.
     */
    fun clearHistory() {
        viewModelScope.launch { playback.clearAll() }
    }
}
