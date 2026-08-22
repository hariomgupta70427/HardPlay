package com.hardplay.data.repo

import androidx.paging.PagingSource
import com.hardplay.data.db.dao.MediaDao
import com.hardplay.data.db.entity.MediaEntity
import com.hardplay.data.db.projection.LibraryRow
import com.hardplay.data.db.projection.LibraryTotals
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
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The repair mechanism, which is what stands between the app and the bug it was
 * written for: old content refusing to play with a bare "Source error".
 *
 * Each of these guards a failure that already happened, or that the design is one
 * careless edit away from reintroducing.
 */
class MediaFileRepairTest {

    private fun entity(
        localId: Long = 1L,
        fileId: Int = 100,
        thumb: Int? = 101,
        preview: Int? = 102,
    ) = MediaEntity(
        localId = localId,
        chatId = -1001L,
        messageId = 55L,
        type = "VIDEO",
        caption = "",
        title = "",
        date = 0,
        durationSeconds = null,
        width = null,
        height = null,
        thumbnailFileId = thumb,
        previewFileId = preview,
        posterPath = null,
        minithumbnail = null,
        posterFileId = null,
        posterForMessageId = null,
        fileId = fileId,
        remoteFileId = "remote-1",
        remoteUniqueId = "uniq-1",
        fileSizeBytes = 1_000,
        lastSyncedAt = 0,
    )

    private fun message(
        fileId: Int = 900,
        thumb: Int? = 901,
        preview: Int? = 902,
    ) = TelegramMessage(
        messageId = 55L,
        chatId = -1001L,
        date = 0,
        caption = "",
        kind = TelegramMediaKind.VIDEO,
        fileId = fileId,
        remoteFileId = "remote-1",
        remoteUniqueId = "uniq-1",
        fileSizeBytes = 1_000,
        thumbnailFileId = thumb,
        previewFileId = preview,
        minithumbnail = null,
        durationSeconds = null,
        width = null,
        height = null,
        mimeType = null,
    )

    @Test
    fun `returns the refreshed id for the role asked for`() = runTest {
        val dao = FakeMediaDao(entity())
        val gateway = FakeGateway(GatewayResult.Success(message()))
        val repair = MediaFileRepair(gateway, dao, backgroundScope)

        assertEquals(900, repair.repair(1L, MediaFileRole.ORIGINAL))
        assertEquals(902, repair.repair(1L, MediaFileRole.PREVIEW))
        assertEquals(901, repair.repair(1L, MediaFileRole.THUMBNAIL))
    }

    /**
     * The write is the point. A repair that is not persisted is paid again by every
     * other surface that wants the same item — grid cell, player, photo viewer — and
     * each of them was failing independently before this existed.
     */
    @Test
    fun `persists the refreshed ids to the row`() = runTest {
        val dao = FakeMediaDao(entity())
        val repair =
            MediaFileRepair(FakeGateway(GatewayResult.Success(message())), dao, backgroundScope)

        repair.repair(1L, MediaFileRole.ORIGINAL)

        assertEquals(listOf(Refreshed(1L, 900, 901, 902)), dao.written)
    }

    /**
     * Forty visible cells discovering the same dead item at once must not become forty
     * `getMessage` calls — that is how an account earns a flood-wait, turning a
     * recoverable failure into a locked-out one.
     */
    @Test
    fun `coalesces a burst for the same item into one round trip`() = runTest {
        val gateway = FakeGateway(GatewayResult.Success(message()))
        val repair = MediaFileRepair(gateway, FakeMediaDao(entity()), backgroundScope)

        val results = List(20) { async { repair.repair(1L, MediaFileRole.THUMBNAIL) } }
            .map { it.await() }

        assertEquals(1, gateway.refreshCalls)
        assertEquals(List(20) { 901 }, results)
    }

    /**
     * A message the channel has deleted can never be repaired. Without a remembered
     * failure, every scroll past its cell would fire another round trip for the life
     * of the process.
     */
    @Test
    fun `remembers a failure instead of asking again`() = runTest {
        val gateway = FakeGateway(
            GatewayResult.Failure(GatewayError.FILE_UNAVAILABLE, "gone"),
        )
        val repair = MediaFileRepair(gateway, FakeMediaDao(entity()), backgroundScope)

        assertNull(repair.repair(1L, MediaFileRole.ORIGINAL))
        assertNull(repair.repair(1L, MediaFileRole.ORIGINAL))

        assertEquals(1, gateway.refreshCalls)
    }

