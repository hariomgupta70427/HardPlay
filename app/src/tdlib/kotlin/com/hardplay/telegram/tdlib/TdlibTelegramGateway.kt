package com.hardplay.telegram.tdlib

import android.content.Context
import android.os.Build
import android.util.Log
import com.hardplay.BuildConfig
import com.hardplay.telegram.GatewayError
import com.hardplay.telegram.GatewayResult
import com.hardplay.telegram.TelegramAuthState
import com.hardplay.telegram.TelegramChat
import com.hardplay.telegram.TelegramConnectionState
import com.hardplay.telegram.TelegramFileState
import com.hardplay.telegram.TelegramGateway
import com.hardplay.telegram.TelegramHistoryPage
import com.hardplay.telegram.TelegramMediaKind
import com.hardplay.telegram.TelegramMessage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

/**
 * TDLib, wrapped.
 *
 * The whole file exists to convert two impedance mismatches:
 *
 *  * **Callbacks to coroutines.** TDLib is a request/response queue with a single
 *    update stream. [send] turns one query into one `suspend` call; updates that
 *    arrive unbidden (auth moved on, a file grew, the socket dropped) are pushed
 *    into flows instead.
 *  * **`TdApi` to plain Kotlin.** Nothing `TdApi` leaves this file. That is the
 *    rule the build depends on — the bindings are a build output, so a `TdApi`
 *    type in a shared signature would stop the app compiling without them.
 *
 * TdApi objects are constructed field-by-field through their no-argument
 * constructors rather than positionally. Generated positional constructors change
 * shape between TDLib releases; field names are far more stable, so this survives
 * a TDLib bump that would otherwise be a compile error in a dozen places.
 */
