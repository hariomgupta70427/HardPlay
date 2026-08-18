package com.hardplay.telegram

/**
 * The gateway's vocabulary.
 *
 * Every type here is plain Kotlin. **Nothing in this file, or in any file that
 * imports it, may reference `org.drinkless.tdlib`** — those bindings are a build
 * output that may not exist (see CLAUDE.md), and the app has to compile either
 * way. Translation from `TdApi` happens once, inside `src/tdlib/kotlin`.
 */

/** A channel the account can read. */
data class TelegramChat(
    val chatId: Long,
    val title: String,
    val username: String?,
    val photoFileId: Int?,
    /** TDLib's count where known; -1 when it hasn't told us yet. */
    val messageCount: Int = -1,
    /** False for groups and DMs, which the picker greys out. */
    val isChannel: Boolean = true,
    val memberCount: Int = 0,
    /**
     * True when the chat sits in Telegram's Archive rather than the main list.
     *
     * Surfaced because archiving is *only* an inbox-tidiness setting — an archived
     * channel is every bit as readable — and a picker that silently omitted them
     * looked like it had lost the channel.
     */
    val isArchived: Boolean = false,
)

enum class TelegramMediaKind { VIDEO, PHOTO }

/**
 * One indexable message, flattened.
 *
 * Flat rather than a sealed content hierarchy because its only consumer maps it
 * straight into a single Room row; a hierarchy would be re-flattened immediately.
 *
 * Equality is not meaningful — [minithumbnail] is a `ByteArray`, so the generated
 * `equals` compares it by reference. Nothing compares whole messages.
 */
data class TelegramMessage(
    val messageId: Long,
    val chatId: Long,
    /** Epoch **seconds** — TDLib's unit, kept rather than converted so the value
     *  in the database matches the value in a TDLib log line. */
    val date: Long,
    val caption: String,
    val kind: TelegramMediaKind,

    /** Session-scoped id. Valid only for the life of the TDLib database. */
    val fileId: Int,
    /** Persistent id. Survives a TDLib reset, unlike [fileId]. */
    val remoteFileId: String,
    /** Stable across accounts, so re-posts and forwards dedupe. */
    val remoteUniqueId: String,
    val fileSizeBytes: Long,

    val thumbnailFileId: Int?,
    /**
     * A larger rung of the same artwork, when the message has one.
     *
     * Telegram ships a photo as a ladder — roughly 90, 320, 800, 1280 and 2560px —
     * and [thumbnailFileId] deliberately holds a small rung, because a grid of
     * three columns wants a small file. A full-width card, and the poster handed to
     * the player, want a bigger one; picking a single rung for both is why artwork
     * looked soft wherever it was shown large.
     *
     * Null for video: Telegram gives a video exactly one thumbnail, so there is no
     * second rung to choose. Sharper video artwork comes from a decoded frame
     * instead — see `MediaEntity.posterPath`.
     */
    val previewFileId: Int?,
    /**
     * TDLib's `minithumbnail` — a ~40px JPEG that arrives *inside* the message
     * rather than as a separate downloadable file.
     *
     * Carried because a great many Telegram videos have no `thumbnail` file at all
     * while almost all of them have this. It is the difference between a grid with
     * artwork and a grid of fallback initials, and it costs no network at all.
     */
    val minithumbnail: ByteArray?,
    val durationSeconds: Int?,
    val width: Int?,
    val height: Int?,
    val mimeType: String?,
    /**
     * Telegram's album grouping; 0 when the message isn't part of one.
     *
     * The reliable half of poster pairing: when a channel posts a screenshot and its
     * video as one album, both messages carry the same id, and that is a fact rather
     * than a guess. Messages posted back-to-back but separately share nothing, and
     * have to be paired by adjacency instead.
     */
    val albumId: Long = 0,
)

/** A page of history, walked newest-to-oldest. */
data class TelegramHistoryPage(
    val messages: List<TelegramMessage>,
    /** Oldest message id seen, including messages with no media. The next page's
     *  cursor: skipping non-media messages here would make the walk stall. */
    val oldestMessageId: Long,
    /**
     * Newest message id seen, again including non-media messages.
     *
     * Also deliberately not media-only. The incremental sync stores this as its
     * floor, and a channel that posts text between videos would otherwise leave the
     * floor stuck at the last *video* — so every later sync would re-walk all the
     * text posts above it, forever.
     */
    val newestMessageId: Long,
    /** True once TDLib has no more history above this cursor. */
    val reachedEnd: Boolean,
    /** Messages inspected, media or not. Drives honest progress counts. */
    val inspected: Int,
)

