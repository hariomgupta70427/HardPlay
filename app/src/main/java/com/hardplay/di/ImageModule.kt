package com.hardplay.di

import android.content.Context
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.hardplay.telegram.TelegramGateway
import com.hardplay.ui.image.PosterFetcher
import com.hardplay.ui.image.PosterKeyer
import com.hardplay.ui.image.PosterSource
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
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
    ): ImageLoader = ImageLoader.Builder(context)
        .components {
            add(PosterKeyer())
            add(PosterFetcher.Factory(gateway))
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
                .directory(File(context.cacheDir, "poster-cache").toOkioPath())
                .maxSizeBytes(POSTER_CACHE_BYTES)
                .build()
        }
        // Decoded posters are small and the grid is dense; a hardware bitmap can't
        // be read back for the shared-element transition, so it stays off.
        .allowHardware(false)
        .crossfade(false)
        .build()

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
