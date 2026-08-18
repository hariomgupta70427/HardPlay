package com.hardplay.ui.media

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.core.content.getSystemService
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hardplay.data.db.dao.FavouriteDao
import com.hardplay.data.db.entity.TagEntity
import com.hardplay.data.db.projection.LibraryRow
import com.hardplay.data.repo.LibraryRepository
import com.hardplay.data.repo.PlaybackRepository
import com.hardplay.data.repo.TagRepository
import com.hardplay.playback.ExternalOpen
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The per-item actions, for every list in the app.
 *
 * One ViewModel rather than one per screen, and one per *screen* rather than one per
 * card: a grid holds dozens of cards and a ViewModel each would be dozens of database
 * subscriptions for a sheet that is shut. The card only reports which item was tapped;
 * everything else hangs off [open].
 *
 * Pair it with `MediaActionHost`, which draws whichever sheet [mode] says is up. A
 * screen wires the whole feature with two lines — the host, and `onMenu` on its cards
 * — which is deliberate: the three-dot control was drawn on the poster for a whole
 * release before anything was behind it, and a dead affordance is worse than no
 * affordance.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MediaActionsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val library: LibraryRepository,
    private val favourites: FavouriteDao,
    private val playback: PlaybackRepository,
    private val tags: TagRepository,
    private val externalOpen: ExternalOpen,
) : ViewModel() {

    /** Which sheet is showing. One value, so two can never be up at once. */
    enum class Mode { NONE, ACTIONS, TAGS }

    private val _mode = MutableStateFlow(Mode.NONE)
    val mode: StateFlow<Mode> = _mode.asStateFlow()

    private val _target = MutableStateFlow<Long?>(null)

    private val _notice = MutableStateFlow<String?>(null)
    /** A one-line result for actions with no visible consequence of their own. */
    val notice: StateFlow<String?> = _notice.asStateFlow()

    /**
     * The item, observed rather than read once.
     *
     * The sheet's own actions change this row — saving it, marking it watched — and a
     * snapshot would leave the labels describing the state the sheet opened in.
     */
    val row: StateFlow<LibraryRow?> = _target
        .flatMapLatest { id -> if (id == null) flowOf(null) else library.observeRow(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val itemTags: StateFlow<List<TagEntity>> = _target
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else tags.observeForItem(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _external = MutableStateFlow<ExternalOpen.State>(ExternalOpen.State.Absent)
    val external: StateFlow<ExternalOpen.State> = _external.asStateFlow()

    fun open(localId: Long) {
        _target.value = localId
        _mode.value = Mode.ACTIONS
        _notice.value = null
        // Asked once per opening rather than observed: whether the bytes are on disk
        // only changes as a result of playback, and playback is not what is happening
        // while this sheet is up.
        viewModelScope.launch {
            val fileId = library.row(localId)?.fileId
            _external.value = if (fileId == null) {
                ExternalOpen.State.Absent
            } else {
                externalOpen.state(fileId)
            }
        }
    }

    fun dismiss() {
        _mode.value = Mode.NONE
        _target.value = null
        _external.value = ExternalOpen.State.Absent
    }

    /** Swaps the actions sheet for the tag editor on the same item. */
    fun editTags() {
        if (_target.value != null) _mode.value = Mode.TAGS
    }

    fun backToActions() {
        if (_target.value != null) _mode.value = Mode.ACTIONS
    }

    fun dismissNotice() { _notice.value = null }

    // --------------------------------------------------------------- actions

    /**
     * Saved state flips through the DAO's own transaction rather than being read,
     * negated and written here — otherwise a double tap reads the same value twice
     * and writes the same state twice.
     */
    fun toggleSaved() {
        val id = _target.value ?: return
        viewModelScope.launch {
            val saved = favourites.toggle(id, System.currentTimeMillis())
            _notice.value = if (saved) "Saved." else "Removed from saved."
        }
    }

    fun setViewed(viewed: Boolean) {
        val id = _target.value ?: return
        viewModelScope.launch {
            if (viewed) playback.markViewed(id) else playback.markUnviewed(id)
            _notice.value = if (viewed) "Marked as watched." else "Marked as unwatched."
        }
    }

    fun copyCaption() {
        val current = row.value ?: return
        val text = current.caption.takeIf { it.isNotBlank() } ?: current.title
        val clipboard = context.getSystemService<ClipboardManager>() ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(current.title, text))
        // Android 13+ shows its own copy confirmation, so saying it again would be
        // two toasts for one action.
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
            _notice.value = "Caption copied."
        }
    }

    /**
     * Hand the file to another app.
     *
     * @param activityContext must be the activity, not the application: the chooser is
     *   a window, and launching it from the application context is the classic source
     *   of a chooser that appears behind everything.
     */
    fun openExternally(activityContext: Context) {
        val current = row.value ?: return
        val ready = _external.value as? ExternalOpen.State.Ready ?: return
        val opened = externalOpen.launch(
            context = activityContext,
            path = ready.path,
            mimeType = if (current.isVideo) "video/*" else "image/*",
            title = current.title,
        )
        if (!opened) _notice.value = "No app on this device opens that file."
        else dismiss()
    }

    // ---------------------------------------------------------------- tags

    fun addTag(name: String) {
        val id = _target.value ?: return
        viewModelScope.launch { tags.addToItem(id, name) }
    }

    fun removeTag(tagId: Long) {
        val id = _target.value ?: return
        viewModelScope.launch { tags.removeFromItem(id, tagId) }
    }

    suspend fun suggestTags(prefix: String): List<String> = tags.suggest(prefix).map { it.name }
}