/**
 * Download state for one file, as the streaming `DataSource` needs to see it.
 *
 * TDLib downloads into a local file and reports how much of a *contiguous prefix*
 * starting at [downloadOffset] has arrived. That prefix, not the total downloaded
 * size, is what bounds a safe read — which is the whole reason this type exists
 * rather than a simple percentage.
 */
data class TelegramFileState(
    val fileId: Int,
    /** Null until TDLib has created the file on disk. */
    val localPath: String?,
    val downloadOffset: Long,
    val downloadedPrefixSize: Long,
    /** Total size. 0 when TDLib doesn't know it yet. */
    val expectedSize: Long,
    val isDownloadingActive: Boolean,
    val isDownloadingCompleted: Boolean,
) {
    /** Highest byte offset that can be read right now. */
    val readableUntil: Long get() = downloadOffset + downloadedPrefixSize

    fun canRead(offset: Long): Boolean =
        localPath != null && offset >= downloadOffset && offset < readableUntil

    companion object {
        fun unknown(fileId: Int) = TelegramFileState(
            fileId = fileId,
            localPath = null,
            downloadOffset = 0,
            downloadedPrefixSize = 0,
            expectedSize = 0,
            isDownloadingActive = false,
            isDownloadingCompleted = false,
        )
    }
}

/** TDLib's own connectivity, surfaced so the UI can explain a stall. */
enum class TelegramConnectionState {
    WAITING_FOR_NETWORK,
    CONNECTING_TO_PROXY,
    CONNECTING,
    UPDATING,
    READY,
}

/**
 * The authentication state machine (PRD §5.1).
 *
 * Mirrors TDLib's `AuthorizationState` rather than inventing a parallel one: the
 * library is the source of truth, it can move state on its own after a session
 * restore or a remote log-out, and any second model of it drifts.
 */
sealed interface TelegramAuthState {
    /** TDLib not started, or still reading its encrypted database. */
    data object Initialising : TelegramAuthState

    data class WaitingForPhoneNumber(
        val previousError: String? = null,
    ) : TelegramAuthState

    data class WaitingForCode(
        val phoneNumber: String,
        /** Digits TDLib expects, for the OTP field's cell count. */
        val codeLength: Int,
        /** Seconds until a resend is allowed; 0 when it already is. */
        val resendIn: Int = 0,
        val previousError: String? = null,
    ) : TelegramAuthState

    data class WaitingForPassword(
        val passwordHint: String?,
        val hasRecoveryEmail: Boolean,
        val previousError: String? = null,
    ) : TelegramAuthState

    /** Logged in and usable. */
    data object Ready : TelegramAuthState

    data object LoggingOut : TelegramAuthState

    data object Closed : TelegramAuthState

    /**
     * TDLib is absent or refused to start. Terminal, and the reason demo mode
     * has to be a first-class path rather than a fallback bolted on later.
     */
    data class Unavailable(val reason: String) : TelegramAuthState
}

/**
 * Result of a gateway call.
 *
 * A sealed result rather than exceptions: every one of these failures is
 * expected traffic — a wrong OTP, a flood-wait, a dropped connection — and
 * expected outcomes should not need a try/catch at each of a dozen call sites.
 */
sealed interface GatewayResult<out T> {
    data class Success<out T>(val value: T) : GatewayResult<T>

    data class Failure(
        val error: GatewayError,
        val message: String,
        /** Seconds to wait, when [GatewayError.FLOOD_WAIT] says so. */
        val retryAfterSeconds: Int = 0,
    ) : GatewayResult<Nothing>

    val valueOrNull: T? get() = (this as? Success)?.value
    val isSuccess: Boolean get() = this is Success
}

enum class GatewayError {
    /** Wrong OTP or expired code. */
    INVALID_CODE,
    INVALID_PHONE_NUMBER,
    INVALID_PASSWORD,
    /** Telegram rate limit. Honour `retryAfterSeconds` — retrying sooner extends it. */
    FLOOD_WAIT,
    NETWORK,
    NOT_AUTHENTICATED,
    CHAT_NOT_FOUND,
    FILE_UNAVAILABLE,
    /** TDLib missing from this build. */
    UNAVAILABLE,
    UNKNOWN,
}

inline fun <T> GatewayResult<T>.onFailure(
    action: (GatewayResult.Failure) -> Unit,
): GatewayResult<T> {
    if (this is GatewayResult.Failure) action(this)
    return this
}

inline fun <T, R> GatewayResult<T>.map(transform: (T) -> R): GatewayResult<R> = when (this) {
    is GatewayResult.Success -> GatewayResult.Success(transform(value))
    is GatewayResult.Failure -> this
}