internal class TdlibTelegramGateway(
    private val context: Context,
    private val io: CoroutineDispatcher,
    private val scope: CoroutineScope,
    private val apiId: Int,
    private val apiHash: String,
) : TelegramGateway {

    override val isDemo: Boolean = false

    private val _authState = MutableStateFlow<TelegramAuthState>(TelegramAuthState.Initialising)
    override val authState: StateFlow<TelegramAuthState> = _authState.asStateFlow()

    private val _connectionState = MutableStateFlow(TelegramConnectionState.CONNECTING)
    override val connectionState: StateFlow<TelegramConnectionState> = _connectionState.asStateFlow()

    private val clientRef = AtomicReference<Client?>(null)
    private val startLock = Mutex()

    /**
     * Last known state per file id.
     *
     * TDLib reports file progress only as deltas, so a reader that subscribes
     * mid-download would otherwise wait for the next update before knowing
     * anything. Seeding a subscriber from here is what makes a seek into
     * already-downloaded bytes resolve instantly instead of after a round trip.
     */
    private val fileStates = ConcurrentHashMap<Int, TelegramFileState>()

    private val fileUpdates = MutableSharedFlow<TelegramFileState>(
        replay = 0,
        // Generous: a 2 GB file at 1 MB parts produces a steady stream of updates
        // and dropping them would stall a reader waiting on a prefix.
        extraBufferCapacity = 512,
    )

    /** Phone number in flight, so a code screen can name it. */
    @Volatile private var pendingPhoneNumber: String = ""

    // ----------------------------------------------------------------- start

    override suspend fun start() {
        startLock.withLock {
            if (clientRef.get() != null) return
            configureLogging()

            val client = runCatching {
                Client.create(
                    { update -> onUpdate(update) },
                    { error -> Log.w(TAG, "TDLib update handler threw", error) },
                    { error -> Log.w(TAG, "TDLib default handler threw", error) },
                )
            }.getOrElse { failure ->
                Log.e(TAG, "Client.create failed", failure)
                _authState.value = TelegramAuthState.Unavailable(
                    failure.message ?: "TDLib could not start.",
                )
                return
            }
            clientRef.set(client)
        }

        // TDLib answers a fresh client with authorizationStateWaitTdlibParameters
        // almost immediately; onUpdate replies to it. Waiting here means callers
        // get a gateway that has decided whether it needs a login.
        withTimeoutOrNull(START_TIMEOUT_MS) {
            authState.first { it !is TelegramAuthState.Initialising }
        }
    }

    private fun configureLogging() = runCatching {
        if (BuildConfig.DEBUG) {
            Client.execute(TdApi.SetLogVerbosityLevel().apply { newVerbosityLevel = 2 })
        } else {
            // No log file at all in release. TDLib's log records phone numbers and
            // chat ids, and PRD §9 promises nothing is retained that doesn't need
            // to be.
            Client.execute(TdApi.SetLogVerbosityLevel().apply { newVerbosityLevel = 0 })
            Client.execute(TdApi.SetLogStream().apply { logStream = TdApi.LogStreamEmpty() })
        }
    }

    override suspend fun close() {
        val client = clientRef.getAndSet(null) ?: return
        runCatching { client.send(TdApi.Close(), {}) }
        _authState.value = TelegramAuthState.Closed
    }

    // --------------------------------------------------------------- updates

    private fun onUpdate(update: TdApi.Object) {
        when (update) {
            is TdApi.UpdateAuthorizationState -> onAuthorizationState(update.authorizationState)
            is TdApi.UpdateConnectionState -> _connectionState.value = mapConnection(update.state)
            is TdApi.UpdateFile -> publishFile(update.file)
            else -> Unit
        }
    }

    private fun onAuthorizationState(state: TdApi.AuthorizationState?) {
        when (state) {
            is TdApi.AuthorizationStateWaitTdlibParameters ->
                scope.launch { sendTdlibParameters() }

            is TdApi.AuthorizationStateWaitPhoneNumber ->
                _authState.value = TelegramAuthState.WaitingForPhoneNumber()

            is TdApi.AuthorizationStateWaitCode ->
                _authState.value = TelegramAuthState.WaitingForCode(
                    phoneNumber = state.codeInfo?.phoneNumber?.takeIf { it.isNotEmpty() }
                        ?: pendingPhoneNumber,
                    codeLength = codeLength(state.codeInfo?.type),
                    resendIn = state.codeInfo?.timeout ?: 0,
                )

            is TdApi.AuthorizationStateWaitPassword ->
                _authState.value = TelegramAuthState.WaitingForPassword(
                    passwordHint = state.passwordHint?.takeIf { it.isNotEmpty() },
                    hasRecoveryEmail = state.hasRecoveryEmailAddress,
                )

            is TdApi.AuthorizationStateReady ->
                _authState.value = TelegramAuthState.Ready

            is TdApi.AuthorizationStateLoggingOut ->
                _authState.value = TelegramAuthState.LoggingOut

            is TdApi.AuthorizationStateClosed -> {
                // The client is dead and cannot be reused; dropping the reference
                // is what lets a subsequent start() build a fresh one, which is
                // the whole log-out-then-log-in-again path.
                clientRef.set(null)
                _authState.value = TelegramAuthState.Closed
            }

            is TdApi.AuthorizationStateWaitRegistration ->
                _authState.value = TelegramAuthState.Unavailable(
                    "That number has no Telegram account. HardPlay reads an " +
                        "existing account; it can't create one.",
                )

            else -> Unit
        }
    }

    private suspend fun sendTdlibParameters() {
        val databaseDir = File(context.noBackupFilesDir, "tdlib").apply { mkdirs() }
        // Media chunks land here and can reach the cache cap, so it is separate
        // from the session database — and both sit under noBackupFilesDir, which
        // keeps an authenticated session out of every backup and transfer channel.
        val filesDir = File(context.noBackupFilesDir, "tdlib-files").apply { mkdirs() }

        val query = TdApi.SetTdlibParameters().apply {
            useTestDc = false
            databaseDirectory = databaseDir.absolutePath
            filesDirectory = filesDir.absolutePath
            databaseEncryptionKey = TdlibSessionKey(context).obtain()
            useFileDatabase = true
            useChatInfoDatabase = true
            useMessageDatabase = true
            // No secret chats: HardPlay never reads them, and enabling the
            // subsystem only widens what the session can touch.
            useSecretChats = false
            apiId = this@TdlibTelegramGateway.apiId
            apiHash = this@TdlibTelegramGateway.apiHash
            systemLanguageCode = Locale.getDefault().language.ifEmpty { "en" }
            deviceModel = Build.MODEL ?: "Android"
            systemVersion = Build.VERSION.RELEASE ?: Build.VERSION.SDK_INT.toString()
            applicationVersion = BuildConfig.VERSION_NAME
        }

        when (val result = send(query)) {
            is TdApi.Error -> {
                Log.e(TAG, "setTdlibParameters rejected: ${result.code} ${result.message}")
                _authState.value = TelegramAuthState.Unavailable(
                    "Telegram rejected this build's credentials: ${result.message}",
                )
            }
            else -> Unit // TDLib now emits the next authorization state itself.
        }
    }

    private fun mapConnection(state: TdApi.ConnectionState?): TelegramConnectionState =
        when (state) {
            is TdApi.ConnectionStateWaitingForNetwork -> TelegramConnectionState.WAITING_FOR_NETWORK
            is TdApi.ConnectionStateConnectingToProxy -> TelegramConnectionState.CONNECTING_TO_PROXY
            is TdApi.ConnectionStateConnecting -> TelegramConnectionState.CONNECTING
            is TdApi.ConnectionStateUpdating -> TelegramConnectionState.UPDATING
            is TdApi.ConnectionStateReady -> TelegramConnectionState.READY
            else -> TelegramConnectionState.CONNECTING
        }

    private fun codeLength(type: TdApi.AuthenticationCodeType?): Int = when (type) {
        is TdApi.AuthenticationCodeTypeTelegramMessage -> type.length
        is TdApi.AuthenticationCodeTypeSms -> type.length
        is TdApi.AuthenticationCodeTypeCall -> type.length
        else -> DEFAULT_CODE_LENGTH
    }.takeIf { it in 4..8 } ?: DEFAULT_CODE_LENGTH

    // ------------------------------------------------------------ query plumbing

    /**
     * One query, one suspension.
     *
     * Errors come back as [TdApi.Error] rather than thrown, because a wrong OTP
     * or a flood-wait is ordinary traffic on this path; the caller matches on the
     * result. A genuine transport exception is converted to the same shape so
     * there is exactly one thing to handle.
     */
    private suspend fun send(query: TdApi.Function<*>): TdApi.Object =
        suspendCancellableCoroutine { continuation ->
            val client = clientRef.get()
            if (client == null) {
                continuation.resume(error(CODE_UNAVAILABLE, "TDLib is not running."))
                return@suspendCancellableCoroutine
            }
            runCatching {
                client.send(
                    query,
                    { result -> if (continuation.isActive) continuation.resume(result) },
                    { throwable ->
                        if (continuation.isActive) {
                            continuation.resume(
                                error(CODE_UNAVAILABLE, throwable.message ?: "TDLib call failed."),
                            )
                        }
                    },
                )
            }.onFailure { throwable ->
                if (continuation.isActive) {
                    continuation.resume(error(CODE_UNAVAILABLE, throwable.message ?: "TDLib is gone."))
                }
            }
        }

    private fun error(code: Int, message: String) = TdApi.Error().apply {
        this.code = code
        this.message = message
    }

    /**
     * Maps a TDLib error onto the gateway's vocabulary.
     *
     * FLOOD_WAIT carries its delay in the message text (`FLOOD_WAIT_42`) rather
     * than a field, and honouring it matters: retrying early extends the ban
     * rather than resetting it, which is how an indexing loop gets an account
     * rate-limited for hours.
     */
    private fun failure(error: TdApi.Error): GatewayResult.Failure {
        val message = error.message.orEmpty()
        val floodSeconds = FLOOD_WAIT.find(message)?.groupValues?.get(1)?.toIntOrNull()

        val kind = when {
            floodSeconds != null -> GatewayError.FLOOD_WAIT
            message.contains("PHONE_CODE_INVALID") ||
                message.contains("PHONE_CODE_EXPIRED") ||
                message.contains("PHONE_CODE_EMPTY") -> GatewayError.INVALID_CODE
            message.contains("PHONE_NUMBER_INVALID") -> GatewayError.INVALID_PHONE_NUMBER
            message.contains("PASSWORD_HASH_INVALID") -> GatewayError.INVALID_PASSWORD
            message.contains("CHAT_NOT_FOUND") ||
                message.contains("USERNAME_NOT_OCCUPIED") ||
                message.contains("CHANNEL_INVALID") ||
                message.contains("CHANNEL_PRIVATE") -> GatewayError.CHAT_NOT_FOUND
            message.contains("FILE_", ignoreCase = true) -> GatewayError.FILE_UNAVAILABLE
            error.code == CODE_UNAUTHORISED -> GatewayError.NOT_AUTHENTICATED
            error.code == CODE_UNAVAILABLE -> GatewayError.UNAVAILABLE
            error.code >= 500 -> GatewayError.NETWORK
            else -> GatewayError.UNKNOWN
        }

        return GatewayResult.Failure(
            error = kind,
            message = humanise(kind, message, floodSeconds),
            retryAfterSeconds = floodSeconds ?: 0,
        )
    }

    /** TDLib's error strings are shouty constants; the UI shows these instead. */
    private fun humanise(kind: GatewayError, raw: String, floodSeconds: Int?): String = when (kind) {
        GatewayError.INVALID_CODE -> "That code isn't right. Check the digits and try again."
        GatewayError.INVALID_PHONE_NUMBER -> "Telegram doesn't recognise that number."
        GatewayError.INVALID_PASSWORD -> "Wrong password."
        GatewayError.FLOOD_WAIT -> {
            val seconds = floodSeconds ?: 0
            if (seconds >= 60) {
                "Telegram is rate-limiting this account. Try again in ${seconds / 60} min."
            } else {
                "Telegram is rate-limiting this account. Try again in ${seconds}s."
            }
        }
        GatewayError.CHAT_NOT_FOUND -> "That channel isn't reachable from this account."
        GatewayError.NOT_AUTHENTICATED -> "Session expired. Sign in again."
        GatewayError.NETWORK -> "Telegram is unreachable right now."
        GatewayError.FILE_UNAVAILABLE -> "Telegram won't serve that file."
        GatewayError.UNAVAILABLE -> raw
        GatewayError.UNKNOWN -> raw.ifEmpty { "Telegram returned an error." }
    }

    private inline fun <T> TdApi.Object.fold(onSuccess: (TdApi.Object) -> GatewayResult<T>): GatewayResult<T> =
        if (this is TdApi.Error) failure(this) else onSuccess(this)

    // ------------------------------------------------------------------ auth

    override suspend fun requestVerificationCode(phoneNumber: String): GatewayResult<Unit> {
        start()
        pendingPhoneNumber = phoneNumber
        val query = TdApi.SetAuthenticationPhoneNumber().apply {
            this.phoneNumber = phoneNumber
            settings = TdApi.PhoneNumberAuthenticationSettings().apply {
                // No flash calls, no missed calls, no SMS retriever: each of those
                // wants a permission or reads the call log, and this app asks for
                // neither. A code typed by hand is the only flow worth supporting.
                allowFlashCall = false
                isCurrentPhoneNumber = false
            }
        }
        return send(query).fold { GatewayResult.Success(Unit) }
    }

    override suspend fun submitVerificationCode(code: String): GatewayResult<Unit> {
        val query = TdApi.CheckAuthenticationCode().apply { this.code = code.trim() }
        return send(query).fold { GatewayResult.Success(Unit) }
    }

    override suspend fun submitPassword(password: String): GatewayResult<Unit> {
        val query = TdApi.CheckAuthenticationPassword().apply { this.password = password }
        return send(query).fold { GatewayResult.Success(Unit) }
    }

    override suspend fun resendVerificationCode(): GatewayResult<Unit> =
        send(TdApi.ResendAuthenticationCode()).fold { GatewayResult.Success(Unit) }

    override suspend fun logOut(): GatewayResult<Unit> =
        send(TdApi.LogOut()).fold { GatewayResult.Success(Unit) }

    // -------------------------------------------------------------- channels

    override suspend fun loadChannels(limit: Int): GatewayResult<List<TelegramChat>> {
        // BOTH chat lists. Archiving a chat in Telegram moves it out of
        // ChatListMain entirely, so a picker that only asked for the main list
        // simply could not see an archived channel — and archiving is nothing but
        // an inbox-tidiness setting, the channel stays perfectly readable. This was
        // a real bug: archiving a synced channel made it vanish from the picker and
        // report "not reachable" on the next sync.
        val lists = listOf<TdApi.ChatList>(TdApi.ChatListMain(), TdApi.ChatListArchive())

        val collected = LinkedHashMap<Long, TelegramChat>()
        var lastError: TdApi.Error? = null

        for (list in lists) {
            // loadChats pulls the list down from the server; getChats then reads
            // what is now local. Skipping the first call yields only chats TDLib
            // happens to have cached, which on a fresh login is nearly none. It
            // answers 404 once a list is fully loaded — expected, not a failure.
            send(TdApi.LoadChats().apply { chatList = list; this.limit = limit })

            val chats = send(TdApi.GetChats().apply { chatList = list; this.limit = limit })
            if (chats is TdApi.Error) {
                lastError = chats
                continue
            }
            val ids = (chats as? TdApi.Chats)?.chatIds ?: continue
            val archived = list is TdApi.ChatListArchive

            // `chatIds` is a Java long[], i.e. a Kotlin LongArray, which has `map`
            // but no `mapNotNull` — hence the `toList()`.
            ids.toList().forEach { chatId ->
                val chat = send(TdApi.GetChat().apply { this.chatId = chatId }) as? TdApi.Chat
                if (chat != null) {
                    val mapped = mapChat(chat, archived)
                    if (mapped.isChannel) collected.putIfAbsent(mapped.chatId, mapped)
                }
            }
        }

        // Only a hard failure if neither list produced anything.
        if (collected.isEmpty() && lastError != null) return failure(lastError)
        return GatewayResult.Success(collected.values.toList())
    }

    override suspend fun resolveChannel(query: String): GatewayResult<TelegramChat> {
        val cleaned = query.trim()

        // A raw chat id, which is how a private channel with no handle is reached.
        cleaned.toLongOrNull()?.let { return channelById(it) }

        val username = cleaned
            .removePrefix("https://").removePrefix("http://")
            .removePrefix("t.me/").removePrefix("telegram.me/")
            .removePrefix("@")
            .trimEnd('/')

        val result = send(TdApi.SearchPublicChat().apply { this.username = username })
        return result.fold { obj ->
            val chat = obj as? TdApi.Chat
                ?: return@fold GatewayResult.Failure(
                    GatewayError.CHAT_NOT_FOUND,
                    "No channel found for “$username”.",
                )
            GatewayResult.Success(mapChat(chat, archived = false))
        }
    }

    override suspend fun channelById(chatId: Long): GatewayResult<TelegramChat> =
        send(TdApi.GetChat().apply { this.chatId = chatId }).fold { obj ->
            val chat = obj as? TdApi.Chat
                ?: return@fold GatewayResult.Failure(
                    GatewayError.CHAT_NOT_FOUND,
                    "Channel $chatId is not reachable.",
                )
            GatewayResult.Success(mapChat(chat, archived = false))
        }

    /**
     * Make sure TDLib has the chat in memory before asking for its history.
     *
     * `getChatHistory` fails with CHAT_NOT_FOUND for a chat TDLib has not loaded,
     * and which chats are loaded depends on which *lists* have been fetched. An
     * archived channel therefore synced fine until it was archived, and then
     * started reporting "not reachable" — the chat had not changed at all, only
     * which list it was in.
     *
     * One `getChat` usually settles it from cache. If it doesn't, both chat lists
     * get loaded and it is tried once more.
     */
    private suspend fun ensureChatKnown(chatId: Long): Boolean {
        if (send(TdApi.GetChat().apply { this.chatId = chatId }) is TdApi.Chat) return true

        listOf<TdApi.ChatList>(TdApi.ChatListMain(), TdApi.ChatListArchive()).forEach { list ->
            send(TdApi.LoadChats().apply { chatList = list; limit = CHAT_LOAD_LIMIT })
        }
        return send(TdApi.GetChat().apply { this.chatId = chatId }) is TdApi.Chat
    }

    private fun mapChat(chat: TdApi.Chat, archived: Boolean): TelegramChat {
        val type = chat.type
        // Broadcast channels only. A supergroup that isn't a channel, a basic
        // group or a DM would index as a library of other people's chatter.
        val isChannel = type is TdApi.ChatTypeSupergroup && type.isChannel
        return TelegramChat(
            chatId = chat.id,
            title = chat.title?.takeIf { it.isNotBlank() } ?: "Untitled channel",
            // The handle lives on the supergroup, not the chat, so surfacing it
            // would cost a round trip per row in the picker. The title identifies
            // a channel well enough for a list the user scrolls once.
            username = null,
            photoFileId = chat.photo?.small?.id,
            messageCount = -1,
            isChannel = isChannel,
            isArchived = archived,
        )
    }

    // --------------------------------------------------------------- history

    override suspend fun fetchHistory(
        chatId: Long,
        fromMessageId: Long,
        limit: Int,
    ): GatewayResult<TelegramHistoryPage> {
        // Without this, an archived channel reports CHAT_NOT_FOUND: getChatHistory
        // only works on a chat TDLib has loaded, and archiving moves a chat into a
        // list the app was never asking for.
        if (!ensureChatKnown(chatId)) {
            return GatewayResult.Failure(
                GatewayError.CHAT_NOT_FOUND,
                "Telegram didn't return channel $chatId. If it was just archived or " +
                    "left, open it once in Telegram and sync again.",
            )
        }

        // getChatHistory answers from the local cache first and returns an empty
        // page while it fetches from the server — documented behaviour, and the
        // single most common reason a naive indexer concludes a channel is empty.
        // Retrying a few times is the sanctioned workaround.
        var attempt = 0
        while (true) {
            val result = send(
                TdApi.GetChatHistory().apply {
                    this.chatId = chatId
                    this.fromMessageId = fromMessageId
                    this.offset = 0
                    this.limit = limit
                    this.onlyLocal = false
                },
            )
            if (result is TdApi.Error) return failure(result)

            val messages = (result as? TdApi.Messages)?.messages?.filterNotNull() ?: emptyList()
            if (messages.isEmpty() && attempt < HISTORY_RETRIES) {
                attempt++
                delay(HISTORY_RETRY_DELAY_MS * attempt)
                continue
            }

            return GatewayResult.Success(
                TelegramHistoryPage(
                    messages = messages.mapNotNull(::mapMessage),
                    // Both cursors walk over *every* message, media or not.
                    // Advancing them only past media would stall the backfill the
                    // first time it met a run of text posts, and would leave the
                    // incremental floor permanently below them.
                    oldestMessageId = messages.minOfOrNull { it.id } ?: 0L,
                    newestMessageId = messages.maxOfOrNull { it.id } ?: 0L,
                    reachedEnd = messages.isEmpty(),
                    inspected = messages.size,
                ),
            )
        }
    }

    private fun mapMessage(message: TdApi.Message): TelegramMessage? =
        when (val content = message.content) {
            is TdApi.MessageVideo -> {
                val video = content.video
                val file = video?.video
                if (file == null) {
                    null
                } else {
                    TelegramMessage(
                        messageId = message.id,
                        chatId = message.chatId,
                        date = message.date.toLong(),
                        caption = content.caption?.text.orEmpty(),
                        kind = TelegramMediaKind.VIDEO,
                        fileId = file.id,
                        remoteFileId = file.remote?.id.orEmpty(),
                        remoteUniqueId = file.remote?.uniqueId.orEmpty(),
                        fileSizeBytes = sizeOf(file),
                        thumbnailFileId = video.thumbnail?.file?.id,
                        // Video gets one thumbnail from Telegram and no ladder, so
                        // there is no larger rung to reach for. Sharper artwork for
                        // video comes from decoding a frame — see PosterStore.
                        previewFileId = null,
                        // Frequently the only artwork a video has — see
                        // TelegramMessage.minithumbnail.
                        minithumbnail = video.minithumbnail?.data,
                        durationSeconds = video.duration,
                        width = video.width,
                        height = video.height,
                        mimeType = video.mimeType,

                        albumId = message.mediaAlbumId,
                    )
                }
            }

            is TdApi.MessagePhoto -> {
                val photo = content.photo
                val sizes = photo?.sizes?.filterNotNull().orEmpty()
                // Telegram ships a ladder per photo — roughly 90, 320, 800, 1280
                // and 2560px. `full` is what the viewer opens.
                val full = sizes.maxByOrNull { it.width.toLong() * it.height }
                val file = full?.photo
                if (file == null) {
                    null
                } else {
                    TelegramMessage(
                        messageId = message.id,
                        chatId = message.chatId,
                        date = message.date.toLong(),
                        caption = content.caption?.text.orEmpty(),
                        kind = TelegramMediaKind.PHOTO,
                        fileId = file.id,
                        remoteFileId = file.remote?.id.orEmpty(),
                        remoteUniqueId = file.remote?.uniqueId.orEmpty(),
                        fileSizeBytes = sizeOf(file),
                        // The smallest rung that is still at least 320px — not the
                        // smallest rung outright. The bottom of that ladder is a
                        // 90px placeholder, and using it as poster art is a large
                        // part of why the grid looked cheap. One rung up is sharp in
                        // a grid cell and still tens of KB, not megabytes.
                        thumbnailFileId = rungAtLeast(sizes, GRID_MIN_EDGE)?.photo?.id ?: file.id,
                        // And a second, larger rung for everywhere the same artwork is
                        // shown big: a full-width card, and the poster the player
                        // holds while the first frame decodes. Chosen at index time
                        // because the ladder is only in the message.
                        previewFileId = rungAtLeast(sizes, PREVIEW_MIN_EDGE)?.photo?.id,
                        minithumbnail = photo.minithumbnail?.data,
                        durationSeconds = null,
                        width = full.width,
                        height = full.height,
                        mimeType = "image/jpeg",
                        albumId = message.mediaAlbumId,
                    )
                }
            }

            else -> null
        }

    /** Smallest photo rung whose long edge clears [minEdge], else the largest there is. */
    private fun rungAtLeast(sizes: List<TdApi.PhotoSize>, minEdge: Int): TdApi.PhotoSize? = sizes
        .filter { maxOf(it.width, it.height) >= minEdge }
        .minByOrNull { it.width.toLong() * it.height }
        ?: sizes.maxByOrNull { it.width.toLong() * it.height }

    /** `size` is 0 until TDLib has the real file; `expectedSize` is its estimate. */
    private fun sizeOf(file: TdApi.File): Long {
        val known = file.size.toLong()
        return if (known > 0L) known else file.expectedSize.toLong()
    }

    // ----------------------------------------------------------------- files

    override suspend fun downloadThumbnail(fileId: Int): GatewayResult<String> =
        downloadWhole(fileId, PRIORITY_THUMBNAIL)

    override suspend fun downloadOriginal(fileId: Int): GatewayResult<String> =
        downloadWhole(fileId, PRIORITY_STREAM)

    /**
     * One whole-file download, blocking until TDLib has all of it.
     *
     * `synchronous = true` is right for artwork and wrong for media: a thumbnail or a
     * photo is worth one blocking call, whereas a 2 GB video is the reason
     * [requestRange] exists.
     */
    private suspend fun downloadWhole(fileId: Int, priority: Int): GatewayResult<String> {
        val result = send(
            TdApi.DownloadFile().apply {
                this.fileId = fileId
                this.priority = priority
                offset = 0
                limit = 0
                synchronous = true
            },
        )
        if (result is TdApi.Error) return failure(result)

        val file = result as? TdApi.File
            ?: return GatewayResult.Failure(GatewayError.FILE_UNAVAILABLE, "No file returned.")
        publishFile(file)

        val path = file.local?.path?.takeIf { it.isNotEmpty() && File(it).length() > 0 }
            ?: return GatewayResult.Failure(
                GatewayError.FILE_UNAVAILABLE,
                "File $fileId downloaded but has no local path.",
            )
        return GatewayResult.Success(path)
    }

    override suspend fun requestRange(
        fileId: Int,
        offset: Long,
        limit: Long,
    ): GatewayResult<TelegramFileState> {
        val result = send(
            TdApi.DownloadFile().apply {
                this.fileId = fileId
                priority = PRIORITY_STREAM
                this.offset = offset
                this.limit = limit
                // Asynchronous: a synchronous call would return only once the
                // whole requested range had arrived, which for a seek into a 2 GB
                // file is exactly the stall streaming exists to avoid.
                synchronous = false
            },
        )
        if (result is TdApi.Error) return failure(result)
        (result as? TdApi.File)?.let(::publishFile)

        fileStates[fileId]?.takeIf { it.canRead(offset) }?.let { return GatewayResult.Success(it) }

        val readable = awaitReadable(fileId, offset)
            ?: return GatewayResult.Failure(
                GatewayError.NETWORK,
                "Telegram didn't deliver data at ${offset}B within ${RANGE_TIMEOUT_MS / 1000}s.",
            )
        return GatewayResult.Success(readable)
    }

    /** Waits for the contiguous prefix to cover [offset]. Null on timeout. */
    private suspend fun awaitReadable(fileId: Int, offset: Long): TelegramFileState? =
        withTimeoutOrNull(RANGE_TIMEOUT_MS) {
            observeFile(fileId).first { state ->
                state.canRead(offset) || (state.isDownloadingCompleted && state.localPath != null)
            }
        }

    override fun observeFile(fileId: Int): Flow<TelegramFileState> = flow {
        // Seed from the cache so a subscriber that arrives mid-download knows the
        // current prefix immediately instead of waiting for the next update.
        emit(fileStates[fileId] ?: TelegramFileState.unknown(fileId))
        emitAll(fileUpdates.filter { it.fileId == fileId })
    }

    private fun publishFile(file: TdApi.File?) {
        val state = file?.toState() ?: return
        fileStates[state.fileId] = state
        fileUpdates.tryEmit(state)
    }

    private fun TdApi.File.toState(): TelegramFileState {
        val local = this.local
        return TelegramFileState(
            fileId = id,
            localPath = local?.path?.takeIf { it.isNotEmpty() },
            downloadOffset = local?.downloadOffset?.toLong() ?: 0L,
            downloadedPrefixSize = local?.downloadedPrefixSize?.toLong() ?: 0L,
            expectedSize = sizeOf(this),
            isDownloadingActive = local?.isDownloadingActive == true,
            isDownloadingCompleted = local?.isDownloadingCompleted == true,
        )
    }

    override suspend fun cancelDownload(fileId: Int) {
        send(
            TdApi.CancelDownloadFile().apply {
                this.fileId = fileId
                // false: cancel even a download already in flight. Cancelling only
                // pending ones would leave the pre-seek range competing for
                // bandwidth with the range the user is now waiting on.
                onlyIfPending = false
            },
        )
    }

    override suspend fun fileIdForRemoteId(
        remoteFileId: String,
        kind: TelegramMediaKind,
    ): GatewayResult<Int> =
        send(
            TdApi.GetRemoteFile().apply {
                this.remoteFileId = remoteFileId
                // The type is load-bearing, not decoration: TDLib validates the
                // reference against it, so a photo asked for as a video is refused
                // and the lazy-repair path silently stops working for stills.
                fileType = when (kind) {
                    TelegramMediaKind.PHOTO -> TdApi.FileTypePhoto()
                    TelegramMediaKind.VIDEO -> TdApi.FileTypeVideo()
                }
            },
        ).fold { obj ->
            val file = obj as? TdApi.File
                ?: return@fold GatewayResult.Failure(
                    GatewayError.FILE_UNAVAILABLE,
                    "Telegram no longer recognises that file reference.",
                )
            publishFile(file)
            GatewayResult.Success(file.id)
        }

    // ----------------------------------------------------------------- cache

    override suspend fun applyCacheLimit(bytes: Long) {
        send(
            TdApi.OptimizeStorage().apply {
                size = bytes
                ttl = 0
                count = 0
                immunityDelay = 0
                // Media only. Naming the types keeps the sweep away from TDLib's
                // own bookkeeping files, which it needs and which are tiny.
                fileTypes = arrayOf(
                    TdApi.FileTypeVideo(),
                    TdApi.FileTypePhoto(),
                    TdApi.FileTypeAnimation(),
                    TdApi.FileTypeDocument(),
                )
                chatIds = longArrayOf()
                excludeChatIds = longArrayOf()
                returnDeletedFileStatistics = false
                chatLimit = 0
            },
        )
    }

    override suspend fun cacheSizeBytes(): Long = withContext(io) {
        (send(TdApi.GetStorageStatisticsFast()) as? TdApi.StorageStatisticsFast)
            ?.filesSize?.toLong() ?: 0L
    }

    override suspend fun clearCache(): GatewayResult<Unit> {
        // size = 0 means "keep nothing you don't have to".
        applyCacheLimit(0L)
        return GatewayResult.Success(Unit)
    }

    private companion object {
        const val TAG = "HardPlay/TDLib"

        const val DEFAULT_CODE_LENGTH = 5
        const val START_TIMEOUT_MS = 12_000L
        const val RANGE_TIMEOUT_MS = 45_000L

        const val HISTORY_RETRIES = 3
        const val HISTORY_RETRY_DELAY_MS = 220L

        /** Chats to pull per list when repairing an unknown chat id. */
        const val CHAT_LOAD_LIMIT = 200

        /**
         * Minimum long edge for the rung used in a grid cell.
         *
         * Telegram's ladder starts at a ~90px placeholder; using that as poster art
         * is most of why the grid looked cheap. 320 lands on the second rung, which
         * is sharp at two or three columns and still tens of KB.
         */
        const val GRID_MIN_EDGE = 320

        /**
         * Minimum long edge for the rung used wherever artwork is shown large — a
         * one-column card, or the poster the player holds during the transition.
         *
         * 1024 lands on Telegram's 1280px rung on a normal ladder. A phone's
         * full-width card is 1080–1440 real pixels, so anything smaller is visibly
         * upscaled, and "upscaled" is exactly what got reported as low quality.
         */
        const val PREVIEW_MIN_EDGE = 1024

        /** TDLib priorities run 1 (idle) to 32 (immediate). */
        const val PRIORITY_STREAM = 32
        const val PRIORITY_THUMBNAIL = 16

        const val CODE_UNAUTHORISED = 401
        /** Not a Telegram code — ours, for "the client isn't there". */
        const val CODE_UNAVAILABLE = -1

        val FLOOD_WAIT = Regex("""FLOOD_WAIT_(\d+)""")
    }
}
