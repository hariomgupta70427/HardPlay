package com.hardplay.telegram

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Everything the app can ask of Telegram.
 *
 * This interface is the seam the whole architecture turns on (CLAUDE.md):
 * `libtdjni.so` and the generated `org.drinkless.tdlib` bindings are build
 * outputs, so exactly two implementations exist — the real one in
 * `src/tdlib/kotlin`, and [DemoTelegramGateway] — and exactly one file,
 * `di/TelegramModule`, knows which is in play. No screen, ViewModel or
 * repository may branch on TDLib's presence; they take this type and are done.
 *
 * All calls are `suspend` and safe to make from the main dispatcher; the
 * implementation moves work off it.
 */
interface TelegramGateway {

    /** True when this is [DemoTelegramGateway]. For a UI banner only — never for
     *  behaviour, which is what the interface exists to make uniform. */
    val isDemo: Boolean

    val authState: StateFlow<TelegramAuthState>
    val connectionState: StateFlow<TelegramConnectionState>

    /**
     * Boot TDLib and restore any stored session. Idempotent — the biometric gate
     * and the sync worker both call it without coordinating.
     */
    suspend fun start()

    /** Release the client. Called when the process is going away for good. */
    suspend fun close()

    // ------------------------------------------------------------------ auth

    /** @param phoneNumber in international form, with or without a leading `+`. */
    suspend fun requestVerificationCode(phoneNumber: String): GatewayResult<Unit>

    suspend fun submitVerificationCode(code: String): GatewayResult<Unit>

    /** Second factor, when the account has one set. */
    suspend fun submitPassword(password: String): GatewayResult<Unit>

    suspend fun resendVerificationCode(): GatewayResult<Unit>

    /** Ends the session and wipes TDLib's local database. */
    suspend fun logOut(): GatewayResult<Unit>

    // -------------------------------------------------------------- channels

    /**
     * Channels the account can read, for the picker.
     *
     * @param limit chats to consider from the main list. TDLib pages its chat
     *   list, so this bounds a first run on an account with thousands of chats.
     */
    suspend fun loadChannels(limit: Int = 400): GatewayResult<List<TelegramChat>>

    /** Resolve `@handle`, a `t.me/…` link, or a raw chat id typed into the picker. */
    suspend fun resolveChannel(query: String): GatewayResult<TelegramChat>

    suspend fun channelById(chatId: Long): GatewayResult<TelegramChat>

    // --------------------------------------------------------------- history

    /**
     * One page of history, walking newest-to-oldest.
     *
     * @param fromMessageId 0 starts at the newest message. Otherwise the page
     *   contains messages strictly older than this id, so passing
     *   [TelegramHistoryPage.oldestMessageId] back walks history without gaps or
     *   repeats.
     * @param limit TDLib caps a page near 100 regardless of what's asked.
     */
    suspend fun fetchHistory(
        chatId: Long,
        fromMessageId: Long,
        limit: Int = 100,
    ): GatewayResult<TelegramHistoryPage>

    // ----------------------------------------------------------------- files

    /**
     * Download a thumbnail in full and return its local path. Thumbnails are a
     * few KB, so unlike media they're fetched whole and eagerly (PRD §5.2).
     */
    suspend fun downloadThumbnail(fileId: Int): GatewayResult<String>

    /**
     * Download a whole file at viewing priority and return its local path.
     *
     * The photo viewer's path, and the reason it exists separately from
     * [downloadThumbnail]: a photo message carries a *ladder* of sizes, and the rung
     * that belongs in a grid cell is not the rung that belongs on a full screen.
     * Opening an image and being shown its 320px preview — upscaled — was the single
     * most visible quality defect in the app.
     *
     * Priority is above thumbnails and level with streaming, because this is a file
     * the user is currently staring at. Only call it for things measured in
     * megabytes; it returns once the whole file has arrived.
     */
    suspend fun downloadOriginal(fileId: Int): GatewayResult<String>

    /**
     * Ask TDLib for `[offset, offset + limit)` of a file and return once at least
     * one byte at [offset] is readable.
     *
     * This is the streaming primitive (PRD §5.4): it maps onto `downloadFile`
     * with an offset and a limit, which is how the official client plays a 4 GB
     * file without downloading it first.
     *
     * @param limit bytes wanted; 0 means "to the end of the file".
     */
    suspend fun requestRange(
        fileId: Int,
        offset: Long,
        limit: Long,
    ): GatewayResult<TelegramFileState>

    /** Progress for a file. Emits on every TDLib file update, and immediately on
     *  collection with the last known state. */
    fun observeFile(fileId: Int): Flow<TelegramFileState>

    /**
     * Stop an in-flight download. Called on seek: without it, the abandoned
     * range keeps consuming bandwidth and competes with the range just asked for.
     */
    suspend fun cancelDownload(fileId: Int)

    /**
     * Re-resolve a persistent remote id into a session-scoped file id.
     *
     * Needed after TDLib's database is recreated — stored [TelegramMessage.fileId]
     * values are dead at that point, while `remoteFileId` still resolves. Turns a
     * full re-index into a lazy repair.
     *
     * @param kind which file type TDLib should resolve the reference as. It is not
     *   cosmetic: `getRemoteFile` takes the type as an argument and a photo asked for
     *   as a video comes back refused. Defaults to video because the streaming path
     *   is the caller that runs on every playback.
     */
    suspend fun fileIdForRemoteId(
        remoteFileId: String,
        kind: TelegramMediaKind = TelegramMediaKind.VIDEO,
    ): GatewayResult<Int>

    // ----------------------------------------------------------------- cache

    /** Apply the user's chunk-cache cap (CLAUDE.md: 4 GB default, adjustable). */
    suspend fun applyCacheLimit(bytes: Long)

    /** Bytes TDLib is currently holding in its file store. */
    suspend fun cacheSizeBytes(): Long

    /** Evict cached media. Never touches the session database. */
    suspend fun clearCache(): GatewayResult<Unit>
}