    /** `forget` is what makes the player's retry button mean something. */
    @Test
    fun `forget clears a remembered failure so a retry really retries`() = runTest {
        val gateway = FakeGateway(
            GatewayResult.Failure(GatewayError.FILE_UNAVAILABLE, "gone"),
        )
        val repair = MediaFileRepair(gateway, FakeMediaDao(entity()), backgroundScope)

        repair.repair(1L, MediaFileRole.ORIGINAL)
        repair.forget(1L)
        repair.repair(1L, MediaFileRole.ORIGINAL)

        assertEquals(2, gateway.refreshCalls)
    }

    /**
     * Video carries no preview rung — Telegram gives it one thumbnail and no ladder —
     * so a caller asking for one must get the media file rather than null, or the
     * artwork path would treat a perfectly healthy item as unrepairable.
     */
    @Test
    fun `falls back down the rungs when a role has no file`() = runTest {
        val repair = MediaFileRepair(
            FakeGateway(GatewayResult.Success(message(thumb = null, preview = null))),
            FakeMediaDao(entity()),
            backgroundScope,
        )

        assertEquals(900, repair.repair(1L, MediaFileRole.PREVIEW))
        assertEquals(900, repair.repair(1L, MediaFileRole.THUMBNAIL))
    }

    @Test
    fun `an unknown row is not worth a round trip`() = runTest {
        val gateway = FakeGateway(GatewayResult.Success(message()))
        val repair = MediaFileRepair(gateway, FakeMediaDao(null), backgroundScope)

        assertNull(repair.repair(1L, MediaFileRole.ORIGINAL))
        assertEquals(0, gateway.refreshCalls)
    }

    @Test
    fun `an unsaved item is rejected without touching the database`() = runTest {
        val dao = FakeMediaDao(entity())
        val repair =
            MediaFileRepair(FakeGateway(GatewayResult.Success(message())), dao, backgroundScope)

        assertNull(repair.repair(0L, MediaFileRole.ORIGINAL))
        assertEquals(0, dao.reads)
    }
}

private data class Refreshed(
    val localId: Long,
    val fileId: Int,
    val thumbnailFileId: Int?,
    val previewFileId: Int?,
)

/**
 * Only the two methods the repair uses are real; the rest of the DAO is a large
 * read surface that has no business in this test. They fail loudly rather than
 * returning a plausible empty value, so a future change that starts calling one of
 * them shows up as a named failure instead of a mysteriously passing test.
 */
private class FakeMediaDao(private val row: MediaEntity?) : MediaDao {

    val written = mutableListOf<Refreshed>()
    var reads = 0
        private set

    override suspend fun byId(localId: Long): MediaEntity? {
        reads++
        return row?.takeIf { it.localId == localId }
    }

    override suspend fun refreshFileIds(
        localId: Long,
        fileId: Int,
        thumbnailFileId: Int?,
        previewFileId: Int?,
    ) {
        written += Refreshed(localId, fileId, thumbnailFileId, previewFileId)
    }

    private fun unused(): Nothing = error("not used by MediaFileRepair")

    override fun pageLibrary(
        sourceIds: List<Long>,
        sourceCount: Int,
        type: String?,
        hasQuery: Int,
        ftsMatch: String?,
        tagIds: List<Long>,
        tagCount: Int,
        unseenOnly: Int,
        favouritesOnly: Int,
        hidePairedStills: Int,
        sort: Int,
        shuffleSeed: Int,
    ): PagingSource<Int, LibraryRow> = unused()

    override fun countLibrary(
        sourceIds: List<Long>,
        sourceCount: Int,
        type: String?,
        hasQuery: Int,
        ftsMatch: String?,
        tagIds: List<Long>,
        tagCount: Int,
        unseenOnly: Int,
        favouritesOnly: Int,
        hidePairedStills: Int,
    ): Flow<Int> = unused()

