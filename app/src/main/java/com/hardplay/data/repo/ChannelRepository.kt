package com.hardplay.data.repo

import com.hardplay.data.db.dao.ChannelDao
import com.hardplay.data.db.dao.SyncStateDao
import com.hardplay.data.db.entity.ChannelEntity
import com.hardplay.data.db.entity.SyncStateEntity
import com.hardplay.telegram.GatewayResult
import com.hardplay.telegram.TelegramChat
import com.hardplay.telegram.TelegramGateway
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The set of channels the library is built from (CLAUDE.md: multi-channel, chosen
 * by an in-app picker on first run, merged into one library with a source filter).
 */
@Singleton
class ChannelRepository @Inject constructor(
    private val channelDao: ChannelDao,
    private val syncStateDao: SyncStateDao,
    private val gateway: TelegramGateway,
) {

    fun observeAll(): Flow<List<ChannelEntity>> = channelDao.observeAll()

    fun observeEnabled(): Flow<List<ChannelEntity>> = channelDao.observeEnabled()

    /**
     * Per-channel indexing state, for the source manager.
     *
     * Exposed because "how much of this channel do I actually have" is the one
     * question the manager screen exists to answer, and a list of channel names with
     * no indexing state beside them reads as a settings page rather than a status
     * board.
     */
    fun observeSyncStates(): Flow<List<SyncStateEntity>> = syncStateDao.observeAll()

    fun observeCount(): Flow<Int> = channelDao.observeCount()

    suspend fun count(): Int = channelDao.count()

    /** Channels available to add, as offered by the picker. */
    suspend fun discover(): GatewayResult<List<TelegramChat>> = gateway.loadChannels()

    suspend fun resolve(query: String): GatewayResult<TelegramChat> = gateway.resolveChannel(query)

    /**
     * Add a channel and seed its sync state.
     *
     * The sync row is created here rather than lazily by the indexer so that a
     * channel added but never synced still appears in the source list with an
     * honest "not indexed yet" state, instead of being invisible until the first
     * sync happens to run.
     */
    suspend fun add(chat: TelegramChat) {
        val existing = channelDao.byId(chat.chatId)
        channelDao.upsert(
            ChannelEntity(
                chatId = chat.chatId,
                title = chat.title,
                username = chat.username,
                photoFileId = chat.photoFileId,
                knownMessageCount = chat.messageCount,
                sortIndex = existing?.sortIndex ?: channelDao.nextSortIndex(),
                enabled = existing?.enabled ?: true,
                addedAt = existing?.addedAt ?: System.currentTimeMillis(),
            ),
        )
        syncStateDao.insertIgnoring(SyncStateEntity(chatId = chat.chatId))
    }

    suspend fun addAll(chats: List<TelegramChat>) = chats.forEach { add(it) }

    /** Hides a source from the library without discarding its index. */
    suspend fun setEnabled(chatId: Long, enabled: Boolean) = channelDao.setEnabled(chatId, enabled)

    /**
     * Removes the channel and everything indexed from it.
     *
     * Cascades through media, tag links, playback positions and sync state — so
     * this is destructive in a way [setEnabled] is not, and the UI asks first.
     */
    suspend fun remove(chatId: Long) = channelDao.delete(chatId)

    /**
     * Re-read titles and photos from Telegram. Channels get renamed, and a library
     * whose source filter shows last month's names looks stale in a way that reads
     * as broken.
     */
    suspend fun refreshMetadata() {
        channelDao.all().forEach { channel ->
            val chat = gateway.channelById(channel.chatId).valueOrNull ?: return@forEach
            channelDao.refreshMetadata(
                chatId = channel.chatId,
                title = chat.title,
                username = chat.username,
                photoFileId = chat.photoFileId,
                knownMessageCount = chat.messageCount,
            )
        }
    }
}
