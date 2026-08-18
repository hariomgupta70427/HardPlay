package com.hardplay

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import javax.inject.Provider

/**
 * There is deliberately nothing here beyond DI setup. No analytics init, no
 * crash reporter, no remote config, no "phone home on first launch" — the
 * guarantee in PRD §9 is that nothing leaves the device except traffic to
 * Telegram, and an Application class is where that guarantee usually dies.
 */
@HiltAndroidApp
class HardPlayApp : Application(), Configuration.Provider, ImageLoaderFactory {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    /**
     * A [Provider], not a direct injection.
     *
     * Coil asks for the loader through [newImageLoader], which can be called
     * before field injection has finished on some launch paths. More importantly,
     * building the loader pulls in the Telegram gateway, and constructing that
     * eagerly on the main thread during `Application.onCreate` would load
     * `libtdjni.so` on the cold-start frame. Deferring it keeps launch cheap.
     */
    @Inject
    lateinit var imageLoader: Provider<ImageLoader>

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(android.util.Log.WARN)
            .build()

    /** Makes every `AsyncImage` in the app use the loader from [com.hardplay.di.ImageModule]. */
    override fun newImageLoader(): ImageLoader = imageLoader.get()
}
