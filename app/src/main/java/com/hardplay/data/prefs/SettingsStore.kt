package com.hardplay.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.hardplay.data.model.CardAspect
import com.hardplay.data.model.LibrarySort
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "hardplay_settings",
)

/**
 * User settings.
 *
 * Preferences DataStore rather than SharedPreferences because every read here is
 * consumed as a flow by a ViewModel, and because `SharedPreferences.getX` on the
 * main thread is a disk read on first touch — which lands squarely on the
 * cold-start frame this app is judged on.
 */
@Singleton
class SettingsStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val store get() = context.settingsDataStore

    val settings: Flow<AppSettings> = store.data.map { prefs ->
        AppSettings(
            discreetLauncher = prefs[Keys.DISCREET_LAUNCHER] ?: true,
            requireUnlock = prefs[Keys.REQUIRE_UNLOCK] ?: true,
            blockScreenshots = prefs[Keys.BLOCK_SCREENSHOTS] ?: true,
            cacheCapBytes = prefs[Keys.CACHE_CAP_BYTES] ?: DEFAULT_CACHE_CAP_BYTES,
            autoTagCaptions = prefs[Keys.AUTO_TAG] ?: true,
            hidePairedStills = prefs[Keys.HIDE_PAIRED_STILLS] ?: true,
            sharpVideoArtwork = prefs[Keys.SHARP_VIDEO_ARTWORK] ?: true,
            backgroundSync = prefs[Keys.BACKGROUND_SYNC] ?: true,
            // No `?: 0`. Zero is NEWEST, and the default order is now SHUFFLE — a
            // library read newest-first only ever surfaces its newest few hundred items.
            // An explicit choice still persists and still wins; this is only what applies
            // before one has been made.
            librarySort = LibrarySort.fromOrdinal(
                prefs[Keys.LIBRARY_SORT] ?: LibrarySort.SHUFFLE.ordinal,
            ),
            gridColumns = prefs[Keys.GRID_COLUMNS] ?: 0,
            cardAspect = CardAspect.fromOrdinal(prefs[Keys.CARD_ASPECT] ?: 0),
            skipSeconds = prefs[Keys.SKIP_SECONDS] ?: DEFAULT_SKIP_SECONDS,
            playbackSpeed = prefs[Keys.PLAYBACK_SPEED] ?: 1f,
            onboardingComplete = prefs[Keys.ONBOARDING_COMPLETE] ?: false,
        )
    }

    suspend fun setDiscreetLauncher(enabled: Boolean) = put(Keys.DISCREET_LAUNCHER, enabled)
    suspend fun setRequireUnlock(enabled: Boolean) = put(Keys.REQUIRE_UNLOCK, enabled)
    suspend fun setBlockScreenshots(enabled: Boolean) = put(Keys.BLOCK_SCREENSHOTS, enabled)
    suspend fun setCacheCapBytes(bytes: Long) = put(Keys.CACHE_CAP_BYTES, bytes)
    suspend fun setAutoTagCaptions(enabled: Boolean) = put(Keys.AUTO_TAG, enabled)
    suspend fun setHidePairedStills(enabled: Boolean) = put(Keys.HIDE_PAIRED_STILLS, enabled)
    suspend fun setSharpVideoArtwork(enabled: Boolean) = put(Keys.SHARP_VIDEO_ARTWORK, enabled)
    suspend fun setBackgroundSync(enabled: Boolean) = put(Keys.BACKGROUND_SYNC, enabled)
    suspend fun setLibrarySort(sort: LibrarySort) = put(Keys.LIBRARY_SORT, sort.ordinal)
    suspend fun setGridColumns(columns: Int) = put(Keys.GRID_COLUMNS, columns)
    suspend fun setCardAspect(aspect: CardAspect) = put(Keys.CARD_ASPECT, aspect.ordinal)
    suspend fun setSkipSeconds(seconds: Int) = put(Keys.SKIP_SECONDS, seconds)
    suspend fun setPlaybackSpeed(speed: Float) = put(Keys.PLAYBACK_SPEED, speed)
    suspend fun setOnboardingComplete(complete: Boolean) = put(Keys.ONBOARDING_COMPLETE, complete)

    private suspend fun <T> put(key: Preferences.Key<T>, value: T) {
        store.edit { it[key] = value }
    }

    private object Keys {
        val DISCREET_LAUNCHER = booleanPreferencesKey("discreet_launcher")
        val REQUIRE_UNLOCK = booleanPreferencesKey("require_unlock")
        val BLOCK_SCREENSHOTS = booleanPreferencesKey("block_screenshots")
        val CACHE_CAP_BYTES = longPreferencesKey("cache_cap_bytes")
        val AUTO_TAG = booleanPreferencesKey("auto_tag_captions")
        val HIDE_PAIRED_STILLS = booleanPreferencesKey("hide_paired_stills")
        val SHARP_VIDEO_ARTWORK = booleanPreferencesKey("sharp_video_artwork")
        val BACKGROUND_SYNC = booleanPreferencesKey("background_sync")
        val LIBRARY_SORT = intPreferencesKey("library_sort")
        val GRID_COLUMNS = intPreferencesKey("grid_columns")
        val CARD_ASPECT = intPreferencesKey("card_aspect")
        val SKIP_SECONDS = intPreferencesKey("skip_seconds")
        val PLAYBACK_SPEED = floatPreferencesKey("playback_speed")
        val ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")
    }

    companion object {
        /** 4 GB, per the locked decision in CLAUDE.md. Adjustable in Settings. */
        const val DEFAULT_CACHE_CAP_BYTES = 4L * 1024 * 1024 * 1024
        const val DEFAULT_SKIP_SECONDS = 10

        val CACHE_CAP_CHOICES = listOf(
            1L * 1024 * 1024 * 1024,
            2L * 1024 * 1024 * 1024,
            4L * 1024 * 1024 * 1024,
            8L * 1024 * 1024 * 1024,
            16L * 1024 * 1024 * 1024,
        )
        val SKIP_CHOICES = listOf(5, 10, 15, 30)
        val SPEED_CHOICES = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
    }
}

