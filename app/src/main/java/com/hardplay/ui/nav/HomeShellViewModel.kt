package com.hardplay.ui.nav

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hardplay.data.repo.LibraryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * The figures the bottom bar puts beside Saved and History, and the cross-tab handoff.
 *
 * Its own ViewModel rather than a field on each tab's: the counts have to be there
 * *before* the tab is opened, which is the entire point of showing them, and a tab's
 * ViewModel does not exist until you go there. It is also the only place with a lifetime
 * long enough to hold [LibraryFocus] for the shell.
 */
@HiltViewModel
class HomeShellViewModel @Inject constructor(
    library: LibraryRepository,
    private val libraryFocus: LibraryFocus,
) : ViewModel() {

    val counts: StateFlow<Map<HomeTab, Int>> = combine(
        library.savedCount(),
        library.historyCount(),
    ) { saved, history ->
        mapOf(HomeTab.SAVED to saved, HomeTab.HISTORY to history)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /**
     * Hand a tag to the Library tab, then switch to it.
     *
     * Deliberately not a navigation argument — the tab keeps its scroll and paging state
     * because it is not popped and re-pushed. See [LibraryFocus].
     */
    fun focusTag(tagId: Long) = libraryFocus.focusTag(tagId)
}
