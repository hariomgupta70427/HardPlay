package com.hardplay.ui.saved

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.hardplay.data.db.projection.LibraryRow
import com.hardplay.data.model.CardAspect
import com.hardplay.data.prefs.SettingsStore
import com.hardplay.data.repo.LibraryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * The Saved tab's state.
 *
 * Reads [LibraryRepository.savedPager] rather than the library pager with a
 * favourites flag set, and that distinction is deliberate: Saved is ordered by *when
 * you saved something*, so routing it through the shared query would leave it obeying
 * the Library sort control — and changing the grid's sort would then silently
 * reshuffle a list the user curated by hand.
 */
@HiltViewModel
class SavedViewModel @Inject constructor(
    library: LibraryRepository,
    settings: SettingsStore,
) : ViewModel() {

    /**
     * `cachedIn` is not optional. Without it, returning from the player re-fetches
     * page one and drops the user at the top of their own saved list.
     */
    val items: Flow<PagingData<LibraryRow>> = library.savedPager().cachedIn(viewModelScope)

    val count: StateFlow<Int> = library.savedCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** 0 means "adapt to the screen width". Shared with every other grid in the app. */
    val gridColumns: StateFlow<Int> = settings.settings
        .map { it.gridColumns }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val cardAspect: StateFlow<CardAspect> = settings.settings
        .map { it.cardAspect }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CardAspect.WIDE)
}
