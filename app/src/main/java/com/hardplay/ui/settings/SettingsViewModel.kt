package com.hardplay.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hardplay.BuildConfig
import com.hardplay.core.Format
import com.hardplay.data.db.dao.MediaDao
import com.hardplay.data.db.dao.SyncStateDao
import com.hardplay.data.prefs.AppSettings
import com.hardplay.data.prefs.SettingsStore
import com.hardplay.data.repo.PlaybackRepository
import com.hardplay.data.model.CardAspect
import com.hardplay.data.model.LibrarySort
import com.hardplay.sync.LibrarySyncWorker
import com.hardplay.telegram.TelegramGateway
import com.hardplay.ui.image.PosterStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsStore,
    private val gateway: TelegramGateway,
    private val discreetLauncher: DiscreetLauncher,
    private val mediaDao: MediaDao,
    private val syncStateDao: SyncStateDao,
    private val playback: PlaybackRepository,
    private val posterStore: PosterStore,
) : ViewModel() {

    val settingsState: StateFlow<AppSettings?> = settings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _ui = MutableStateFlow(
        SettingsUiState(
            hasTdlib = BuildConfig.HAS_TDLIB,
            hasCredentials = BuildConfig.HAS_TELEGRAM_CREDENTIALS,
            isDemo = gateway.isDemo,
            versionName = BuildConfig.VERSION_NAME,
        ),
    )
    val ui: StateFlow<SettingsUiState> = _ui.asStateFlow()

    init {
        refreshCacheSize()
        // Apply the cap on every settings visit. TDLib holds no persistent cap of its
        // own — it only prunes when asked — so this is where the user's choice
        // actually takes effect.
        viewModelScope.launch {
            gateway.applyCacheLimit(settings.settings.first().cacheCapBytes)
        }
    }

    fun refreshCacheSize() {
        viewModelScope.launch {
            _ui.update {
                it.copy(
                    cacheBytes = gateway.cacheSizeBytes(),
                    artworkBytes = posterStore.sizeBytes(),
                )
            }
        }
    }

    fun setDiscreetLauncher(enabled: Boolean) {
        viewModelScope.launch {
            settings.setDiscreetLauncher(enabled)
            discreetLauncher.apply(enabled)
            _ui.update {
                it.copy(
                    notice = if (enabled) {
                        "Launcher shows “Archive”. It can take a moment to update."
                    } else {
                        "Launcher shows “HardPlay”. It can take a moment to update."
                    },
                )
            }
        }
    }

    fun setRequireUnlock(enabled: Boolean) {
        viewModelScope.launch { settings.setRequireUnlock(enabled) }
    }

    /**
     * `FLAG_SECURE` on the window, applied reactively by `MainActivity`.
     *
     * A switch rather than a constant because the same flag marks the window as
     * protected content, and some devices render a picture-in-picture window black
     * while it is set. Someone who wants PiP more than a blank recents thumbnail is
     * entitled to say so.
     */
    fun setBlockScreenshots(enabled: Boolean) {
        viewModelScope.launch { settings.setBlockScreenshots(enabled) }
    }

    /**
     * Decode a frame for videos Telegram gave no artwork at all.
     *
     * On by default. Telegram hands out one thumbnail per video — often no more than
     * 320px — and no larger rung to ask for, so a full-width cell is always upscaling
     * something small. A real frame is the only fix, and it costs a couple of megabytes
     * per item, once.
     */
    fun setSharpVideoArtwork(enabled: Boolean) {
        viewModelScope.launch { settings.setSharpVideoArtwork(enabled) }
    }

    /**
     * Throw away the decoded frames.
     *
     * Safe rather than destructive, which is why it needs no confirmation: an item with
     * no frame falls back to Telegram's own thumbnail, exactly as it did before one was
     * taken. Both halves run — the files, and the rows that name them — because a row
     * pointing at a deleted file would keep a stale cache key alive.
     */
    fun clearExtractedArtwork() {
        viewModelScope.launch {
            _ui.update { it.copy(busy = true) }
            val freed = posterStore.clearFiles()
            mediaDao.clearPosterPaths()
            _ui.update {
                it.copy(
                    busy = false,
                    artworkBytes = 0,
                    notice = if (freed > 0) {
                        "Freed ${Format.bytes(freed)} of extracted artwork."
                    } else {
                        "No extracted artwork to clear."
                    },
                )
            }
        }
    }

    fun setAutoTag(enabled: Boolean) {
        viewModelScope.launch { settings.setAutoTagCaptions(enabled) }
    }

    fun setHidePairedStills(enabled: Boolean) {
        viewModelScope.launch { settings.setHidePairedStills(enabled) }
    }

    fun setBackgroundSync(enabled: Boolean) {
        viewModelScope.launch {
            settings.setBackgroundSync(enabled)
            if (enabled) {
                LibrarySyncWorker.schedule(context)
            } else {
                LibrarySyncWorker.cancel(context)
            }
        }
    }

    fun setCacheCap(bytes: Long) {
        viewModelScope.launch {
            settings.setCacheCapBytes(bytes)
            gateway.applyCacheLimit(bytes)
            refreshCacheSize()
        }
    }

    fun setGridColumns(columns: Int) {
        viewModelScope.launch { settings.setGridColumns(columns) }
    }

    fun setCardAspect(aspect: CardAspect) {
        viewModelScope.launch { settings.setCardAspect(aspect) }
    }

    fun setSkipSeconds(seconds: Int) {
        viewModelScope.launch { settings.setSkipSeconds(seconds) }
    }

    /**
     * The speed a newly opened item starts at.
     *
     * Shares its key with the player's own speed control, so changing speed mid-item
     * also changes the default. That is deliberate — a speed you chose is a speed you
     * prefer, and two separate values would mean explaining the difference.
     */
    fun setDefaultSpeed(speed: Float) {
        viewModelScope.launch { settings.setPlaybackSpeed(speed) }
    }

    fun setSort(sort: LibrarySort) {
        viewModelScope.launch { settings.setLibrarySort(sort) }
    }

    fun clearCache() {
        viewModelScope.launch {
            _ui.update { it.copy(busy = true) }
            gateway.clearCache()
            _ui.update { it.copy(busy = false, notice = "Cached media cleared.") }
            refreshCacheSize()
        }
    }

    /**
     * Rebuild the full-text index.
     *
     * Worth a button because the index is the one derived structure the app cannot
     * repair by re-syncing: a caption change that failed to reindex leaves an item
     * unsearchable while looking perfectly fine in the grid.
     */
    fun rebuildSearchIndex() {
        viewModelScope.launch {
            _ui.update { it.copy(busy = true) }
            mediaDao.reindexAll()
            _ui.update { it.copy(busy = false, notice = "Search index rebuilt.") }
        }
    }

    fun clearWatchHistory() {
        viewModelScope.launch {
            playback.clearAll()
            _ui.update { it.copy(notice = "Resume positions cleared.") }
        }
    }

    fun signOut(onDone: () -> Unit) {
        viewModelScope.launch {
            _ui.update { it.copy(busy = true) }
            gateway.logOut()
            settings.setOnboardingComplete(false)
            _ui.update { it.copy(busy = false) }
            onDone()
        }
    }

    fun dismissNotice() = _ui.update { it.copy(notice = null) }
}

data class SettingsUiState(
    val hasTdlib: Boolean,
    val hasCredentials: Boolean,
    val isDemo: Boolean,
    val versionName: String,
    val cacheBytes: Long = 0,
    /** Disk held by decoded video frames. See `PosterStore`. */
    val artworkBytes: Long = 0,
    val busy: Boolean = false,
    val notice: String? = null,
) {
    /** Plain-language answer to "why can't I stream anything?". */
    val engineSummary: String
        get() = when {
            !hasTdlib && !hasCredentials ->
                "No TDLib and no credentials — demo mode."
            !hasTdlib -> "Credentials present, TDLib missing — run tools/build-tdlib.sh."
            !hasCredentials -> "TDLib present, credentials missing — see local.properties."
            isDemo -> "TDLib present but not usable on this device — demo mode."
            else -> "TDLib active."
        }
}
