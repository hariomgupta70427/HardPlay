package com.hardplay.telegram.tdlib

import android.content.Context
import android.util.Log
import com.hardplay.telegram.TelegramGateway
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope

/**
 * The TDLib half of the pair. Compiled only when the generated bindings exist —
 * see the twin in `src/no-tdlib/kotlin` and keep the signatures identical.
 *
 * The `runCatching(Throwable)` is deliberately that broad. Touching
 * [TdlibTelegramGateway] loads `libtdjni.so` through a static initialiser, and
 * the ways that fails are `UnsatisfiedLinkError` and `ExceptionInInitializerError`
 * — [Error]s, not [Exception]s, so a narrower catch would let a missing or
 * wrong-ABI library take down the process on launch instead of falling back to
 * demo mode.
 */
object TdlibGatewayFactory {

    const val AVAILABLE: Boolean = true

    fun create(
        context: Context,
        io: CoroutineDispatcher,
        scope: CoroutineScope,
        apiId: Int,
        apiHash: String,
    ): TelegramGateway? = runCatching {
        TdlibTelegramGateway(
            context = context,
            io = io,
            scope = scope,
            apiId = apiId,
            apiHash = apiHash,
        )
    }.onFailure { failure ->
        Log.e("HardPlay", "TDLib present but unusable; falling back to demo mode", failure)
    }.getOrNull()
}