/** Immutable snapshot of every setting, so screens observe one object. */
data class AppSettings(
    val discreetLauncher: Boolean,
    val requireUnlock: Boolean,
    /**
     * `FLAG_SECURE` on the window: no screenshots, and a blank recents thumbnail.
     *
     * On by default, because a blank recents card is most of the point of a discreet
     * app. It is a setting rather than a constant for one honest reason: the same flag
     * marks the window as protected content, and some devices render a
     * picture-in-picture window black because of it. Someone who wants PiP more than
     * they want a blank thumbnail should be able to say so.
     */
    val blockScreenshots: Boolean,
    val cacheCapBytes: Long,
    val autoTagCaptions: Boolean,
    /**
     * Fold away a screenshot that is only there to preview the video next to it.
     *
     * On by default: a channel that posts a still and then its video produces two
     * rows for one thing, and a grid showing everything twice is the single biggest
     * reason a synced library looks cluttered. The still stays indexed and
     * searchable either way.
     */
    val hidePairedStills: Boolean,
    /**
     * Decode a frame from videos Telegram gave no artwork at all, and keep it.
     *
     * On by default because the alternative for those items is a 40px blur or a
     * fallback initial, and a library you cannot scan is the defect this app was
     * built to avoid. It costs bandwidth — a couple of megabytes per item, once —
     * so it is bounded, restricted to artless videos, and switchable here.
     */
    val sharpVideoArtwork: Boolean,
    val backgroundSync: Boolean,
    val librarySort: LibrarySort,
    /** 0 = adapt to screen width. Otherwise a forced column count. */
    val gridColumns: Int,
    /** Cell shape. Defaults to 16:9 — see [CardAspect]. */
    val cardAspect: CardAspect,
    val skipSeconds: Int,
    val playbackSpeed: Float,
    val onboardingComplete: Boolean,
)
