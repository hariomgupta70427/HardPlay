package com.hardplay.di

import android.content.Context
import androidx.room.Room
import com.hardplay.data.db.HardPlayDatabase
import com.hardplay.data.db.dao.ChannelDao
import com.hardplay.data.db.dao.FavouriteDao
import com.hardplay.data.db.dao.MediaDao
import com.hardplay.data.db.dao.PlaybackDao
import com.hardplay.data.db.dao.SyncStateDao
import com.hardplay.data.db.dao.TagDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): HardPlayDatabase =
        Room.databaseBuilder(context, HardPlayDatabase::class.java, HardPlayDatabase.NAME)
            .addMigrations(HardPlayDatabase.MIGRATION_1_2, HardPlayDatabase.MIGRATION_2_3)
            // Kept as a backstop only. Every schema change should ship a migration
            // (see MIGRATION_1_2): tags, saved items and resume positions are the
            // one part of this database Telegram cannot give back, so silently
            // dropping them on upgrade is not an acceptable default. This exists so
            // that a *development* schema change doesn't hard-crash the app on
            // launch before its migration is written.
            .fallbackToDestructiveMigration()
            .build()

    @Provides fun mediaDao(db: HardPlayDatabase): MediaDao = db.mediaDao()
    @Provides fun tagDao(db: HardPlayDatabase): TagDao = db.tagDao()
    @Provides fun channelDao(db: HardPlayDatabase): ChannelDao = db.channelDao()
    @Provides fun syncStateDao(db: HardPlayDatabase): SyncStateDao = db.syncStateDao()
    @Provides fun playbackDao(db: HardPlayDatabase): PlaybackDao = db.playbackDao()
    @Provides fun favouriteDao(db: HardPlayDatabase): FavouriteDao = db.favouriteDao()
}
