package com.hardplay.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher

/**
 * A scope that outlives every screen.
 *
 * Needed because two things here must not be cancelled by navigation: TDLib's
 * client, whose update handler has to keep draining for the life of the process,
 * and the resume-position write that happens as the player is being torn down.
 * Both would be dropped by a `viewModelScope`.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AppScope

@Module
@InstallIn(SingletonComponent::class)
object CoroutinesModule {

    @Provides
    @IoDispatcher
    fun ioDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @DefaultDispatcher
    fun defaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    @Provides
    @Singleton
    @AppScope
    fun appScope(@IoDispatcher io: CoroutineDispatcher): CoroutineScope =
        // SupervisorJob: one failed background job — a sync that hit a flood-wait,
        // say — must not cancel its siblings or the TDLib update pump.
        CoroutineScope(SupervisorJob() + io)
}