    override fun observeTotals(): Flow<LibraryTotals> = unused()
    override fun observeRow(localId: Long): Flow<LibraryRow?> = unused()
    override suspend fun row(localId: Long): LibraryRow? = unused()
    override fun pageHistory(): PagingSource<Int, LibraryRow> = unused()
    override fun observeMostWatched(limit: Int): Flow<List<LibraryRow>> = unused()
    override fun observeBecauseYouWatched(limit: Int): Flow<List<LibraryRow>> = unused()
    override fun observeUnseen(limit: Int): Flow<List<LibraryRow>> = unused()
    override fun observeRediscover(limit: Int): Flow<List<LibraryRow>> = unused()
    override fun observeContinueWatching(limit: Int): Flow<List<LibraryRow>> = unused()
    override fun pageFavourites(): PagingSource<Int, LibraryRow> = unused()
    override fun observeFavouriteCount(): Flow<Int> = unused()
    override fun observeHistoryCount(): Flow<Int> = unused()
    override suspend fun videoIdsInOrder(sourceIds: List<Long>, sourceCount: Int): List<Long> =
        unused()

    override suspend fun findLocalId(chatId: Long, messageId: Long): Long? = unused()
    override suspend fun countForChannel(chatId: Long): Int = unused()
    override suspend fun newestMessageId(chatId: Long): Long? = unused()
    override suspend fun insertIgnoring(item: MediaEntity): Long = unused()
    override suspend fun update(item: MediaEntity) = unused()
    override suspend fun markTagsParsed(localId: Long) = unused()
    override suspend fun setPosterPath(localId: Long, path: String?) = unused()
    override suspend fun clearPosterPaths() = unused()
    override suspend fun needingFrameArt(limit: Int): List<MediaEntity> = unused()
    override suspend fun deleteById(localId: Long) = unused()
    override suspend fun deleteFtsRow(localId: Long) = unused()
    override suspend fun insertFtsRow(localId: Long) = unused()
    override suspend fun clearFts() = unused()
    override suspend fun insertFtsAll() = unused()
}

/** Counts refreshes, so coalescing and the negative memo are observable. */
private class FakeGateway(
    private val answer: GatewayResult<TelegramMessage>,
) : TelegramGateway {

    var refreshCalls = 0
        private set

    override suspend fun refreshMessage(
        chatId: Long,
        messageId: Long,
    ): GatewayResult<TelegramMessage> {
        refreshCalls++
        return answer
    }

    override val isDemo: Boolean = false
    override val authState: StateFlow<TelegramAuthState> =
        MutableStateFlow(TelegramAuthState.Ready)
    override val connectionState: StateFlow<TelegramConnectionState> =
        MutableStateFlow(TelegramConnectionState.READY)

    private fun unused(): Nothing = error("not used by MediaFileRepair")

    override suspend fun start() = unused()
    override suspend fun close() = unused()
    override suspend fun requestVerificationCode(phoneNumber: String): GatewayResult<Unit> =
        unused()

    override suspend fun submitVerificationCode(code: String): GatewayResult<Unit> = unused()
    override suspend fun submitPassword(password: String): GatewayResult<Unit> = unused()
    override suspend fun resendVerificationCode(): GatewayResult<Unit> = unused()
    override suspend fun logOut(): GatewayResult<Unit> = unused()
    override suspend fun loadChannels(limit: Int): GatewayResult<List<TelegramChat>> = unused()
    override suspend fun resolveChannel(query: String): GatewayResult<TelegramChat> = unused()
    override suspend fun channelById(chatId: Long): GatewayResult<TelegramChat> = unused()
    override suspend fun fetchHistory(
        chatId: Long,
        fromMessageId: Long,
        limit: Int,
    ): GatewayResult<TelegramHistoryPage> = unused()

    override suspend fun downloadThumbnail(fileId: Int): GatewayResult<String> = unused()
    override suspend fun downloadOriginal(fileId: Int): GatewayResult<String> = unused()
    override suspend fun requestRange(
        fileId: Int,
        offset: Long,
        limit: Long,
    ): GatewayResult<TelegramFileState> = unused()

    override fun observeFile(fileId: Int): Flow<TelegramFileState> = emptyFlow()
    override suspend fun cancelDownload(fileId: Int) = unused()
    override suspend fun fileIdForRemoteId(
        remoteFileId: String,
        kind: TelegramMediaKind,
    ): GatewayResult<Int> = unused()

    override suspend fun applyCacheLimit(bytes: Long) = unused()
    override suspend fun cacheSizeBytes(): Long = unused()
    override suspend fun clearCache(): GatewayResult<Unit> = unused()
}
