package com.hardplay.telegram.tdlib

import android.content.Context
import com.hardplay.telegram.TelegramGateway
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope

/**
 * The no-TDLib half of the pair.
 *
 * `app/build.gradle.kts` wires `src/no-tdlib/kotlin` into the main source set
 * whenever `src/main/java/org/drinkless/tdlib/TdApi.java` is absent, so this file
 * and its twin in `src/tdlib/kotlin` are never compiled together. Keep the two
 * signatures identical — a mismatch only shows up as a compile failure in
 * whichever configuration nobody happens to be building.
 *
 * Returning null is not an error path. It means "this build has no Telegram
 * engine", and `di/TelegramModule` answers it with the demo gateway.
 */
object TdlibGatewayFactory {

    const val AVAILABLE: Boolean = false

    @Suppress("UNUSED_PARAMETER")
    fun create(
        context: Context,
        io: CoroutineDispatcher,
        scope: CoroutineScope,
        apiId: Int,
        apiHash: String,
    ): TelegramGateway? = null
}
