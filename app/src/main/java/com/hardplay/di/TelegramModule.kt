package com.hardplay.di

import android.content.Context
import android.util.Log
import com.hardplay.BuildConfig
import com.hardplay.telegram.DemoTelegramGateway
import com.hardplay.telegram.TelegramGateway
import com.hardplay.telegram.tdlib.TdlibGatewayFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import javax.inject.Singleton

/**
 * The one file in the app that knows whether TDLib exists.
 *
 * That is a hard architectural rule, not a stylistic one (CLAUDE.md): everything
 * else takes [TelegramGateway] and cannot tell which implementation it received.
 * If a second `BuildConfig.HAS_TDLIB` check ever appears elsewhere in the tree,
 * something has gone wrong here.
 *
 * Two independent things can be missing, and they fail differently:
 *
 *  * **The bindings** — `libtdjni.so` plus `org.drinkless.tdlib`. Absent until
 *    `tools/build-tdlib.sh` has run. `TdlibGatewayFactory` returns null.
 *  * **The credentials** — `api_id` / `api_hash` from local.properties. Present
 *    bindings with no credentials would reach `setTdlibParameters` and be rejected
 *    by Telegram, so that case is caught here instead of surfacing as an opaque
 *    auth error three screens later.
 *
 * Either one missing means demo mode, which is a supported configuration rather
 * than a degraded one.
 */
@Module
@InstallIn(SingletonComponent::class)
object TelegramModule {

    @Provides
    @Singleton
    fun telegramGateway(
        @ApplicationContext context: Context,
        @IoDispatcher io: CoroutineDispatcher,
        @AppScope scope: CoroutineScope,
    ): TelegramGateway {
        if (!BuildConfig.HAS_TELEGRAM_CREDENTIALS) {
            Log.i(TAG, "No api_id/api_hash in local.properties — demo mode.")
            return DemoTelegramGateway(context, io)
        }

        val real = TdlibGatewayFactory.create(
            context = context,
            io = io,
            scope = scope,
            apiId = BuildConfig.TELEGRAM_API_ID,
            apiHash = BuildConfig.TELEGRAM_API_HASH,
        )

        if (real == null) {
            Log.i(TAG, "TDLib bindings unavailable — demo mode.")
            return DemoTelegramGateway(context, io)
        }
        return real
    }

    private const val TAG = "HardPlay/DI"
}
