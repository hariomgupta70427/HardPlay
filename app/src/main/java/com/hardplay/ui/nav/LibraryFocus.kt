package com.hardplay.ui.nav

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A request for the Library tab to show one tag.
 *
 * Discover's tag cloud needs to hand a tag to a different tab. The obvious way to do
 * that is a navigation argument — `tab/library?tagId={tagId}` — and it was written that
 * way first. Two things make this better:
 *
 *  * **A route with an optional argument is registered under the whole pattern.** A
 *    destination's id is the hash of the exact string it was registered with, so the bare
 *    route and the pattern are different ids, and every `startDestination` or `popUpTo`
 *    naming one while the graph holds the other fails — at launch, on the app's front
 *    door, with an error about a start destination rather than about the argument.
 *  * **Arriving by argument means arriving on a new entry.** The Library tab would be
 *    popped and re-pushed to carry the tag, discarding the scroll position and paging
 *    state that `saveState`/`restoreState` exist to keep.
 *
 * A one-shot [StateFlow] instead. A `StateFlow` rather than an event channel because the
 * Library tab's ViewModel does not necessarily exist at the moment the chip is tapped —
 * its back-stack entry may have been popped while the user was on Discover — and a hot
 * event emitted into an empty room is lost. The value waits; whoever comes alive next
 * reads it and calls [clear].
 */
@Singleton
class LibraryFocus @Inject constructor() {

    private val _tagId = MutableStateFlow<Long?>(null)

    /** Non-null when a tag is waiting to be applied. Cleared by the consumer. */
    val tagId: StateFlow<Long?> = _tagId.asStateFlow()

    fun focusTag(tagId: Long) { _tagId.value = tagId }

    fun clear() { _tagId.value = null }
}
