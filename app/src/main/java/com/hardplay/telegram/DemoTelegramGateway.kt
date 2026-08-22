package com.hardplay.telegram

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.math.abs
import kotlin.random.Random

/**
 * A gateway that answers from a generated library instead of Telegram.
 *
 * Not a stub. It is the app's second supported configuration (CLAUDE.md), and it
 * exists for three reasons: the build has to work before TDLib finishes
 * compiling, the UI has to be reviewable without a phone number in it, and the
 * data layer needs a source that can be driven into flood-waits and dropped
 * connections on demand rather than by waiting for Telegram to do it.
 *
 * What it does *not* fake is media bytes. Thumbnails are real JPEGs rendered on
 * device, so the grid can be judged honestly; video has no bytes behind it, and
 * asking for a range says so plainly rather than hanging in the buffering state.
 */
class DemoTelegramGateway @Inject constructor(
    @ApplicationContext private val context: Context,
    private val io: CoroutineDispatcher,
) : TelegramGateway {

    override val isDemo: Boolean = true

    private val _authState = MutableStateFlow<TelegramAuthState>(TelegramAuthState.Initialising)
    override val authState: StateFlow<TelegramAuthState> = _authState.asStateFlow()

    private val _connectionState = MutableStateFlow(TelegramConnectionState.READY)
    override val connectionState: StateFlow<TelegramConnectionState> = _connectionState.asStateFlow()

    private var pendingPhone: String? = null

    // ------------------------------------------------------------------ auth

    override suspend fun start() {
        if (_authState.value is TelegramAuthState.Ready) return
        // A beat of latency on purpose: an auth screen that resolves in zero
        // milliseconds hides every loading state it is supposed to exercise.
        delay(240)
        _authState.value = TelegramAuthState.WaitingForPhoneNumber()
    }

    override suspend fun close() {
        _authState.value = TelegramAuthState.Closed
    }

    override suspend fun requestVerificationCode(phoneNumber: String): GatewayResult<Unit> {
        val digits = phoneNumber.count(Char::isDigit)
        if (digits < 6) {
            return GatewayResult.Failure(
                GatewayError.INVALID_PHONE_NUMBER,
                "That doesn't look like a phone number.",
            )
        }
        delay(600)
        pendingPhone = phoneNumber
        _authState.value = TelegramAuthState.WaitingForCode(
            phoneNumber = phoneNumber,
            codeLength = DEMO_CODE.length,
            resendIn = 30,
        )
        return GatewayResult.Success(Unit)
    }

    override suspend fun submitVerificationCode(code: String): GatewayResult<Unit> {
        delay(450)
        if (code.trim() != DEMO_CODE) {
            _authState.value = TelegramAuthState.WaitingForCode(
                phoneNumber = pendingPhone ?: "",
                codeLength = DEMO_CODE.length,
                previousError = "Incorrect code. In demo mode it is $DEMO_CODE.",
            )
            return GatewayResult.Failure(GatewayError.INVALID_CODE, "Incorrect code.")
        }
        _authState.value = TelegramAuthState.Ready
        return GatewayResult.Success(Unit)
    }

    override suspend fun submitPassword(password: String): GatewayResult<Unit> {
        delay(450)
        _authState.value = TelegramAuthState.Ready
        return GatewayResult.Success(Unit)
    }

    override suspend fun resendVerificationCode(): GatewayResult<Unit> {
        delay(300)
        return GatewayResult.Success(Unit)
    }

    override suspend fun logOut(): GatewayResult<Unit> {
        _authState.value = TelegramAuthState.WaitingForPhoneNumber()
        return GatewayResult.Success(Unit)
    }

    // -------------------------------------------------------------- channels

    override suspend fun loadChannels(limit: Int): GatewayResult<List<TelegramChat>> {
        delay(500)
        return GatewayResult.Success(DEMO_CHANNELS)
    }

    override suspend fun resolveChannel(query: String): GatewayResult<TelegramChat> {
        delay(400)
        val cleaned = query.trim().removePrefix("@").substringAfterLast('/')
        val hit = DEMO_CHANNELS.firstOrNull {
            it.username?.equals(cleaned, ignoreCase = true) == true ||
                it.title.contains(cleaned, ignoreCase = true) ||
                it.chatId.toString() == cleaned
        }
        return hit?.let { GatewayResult.Success(it) }
            ?: GatewayResult.Failure(
                GatewayError.CHAT_NOT_FOUND,
                "No demo channel matches “$cleaned”.",
            )
    }

    override suspend fun channelById(chatId: Long): GatewayResult<TelegramChat> =
        DEMO_CHANNELS.firstOrNull { it.chatId == chatId }
            ?.let { GatewayResult.Success(it) }
            ?: GatewayResult.Failure(GatewayError.CHAT_NOT_FOUND, "Unknown demo channel.")

    // --------------------------------------------------------------- history

    override suspend fun fetchHistory(
        chatId: Long,
        fromMessageId: Long,
        limit: Int,
    ): GatewayResult<TelegramHistoryPage> {
        val channel = DEMO_CHANNELS.firstOrNull { it.chatId == chatId }
            ?: return GatewayResult.Failure(GatewayError.CHAT_NOT_FOUND, "Unknown demo channel.")

        delay(320)

        // Message ids descend from the channel's total, so a walk behaves exactly
        // like the real thing: cursor down, stop at 1.
        val newest = if (fromMessageId == 0L) channel.messageCount.toLong() else fromMessageId - 1
        if (newest <= 0L) {
            return GatewayResult.Success(
                TelegramHistoryPage(
                    messages = emptyList(),
                    oldestMessageId = 0,
                    newestMessageId = 0,
                    reachedEnd = true,
                    inspected = 0,
                ),
            )
        }

        val take = minOf(limit, newest.toInt())
        val messages = (0 until take).map { step ->
            demoMessage(channel, newest - step)
        }
        val oldest = newest - take + 1
        return GatewayResult.Success(
            TelegramHistoryPage(
                messages = messages,
                oldestMessageId = oldest,
                newestMessageId = newest,
                reachedEnd = oldest <= 1L,
                inspected = take,
            ),
        )
    }

    /**
     * Deterministic per (chat, message) so re-syncing produces identical rows and
     * the incremental path can be tested for idempotence.
     */
    private fun demoMessage(channel: TelegramChat, messageId: Long): TelegramMessage {
        val rng = Random(channel.chatId * 31 + messageId)
        val isPhoto = messageId % 7 == 0L
        val caption = DEMO_CAPTIONS[(messageId % DEMO_CAPTIONS.size).toInt()]
        val duration = if (isPhoto) null else 240 + rng.nextInt(2400)
        val sizeMb = if (isPhoto) rng.nextInt(1, 6) else rng.nextInt(180, 2600)

        return TelegramMessage(
            messageId = messageId,
            chatId = channel.chatId,
            // Spread backwards from now, ~9 hours per message.
            date = TimeUnit.MILLISECONDS.toSeconds(DEMO_EPOCH_MS) - messageId * 33_000,
            caption = caption,
            kind = if (isPhoto) TelegramMediaKind.PHOTO else TelegramMediaKind.VIDEO,
            fileId = fileIdFor(channel.chatId, messageId, thumbnail = false),
            remoteFileId = "demo:${channel.chatId}:$messageId",
            remoteUniqueId = "demo-uniq:${channel.chatId}:$messageId",
            fileSizeBytes = sizeMb * 1024L * 1024L,
            thumbnailFileId = fileIdFor(channel.chatId, messageId, thumbnail = true),
            // A second, larger rung so the size-aware poster path is exercised in
            // demo mode too — the grid and a full-width card must be able to pick
            // different files, or the code that does it is never reviewed.
            previewFileId = fileIdFor(channel.chatId, messageId, thumbnail = true) or PREVIEW_FLAG,
            // Demo posters are rendered on demand by downloadThumbnail, so there is
            // nothing inline to carry. Leaving this null also exercises the
            // thumbnail-file path rather than the minithumbnail fallback.
            minithumbnail = null,
            durationSeconds = duration,
            width = if (isPhoto) 1440 else 1920,
            height = if (isPhoto) 1800 else 1080,
            mimeType = if (isPhoto) "image/jpeg" else "video/mp4",
        )
    }

    /**
     * Packs identity into an Int so a demo file id can be decoded back later.
     *
     * The top three bits are flags ([THUMBNAIL_FLAG], [PREVIEW_FLAG]) and the rest is
     * the identity, so a rung can be added to an id and stripped back off it.
     */
    private fun fileIdFor(chatId: Long, messageId: Long, thumbnail: Boolean): Int {
        val base = (abs(chatId % 997L) * 100_003L + messageId).toInt() and IDENTITY_MASK
        return if (thumbnail) base or THUMBNAIL_FLAG else base
    }

    // ----------------------------------------------------------------- files

    /**
     * Renders a poster JPEG on device and returns its path.
     *
     * Real pixels, not a placeholder drawable: the grid's crossfade, Coil's
     * caching and the poster scrim all behave differently against an actual
     * decoded bitmap, and those are exactly the things worth reviewing before
     * credentials exist.
     */
    override suspend fun downloadThumbnail(fileId: Int): GatewayResult<String> =
        withContext(io) {
            val file = File(thumbnailDir(), "demo_$fileId.jpg")
            if (file.exists() && file.length() > 0) {
                return@withContext GatewayResult.Success(file.absolutePath)
            }
            runCatching {
                thumbnailDir().mkdirs()
                val bitmap = renderPoster(fileId)
                file.outputStream().use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out)
                }
                bitmap.recycle()
                file.absolutePath
            }.fold(
                onSuccess = { GatewayResult.Success(it) },
                onFailure = {
                    GatewayResult.Failure(
                        GatewayError.FILE_UNAVAILABLE,
                        it.message ?: "Could not render a demo thumbnail.",
                    )
                },
            )
        }

    /** Same renderer; the id carries the rung, so a larger request gets more pixels. */
    override suspend fun downloadOriginal(fileId: Int): GatewayResult<String> =
        downloadThumbnail(fileId or PREVIEW_FLAG)

    private fun thumbnailDir() = File(context.cacheDir, "demo_thumbs")

    /**
     * An ember-on-oxblood field with a bright band and grain — recognisably
     * within the design system, and varied enough per id that a scrolling grid
     * doesn't look like one image repeated.
     *
     * Landscape by default, because the app's default card is 16:9 and a portrait
     * demo poster would letterbox in every cell. The [PREVIEW_FLAG] bit renders the
     * same composition at four times the area, so demo mode exercises the real
     * small-rung/large-rung split rather than pretending there is only one size.
     */
    private fun renderPoster(fileId: Int): Bitmap {
        val large = fileId and PREVIEW_FLAG != 0
        val w = if (large) 1280 else 480
        val h = if (large) 720 else 270
        // Seeded on the rung-independent id so both sizes draw the same picture.
        val rng = Random((fileId and PREVIEW_FLAG.inv()).toLong())
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val oxblood = Color.rgb(26, 11, 16)
        val ink = Color.rgb(8, 7, 10)
        val emberLow = Color.rgb(255, 77, 46)
        val emberHigh = Color.rgb(255, 138, 61)

        canvas.drawColor(oxblood)

        // Ember wash from one corner, angle varying per id.
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val fromLeft = rng.nextBoolean()
        paint.shader = LinearGradient(
            if (fromLeft) 0f else w.toFloat(), 0f,
            if (fromLeft) w.toFloat() else 0f, h.toFloat(),
            intArrayOf(emberLow, oxblood, ink),
            floatArrayOf(0f, 0.45f + rng.nextFloat() * 0.2f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)

        // One bright band, so each poster has a focal line.
        paint.shader = null
        paint.color = emberHigh
        paint.alpha = 70 + rng.nextInt(60)
        val bandY = h * (0.25f + rng.nextFloat() * 0.5f)
        val bandH = 6f + rng.nextFloat() * 26f
        canvas.drawRect(0f, bandY, w.toFloat(), bandY + bandH, paint)

        // Grain, matching the app's own overlay so the two don't fight.
        paint.alpha = 26
        paint.color = Color.rgb(245, 240, 232)
        val specks = (w * h) / 92
        repeat(specks) {
            canvas.drawPoint(rng.nextFloat() * w, rng.nextFloat() * h, paint)
        }

        // Frame number in the corner: identifies the cell during review, and
        // proves the image is generated rather than shipped.
        paint.alpha = 150
        paint.textSize = h / 26f
        paint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        canvas.drawText("#${fileId and 0xFFFF}", w / 28f, h - h / 32f, paint)

        return bitmap
    }

    override suspend fun requestRange(
        fileId: Int,
        offset: Long,
        limit: Long,
    ): GatewayResult<TelegramFileState> = GatewayResult.Failure(
        GatewayError.FILE_UNAVAILABLE,
        "Demo mode has metadata and artwork, but no video bytes. " +
            "Build TDLib and sign in to stream.",
    )

    override fun observeFile(fileId: Int): Flow<TelegramFileState> = flow {
        emit(TelegramFileState.unknown(fileId))
    }

    override suspend fun cancelDownload(fileId: Int) = Unit

    override suspend fun fileIdForRemoteId(
        remoteFileId: String,
        kind: TelegramMediaKind,
    ): GatewayResult<Int> {
        val parts = remoteFileId.split(':')
        if (parts.size != 3 || parts[0] != "demo") {
            return GatewayResult.Failure(GatewayError.FILE_UNAVAILABLE, "Not a demo file id.")
        }
        val chatId = parts[1].toLongOrNull()
        val messageId = parts[2].toLongOrNull()
        if (chatId == null || messageId == null) {
            return GatewayResult.Failure(GatewayError.FILE_UNAVAILABLE, "Malformed demo file id.")
        }
        return GatewayResult.Success(fileIdFor(chatId, messageId, thumbnail = false))
    }

    /**
     * Demo mode's file ids never go stale, so this is a lookup rather than a repair
     * — but it has to answer, because the real gateway's repair path is exercised by
     * every playback and a demo that failed it would look like the bug it fixes.
     */
    override suspend fun refreshMessage(
        chatId: Long,
        messageId: Long,
    ): GatewayResult<TelegramMessage> {
        val channel = DEMO_CHANNELS.firstOrNull { it.chatId == chatId }
            ?: return GatewayResult.Failure(GatewayError.CHAT_NOT_FOUND, "Unknown demo channel.")
        if (messageId <= 0L || messageId > channel.messageCount.toLong()) {
            return GatewayResult.Failure(
                GatewayError.FILE_UNAVAILABLE,
                "No demo message $messageId in channel $chatId.",
            )
        }
        // demoMessage is deterministic per (chat, message), so this returns exactly
        // what the indexer stored — which is the point: a repair must not invent a
        // different item.
        return GatewayResult.Success(demoMessage(channel, messageId))
    }

    // ----------------------------------------------------------------- cache

    override suspend fun applyCacheLimit(bytes: Long) = Unit

    override suspend fun cacheSizeBytes(): Long = withContext(io) {
        thumbnailDir().listFiles()?.sumOf { it.length() } ?: 0L
    }

    override suspend fun clearCache(): GatewayResult<Unit> = withContext(io) {
        thumbnailDir().listFiles()?.forEach { it.delete() }
        GatewayResult.Success(Unit)
    }

    private companion object {
        const val DEMO_CODE = "22222"
        const val IDENTITY_MASK = 0x1FFF_FFFF
        const val THUMBNAIL_FLAG = 0x4000_0000
        /** Marks the larger rung of the same artwork. See `renderPoster`. */
        const val PREVIEW_FLAG = 0x2000_0000

        /** Fixed clock: demo dates must not drift between runs. */
        const val DEMO_EPOCH_MS = 1_755_000_000_000L

        val DEMO_CHANNELS = listOf(
            TelegramChat(-1001, "Night Reel", "nightreel", null, 148, true, 1),
            TelegramChat(-1002, "Vault — Archive", "vaultarchive", null, 96, true, 1),
            TelegramChat(-1003, "Studio Dailies", null, null, 61, true, 1),
        )

        /**
         * Written to exercise [com.hardplay.data.tagging.CaptionParser]: hashtags,
         * bracketed segments, `Key: value` lines, technical tokens, years, links
         * that must be stripped, and a blank caption that must fall back to a
         * synthesised title.
         */
        val DEMO_CAPTIONS = listOf(
            "Harbour Lights — final grade\n#4K #HDR #nightshoot\nGenre: Ambient, Cityscape",
            "Rooftop b-roll [1080p] (2023)\nStudio: Northline\nhttps://t.me/nightreel",
            "Test card sequence #60fps",
            "Long Way Down\nQuality: BluRay Remux\nTags: Drive, Nocturne, Slow",
            "",
            "Cold Open v3 [HEVC] #uncut — @nightreel",
            "Desert Plates #4k #dual\nCast: Unit B",
            "Interview — raw, unlit room (720p)",
            "Ember Study\nGenre: Abstract\n#hdr10",
            "Rain on Glass · 2019 · #1080p",
        )
    }
}
