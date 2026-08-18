package com.hardplay.ui.root

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.getSystemService
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hardplay.data.db.dao.SyncStateDao
import com.hardplay.data.prefs.SettingsStore
import com.hardplay.data.repo.ChannelRepository
import com.hardplay.sync.LibraryIndexer
import com.hardplay.sync.LibrarySyncWorker
import com.hardplay.sync.SyncMode
import com.hardplay.telegram.TelegramAuthState
import com.hardplay.telegram.TelegramGateway
import com.hardplay.ui.image.FrameHarvester
import com.hardplay.ui.settings.DiscreetLauncher
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Decides which of the four possible first screens the app shows.
 *
 * There is no navigation between these: they are states, not destinations. Modelling
 * "locked" or "signed out" as a route would put them on a back stack, and a back
 * gesture that returns you *past* the lock screen is not a lock screen.
 */
@HiltViewModel
class RootViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsStore,
    private val gateway: TelegramGateway,
    private val channels: ChannelRepository,
    private val discreetLauncher: DiscreetLauncher,
    private val indexer: LibraryIndexer,
    private val syncStateDao: SyncStateDao,
    private val frameHarvester: FrameHarvester,
) : ViewModel() {

    private val unlocked = MutableStateFlow(false)

    val state: StateFlow<RootState> = combine(
        settings.settings,
        gateway.authState,
        channels.observeCount(),
        unlocked,
    ) { appSettings, auth, channelCount, isUnlocked ->
        when {
            appSettings.requireUnlock && !isUnlocked -> RootState.Locked
            auth is TelegramAuthState.Initialising -> RootState.Starting
            auth is TelegramAuthState.Ready -> {
                if (channelCount == 0) RootState.NeedsSources else RootState.Ready
            }
            // Unavailable is terminal but still needs a screen that explains itself,
            // and the login screen is the one that already does.
            else -> RootState.NeedsAuth
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, RootState.Starting)

    /**
     * Re-locks after the app has been away for a while.
     *
     * Locking on every `onStop` would demand a fingerprint after every notification
     * shade pull or share-sheet dismissal, which trains people to turn the lock off.
     * A grace period keeps the gate meaningful without making it hostile.
     */
    private val backgroundWatcher = object : DefaultLifecycleObserver {
        private var leftAt = 0L

        override fun onStop(owner: LifecycleOwner) {
            leftAt = System.currentTimeMillis()
        }

        override fun onStart(owner: LifecycleOwner) {
            if (leftAt == 0L) return
            if (System.currentTimeMillis() - leftAt > RELOCK_GRACE_MS) {
                unlocked.value = false
            }
        }
    }

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(backgroundWatcher)

        viewModelScope.launch {
            gateway.start()

            // Keep the launcher alias in step with the stored preference. The two can
            // drift: a reinstall resets component state to the manifest defaults while
            // the DataStore value survives in a backup-free but not wipe-free way.
            val appSettings = settings.settings.first()
            if (discreetLauncher.isDiscreet() != appSettings.discreetLauncher) {
                discreetLauncher.apply(appSettings.discreetLauncher)
            }

            gateway.applyCacheLimit(appSettings.cacheCapBytes)
            if (appSettings.backgroundSync) LibrarySyncWorker.schedule(context)

            continueUnfinishedIndexing()
            harvestArtworkIfUnmetered()
        }
    }

    /**
     * Resume a backfill that has not finished, on launch.
     *
     * Two things need this and neither is served by the head sync the library screen
     * runs. A first index of a large channel spans several launches by design, and
     * leaving it to a "keep indexing" button means it only advances when someone
     * remembers to press one. And a schema upgrade that rewinds the tail cursor —
     * `MIGRATION_2_3` does, to pick up the larger artwork rung for rows already
     * indexed — produces exactly this state, so without this the new artwork would
     * never appear for anything already in the library.
     *
     * `SyncMode.FULL` rather than `BACKFILL`: the indexer runs one sync at a time and
     * *drops* a second request rather than queueing it, so a bare backfill here would
     * silently swallow the library screen's head sync and new posts would not show up
     * on this launch. FULL walks both ends under one budget.
     *
     * Costs nothing once history is complete: `backfillComplete` is then true for
     * every channel and this returns without a single request.
     */
    private suspend fun continueUnfinishedIndexing() {
        val unfinished = syncStateDao.observeIncompleteBackfills().first()
        if (unfinished > 0) indexer.sync(SyncMode.FULL)
    }

    /**
     * Decode frames for videos Telegram gave no artwork at all — but only on Wi-Fi.
     *
     * The background sync job already does this and already carries an UNMETERED
     * constraint, so strictly speaking this is a duplicate. It is here because the
     * background job runs every six hours and the improvement is the whole point of
     * the release: waiting until tonight to see sharper artwork is indistinguishable
     * from the feature not working.
     *
     * The metered check is not optional. A frame costs a couple of megabytes and the
     * sweep will happily do two dozen of them, which is a real amount of somebody's
     * data allowance to spend on thumbnails without asking. `FrameHarvester` caps
     * itself and returns immediately when the setting is off or the app is in demo
     * mode, so this call is cheap in every case that isn't the intended one.
     */
    private suspend fun harvestArtworkIfUnmetered() {
        if (!isUnmetered()) return
        runCatching { frameHarvester.sweep() }
    }

    private fun isUnmetered(): Boolean {
        val manager = context.getSystemService<ConnectivityManager>() ?: return false
        val capabilities = manager.activeNetwork?.let(manager::getNetworkCapabilities) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    fun onUnlocked() {
        unlocked.value = true
    }

    override fun onCleared() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(backgroundWatcher)
        super.onCleared()
    }

    private companion object {
        /** Long enough to survive a shade pull, short enough to matter. */
        const val RELOCK_GRACE_MS = 60_000L
    }
}

sealed interface RootState {
    /** TDLib is still reading its database; show nothing rather than a wrong screen. */
    data object Starting : RootState
    data object Locked : RootState
    data object NeedsAuth : RootState
    data object NeedsSources : RootState
    data object Ready : RootState
}
