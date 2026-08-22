package com.hardplay.di

import android.content.Context
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.hardplay.data.repo.MediaFileRepair
import com.hardplay.telegram.TelegramGateway
import com.hardplay.ui.image.PosterFetcher
import com.hardplay.ui.image.PosterKeyer
import com.hardplay.ui.image.PosterSource
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import okio.Path.Companion.toOkioPath
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ImageModule {

    /**
     * The app's one image loader.
     *
     * Two things here are not defaults. First, the Telegram fetcher is registered
     * so `AsyncImage` can take a [PosterSource] directly and the grid never
     * has to know how artwork arrives. Second, **no network component is
     * configured at all** — Coil ships with an OkHttp-backed HTTP fetcher, and
     * leaving it in place would mean one careless `AsyncImage(model = someUrl)`
     * could make an outbound request, breaking the guarantee in PRD §9 that
     * nothing leaves the device except traffic to Telegram. Every image in this app
     * comes from TDLib or from resources, so an HTTP path is a liability with no
     * upside.
     */
    @Provides
    @Singleton
    fun imageLoader(
        @ApplicationContext context: Context,
        gateway: TelegramGateway,
        repair: MediaFileRepair,
        @AppScope appScope: CoroutineScope,
    ): ImageLoader {
        purgeLegacyCache(context, appScope)
        return ImageLoader.Builder(context)
            .components {
                add(PosterKeyer())
                add(PosterFetcher.Factory(gateway, repair))
            }
            .memoryCache {
                MemoryCache.Builder(context)
                    // A poster grid scrolls fast and revisits rows constantly, so it
                    // is worth more than Coil's default share of the heap.
                    .maxSizePercent(0.28)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(File(context.cacheDir, POSTER_CACHE_DIR).toOkioPath())
                    .maxSizeBytes(POSTER_CACHE_BYTES)
                    .build()
            }
            // Decoded posters are small and the grid is dense; a hardware bitmap can't
            // be read back for the shared-element transition, so it stays off.
            .allowHardware(false)
            .crossfade(false)
            .build()
    }

    /**
     * Delete the pre-fix cache directory, once.
     *
     * Everything in `poster-cache` was keyed on a TDLib **session** file id (see
     * `PosterSource.Rung.Remote.cacheKey`), so any device that had re-logged in was
     * serving one item's artwork for another out of it. The new keys simply never hit
     * those entries — but 512 MB of them would sit there being slowly evicted while the
     * user waits, so the directory is renamed and the old one removed rather than left
     * to age out. Costs one `listFiles` walk on the launch after upgrading and nothing
     * afterwards, because the directory is gone.
     *
     * Off the main thread and fire-and-forget: this is housekeeping, and a failure only
     * means some dead bytes stay on disk until the OS reclaims the cache directory.
     * [AppScope] is already dispatched on IO, so no dispatcher is named here.
     */
    private fun purgeLegacyCache(context: Context, appScope: CoroutineScope) {
        appScope.launch {
            runCatching {
                val legacy = File(context.cacheDir, LEGACY_POSTER_CACHE_DIR)
                if (legacy.isDirectory) legacy.deleteRecursively()
            }
        }
    }

    /**
     * Bumped when the key scheme changed.
     *
     * A cache whose keys mean something different from what its entries were stored
     * under is worse than a cold one, so the directory name carries the scheme version.
     */
    private const val POSTER_CACHE_DIR = "poster-cache-v2"
    private const val LEGACY_POSTER_CACHE_DIR = "poster-cache"

    /**
     * 512 MB of cached artwork.
     *
     * Was 192 MB, and that figure was sized for ~320px thumbnails. Artwork shown large
     * now comes from a ~1280px rung of Telegram's ladder or from a decoded video frame,
     * which is roughly an order of magnitude more bytes per item — at the old ceiling a
     * few screens of scrolling would evict the entries it had just fetched and the grid
     * would re-download its way back down the page.
     *
     * Media chunks remain TDLib's problem, not Coil's, and answer to their own cap.
     */
    private const val POSTER_CACHE_BYTES = 512L * 1024 * 1024
}
